package fr.gotatanka.serialscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.gotatanka.serialscanner.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Le relevé d'un lot : ses lectures, leur correction, leur export. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ReadingsAdapter
    private lateinit var lot: Lot
    private val readings get() = lot.readings

    /** Recalculé à chaque modification plutôt qu'à chaque ligne dessinée : la
     *  comparaison croisée est quadratique. Partagé avec l'adaptateur. */
    private val suspects = mutableSetOf<String>()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> if (uris.isNotEmpty()) processImages(uris) }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val serial = data.getStringExtra(ScanActivity.EXTRA_SERIAL) ?: return@registerForActivityResult
        // Filet de sécurité : la liste transmise au scan peut avoir vieilli si
        // une lecture est arrivée entre-temps par l'import.
        if (lot.contient(serial)) {
            message(getString(R.string.deja_au_lot, serial))
            return@registerForActivityResult
        }
        // Un numéro à un caractère près d'un autre du lot est presque toujours
        // le même capot relevé deux fois, avec un caractère lu autrement. On
        // l'enregistre quand même — deux machines voisines existent — mais en
        // rouge, et on le dit tout de suite : l'opérateur a encore la machine
        // sous la main.
        val voisin = lot.proche(serial)
        if (voisin != null) message(getString(R.string.proche_de, serial, voisin))
        ajouter(
            Reading(
                photo = data.getStringExtra(ScanActivity.EXTRA_PHOTO).orEmpty(),
                origine = Origine.SCAN,
                serial = serial,
                model = data.getStringExtra(ScanActivity.EXTRA_MODEL),
                emc = data.getStringExtra(ScanActivity.EXTRA_EMC),
                needsReview = voisin != null ||
                    data.getBooleanExtra(ScanActivity.EXTRA_INCERTAIN, false)
            )
        )
    }

    /** Le contrôle ne rend un résultat qu'une fois le lot entier tranché : c'est
     *  le seul cas où l'on a quelque chose à annoncer. */
    private val verifLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data?.getBooleanExtra(VerifActivity.EXTRA_TERMINE, false) == true) {
            controleTermine()
        }
    }

    private val creerCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(csv().toByteArray()) }
        }.onSuccess { message(getString(R.string.export_ok)) }
            .onFailure { message(getString(R.string.export_echec)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Le lot peut avoir été supprimé pendant que l'écran était en arrière-plan.
        val trouve = Depot.lot(this, intent.getStringExtra(LotsActivity.EXTRA_LOT))
        if (trouve == null) {
            finish()
            return
        }
        lot = trouve

        setSupportActionBar(binding.toolbar)
        // Passer par l'ActionBar et non par la Toolbar : une fois attachée,
        // elle réapplique le label de l'application par-dessus son propre titre.
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = lot.nom
        majSousTitre()

        majSuspects()
        adapter = ReadingsAdapter(
            readings,
            suspects,
            { pos -> confirmerSuppression(pos) },
            { pos -> verifLauncher.launch(VerifActivity.intent(this, lot.id, pos)) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        ItemTouchHelper(balayage()).attachToRecyclerView(binding.list)

        binding.btnImport.setOnClickListener {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnScan.setOnClickListener {
            scanLauncher.launch(
                Intent(this, ScanActivity::class.java)
                    .putStringArrayListExtra(
                        ScanActivity.EXTRA_DEJA_RELEVES,
                        ArrayList(readings.mapNotNull { it.serial })
                    )
                    .putExtra(ScanActivity.EXTRA_MARQUE, lot.marque)
            )
        }
        majCompteur()
    }

    /** Le contrôle modifie les lectures dans le dépôt, qui est la même liste :
     *  il suffit de redemander l'affichage au retour. */
    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
            majCompteur()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_lot, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_envoyer -> { envoyer(); true }
        R.id.action_enregistrer -> { enregistrer(); true }
        R.id.action_renommer -> { renommer(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // --- lectures ---

    private fun ajouter(r: Reading) {
        readings.add(r)
        Depot.sauver(this)
        adapter.notifyItemInserted(readings.size - 1)
        binding.list.scrollToPosition(readings.size - 1)
        majCompteur()
    }

    /** Balayer une ligne la supprime, avec un délai d'annulation : le relevé se
     *  fait debout, une main sur la machine, et le geste part tout seul. */
    private fun balayage() = object : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            cible: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val pos = vh.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            supprimerLigne(pos)
        }
    }

    private fun confirmerSuppression(pos: Int) {
        val r = readings.getOrNull(pos) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(r.serial ?: getString(R.string.status_review))
            .setMessage(R.string.supprimer_lecture)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_supprimer) { _, _ -> supprimerLigne(pos) }
            .show()
    }

    /** Supprime en laissant le temps de se rétracter : une lecture perdue,
     *  c'est une machine à ressortir de la palette. */
    private fun supprimerLigne(pos: Int) {
        val supprime = readings.removeAt(pos)
        Depot.sauver(this)
        adapter.notifyItemRemoved(pos)
        majCompteur()
        Ui.message(binding.root, getString(R.string.ligne_supprimee), binding.actions)
            .setAction(R.string.action_annuler) {
                readings.add(pos, supprime)
                Depot.sauver(this)
                adapter.notifyItemInserted(pos)
                majCompteur()
            }
            // La vignette n'est supprimée qu'une fois l'annulation hors de
            // portée : la reprendre sans sa photo serait une lecture qu'on ne
            // peut plus contrôler.
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(sb: Snackbar, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) Depot.purgerPhotos(this@MainActivity)
                }
            })
            .show()
    }

    /**
     * Toutes les lignes du lot ont été ouvertes et tranchées, photo à l'appui.
     *
     * Le dire franchement, une fois : sans point d'arrêt, rien ne distingue
     * « j'ai fini le lot » de « je me suis arrêté au milieu », et c'est cette
     * liste-là qu'on remonte au client. Le décompte des lignes à reprendre est
     * dans le même message — finir le contrôle n'est pas finir le lot.
     */
    private fun controleTermine() {
        majCompteur()
        adapter.notifyDataSetChanged()
        val aReprendre = lot.aReprendre
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.controle_fini_titre)
            .setMessage(
                if (aReprendre == 0) getString(R.string.controle_fini_ok, readings.size)
                else getString(R.string.controle_fini_reste, readings.size, aReprendre)
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun processImages(uris: List<Uri>) {
        lifecycleScope.launch {
            for (uri in uris) {
                val r = runCatching { lireImage(uri) }
                    .getOrElse {
                        Reading(uri.toString(), Origine.IMPORT, null, null, null, true)
                    }
                ajouter(r)
            }
        }
    }

    private suspend fun lireImage(uri: Uri): Reading = withContext(Dispatchers.IO) {
        val texte = reconnaitre(InputImage.fromFilePath(this@MainActivity, uri))
        // Sur une photo de capot, la ligne gravée occupe quelques pour cent de
        // l'image : ML Kit sous-échantillonne l'ensemble et la perd. Reconnue
        // une première fois, elle sert de repère pour une seconde passe à
        // pleine résolution sur ce seul rectangle.
        val premiere = SerialParser.parse(texte.text, lot.profil())
        // La seconde passe ne voit que le rectangle de la ligne : elle apporte
        // le numéro, mais le modèle et l'EMC restent ceux de la vue d'ensemble
        // quand le recadrage les a laissés dehors.
        val recadree = if (premiere.serial == null) relireSurAncre(uri, texte) else null
        val serial = recadree?.serial ?: premiere.serial
        Reading(
            photo = uri.toString(),
            origine = Origine.IMPORT,
            serial = serial,
            model = premiere.model ?: recadree?.model,
            emc = premiere.emc ?: recadree?.emc,
            // Une lettre proscrite par le format vaut lecture douteuse : c'est
            // un O lu pour un 0 qui a fait passer un faux numéro en vert.
            needsReview = serial == null ||
                Controle.ambigu(serial, SerialParser.formatPour(texte.text, lot.profil()))
        )
    }

    private suspend fun reconnaitre(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    /** Seconde passe sur le rectangle de la ligne « Serial », ou null si elle
     *  n'a pas été repérée ou si l'image n'a pas pu être rechargée. */
    private suspend fun relireSurAncre(uri: Uri, texte: Text): SerialParser.Reading? {
        val ligne = texte.textBlocks.flatMap { it.lines }
            .firstOrNull { SerialParser.voitAncre(it.text) } ?: return null
        val cadre = ligne.boundingBox ?: return null
        val complete = bitmapRedresse(uri) ?: return null
        return try {
            // Marge généreuse : la boîte de la ligne s'arrête souvent avant les
            // derniers caractères quand ceux-ci sont mal contrastés.
            val marge = (cadre.height() * 0.6f).toInt()
            val gauche = (cadre.left - marge).coerceAtLeast(0)
            val haut = (cadre.top - marge).coerceAtLeast(0)
            val largeur = (cadre.width() + 2 * marge).coerceAtMost(complete.width - gauche)
            val hauteur = (cadre.height() + 2 * marge).coerceAtMost(complete.height - haut)
            if (largeur <= 0 || hauteur <= 0) return null
            val morceau = Bitmap.createBitmap(complete, gauche, haut, largeur, hauteur)
            val relu = reconnaitre(InputImage.fromBitmap(morceau, 0))
            morceau.recycle()
            SerialParser.parse(relu.text, lot.profil()).takeIf { it.serial != null }
        } catch (e: Exception) {
            Log.w(TAG, "relecture recadrée impossible", e)
            null
        } finally {
            complete.recycle()
        }
    }

    /** L'image dans le repère où ML Kit a rendu ses coordonnées : orientation
     *  EXIF appliquée, comme le fait `InputImage.fromFilePath`. */
    private fun bitmapRedresse(uri: Uri): Bitmap? {
        val brut = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degres = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degres == 0f) return brut
        val m = Matrix().apply { postRotate(degres) }
        val tourne = Bitmap.createBitmap(brut, 0, 0, brut.width, brut.height, m, true)
        if (tourne !== brut) brut.recycle()
        return tourne
    }

    // --- export ---

    private fun csv(): String = CsvExport.document(readings.map { CsvExport.ligne(it) })

    private fun enregistrer() {
        creerCsv.launch(CsvExport.nomFichier(lot.nom, System.currentTimeMillis()))
    }

    private fun envoyer() {
        val dossier = File(filesDir, "exports").apply { mkdirs() }
        val fichier = File(dossier, CsvExport.nomFichier(lot.nom, System.currentTimeMillis()))
        runCatching {
            fichier.writeText(csv())
            val uri = FileProvider.getUriForFile(this, "$packageName.fichiers", fichier)
            val envoi = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.mail_sujet, lot.nom))
                putExtra(
                    Intent.EXTRA_TEXT,
                    getString(R.string.mail_corps, lot.nom, readings.size, lot.valides, lot.aReprendre)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envoi, getString(R.string.action_envoyer)))
        }.onFailure { message(getString(R.string.export_echec)) }
    }

    private fun renommer() {
        val champ = EditText(this).apply {
            setText(lot.nom)
            setSelection(lot.nom.length)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_renommer)
            .setView(champ)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val nom = champ.text.toString().trim()
                if (nom.isNotEmpty()) {
                    Depot.renommer(this, lot.id, nom)
                    lot = Depot.lot(this, lot.id) ?: lot
                    supportActionBar?.title = nom
                    majSousTitre()
                }
            }
            .show()
    }

    /** La marque du lot est un réglage de lecture : la garder sous les yeux
     *  évite de relever trente Dell avec le format Apple. */
    private fun majSousTitre() {
        supportActionBar?.subtitle =
            if (lot.marque == Lot.MARQUE_AUTO) null else lot.marque
    }

    private fun majSuspects() {
        suspects.clear()
        suspects.addAll(lot.suspects)
    }

    private fun majCompteur() {
        majSuspects()
        binding.counter.text = getString(R.string.counter_fmt, lot.controles, readings.size)
        val vide = readings.isEmpty()
        binding.empty.isVisible = vide
        binding.list.isVisible = !vide
    }

    private fun message(texte: String) =
        Ui.message(binding.root, texte, binding.actions).show()

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
