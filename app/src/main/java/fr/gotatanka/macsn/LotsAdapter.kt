package fr.gotatanka.macsn

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import fr.gotatanka.macsn.databinding.ItemLotBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LotsAdapter(
    private val items: List<Lot>,
    private val onOuvrir: (Lot) -> Unit,
    private val onMenu: (Lot) -> Unit
) : RecyclerView.Adapter<LotsAdapter.VH>() {

    inner class VH(val b: ItemLotBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(lot: Lot) {
            b.nom.text = lot.nom
            val ctx = b.root.context
            val date = Instant.ofEpochMilli(lot.cree).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            // Le nombre à reprendre n'apparaît que s'il y en a : une palette
            // propre ne doit pas afficher un « 0 à reprendre » qui attire l'œil.
            b.detail.text = buildString {
                if (lot.marque != Lot.MARQUE_AUTO) append(lot.marque).append(" · ")
                append(ctx.getString(R.string.lot_detail_fmt, lot.readings.size, date))
                if (lot.aReprendre > 0) {
                    append(ctx.getString(R.string.lot_detail_reprise, lot.aReprendre))
                }
            }
            b.root.setOnClickListener { onOuvrir(lot) }
            b.menu.setOnClickListener { onMenu(lot) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemLotBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size
}
