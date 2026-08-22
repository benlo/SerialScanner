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

    private fun creer() {
        // La marque d'abord : elle resserre le format attendu, donc écarte du
        // bruit dès la première lecture. Elle sert aussi à nommer le lot.
        demanderMarque { marque ->
            val jour = Instant.now().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM"))
            // Proposer un nom parlant : dans un atelier, un lot sans nom se
            // retrouve à deux palettes d'écart le lendemain.
            val defaut = if (marque == Lot.MARQUE_AUTO) {
                getString(R.string.lot_nom_defaut, jour)
            } else {
                getString(R.string.lot_nom_marque, marque, jour)
            }
            demanderNom(getString(R.string.titre_nouveau_lot), defaut) { nom ->
                val lot = Depot.creer(this, nom, marque)
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

    private fun menu(lot: Lot) {
        val actions = arrayOf(
            getString(R.string.action_renommer),
            getString(R.string.action_marque),
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
                    2 -> confirmerSuppression(lot)
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
