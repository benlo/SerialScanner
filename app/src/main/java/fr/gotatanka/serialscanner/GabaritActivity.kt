package fr.gotatanka.serialscanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.gotatanka.serialscanner.databinding.ActivityGabaritBinding
import java.util.UUID

/**
 * Étalonner un gabarit à la main, sur une photo d'étiquette.
 *
 * La photo se prend avec l'appareil du système plutôt qu'avec un écran caméra
 * maison : un écran de plus à maintenir pour le même résultat. Le cliché va dans
 * le cache de l'application, pas dans la pellicule — il ne sert qu'à
 * l'étalonnage.
 *
 * L'opérateur désigne deux mots reconnus — le point clé, puis le numéro. C'est
 * ce que la déduction automatique ne peut pas faire quand l'étiquette porte
 * plusieurs candidats également plausibles : un MTM Lenovo a la même longueur
 * qu'un numéro de série, et refuser de trancher est délibéré. Ici, c'est
 * l'opérateur qui trancher, une fois, pour toute la palette.
 */
class GabaritActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGabaritBinding
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Le gabarit modifié, ou null pour une création. */
    private val existant: Gabarit? by lazy {
        Depot.gabarit(this, intent.getStringExtra(EXTRA_GABARIT_ID))
    }

    /** Le cliché pris a l'instant, en attente du retour de l'appareil photo. */
    private var cliche: Uri? = null

    /** Le bitmap affiché, celui-là même qui a servi à la reconnaissance. */
    private var courant: android.graphics.Bitmap? = null

    private val photographier = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { pris ->
        Log.d(TAG, "appareil photo : pris=" + pris + " uri=" + cliche)
        if (pris) cliche?.let { charger(it) }
        else Ui.message(binding.root, getString(R.string.gabarit_cliche_abandonne))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGabaritBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(
            if (existant == null) R.string.gabarit_titre_nouveau else R.string.gabarit_titre_modifier
        )

        existant?.let { g ->
            binding.nom.setText(g.nom)
            fichierPhoto(g.photo)?.let { charger(Uri.fromFile(it)) }
        }
        binding.photo.surSelection = ::majEtat
        binding.photographier.setOnClickListener { prendreCliche() }
        binding.roles.check(R.id.roleAncre)
        binding.roles.addOnButtonCheckedListener { _, id, coche ->
            if (!coche) return@addOnButtonCheckedListener
            binding.photo.role =
                if (id == R.id.roleAncre) VueGabarit.Role.ANCRE else VueGabarit.Role.NUMERO
        }
        binding.effacer.setOnClickListener { binding.photo.effacer() }
        binding.enregistrer.setOnClickListener { enregistrer() }
        majEtat()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Photographie l'etiquette de reference.
     *
     * Le cliche va dans le cache : il ne sert qu'a l'etalonnage, et n'a aucune
     * raison d'encombrer la pellicule de l'operateur.
     */
    private fun prendreCliche() {
        val dossier = File(cacheDir, "references").apply { mkdirs() }
        val fichier = File(dossier, "ref_" + System.currentTimeMillis() + ".jpg")
        // Concaténation et non interpolation : l'autorité doit correspondre au
        // manifeste au caractère près, et une chaîne mal échappée y produit un
        // plantage à l'exécution plutôt qu'une erreur de compilation.
        val uri = FileProvider.getUriForFile(this, packageName + ".fichiers", fichier)
        cliche = uri
        photographier.launch(uri)
    }

    /**
     * Lit la photo, en tire les mots et leurs boites, et les donne a la vue.
     *
     * Le **meme bitmap** part a l'affichage et a la reconnaissance. Alimenter
     * l'ImageView par l'URI montrerait l'image brute alors que ML Kit travaille
     * sur l'image redressee par l'EXIF : d'un quart de tour d'ecart, les boites
     * tombent a cote des mots et plus rien ne se laisse designer.
     */
    private fun charger(uri: Uri) {
        val bitmap = runCatching { Photos.redresse(this, uri) }.getOrNull()
        if (bitmap == null) {
            Ui.message(binding.root, getString(R.string.gabarit_photo_illisible))
            return
        }
        courant = bitmap
        Log.d(TAG, "photo chargee " + bitmap.width + "x" + bitmap.height)
        binding.photo.setImageBitmap(bitmap)
        binding.photo.ajuster()
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val mots = result.textBlocks
                    .flatMap { it.lines }
                    .flatMap { it.elements }
                    .mapNotNull { e ->
                        e.boundingBox?.let {
                            Gabarit.Mot(e.text, ScanRoi.Box(it.left, it.top, it.right, it.bottom))
                        }
                    }
                Log.d(TAG, "mots reconnus : " + mots.size)
                binding.photo.mots = mots
                existant?.let { preselectionner(it, mots) }
                binding.consigne.text = getString(
                    if (mots.isEmpty()) R.string.gabarit_aucun_mot else R.string.gabarit_consigne_ancre
                )
                majEtat()
            }
            .addOnFailureListener {
                Ui.message(binding.root, getString(R.string.gabarit_photo_illisible))
            }
    }

    /** La consigne suit l'avancement : elle dit toujours le geste suivant. */
    private fun majEtat() {
        val ancre = binding.photo.ancre
        val numero = binding.photo.numero
        binding.enregistrer.isEnabled = ancre != null && numero != null
        // Le bouton dit ce qu'il vide : « Effacer » seul laissait croire qu'il
        // effaçait tout, alors qu'il ne touche qu'au role actif.
        binding.effacer.setText(
            if (binding.photo.role == VueGabarit.Role.ANCRE) R.string.gabarit_effacer_ancre
            else R.string.gabarit_effacer_numero
        )
        if (binding.photo.mots.isEmpty()) return
        binding.consigne.text = when {
            ancre == null -> getString(R.string.gabarit_consigne_ancre)
            numero == null -> getString(R.string.gabarit_consigne_numero)
            // Rendre les deux choix lisibles noir sur blanc : c'est le seul
            // moyen de voir qu'on a designe le mauvais mot avant d'enregistrer
            // un gabarit qui vaudra pour toute la palette.
            else -> getString(R.string.gabarit_consigne_pret, ancre.texte, numero.texte)
        }
    }

    private fun enregistrer() {
        val ancre = binding.photo.ancre ?: return
        val numero = binding.photo.numero ?: return
        val nom = binding.nom.text.toString().trim().ifEmpty { ancre.texte.trim() }
        val gabarit = Gabarit.depuisReference(
            existant?.id ?: UUID.randomUUID().toString(),
            nom,
            ancre.texte.trim(),
            ancre.boite,
            numero.boite,
            numero.texte
        )
        // Deux mots aux deux bouts de la photo ne sont pas sur la même
        // étiquette : le dire plutôt que d'enregistrer un gabarit qui ne
        // retrouvera jamais rien.
        if (gabarit == null || !gabarit.raisonnable) {
            Ui.message(binding.root, getString(R.string.gabarit_invraisemblable))
            return
        }
        val avecPhoto = gabarit.copy(photo = enregistrerPhoto(gabarit.id) ?: existant?.photo.orEmpty())
        if (existant == null) Depot.ajouterGabarit(this, avecPhoto)
        else Depot.modifierGabarit(this, avecPhoto)
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_GABARIT_ID, gabarit.id)
                .putExtra(EXTRA_NOM, gabarit.nom)
        )
        finish()
    }

    /** Le fichier de la photo de reference d'un gabarit, s'il existe. */
    private fun fichierPhoto(nom: String): File? =
        if (nom.isEmpty()) null else File(File(filesDir, DOSSIER), nom).takeIf { it.exists() }

    /**
     * Range la photo affichee a cote du gabarit.
     *
     * Elle n'est pas un agrement : c'est la seule piece qui permet de rouvrir
     * un etalonnage et de voir ce qui avait ete designe.
     */
    private fun enregistrerPhoto(id: String): String? {
        val bitmap = courant ?: return null
        val dossier = File(filesDir, DOSSIER).apply { mkdirs() }
        val nom = id + ".jpg"
        return runCatching {
            File(dossier, nom).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }
            nom
        }.getOrNull()
    }

    /**
     * Repose sur la photo ce que le gabarit désigne déjà.
     *
     * À partir des rectangles mémorisés, pas du texte : `Serial Number` et
     * `Type Number` partagent le mot `Number`, et une reconnaissance par texte
     * étirait l'ancre d'un bout à l'autre de l'étiquette.
     */
    private fun preselectionner(g: Gabarit, mots: List<Gabarit.Mot>) {
        fun dans(boite: ScanRoi.Box?) = boite?.let { b ->
            mots.filter {
                b.contains(
                    (it.boite.left + it.boite.right) / 2,
                    (it.boite.top + it.boite.bottom) / 2
                )
            }
        }.orEmpty()

        val ancres = dans(g.refAncre)
        val numeros = dans(g.refSN)
        Log.d(TAG, "preselection : ancre=" + ancres.size + " numero=" + numeros.size)
        if (ancres.isEmpty() && numeros.isEmpty()) return
        // La zone enregistrée peut avoir été élargie à la main : la repasser
        // telle quelle, sinon l'ajustement se perdrait à chaque réouverture.
        binding.photo.preselectionner(ancres, numeros, g.refSN)
    }

    companion object {
        private const val TAG = "GabaritActivity"

        /** Les photos de reference, a cote de celles des lectures. */
        const val DOSSIER = "gabarits"

        const val EXTRA_GABARIT_ID = "gabaritId"
        const val EXTRA_NOM = "nom"

        fun intent(ctx: Context, gabaritId: String? = null): Intent =
            Intent(ctx, GabaritActivity::class.java).putExtra(EXTRA_GABARIT_ID, gabaritId)
    }
}
