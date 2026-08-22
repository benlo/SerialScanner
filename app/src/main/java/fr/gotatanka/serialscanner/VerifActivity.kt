package fr.gotatanka.serialscanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import fr.gotatanka.serialscanner.databinding.ActivityVerifBinding

/**
 * Contrôle d'une lecture : sa photo et son numéro dans la même vue.
 *
 * Ce que la double lecture ne peut pas attraper, c'est une erreur que ML Kit
 * commet deux fois — sur le lot du 20/08, deux numéros verts sur vingt-trois
 * étaient faux. Le seul juge est l'œil, et il ne juge que s'il voit la gravure
 * en même temps que le numéro proposé. D'où l'écran : photo agrandissable,
 * champ éditable dessous, et de quoi passer à la machine suivante sans
 * remonter à la liste.
 */
class VerifActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerifBinding
    private lateinit var lot: Lot
    private var index = 0

    /** Vrai pendant qu'on remplit les champs : sans ce garde-fou, remplir la
     *  vue ferait passer chaque ligne parcourue en saisie manuelle. */
    private var remplissage = false

    private val format get() = lot.profil()?.format ?: SerialParser.APPLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val trouve = Depot.lot(this, intent.getStringExtra(EXTRA_LOT))
        if (trouve == null || trouve.readings.isEmpty()) {
            finish()
            return
        }
        lot = trouve
        // La ligne affichée fait partie de l'état de l'écran : sans elle, une
        // recréation (rotation, retour après une mise en veille longue) rouvre
        // la ligne d'origine et applique à celle-là ce qui valait pour l'autre.
        index = (savedInstanceState?.getInt(ETAT_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, lot.readings.lastIndex)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.verif_titre)

        // Le regard va d'abord aux caractères qui peuvent être faux — c'est le
        // seul écran où l'œil arbitre entre le texte et la photo.
        Ui.teinterConfusables(binding.serial)

        binding.serial.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, d: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, d: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (remplissage) return
                enregistrer(s?.toString().orEmpty())
                majAlertes(s?.toString().orEmpty())
            }
        })

        // Trancher, c'est contrôler : les deux boutons posent `controle`, l'un
        // en vert, l'autre en rouge. Tant qu'aucun n'est pressé la ligne reste
        // « lue », c'est-à-dire proposée par la machine et rien de plus.
        binding.statut.addOnButtonCheckedListener { _, id, coche ->
            if (remplissage || !coche) return@addOnButtonCheckedListener
            val ligne = courante() ?: return@addOnButtonCheckedListener
            remplacer(ligne.copy(needsReview = id == R.id.btnRevoir, controle = true))
            // Enchaîner tout seul : contrôler un lot, c'est regarder, trancher,
            // ligne suivante. Décalé d'une image, sinon on redessine le
            // sélecteur depuis son propre écouteur.
            binding.statut.post { apresTranche() }
        }

        binding.btnPrec.setOnClickListener { aller(index - 1) }
        binding.btnSuiv.setOnClickListener { aller(index + 1) }
        binding.btnCorriger.setOnClickListener {
            val corrige = Controle.desambiguise(binding.serial.text.toString())
            binding.serial.setText(corrige)
            binding.serial.setSelection(corrige.length)
        }
        binding.photo.setOnClickListener { /* absorbe le tap, le zoom fait le reste */ }

        afficher()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ETAT_INDEX, index)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun courante(): Reading? = lot.readings.getOrNull(index)

    /** Passe à la ligne visée, ou reste là s'il n'y en a plus : la fin du lot
     *  ne doit pas fermer l'écran sous les doigts de l'opérateur. */
    private fun aller(cible: Int) {
        if (cible !in lot.readings.indices) return
        index = cible
        afficher()
    }

    /**
     * Après avoir tranché : la ligne suivante qui reste à contrôler, ou la fin.
     *
     * La fin, c'est le retour à la liste : c'est là qu'on relit le lot entier
     * et qu'on l'exporte. Rester sur la dernière photo laisserait l'opérateur
     * sans signal que le lot est bouclé — et c'est le seul moment où l'on peut
     * l'affirmer sans mentir.
     */
    private fun apresTranche() {
        val suivant = lot.prochainAControler(index)
        if (suivant == null) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TERMINE, true))
            finish()
        } else {
            aller(suivant)
        }
    }

    private fun afficher() {
        val r = courante() ?: return finish()
        remplissage = true

        supportActionBar?.subtitle = getString(
            R.string.verif_position, index + 1, lot.readings.size, origine(r)
        )

        val aPhoto = r.photo.isNotBlank()
        binding.photo.isVisible = aPhoto
        binding.sansPhoto.isVisible = !aPhoto
        if (aPhoto) binding.photo.setImageURI(Uri.parse(r.photo))

        binding.serial.setText(r.serial.orEmpty())
        binding.serial.setSelection(binding.serial.text.length)
        // Rien de coché tant que personne n'a tranché : un bouton présélectionné
        // se lirait comme une décision déjà prise.
        if (r.controle) {
            binding.statut.check(if (r.needsReview) R.id.btnRevoir else R.id.btnOk)
        } else {
            binding.statut.clearChecked()
        }

        binding.btnPrec.isEnabled = index > 0
        binding.btnSuiv.isEnabled = index < lot.readings.lastIndex

        remplissage = false
        majAlertes(r.serial.orEmpty())
    }

    /** Enregistre le numéro saisi sans toucher au statut : après contrôle à
     *  l'œil, c'est l'opérateur qui le fixe, pas la frappe. */
    private fun enregistrer(saisi: String) {
        val avant = courante() ?: return
        val nouveau = saisi.trim().uppercase()
        if (nouveau == avant.serial.orEmpty()) return
        remplacer(
            avant.copy(
                serial = nouveau.ifBlank { null },
                origine = Origine.SAISIE
            )
        )
    }

    private fun remplacer(r: Reading) {
        lot.readings[index] = r
        Depot.sauver(this)
    }

    private fun majAlertes(serial: String) {
        val doublon = serial.isNotBlank() &&
            lot.readings.filterIndexed { i, _ -> i != index }.any { it.serial == serial }
        val voisin = if (serial.isBlank()) null else lot.proche(serial, index)
        val alertes = Controle.alertes(serial, format, doublon, voisin)

        val texte = alertes.joinToString(" · ") {
            when (it) {
                Controle.Alerte.VIDE -> getString(R.string.verif_alerte_vide)
                Controle.Alerte.LONGUEUR -> getString(
                    R.string.verif_alerte_longueur,
                    serial.length,
                    format.longueurs.sorted().joinToString(" ou ")
                )
                Controle.Alerte.AMBIGU -> getString(R.string.verif_alerte_ambigu)
                Controle.Alerte.DOUBLON -> getString(R.string.verif_alerte_doublon)
                Controle.Alerte.PROCHE -> getString(R.string.verif_alerte_proche, voisin)
            }
        }
        binding.alerte.text = texte
        binding.alerte.isVisible = texte.isNotEmpty()

        // La correction est proposée, jamais appliquée d'office : c'est la
        // photo qui tranche, et elle est juste au-dessus.
        val ambigu = Controle.Alerte.AMBIGU in alertes
        binding.btnCorriger.isVisible = ambigu
        if (ambigu) {
            binding.btnCorriger.text =
                getString(R.string.verif_corriger, Controle.desambiguise(serial))
        }
    }

    private fun origine(r: Reading) = getString(
        when (r.origine) {
            Origine.SCAN -> R.string.origine_scan
            Origine.IMPORT -> R.string.origine_import
            Origine.SAISIE -> R.string.origine_saisie
        }
    )

    companion object {
        /** Vrai au retour quand la dernière ligne du lot vient d'être tranchée. */
        const val EXTRA_TERMINE = "termine"

        private const val EXTRA_LOT = "lot"
        private const val EXTRA_INDEX = "index"
        private const val ETAT_INDEX = "index_affiche"

        fun intent(ctx: Context, lotId: String, index: Int) =
            Intent(ctx, VerifActivity::class.java)
                .putExtra(EXTRA_LOT, lotId)
                .putExtra(EXTRA_INDEX, index)
    }
}
