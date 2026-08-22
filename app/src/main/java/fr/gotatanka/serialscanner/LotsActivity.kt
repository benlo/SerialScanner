package fr.gotatanka.serialscanner

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import fr.gotatanka.serialscanner.databinding.ActivityLotsBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Écran d'accueil : les lots de relevé. Un lot par palette, par client ou par
 *  journée, selon le découpage de l'atelier. */
class LotsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLotsBinding
    private lateinit var adapter: LotsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLotsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = LotsAdapter(Depot.lots(this), ::ouvrir, ::menu)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        binding.btnNouveau.setOnClickListener { creer() }
        binding.btnGabarits.setOnClickListener { startActivity(GabaritsActivity.intent(this)) }

        // Au lancement : rattrape tout ce qu'une suppression antérieure, un
        // scan abandonné ou une version précédente a laissé derrière.
        Depot.purgerPhotos(this)
    }

    override fun onResume() {
        super.onResume()
        // Le compte de lectures a pu changer dans l'écran du lot.
        adapter.notifyDataSetChanged()
        rafraichir()
    }

    private fun rafraichir() {
        val vide = Depot.lots(this).isEmpty()
        binding.empty.isVisible = vide
        binding.list.isVisible = !vide
    }

    private fun ouvrir(lot: Lot) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_LOT, lot.id))
    }

    /**
     * Créer un lot : le gabarit, puis le nom.
     *
     * **Plus de question de marque.** Elle ne servait qu'à donner le format —
     * longueur et alphabet —, et c'est désormais le gabarit qui le porte, relevé
     * sur le numéro que l'opérateur a lui-même désigné. Une table de marques
     * devinait ; le gabarit constate.
     *
     * Sans gabarit, le lot lit à l'ancrage textuel et retombe sur le format
     * Apple, le plus contraint et le seul éprouvé sur un parc réel. C'est le
     * bon défaut : mieux vaut refuser une étiquette inconnue que valider un
     * numéro douteux. La marque reste changeable par le menu du lot, pour le cas
     * d'un parc non-Apple qu'on préfère lire sans étalonner.
     */
    private fun creer() {
        demanderGabarit { gabaritId ->
            val jour = Instant.now().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM"))
            // Proposer un nom parlant : dans un atelier, un lot sans nom se
            // retrouve à deux palettes d'écart le lendemain. Le gabarit nomme
            // mieux qu'une date seule — c'est le modèle de machine.
            val gabarit = Depot.gabarit(this, gabaritId)
            val defaut = if (gabarit == null) {
                getString(R.string.lot_nom_defaut, jour)
            } else {
                getString(R.string.lot_nom_marque, gabarit.nom, jour)
            }
            demanderNom(getString(R.string.titre_nouveau_lot), defaut) { nom ->
                val lot = Depot.creer(this, nom, Lot.MARQUE_AUTO, gabaritId)
                adapter.notifyItemInserted(0)
                rafraichir()
                ouvrir(lot)
            }
        }
    }

    /** Les marques proposées : celles du parc, plus la détection automatique
     *  pour un lot dépareillé. */
    private fun marques() =
        listOf(Lot.MARQUE_AUTO) + SerialParser.PROFILS.map { it.nom }

    private fun demanderMarque(suite: (String) -> Unit) {
        val choix = marques()
        val libelles = choix.map {
            if (it == Lot.MARQUE_AUTO) getString(R.string.marque_auto) else it
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.titre_marque)
            .setItems(libelles) { _, i -> suite(choix[i]) }
            .show()
    }

    /**
     * Le gabarit du lot, choisi a la creation.
     *
     * Passe sans rien demander quand la bibliotheque est vide : poser une
     * question a un seul choix possible ne renseigne personne. Un lot sans
     * gabarit lit a l'ancrage textuel, et s'etalonne tout seul s'il y arrive.
     */
    private fun demanderGabarit(suite: (String?) -> Unit) {
        val gabarits = Depot.gabarits(this)
        if (gabarits.isEmpty()) {
            suite(null)
            return
        }
        val libelles = (listOf(getString(R.string.gabarit_aucun)) + gabarits.map { it.nom })
            .toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.titre_gabarit_lot)
            .setItems(libelles) { _, i -> suite(if (i == 0) null else gabarits[i - 1].id) }
            .show()
    }

    private fun menu(lot: Lot) {
        val actions = arrayOf(
            getString(R.string.action_renommer),
            getString(R.string.action_marque),
            getString(R.string.action_gabarit_lot),
            getString(R.string.action_supprimer_lot)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(lot.nom)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> demanderNom(getString(R.string.action_renommer), lot.nom) { nom ->
                        Depot.renommer(this, lot.id, nom)
                        adapter.notifyDataSetChanged()
                    }
                    1 -> demanderMarque { marque ->
                        Depot.changerMarque(this, lot.id, marque)
                        adapter.notifyDataSetChanged()
                    }
                    // Un lot vit plus longtemps qu'une idée : le gabarit qui lui
                    // convient est souvent étalonné après sa création.
                    2 -> if (Depot.gabarits(this).isEmpty()) {
                        Ui.message(binding.root, getString(R.string.gabarit_bibliotheque_vide))
                    } else {
                        demanderGabarit { gabaritId ->
                            Depot.remplacer(this, lot.copy(gabaritId = gabaritId))
                            adapter.notifyDataSetChanged()
                            rafraichir()
                        }
                    }
                    3 -> confirmerSuppression(lot)
                }
            }
            .show()
    }

    private fun confirmerSuppression(lot: Lot) {
        // Un lot supprimé emporte tout le relevé de la palette : la confirmation
        // nomme ce qu'on perd plutôt que de demander « êtes-vous sûr ».
        MaterialAlertDialogBuilder(this)
            .setTitle(lot.nom)
            .setMessage(getString(R.string.confirm_supprimer_lot, lot.readings.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_supprimer_lot) { _, _ ->
                val i = Depot.lots(this).indexOfFirst { it.id == lot.id }
                Depot.supprimer(this, lot.id)
                // Le relevé de la palette part avec ses vignettes : les garder
                // ne prouverait plus rien, faute de numéro auquel les rattacher.
                Depot.purgerPhotos(this)
                if (i >= 0) adapter.notifyItemRemoved(i)
                rafraichir()
            }
            .show()
    }

    private fun demanderNom(titre: String, valeur: String, suite: (String) -> Unit) {
        val champ = EditText(this).apply {
            setText(valeur)
            setSelection(valeur.length)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titre)
            .setView(champ)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val nom = champ.text.toString().trim()
                if (nom.isNotEmpty()) suite(nom)
            }
            .show()
    }

    companion object {
        const val EXTRA_LOT = "lot"
    }
}
