package fr.gotatanka.serialscanner

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Photo pinçable et déplaçable.
 *
 * La vignette d'un scan fait quelques centaines de pixels de large et la
 * gravure y occupe une bande : à l'échelle de l'écran, distinguer un 0 d'un O
 * ou un 6 d'un E ne se fait pas. Sans zoom, l'écran de contrôle ne contrôle
 * rien — c'est la raison d'être de cette vue.
 */
class VuePhoto @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val transfo = Matrix()
    private val bornes = RectF()
    private var ajustee = 1f
    private var echelle = 1f

    private val pincee = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomer(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        }
    )

    private val gestes = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(
                depart: MotionEvent?,
                courant: MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                transfo.postTranslate(-dx, -dy)
                recadrer()
                return true
            }

            /** Le double-tap fait l'aller-retour : on agrandit sur le caractère
             *  douteux, on revient à la vue d'ensemble, sans viser un bouton. */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomer(if (echelle > ajustee * 1.4f) ajustee / echelle else 4f, e.x, e.y)
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageURI(uri: android.net.Uri?) {
        super.setImageURI(uri)
        ajuster()
    }

    override fun onSizeChanged(l: Int, h: Int, ancienL: Int, ancienH: Int) {
        super.onSizeChanged(l, h, ancienL, ancienH)
        ajuster()
    }

    /**
     * Cadre l'image à l'ouverture.
     *
     * Pas à la vue d'ensemble : une vignette de scan est une bande large et
     * basse, ajustée à la largeur elle laisse deux bandeaux noirs et des
     * caractères de trois millimètres. On ouvre donc sur la hauteur remplie,
     * bornée pour qu'une photo de galerie, elle, reste entière.
     */
    fun ajuster() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val li = d.intrinsicWidth.toFloat()
        val hi = d.intrinsicHeight.toFloat()
        if (li <= 0f || hi <= 0f) return
        ajustee = minOf(width / li, height / hi)
        echelle = (height / hi).coerceIn(ajustee, ajustee * OUVERTURE)
        transfo.setScale(echelle, echelle)
        transfo.postTranslate((width - li * echelle) / 2f, (height - hi * echelle) / 2f)
        imageMatrix = transfo
        recadrer()
    }

    private fun zoomer(facteur: Float, x: Float, y: Float) {
        // Borner avant d'appliquer : un pincement rapide dépasse largement les
        // limites en un seul évènement, et l'image ne revient jamais seule.
        val vise = (echelle * facteur).coerceIn(ajustee, ajustee * MAX)
        val reel = vise / echelle
        if (reel == 1f) return
        echelle = vise
        transfo.postScale(reel, reel, x, y)
        recadrer()
    }

    /** Empêche l'image de quitter l'écran : recentrée tant qu'elle y tient,
     *  bloquée sur ses bords dès qu'elle le dépasse. */
    private fun recadrer() {
        val d = drawable ?: return
        bornes.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        transfo.mapRect(bornes)
        var dx = 0f
        var dy = 0f
        if (bornes.width() <= width) {
            dx = (width - bornes.width()) / 2f - bornes.left
        } else {
            if (bornes.left > 0) dx = -bornes.left
            if (bornes.right < width) dx = width - bornes.right
        }
        if (bornes.height() <= height) {
            dy = (height - bornes.height()) / 2f - bornes.top
        } else {
            if (bornes.top > 0) dy = -bornes.top
            if (bornes.bottom < height) dy = height - bornes.bottom
        }
        transfo.postTranslate(dx, dy)
        imageMatrix = transfo
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pincee.onTouchEvent(event)
        gestes.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    private companion object {
        /** Douze fois la vue ajustée : au-delà, la vignette n'a plus de pixels
         *  à montrer, on agrandit du flou. */
        const val MAX = 12f

        /** Agrandissement maximal consenti à l'ouverture. Au-delà on ne voit
         *  plus assez de la ligne pour savoir où on est dessus. */
        const val OUVERTURE = 2f
    }
}
