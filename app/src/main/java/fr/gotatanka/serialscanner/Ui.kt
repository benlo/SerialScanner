package fr.gotatanka.serialscanner

import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
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

    /**
     * Teinte, dans un champ de saisie, les caractères d'une classe de confusion.
     *
     * L'ambre est déjà la couleur du « regarde ici » — celle du cadre de visée.
     * Elle ne dit pas *faux*, le terracotta s'en charge ; elle dit *c'est là que
     * ça peut l'être*. Réservé à l'écran de contrôle : c'est le seul endroit où
     * l'œil compare le texte à la photo, et teinter la liste ne ferait du bruit.
     *
     * La teinte se pose aussi à la frappe, sinon un caractère corrigé à la main
     * resterait signalé — ou pire, ne le serait pas.
     */
    fun teinterConfusables(champ: EditText) {
        val couleur = champ.context.getColor(R.color.ambre)
        fun poser() {
            val texte = champ.text ?: return
            // Poser des spans ne renotifie pas le TextWatcher — seule une
            // modification de texte le fait. Pas de garde nécessaire.
            texte.getSpans(0, texte.length, ForegroundColorSpan::class.java)
                .forEach { texte.removeSpan(it) }
            Controle.positionsConfusables(texte.toString()).forEach { i ->
                texte.setSpan(
                    ForegroundColorSpan(couleur), i, i + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        poser()
        champ.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = poser()
        })
    }
}
