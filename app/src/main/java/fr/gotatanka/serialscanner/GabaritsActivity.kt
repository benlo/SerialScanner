package fr.gotatanka.serialscanner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.gotatanka.serialscanner.databinding.ActivityGabaritsBinding
import fr.gotatanka.serialscanner.databinding.ItemLotBinding

/**
 * La bibliothèque de gabarits.
 *
 * Un gabarit s'étalonne une fois sur un modèle de machine et sert à toutes les
 * palettes qui en contiennent — d'où une liste à part, et non un réglage caché
 * dans un lot.
 */
class GabaritsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGabaritsBinding
    private lateinit var adapter: Adapter

    private val editer = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { resultat ->
        rafraichir()
        // Confirmer explicitement : l'ecran se fermait sans un mot, et rien ne
        // distinguait un enregistrement reussi d'un abandon.
        resultat.data?.getStringExtra(GabaritActivity.EXTRA_NOM)?.let {
            Ui.message(binding.root, getString(R.string.gabarit_enregistre, it))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGabaritsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = Adapter(Depot.gabarits(this), ::menu)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnNouveau.setOnClickListener { editer.launch(GabaritActivity.intent(this)) }
        rafraichir()
    }

    override fun onResume() {
        super.onResume()
        rafraichir()
    }

    private fun rafraichir() {
        adapter.notifyDataSetChanged()
        binding.empty.visibility = if (Depot.gabarits(this).isEmpty()) android.view.View.VISIBLE
        else android.view.View.GONE
    }

    private fun menu(gabarit: Gabarit) {
        val actions = listOf(
            getString(R.string.gabarit_titre_modifier),
            getString(R.string.action_supprimer)
        )
        AlertDialog.Builder(this)
            .setTitle(gabarit.nom)
            .setItems(actions.toTypedArray()) { _, i ->
                if (i == 0) editer.launch(GabaritActivity.intent(this, gabarit.id))
                else supprimer(gabarit)
            }
            .show()
    }

    /**
     * Supprime, ou explique pourquoi c'est refusé.
     *
     * Un gabarit retiré sous les pieds d'un lot laisserait ce lot à moitié
     * étalonné, sans que rien à l'écran ne dise pourquoi ses lectures ont
     * changé de comportement en cours de route.
     */
    private fun supprimer(gabarit: Gabarit) {
        val bloquants = GabaritStore.utilisePar(gabarit.id, Depot.lots(this))
        if (bloquants.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(gabarit.nom)
                .setMessage(
                    getString(
                        R.string.gabarit_utilise,
                        bloquants.size,
                        bloquants.joinToString(", ") { it.nom }
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.gabarit_confirm_supprimer, gabarit.nom))
            .setNegativeButton(R.string.action_annuler, null)
            .setPositiveButton(R.string.action_supprimer) { _, _ ->
                Depot.supprimerGabarit(this, gabarit.id)
                rafraichir()
            }
            .show()
    }

    private class Adapter(
        private val items: List<Gabarit>,
        private val onMenu: (Gabarit) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {

        class VH(val b: ItemLotBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemLotBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val g = items[position]
            val ctx = holder.b.root.context
            holder.b.nom.text = g.nom
            // Un gabarit sans format vient d'avant que le gabarit en porte un :
            // il place bien la zone, mais ne sait pas quelle longueur attendre,
            // et le lot retombe alors sur le format Apple. Le dire franchement,
            // sinon le scan échoue sans que rien n'explique pourquoi.
            holder.b.detail.text = if (g.format == null) {
                ctx.getString(R.string.gabarit_a_reetalonner)
            } else {
                // La géométrie en clair : c'est ce qui permet de reconnaître un
                // gabarit mal étalonné sans avoir à le rouvrir.
                ctx.getString(R.string.gabarit_detail, g.ancre, g.longueur, g.dx, g.dy)
            }
            holder.b.root.setOnClickListener { onMenu(g) }
            holder.b.menu.setOnClickListener { onMenu(g) }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, GabaritsActivity::class.java)
    }
}
