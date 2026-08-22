package fr.gotatanka.serialscanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.gotatanka.serialscanner.databinding.ActivityScanBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var cameraControl: CameraControl? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var validated: Boolean = false

    /** La séquence des deux lectures. Voir [DoubleLecture] : deux images
     *  consécutives sont une seule mesure comptée deux fois. */
    private val sequence = DoubleLecture()

    /** Rien n'est relu avant cet instant : c'est le délai qui fait que la
     *  seconde image n'est pas la première. */
    @Volatile private var prochaineLectureA = 0L

    /**
     * Échéance de la tentative au palier élargi, 0 quand il n'y en a pas.
     *
     * La seconde lecture est d'abord tentée à un autre zoom — deux zooms sont
     * deux mesures franchement différentes, là où deux images du même cadrage
     * partagent leurs défauts. Mais si le palier élargi ne rend rien, il faut
     * **revenir**, pas insister : c'est l'absence de ce repli qui faisait
     * boucler la version d'hier soir.
     */
    @Volatile private var essaiJusqua = 0L
    private var zoomCourant = 1f
    private var zoomPremiere = 1f
    private var autoAvantSequence = true

    /** Dernier pas franchi dans la séquence, pour l'abandonner si l'opérateur
     *  a quitté la ligne entre les deux lectures. */
    @Volatile private var derniereEtape = 0L

    /** Rampe de zoom : le zoom recadre le capteur, donc à cadre égal chaque
     *  caractère reçoit plus de pixels — c'est ce qui fait la différence entre
     *  un Q et un O. On balaie les paliers tant qu'aucune ligne « Serial »
     *  n'est en vue, et on se fige dès qu'on en voit une. */
    private val zoomHandler = Handler(Looper.getMainLooper())
    @Volatile private var autoZoom = true
    @Volatile private var dernierIndice = 0L
    private var palier = -1
    private var zoomMax = 1f

    /** Profil de lecture imposé par le lot, null si détection sur l'étiquette. */
    private val profil: SerialParser.Profil? by lazy {
        SerialParser.profilParNom(intent.getStringExtra(EXTRA_MARQUE))
    }

    /** Numéros déjà au lot, transmis par l'écran du relevé. */
    private val dejaReleves: Set<String> by lazy {
        intent.getStringArrayListExtra(EXTRA_DEJA_RELEVES)?.toSet().orEmpty()
    }

    private val requestCameraPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.cancel.setOnClickListener { finish() }
        // L'autofocus continu patine sur une gravure sans contraste : laisser
        // l'opérateur désigner le point de mise au point.
        binding.preview.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = binding.preview.meteringPointFactory.createPoint(event.x, event.y)
                cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
                )
                v.performClick()
            }
            true
        }
        binding.zoomAuto.setOnClickListener { activerAuto() }
        for ((bouton, ratio) in pastilles()) {
            bouton.setOnClickListener {
                autoZoom = false
                appliquerZoom(ratio)
            }
        }
        binding.zoomGroupe.check(R.id.zoomAuto)

        // Le ViewPort et la taille du cadre demandent une vue déjà mesurée.
        binding.preview.post {
            binding.viewfinder.updateLayoutParams {
                width = (binding.preview.width * ScanRoi.FRAC_W).toInt()
                height = (binding.preview.height * ScanRoi.FRAC_H).toInt()
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                requestCameraPerm.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            // 1080p et non le 640x480 par défaut d'ImageAnalysis : sur une gravure
            // cadrée à 20 cm, un caractère ne fait qu'une dizaine de pixels de haut
            // en VGA, et la queue du Q — un ou deux pixels — disparaît au
            // sous-échantillonnage. Le O, lui, survit : d'où les Q lus en O.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolution)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyze) }
            val group = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(analyzer)
                .apply { binding.preview.viewPort?.let { setViewPort(it) } }
                .build()
            val camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, group
            )
            cameraControl = camera.cameraControl
            // Observer plutôt que lire `.value` : juste après bindToLifecycle le
            // LiveData n'est pas encore alimenté et rend null, ce qui bloquait
            // zoomMax à 1 — la rampe ne trouvait qu'un palier et ne bougeait pas.
            camera.cameraInfo.zoomState.observe(this) { etat ->
                zoomMax = etat.maxZoomRatio
                // Au-delà du maximum de l'appareil, une pastille mentirait :
                // mieux vaut ne pas la proposer que la voir sans effet.
                for ((bouton, ratio) in pastilles()) {
                    bouton.isEnabled = ratio <= zoomMax
                }
            }
            zoomHandler.post(rampeZoom)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pastilles() = listOf(
        binding.zoom1 to 1f,
        binding.zoom2 to 2f,
        binding.zoom4 to 4f,
        binding.zoom6 to 6f
    )

    private fun activerAuto() {
        autoZoom = true
        palier = -1
        // Repartir du plan large : c'est la position de cadrage, celle où l'on
        // retrouve la ligne avant de la serrer.
        appliquerZoom(1f)
    }

    private fun appliquerZoom(ratio: Float) {
        val borne = ratio.coerceIn(1f, zoomMax.coerceAtLeast(1f))
        zoomCourant = borne
        cameraControl?.setZoomRatio(borne)
        val valeur = String.format(Locale.getDefault(), "%.1f", borne)
        binding.etatZoom.text = getString(
            if (autoZoom) R.string.zoom_etat_auto else R.string.zoom_etat_fixe,
            valeur
        )
    }

    private val rampeZoom = object : Runnable {
        override fun run() {
            // La rampe se fige dès qu'une ancre est en vue (`surLaLigne`) :
            // pendant les deux lectures elle ne bouge donc pas d'elle-même.
            abandonnerSequenceSiPerdue()
            if (!validated && autoZoom) {
                // Une ancre vue récemment veut dire que le zoom actuel convient :
                // continuer à balayer ferait perdre la ligne juste avant de la lire.
                val surLaLigne = SystemClock.elapsedRealtime() - dernierIndice < STABLE_MS
                if (!surLaLigne) {
                    val possibles = PALIERS.filter { it <= zoomMax }.ifEmpty { listOf(1f) }
                    palier = (palier + 1) % possibles.size
                    Log.d(TAG, "rampe → ${possibles[palier]}× (max $zoomMax)")
                    appliquerZoom(possibles[palier])
                }
            }
            zoomHandler.postDelayed(this, PALIER_MS)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        if (validated) { proxy.close(); return }
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val crop = proxy.cropRect
        val visible = ScanRoi.toRotatedFrame(
            ScanRoi.Box(crop.left, crop.top, crop.right, crop.bottom),
            rotation, proxy.width, proxy.height
        )
        val roi = ScanRoi.roi(visible)
        val image = InputImage.fromMediaImage(media, rotation)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val texte = textInRoi(result, roi)
                Log.d(TAG, "cadre ${proxy.width}x${proxy.height} → «${texte.replace(SEPARATEUR, " ⏎ ")}»")
                // La ligne gravée est en vue même si le numéro ne se lit pas
                // encore : c'est ce qui distingue « viser à côté » de « viser
                // juste mais trop loin ».
                val surLaLigne = SerialParser.voitAncre(texte)
                if (surLaLigne) {
                    dernierIndice = SystemClock.elapsedRealtime()
                }
                val faute = if (surLaLigne) Cadrage.LIGNE else Cadrage.RIEN
                replierSiPalierMuet()
                // Trop tôt : ce serait la même image que la première, donc la
                // même erreur. C'est tout l'intérêt du délai.
                if (SystemClock.elapsedRealtime() < prochaineLectureA) return@addOnSuccessListener

                val serials = SerialParser.candidats(texte, profil)
                if (serials.size > 1) {
                    // Plusieurs capots dans le cadre : on ne devine pas lequel est visé.
                    cadrage(faute, getString(R.string.scan_ambiguous, serials.size))
                    return@addOnSuccessListener
                }
                val parsed = SerialParser.parse(texte, profil)
                val candidate = parsed.serial
                if (candidate == null) {
                    // Ancre vue mais rien de plausible derrière : un caractère
                    // manque ou se lit mal. Le dire, plutôt que laisser
                    // l'opérateur devant un écran muet — le cas du A1502 dont
                    // le numéro de 12 revenait en 11 caractères.
                    cadrage(
                        faute,
                        getString(
                            if (surLaLigne) R.string.scan_incomplet else R.string.scan_searching
                        )
                    )
                    return@addOnSuccessListener
                }
                // Déjà au lot : l'opérateur enchaîne les capots sans avoir à
                // surveiller l'écran, la même ligne ne doit pas repartir.
                if (candidate in dejaReleves) {
                    cadrage(faute, getString(R.string.scan_deja_releve, candidate))
                    return@addOnSuccessListener
                }
                // Condition de cadrage : le numéro doit tenir franchement dans
                // le cadre. À cheval sur le bord, un caractère peut manquer
                // sans que rien ne le dise — et une lecture tronquée qui passe
                // le format est un faux numéro d'aspect parfait.
                val boite = boiteDuNumero(result, candidate)
                if (boite == null || !roi.contientEntierement(boite)) {
                    cadrage(Cadrage.LIGNE, getString(R.string.scan_recentrer, candidate))
                    return@addOnSuccessListener
                }
                cadrage(Cadrage.PRET, candidate)
                val maintenant = SystemClock.elapsedRealtime()
                derniereEtape = maintenant
                // Une lettre que l'alphabet du fabricant proscrit est une
                // erreur de lecture, même lue deux fois : deux passes de ML Kit
                // sur la même gravure se trompent de la même façon.
                // Un numéro lu sous le mot-clé plutôt que sur sa ligne tient à
                // un garde-fou plus mince : il part en vérification.
                val ambigu = Controle.ambigu(candidate, SerialParser.formatPour(texte, profil)) ||
                    SerialParser.horsLigne(texte, candidate, profil)
                val enSequence = sequence.enCours
                when (val etape = sequence.proposer(candidate, maintenant)) {
                    is DoubleLecture.Etape.Confirmer -> {
                        prochaineLectureA = etape.pasAvant
                        // Une divergence garde le zoom où l'on est : on a déjà
                        // deux points de vue, c'est le numéro qui hésite.
                        if (enSequence) essaiJusqua = 0L else tenterAutrePalier()
                        runOnUiThread { binding.etapes.text = getString(R.string.scan_etape_une) }
                    }
                    is DoubleLecture.Etape.Valider -> {
                        essaiJusqua = 0L
                        runOnUiThread { binding.etapes.text = getString(R.string.scan_etape_deux) }
                        validate(
                            etape.serial,
                            parsed,
                            Snapshot.capturer(this, proxy, roi, rotation),
                            ambigu || etape.incertain
                        )
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    /**
     * Les trois états du cadre, seul guide de l'opérateur.
     *
     * Un cadre binaire ne guide pas : quand il ne verdit jamais, on ne sait pas
     * si l'on vise à côté ou si le numéro se lit mal. L'ambre dit « bonne ligne,
     * numéro pas encore net » — c'est l'état où resserrer sert à quelque chose.
     */
    private enum class Cadrage { RIEN, LIGNE, PRET }

    private fun cadrage(etat: Cadrage, message: String) = runOnUiThread {
        binding.viewfinder.setBackgroundResource(
            when (etat) {
                Cadrage.RIEN -> R.drawable.viewfinder_vide
                Cadrage.LIGNE -> R.drawable.viewfinder
                Cadrage.PRET -> R.drawable.viewfinder_ok
            }
        )
        binding.lastRead.text = message
    }

    /**
     * Le rectangle du numéro lui-même, et non de la ligne qui le porte.
     *
     * La ligne gravée traverse tout le capot — « Assembled in China… » — et
     * déborde toujours du cadre : exiger qu'elle y tienne entièrement serait
     * impossible à satisfaire. Le numéro, lui, y tient, et c'est de lui qu'on
     * veut être sûr. ML Kit le rend en un mot le plus souvent, parfois en
     * plusieurs : on recompose donc les mots consécutifs d'une même ligne.
     */
    private fun boiteDuNumero(result: Text, serial: String): ScanRoi.Box? {
        for (ligne in result.textBlocks.flatMap { it.lines }) {
            val mots = ligne.elements
            for (debut in mots.indices) {
                var texte = ""
                for (fin in debut until mots.size) {
                    texte += mots[fin].text.uppercase()
                    if (texte.length > serial.length) break
                    if (SerialParser.fixPrefix(texte) != serial) continue
                    val cadres = (debut..fin).mapNotNull { mots[it].boundingBox }
                    if (cadres.isEmpty()) break
                    return ScanRoi.Box(
                        cadres.minOf { it.left },
                        cadres.minOf { it.top },
                        cadres.maxOf { it.right },
                        cadres.maxOf { it.bottom }
                    )
                }
            }
        }
        Log.d(TAG, "numéro $serial introuvable dans les mots reconnus")
        return null
    }

    /**
     * Tente la confirmation à un palier élargi.
     *
     * Élargir, et non serrer : le numéro reste dans le cadre à coup sûr. S'il
     * y devient illisible, [replierSiPalierMuet] ramène au palier de départ
     * après un délai borné et la confirmation se fait alors sur le temps.
     */
    private fun tenterAutrePalier() {
        zoomPremiere = zoomCourant
        autoAvantSequence = autoZoom
        val cible = DoubleLecture.palierConfirmation(PALIERS.filter { it <= zoomMax }, zoomCourant)
            ?: return
        Log.d(TAG, "confirmation tentée à ${cible}× (première lecture à ${zoomPremiere}×)")
        autoZoom = false
        appliquerZoom(cible)
        val maintenant = SystemClock.elapsedRealtime()
        prochaineLectureA = maintenant + ZOOM_MS
        essaiJusqua = maintenant + ZOOM_MS + ESSAI_MS
    }

    /** Le palier élargi n'a rien rendu : retour au cadrage qui marchait. */
    private fun replierSiPalierMuet() {
        if (essaiJusqua == 0L || SystemClock.elapsedRealtime() < essaiJusqua) return
        essaiJusqua = 0L
        Log.d(TAG, "palier de confirmation muet, retour à ${zoomPremiere}×")
        appliquerZoom(zoomPremiere)
        prochaineLectureA = SystemClock.elapsedRealtime() + ZOOM_MS
    }

    /** Une première lecture laissée en plan est oubliée : sinon elle
     *  confirmerait la machine suivante avec le numéro de la précédente. */
    private fun abandonnerSequenceSiPerdue() {
        if (validated || !sequence.enCours) return
        if (SystemClock.elapsedRealtime() - derniereEtape < SEQUENCE_MS) return
        Log.d(TAG, "première lecture abandonnée, ligne perdue")
        sequence.oublier()
        essaiJusqua = 0L
        binding.etapes.text = ""
        // Le réglage de l'opérateur est rendu tel qu'il l'avait laissé : la
        // séquence emprunte le zoom, elle ne le confisque pas.
        autoZoom = autoAvantSequence
        appliquerZoom(zoomPremiere)
    }

    /**
     * Le texte des seules lignes dont le centre tombe dans le cadre.
     *
     * Filtrer par ligne et non par bloc : ML Kit regroupe volontiers deux
     * capots voisins dans un même bloc, ce qui ferait rentrer dans le cadre
     * un numéro que l'opérateur ne vise pas.
     */
    private fun textInRoi(result: Text, roi: ScanRoi.Box): String =
        result.textBlocks
            .flatMap { it.lines }
            .filter { line ->
                val b = line.boundingBox ?: return@filter false
                roi.contains(b.centerX(), b.centerY())
            }
            .joinToString(SEPARATEUR) { it.text }

    private fun validate(
        serial: String,
        parsed: SerialParser.Reading,
        photo: String,
        incertain: Boolean
    ) {
        if (validated) return
        validated = true
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_SERIAL, serial)
            putExtra(EXTRA_MODEL, parsed.model)
            putExtra(EXTRA_EMC, parsed.emc)
            putExtra(EXTRA_INCERTAIN, incertain)
            putExtra(EXTRA_PHOTO, photo)
        })
        // Deux impulsions pour une lecture incertaine : au poste, la différence
        // se sent sans regarder l'écran.
        vibrate(if (incertain) longArrayOf(0, 60, 90, 60) else longArrayOf(0, 80))
        cadrage(Cadrage.PRET, serial)
        // Le temps de voir « 1 ✓ 2 ✓ » : sans ce délai l'écran se ferme avant
        // que la confirmation soit affichée, et le retour haptique reste le
        // seul signal — insuffisant quand on doute d'un numéro.
        binding.root.postDelayed({ finish() }, FIN_MS)
    }

    private fun vibrate(motif: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        // Le retour haptique est un confort : il ne doit jamais faire tomber une lecture.
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(motif, -1))
        } catch (e: SecurityException) {
            Log.w(TAG, "vibration indisponible", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        zoomHandler.removeCallbacks(rampeZoom)
        // Délier d'abord : sinon une analyse en vol atteint un recognizer fermé.
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        recognizer.close()
    }

    companion object {
        /** Établissement du zoom demandé avant de relire. */
        private const val ZOOM_MS = 400L

        /** Durée laissée au palier élargi avant de revenir au précédent. */
        private const val ESSAI_MS = 1200L

        /** Au-delà, une séquence entamée est tenue pour perdue. */
        private const val SEQUENCE_MS = 4000L

        /** Affichage de la confirmation avant la fermeture de l'écran. */
        private const val FIN_MS = 400L

        private const val TAG = "ScanActivity"
        private const val SEPARATEUR = "\n"
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_MODEL = "model"
        const val EXTRA_EMC = "emc"
        const val EXTRA_INCERTAIN = "incertain"
        const val EXTRA_PHOTO = "photo"
        const val EXTRA_DEJA_RELEVES = "dejaReleves"
        const val EXTRA_MARQUE = "marque"

        /** Du plan large au serré : la première position sert au cadrage, les
         *  suivantes cherchent la finesse de gravure. */
        /** Ratios de zoom balayés par la rampe, du cadrage au plus serré. */
        private val PALIERS = listOf(1f, 2f, 3f, 4f, 5f, 6f)
        private const val PALIER_MS = 1100L
        private const val STABLE_MS = 2500L
    }
}
