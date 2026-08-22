package fr.gotatanka.serialscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet

/**
 * La photo de référence, ses mots reconnus, et les deux que l'opérateur
 * désigne.
 *
 * **On désigne des mots, on ne trace pas au doigt.** Un rectangle tiré à la
 * main serait approximatif, et surtout il ne dirait pas *quel texte* fait le
 * point clé — or le gabarit a besoin de ce texte pour retrouver son ancre sur
 * les photos suivantes. En touchant un mot reconnu, on récupère d'un coup sa
 * boîte au pixel près et son contenu.
 *
 * C'est aussi ce qui règle le cas des étiquettes denses, où la déduction
 * automatique renonce faute de pouvoir trancher : une étiquette Lenovo porte
 * un MTM aussi plausible que le numéro de série. L'opérateur montre lequel,
 * une fois, et le gabarit vaut pour toute la palette.
 */
class VueGabarit @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : VuePhoto(context, attrs) {

    /** Ce que le prochain appui designe. */
    enum class Role { ANCRE, NUMERO }

    /** Les mots reconnus sur la photo, en coordonnees d'image. */
    var mots: List<Gabarit.Mot> = emptyList()
        set(value) {
            field = value
            ancreMots = emptyList()
            numeroMots = emptyList()
            role = Role.ANCRE
            invalidate()
        }

    /**
     * Le role que le prochain appui alimente.
     *
     * Un ecran qui passe tout seul du mot-cle au numero apres le premier appui
     * ne laisse aucun moyen de corriger le premier : c'etait le defaut de la
     * premiere version. Ici l'operateur dit ce qu'il designe, et peut y revenir
     * autant qu'il veut.
     */
    var role: Role = Role.ANCRE
        set(value) {
            field = value
            invalidate()
            surSelection?.invoke()
        }

    /**
     * Plusieurs mots par role.
     *
     * `Serial Number` fait deux elements ML Kit, et un numero coupe par un
     * tiret peut en faire deux aussi. Exiger un mot unique rendait ces
     * etiquettes-la intracables.
     */
    var ancreMots: List<Gabarit.Mot> = emptyList()
        private set

    var numeroMots: List<Gabarit.Mot> = emptyList()
        private set

    /** La boite qui englobe les mots d'un role, ou null s'il est vide. */
    private fun union(mots: List<Gabarit.Mot>): ScanRoi.Box? {
        if (mots.isEmpty()) return null
        return ScanRoi.Box(
            mots.minOf { it.boite.left },
            mots.minOf { it.boite.top },
            mots.maxOf { it.boite.right },
            mots.maxOf { it.boite.bottom }
        )
    }

    val ancre: Gabarit.Mot?
        get() = union(ancreMots)?.let { Gabarit.Mot(ancreMots.joinToString(" ") { m -> m.texte }, it) }

    val numero: Gabarit.Mot?
        get() = union(numeroMots)?.let { Gabarit.Mot(numeroMots.joinToString(" ") { m -> m.texte }, it) }

    /** Prevenu a chaque designation, pour que l'ecran suive. */
    var surSelection: (() -> Unit)? = null

    private val rect = RectF()

    private fun pinceau(couleur: Int, epaisseur: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = epaisseur
        color = couleur
    }

    private val motOrdinaire = pinceau(Color.argb(90, 255, 255, 255), 2f)
    private val motAncre = pinceau(Color.parseColor("#FFCC00"), 6f)
    private val motNumero = pinceau(Color.parseColor("#4CAF7D"), 6f)

    /**
     * Repose une sélection connue, à la réouverture d'un gabarit.
     *
     * Sans ça, « Modifier » rouvrirait sur une photo vierge et l'opérateur
     * n'aurait aucun moyen de voir ce qui avait été désigné — donc aucun moyen
     * de comprendre pourquoi la zone tombe à côté.
     */
    fun preselectionner(ancres: List<Gabarit.Mot>, numeros: List<Gabarit.Mot>) {
        ancreMots = ancres
        numeroMots = numeros
        invalidate()
        surSelection?.invoke()
    }

    /** Repart de zero : l'operateur s'est trompe, il redesigne. */
    fun effacer() {
        ancreMots = emptyList()
        numeroMots = emptyList()
        role = Role.ANCRE
        invalidate()
        surSelection?.invoke()
    }

    /**
     * Un appui ajoute le mot au role courant, ou l'en retire s'il y etait deja.
     *
     * Toucher a cote d'un mot ne fait rien plutot que de poser une boite au
     * juge : ici, une approximation se paierait sur toutes les machines du lot.
     */
    override fun surTap(x: Float, y: Float) {
        val touche = motSous(x, y) ?: return
        // Un meme mot ne peut pas etre a la fois le mot-cle et le numero.
        ancreMots = ancreMots - touche
        numeroMots = numeroMots - touche
        val cible = if (role == Role.ANCRE) ancreMots else numeroMots
        val nouveau = if (cible.contains(touche)) cible - touche else cible + touche
        if (role == Role.ANCRE) ancreMots = nouveau else numeroMots = nouveau
        invalidate()
        surSelection?.invoke()
    }

    /** Le mot dont la boite, ramenee a l'ecran, contient le point touche. */
    private fun motSous(x: Float, y: Float): Gabarit.Mot? = mots.firstOrNull { m ->
        rect.set(
            m.boite.left.toFloat(), m.boite.top.toFloat(),
            m.boite.right.toFloat(), m.boite.bottom.toFloat()
        )
        transfo.mapRect(rect)
        rect.contains(x, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (m in mots) {
            val pinceau = when {
                ancreMots.contains(m) -> motAncre
                numeroMots.contains(m) -> motNumero
                else -> motOrdinaire
            }
            rect.set(
                m.boite.left.toFloat(), m.boite.top.toFloat(),
                m.boite.right.toFloat(), m.boite.bottom.toFloat()
            )
            transfo.mapRect(rect)
            canvas.drawRect(rect, pinceau)
        }
    }
}
