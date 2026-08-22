package fr.gotatanka.serialscanner

import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

/**
 * Les messages passagers de l'app.
 *
 * Le bandeau par défaut de Material est translucide et posé tout en bas : sur
 * cet écran, il se confond avec le fond de la caméra ou de la liste, et il
 * recouvre les boutons d'action au moment précis où l'on veut y revenir. On le
 * remonte au-dessus de la barre d'actions, on l'opacifie, et on le raccourcit —
 * un message d'atelier se lit en deux secondes ou ne se lit pas.
 */
object Ui {

    fun message(racine: View, texte: CharSequence, ancre: View? = null): Snackbar {
        val ctx = racine.context
        val barre = Snackbar.make(racine, texte, Snackbar.LENGTH_SHORT)
        ancre?.let { barre.anchorView = it }
        barre.view.background = ContextCompat.getDrawable(ctx, R.drawable.fond_message)
        // Sans cette ligne, rien n'est lisible : Material pose sa propre teinte
        // par-dessus le fond qu'on vient de mettre — un gris clair, sur lequel
        // le texte clair du thème disparaît.
        barre.view.backgroundTintList = null
        barre.setTextColor(ctx.getColor(R.color.texte))
        barre.setActionTextColor(ctx.getColor(R.color.ambre))
        return barre
    }
}
