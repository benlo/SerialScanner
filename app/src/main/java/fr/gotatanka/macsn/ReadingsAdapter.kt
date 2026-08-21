package fr.gotatanka.macsn

import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import fr.gotatanka.macsn.databinding.ItemReadingBinding

/**
 * La liste du lot : une ligne par machine, en lecture seule.
 *
 * Elle ne montre que ce qui sert à décider quoi contrôler — le numéro et son
 * état. Le modèle et l'EMC restent dans l'export, pas à l'écran : sur trente
 * lignes, ils poussent le numéro hors de vue sans jamais servir au relevé.
 */
class ReadingsAdapter(
    private val items: List<Reading>,
    /** Numéros à un caractère près d'un autre du lot — voir [Lot.suspects]. */
    private val suspects: Set<String>,
    private val onSupprimer: (position: Int) -> Unit,
    private val onControler: (position: Int) -> Unit
) : RecyclerView.Adapter<ReadingsAdapter.VH>() {

    inner class VH(val b: ItemReadingBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(r: Reading) {
            val ctx = b.root.context
            // Un scan live sans vignette laisse la case vide plutôt qu'une
            // image d'une autre ligne récupérée par le recyclage.
            b.thumb.setImageURI(if (r.photo.isBlank()) null else Uri.parse(r.photo))

            b.serial.text = r.serial ?: ctx.getString(R.string.review_hint)
            b.serial.setTextColor(
                ctx.getColor(if (r.serial == null) R.color.texte_faible else R.color.texte)
            )

            // Un doublon se voit sur la ligne elle-même : chercher deux lignes
            // identiques dans une liste de trente ne se fait pas à l'œil. Et
            // celui qui ne diffère que d'un caractère ne s'y voit pas du tout.
            val double = r.serial != null && items.count { it.serial == r.serial } > 1
            val proche = !double && r.serial != null && r.serial in suspects
            b.meta.isVisible = double || proche
            b.meta.text = ctx.getString(
                if (double) R.string.ligne_doublon else R.string.ligne_proche
            )

            // Trois états, parce que « la machine a lu » n'est pas « quelqu'un
            // a vérifié » : c'est cette confusion qui a laissé passer deux faux
            // numéros verts sur le lot du 20/08.
            val etat = if (double || proche) Etat.A_REPRENDRE else r.etat
            b.status.backgroundTintList = ColorStateList.valueOf(
                ctx.getColor(
                    when (etat) {
                        Etat.A_REPRENDRE -> R.color.a_reprendre
                        Etat.CONTROLE -> R.color.ok
                        Etat.LU -> R.color.lu
                    }
                )
            )
            b.status.contentDescription = ctx.getString(
                when (etat) {
                    Etat.A_REPRENDRE -> R.string.status_review
                    Etat.CONTROLE -> R.string.status_ok
                    Etat.LU -> R.string.status_lu
                }
            )

            // Le balayage supprime aussi, mais rien ne l'annonce : l'appui long
            // est le geste qu'on trouve sans qu'on l'explique.
            b.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSupprimer(pos)
                true
            }

            // Toute la ligne ouvre le contrôle : c'est le seul endroit où on
            // corrige, et il montre la photo en même temps que le numéro.
            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onControler(pos)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemReadingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size
}
