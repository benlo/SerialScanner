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
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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

    /** Origine des temps de cet écran. Sans elle, mesurer un scan demande de
     *  soustraire des horodatages logcat à la main — c'est ce qu'a coûté la
     *  session du 25/08. */
    private val debut = SystemClock.elapsedRealtime()

    /** Millisecondes écoulées depuis l'ouverture de l'écran. */
    private fun depuisDebut() = SystemClock.elapsedRealtime() - debut

    @Volatile private var zoomCourant = 1f

    /** Rampe de zoom : voir [Rampe]. On balaie les paliers tant qu'aucune ligne
     *  « Serial » n'est en vue, et on se fige dès qu'on en voit une. */
    private val zoomHandler = Handler(Looper.getMainLooper())
    @Volatile private var autoZoom = true
    @Volatile private var dernierIndice = 0L
    private var palier = -1
    private var zoomMax = 1f

    /** Le palier qui a lu la machine précédente, 0 à la première du lot. */
    private val zoomSouhaite: Float by lazy { intent.getFloatExtra(EXTRA_ZOOM, 0f) }

    /** Le départ au palier retenu n'a lieu qu'une fois, et seulement quand la
     *  plage de zoom est connue : juste après `bindToLifecycle`, `zoomMax` vaut
     *  encore 1 et tous les paliers seraient écartés. */
    private var departPris = false

    /** Fin de la pose : la rampe ne change pas de palier avant cet instant.
     *  C'est le temps de se caler sur la machine suivante quand on enchaîne.
     *  Voir [Rampe.POSE_MS]. */
    @Volatile private var rampePasAvant = 0L

    /** Le gabarit du lot, s'il en a un. Null : on lit comme avant, à
     *  l'ancrage textuel — le chemin éprouvé sur les capots Apple. */
    private val gabarit: Gabarit? by lazy {
        runCatching {
            GabaritStore.depuisJson(intent.getStringExtra(EXTRA_GABARIT) ?: "[]").firstOrNull()
        }.getOrNull()
    }

    /** Le gabarit déduit pendant cette session, à rendre à l'appelant. */
    @Volatile private var gabaritDeduit: Gabarit? = null

    /** Déduction en attente de confirmation par une seconde trame. */
    @Volatile private var gabaritCandidat: Gabarit? = null

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

        // Fil unique, comme avant — `analyze`, le bloc ML Kit et `proxy.close()`
        // restent sérialisés dans l'ordre. Ce qui change est la **politique de
        // rejet** : après `shutdown()`, une tâche soumise est jetée au lieu de
        // lever. Sans ça, quitter l'écran pendant qu'une reconnaissance est en
        // vol tuait l'application — plantage du 27/08/2026 à 08:28:12, une
        // `RejectedExecutionException` remontée sur le fil principal depuis
        // `zzs.onCanceled` : la fermeture du recognizer annule les tâches
        // restantes, et play-services dispatche leurs rappels sur cet
        // exécuteur-ci, déjà terminé. L'ordre de `onDestroy` n'y suffit pas,
        // le dispatch passant par le handler principal.
        cameraExecutor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
            ThreadPoolExecutor.DiscardPolicy()
        )

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
            //
            // Ce qu'on demande n'est pas ce qu'on obtient : le Pixel 6 n'expose
            // que du 4:3 et rend 1920x1440, mesuré au logcat le 25/08. Les 360
            // lignes en trop sont jetées deux fois — par le ViewPort à
            // l'affichage, par la ROI à l'analyse — et coûtent environ un tiers
            // des 135 ms par trame. On les paie quand même : la largeur est ce
            // qui donne ses pixels au caractère, et demander un cadre plus petit
            // la réduirait sur les appareils qui, eux, savent rendre du 16:9.
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
                poserPalierDeDepart()
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
        departPris = false
        // Reprendre la main, c'est se recaler : la même pose qu'entre deux
        // machines, sinon la rampe balaie sous le nez de l'opérateur.
        rampePasAvant = SystemClock.elapsedRealtime() + Rampe.POSE_MS
        // Le palier retenu du lot d'abord ; sans souvenir, le plan large, qui
        // est la position de cadrage — celle où l'on retrouve la ligne avant
        // de la serrer.
        if (!poserPalierDeDepart()) appliquerZoom(1f)
    }

    /**
     * Place la rampe au palier qui a lu la machine précédente.
     *
     * Rend `false` quand il n'y a rien à poser — premier scan du lot, ou zoom
     * laissé à la main par l'opérateur. Le balayage reprend ensuite depuis ce
     * palier : un lot dépareillé n'est pas bloqué, il perd seulement le
     * raccourci.
     */
    private fun poserPalierDeDepart(): Boolean {
        if (departPris || !autoZoom || zoomSouhaite <= 0f) return false
        departPris = true
        val possibles = Rampe.disponibles(zoomMax)
        palier = Rampe.depart(possibles, zoomSouhaite)
        Log.d(TAG, "départ au palier retenu ${possibles[palier]}× (souhaité ${zoomSouhaite}×)")
        appliquerZoom(possibles[palier])
        // L'observateur du zoom et le premier tour de rampe sont deux messages
        // du même looper, dans un ordre que rien ne garantit. Remettre le
        // handler à l'heure ici ne suffisait pas — le tour posté juste après
        // `observe()` repartait à zéro délai et appelait `suivant()` trois
        // millisecondes plus tard. C'est la pose, et elle seule, qui protège le
        // palier qu'on vient de poser.
        rampePasAvant = SystemClock.elapsedRealtime() + Rampe.POSE_MS
        return true
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
                val maintenant = SystemClock.elapsedRealtime()
                val surLaLigne = maintenant - dernierIndice < STABLE_MS
                if (Rampe.avance(maintenant, rampePasAvant, surLaLigne)) {
                    val possibles = Rampe.disponibles(zoomMax)
                    palier = Rampe.suivant(palier, possibles.size)
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
        // Sur le fil caméra et non sur l'UI : les callbacks ML Kit y tombaient
        // par défaut, et avec eux `motsDe`, `boiteDuNumero` et surtout
        // l'encodage JPEG de `Snapshot.capturer` — du travail d'image sur le
        // thread qui dessine l'aperçu. L'exécuteur est à fil unique, donc
        // `analyze`, ce bloc et `proxy.close()` restent sérialisés dans l'ordre.
        recognizer.process(image)
            .addOnSuccessListener(cameraExecutor) { result ->
                // Avec un gabarit, c'est la zone projetée depuis le mot-clé qui
                // filtre le texte, et non plus le viseur. Tout ce qui tombe
                // hors d'elle n'est pas analysé — la garantie `24M` et la date
                // `MFD:` cessent d'être des candidats, non parce qu'elles
                // échouent à un test mais parce qu'on ne les regarde pas.
                val lignes = lignesDe(result)
                val mots = lignes.flatten()
                if (gabarit == null) etalonner(mots)
                // Le gabarit reçu, ou celui qu'on vient de déduire : l'étalonnage
                // prend effet dans la session même, sinon un lot dont la lecture
                // textuelle échoue ne pourrait jamais s'amorcer.
                val zone = (gabarit ?: gabaritDeduit)?.let { g ->
                    g.ancreParmi(mots)?.let { g.projeter(it.boite) }
                }
                // Deux découpages, parce que les deux zones n'ont pas la même
                // taille. Le viseur est une bande large : le grain de la ligne y
                // rend la gravure entière, dont [SerialParser.parse] tire aussi
                // le modèle et l'EMC. La zone d'un gabarit est taillée pour le
                // numéro seul : au grain de la ligne, elle ratait une trame sur
                // six. Voir [Recompose.texteDans].
                val texte = if (zone != null) Recompose.texteDans(lignes, zone)
                else textInRoi(result, roi)
                // Le délai restant fait partie de la trace : sans lui, on ne
                // voit pas *pourquoi* une lecture parfaitement bonne n'a pas
                // compté, et deux trames identiques écartées passent pour du
                // silence.
                val resteDelai = (prochaineLectureA - SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
                Log.d(
                    TAG,
                    "+${depuisDebut()}ms cadre ${proxy.width}x${proxy.height}" +
                        " ${zoomCourant}×" +
                        (zone?.let { " gabarit→$it" } ?: "") +
                        (if (resteDelai > 0) " [délai ${resteDelai}ms]" else "") +
                        " → «${texte.replace(SEPARATEUR, " ⏎ ")}»"
                )
                // La ligne gravée est en vue même si le numéro ne se lit pas
                // encore : c'est ce qui distingue « viser à côté » de « viser
                // juste mais trop loin ».
                // Avec un gabarit, la zone n'existe que parce que le mot-cle
                // a ete trouve : on est sur la ligne par construction, meme si
                // le texte decoupe ne contient plus le mot-cle.
                val surLaLigne = zone != null || SerialParser.voitAncre(texte)
                if (surLaLigne) {
                    dernierIndice = SystemClock.elapsedRealtime()
                }
                val faute = if (surLaLigne) Cadrage.LIGNE else Cadrage.RIEN
                // Trop tôt : ce serait la même image que la première, donc la
                // même erreur. C'est tout l'intérêt du délai.
                if (SystemClock.elapsedRealtime() < prochaineLectureA) return@addOnSuccessListener

                // Le gabarit prime : sa longueur vient du numéro que l'opérateur
                // a désigné sur l'étiquette, pas d'une table de marques. C'est
                // ce qui rend la déclaration de marque inutile — et ce qui ouvre
                // l'app aux étiquettes dont aucun profil ne connaît le format.
                val format = gabarit?.format ?: SerialParser.formatPour(texte, profil)
                val serials = if (zone != null) SerialParser.dansZone(texte, format)
                else SerialParser.candidats(texte, profil)
                if (serials.size > 1) {
                    // Plusieurs capots dans le cadre : on ne devine pas lequel est visé.
                    cadrage(faute, getString(R.string.scan_ambiguous, serials.size))
                    return@addOnSuccessListener
                }
                // Dans une zone de gabarit, le modele et l'EMC sont hors cadre
                // par construction : seul le numero en sort.
                val parsed = if (zone != null) SerialParser.Reading(serials.singleOrNull(), null, null)
                else SerialParser.parse(texte, profil)
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
                // Avec un gabarit, c'est la geometrie qui place le numero, pas
                // l'operateur : exiger qu'il tienne dans la bande du viseur
                // serait intenable. Il doit tenir dans l'image visible, ce qui
                // suffit a garantir qu'aucun caractere n'est coupe au bord.
                val cadreExige = if (zone != null) visible else roi
                val boite = boiteDuNumero(lignes, candidate)
                if (boite == null || !cadreExige.contientEntierement(boite)) {
                    cadrage(Cadrage.LIGNE, getString(R.string.scan_recentrer, candidate))
                    return@addOnSuccessListener
                }
                cadrage(Cadrage.PRET, candidate)
                val maintenant = SystemClock.elapsedRealtime()
                // Une lettre que l'alphabet du fabricant proscrit est une
                // erreur de lecture, même lue deux fois : deux passes de ML Kit
                // sur la même gravure se trompent de la même façon.
                // Un numéro lu sous le mot-clé plutôt que sur sa ligne tient à
                // un garde-fou plus mince : il part en vérification.
                val ambigu = Controle.ambigu(candidate, format) ||
                    SerialParser.horsLigne(texte, candidate, profil)
                // La séquence est touchée par deux fils — ici le fil caméra,
                // et la rampe sur l'UI qui peut l'abandonner. [DoubleLecture]
                // n'est pas concurrente : c'est ce verrou qui la protège.
                val (enSequence, etape) = synchronized(sequence) {
                    sequence.enCours to sequence.proposer(candidate, maintenant)
                }
                when (etape) {
                    is DoubleLecture.Etape.Confirmer -> {
                        Log.d(
                            TAG,
                            "+${depuisDebut()}ms 1ʳᵉ lecture «$candidate» à ${zoomCourant}×" +
                                (if (enSequence) " (divergence, la précédente est oubliée)" else "")
                        )
                        // Le zoom ne bouge pas : on reste au palier qui vient de
                        // lire. Voir [DoubleLecture] — le saut vers un autre
                        // palier a été mesuré et il coûtait plus qu'il ne
                        // rapportait.
                        prochaineLectureA = etape.pasAvant
                        runOnUiThread { binding.etapes.text = getString(R.string.scan_etape_une) }
                    }
                    is DoubleLecture.Etape.Valider -> {
                        Log.d(
                            TAG,
                            "+${depuisDebut()}ms VALIDÉ «${etape.serial}» à ${zoomCourant}×" +
                                " incertain=${ambigu || etape.incertain}" +
                                " (ambigu=$ambigu concordance=${!etape.incertain})"
                        )
                        runOnUiThread { binding.etapes.text = getString(R.string.scan_etape_deux) }
                        validate(
                            etape.serial,
                            parsed,
                            // Sur le numéro lui-même, jamais sur le viseur :
                            // avec un gabarit, le numéro n'y est pas. Voir
                            // [ScanRoi.vignette].
                            Snapshot.capturer(
                                this, proxy, ScanRoi.vignette(boite, visible), rotation
                            ),
                            ambigu || etape.incertain
                        )
                    }
                }
            }
            .addOnCompleteListener(cameraExecutor) { proxy.close() }
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
    /**
     * Une première lecture laissée en plan est oubliée : sinon elle
     * confirmerait la machine suivante avec le numéro de la précédente.
     *
     * **Le compte part de la dernière ancre vue, pas du dernier candidat
     * complet.** Il partait de celui-ci, et c'était le défaut signalé le 25/08
     * sur les gravures Apple en lumière moyenne : pendant une confirmation
     * difficile — ancre bien en vue, numéro pas encore net — aucun candidat
     * n'atteint [Cadrage.PRET], donc le compteur courait pendant que
     * l'opérateur visait juste, et la première lecture était jetée sous ses
     * doigts. `dernierIndice` est mis à jour dès que la ligne est en vue, ce
     * qui est exactement la condition « l'opérateur n'a pas quitté la machine ».
     *
     * Le garde-fou tient toujours pour le cas visé : passer d'une machine à la
     * suivante fait perdre l'ancre le temps de lever le téléphone. Et même
     * sans ça, une lecture retenue ne peut valider qu'un numéro qui lui est
     * identique — un capot voisin diverge et repart en première lecture.
     */
    private fun abandonnerSequenceSiPerdue() {
        if (validated) return
        if (SystemClock.elapsedRealtime() - dernierIndice < SEQUENCE_MS) return
        val perdue = synchronized(sequence) {
            if (!sequence.enCours) false else { sequence.oublier(); true }
        }
        if (perdue) {
            Log.d(TAG, "première lecture abandonnée, ligne perdue")
            binding.etapes.text = ""
        }
    }

    private fun boiteDuNumero(lignes: List<List<Gabarit.Mot>>, serial: String): ScanRoi.Box? =
        Recompose.boite(lignes, serial)
            ?: null.also { Log.d(TAG, "numero " + serial + " introuvable dans les mots reconnus") }

    /**
     * Cherche le gabarit du lot dans la trame, tant qu'il n'en a pas.
     *
     * Deux trames concordantes avant d'adopter, dans l'esprit de la double
     * lecture — une déduction unique ne prouve rien. Mais c'est bien la
     * **géométrie** qu'on confirme, pas les caractères : un numéro mal lu a
     * quand même la bonne boîte, et c'est ce qui autorise à étalonner sur une
     * lecture non confirmée. Sans ça, un lot dont la lecture textuelle échoue
     * — étiquette dont le numéro sort avant son mot-clé — ne pourrait jamais
     * s'étalonner, précisément là où le gabarit sert le plus.
     */
    private fun etalonner(mots: List<Gabarit.Mot>) {
        if (gabaritDeduit != null) return
        val propose = Gabarit.auto(
            UUID.randomUUID().toString(),
            intent.getStringExtra(EXTRA_MARQUE)?.takeIf { it != Lot.MARQUE_AUTO }.orEmpty(),
            mots,
            SerialParser.formatPour(mots.joinToString(" ") { it.texte }, profil)
        ) ?: return
        val precedent = gabaritCandidat
        if (precedent != null && precedent.proche(propose)) {
            gabaritDeduit = precedent
            Log.d(TAG, "gabarit adopté : $precedent")
        } else {
            gabaritCandidat = propose
            Log.d(TAG, "gabarit proposé : $propose")
        }
    }

    /**
     * Les mots reconnus et leurs boîtes, groupés par ligne.
     *
     * Au grain du mot et non de la ligne : le gabarit raisonne sur la boîte du
     * mot-clé seul, alors qu'une ligne peut porter `SN:` *et* la garantie, ce
     * qui doublerait sa largeur et fausserait l'unité d'échelle.
     *
     * Le groupement par ligne est conservé parce que trois lectures en
     * dépendent — [Recompose.boite] ne recompose que des mots **consécutifs
     * d'une même ligne**, [Recompose.texteDans] découpe la zone du gabarit au
     * même grain, et [SerialParser.dansZone] relit ces lignes. Une seule
     * traversée pour les trois : elles se contredisaient quand elles
     * redécoupaient chacune de leur côté.
     */
    private fun lignesDe(result: Text): List<List<Gabarit.Mot>> =
        result.textBlocks
            .flatMap { it.lines }
            .map { ligne ->
                ligne.elements.mapNotNull { e ->
                    e.boundingBox?.let {
                        Gabarit.Mot(e.text, ScanRoi.Box(it.left, it.top, it.right, it.bottom))
                    }
                }
            }

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
        // Posé tout de suite, et non dans le bloc UI : c'est ce drapeau qui
        // ferme la porte aux trames encore en vol sur le fil caméra.
        validated = true
        // Le zoom qui vient de lire part avec le résultat : la machine suivante
        // du lot y démarrera au lieu de rebalayer la rampe. Voir [Rampe].
        val zoomQuiALu = zoomCourant
        runOnUiThread {
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_SERIAL, serial)
                putExtra(EXTRA_MODEL, parsed.model)
                putExtra(EXTRA_EMC, parsed.emc)
                putExtra(EXTRA_INCERTAIN, incertain)
                putExtra(EXTRA_PHOTO, photo)
                putExtra(EXTRA_ZOOM, zoomQuiALu)
                gabaritDeduit?.let {
                    putExtra(EXTRA_GABARIT_AUTO, GabaritStore.versJson(listOf(it)))
                }
            })
            // Deux impulsions pour une lecture incertaine : au poste, la
            // différence se sent sans regarder l'écran.
            vibrate(if (incertain) longArrayOf(0, 60, 90, 60) else longArrayOf(0, 80))
            cadrage(Cadrage.PRET, serial)
            // Le temps de voir « 1 ✓ 2 ✓ » : sans ce délai l'écran se ferme
            // avant que la confirmation soit affichée, et le retour haptique
            // reste le seul signal — insuffisant quand on doute d'un numéro.
            binding.root.postDelayed({ finish() }, FIN_MS)
        }
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
        // Le recognizer avant l'exécuteur : ses annulations ont ainsi encore un
        // exécuteur vivant où tomber. Celles qui arrivent quand même après sont
        // jetées par la politique de rejet posée dans `onCreate`.
        recognizer.close()
        cameraExecutor.shutdown()
    }

    companion object {
        /** Au-delà de ce silence — ancre comprise —, une séquence entamée est
         *  tenue pour perdue. Voir [abandonnerSequenceSiPerdue]. */
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

        /** Le gabarit du lot, sérialisé avec le JSON de [GabaritStore] — un
         *  extra plutôt qu'un accès au dépôt, comme la marque : cet écran ne
         *  connaît pas le lot, il reçoit ce qu'il lui faut et rend ce qu'il a
         *  trouvé. */
        const val EXTRA_GABARIT = "gabarit"

        /** Le gabarit déduit tout seul de la première lecture d'un lot qui n'en
         *  avait pas. L'appelant décide s'il le garde. */
        const val EXTRA_GABARIT_AUTO = "gabaritAuto"

        /** Le palier de zoom qui a lu — reçu de la machine précédente à
         *  l'entrée, rendu à l'appelant à la sortie. Un aller-retour plutôt
         *  qu'un état de l'écran : trente capots identiques se lisent au même
         *  zoom, et le retrouver coûtait près de deux secondes par machine. */
        const val EXTRA_ZOOM = "zoom"

        /** Temps laissé à un palier avant de passer au suivant. Les ratios
         *  eux-mêmes sont dans [Rampe]. */
        private const val PALIER_MS = 1100L
        private const val STABLE_MS = 2500L
    }
}
