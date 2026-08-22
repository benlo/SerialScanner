package fr.gotatanka.serialscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent

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
            bordGauche = null
            bordDroit = null
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

    /**
     * Bords de la zone SN repoussés à la main, en coordonnées d'image.
     *
     * Null tant qu'on n'y a pas touché : la zone vaut alors exactement l'union
     * des mots désignés. Les poignées ne servent qu'à rattraper un détourage
     * trop court de ML Kit — le **texte** du numéro, donc le format qu'on en
     * déduit, reste celui des mots désignés, pas de la zone élargie.
     */
    private var bordGauche: Int? = null
    private var bordDroit: Int? = null

    val numero: Gabarit.Mot?
        get() {
            val u = union(numeroMots) ?: return null
            val boite = ScanRoi.Box(bordGauche ?: u.left, u.top, bordDroit ?: u.right, u.bottom)
            return Gabarit.Mot(numeroMots.joinToString(" ") { m -> m.texte }, boite)
        }

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
    private val poigneePinceau = pinceau(Color.parseColor("#4CAF7D"), 14f).apply {
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * Repose une sélection connue, à la réouverture d'un gabarit.
     *
     * Sans ça, « Modifier » rouvrirait sur une photo vierge et l'opérateur
     * n'aurait aucun moyen de voir ce qui avait été désigné — donc aucun moyen
     * de comprendre pourquoi la zone tombe à côté.
     */
    fun preselectionner(
        ancres: List<Gabarit.Mot>,
        numeros: List<Gabarit.Mot>,
        zoneSN: ScanRoi.Box? = null
    ) {
        ancreMots = ancres
        numeroMots = numeros
        // Restituer l'etirement : la zone enregistree peut etre plus large que
        // les mots qu'elle contient, et rouvrir en la retrecissant ferait perdre
        // silencieusement un ajustement voulu.
        val u = union(numeros)
        bordGauche = zoneSN?.left?.takeIf { u == null || it != u.left }
        bordDroit = zoneSN?.right?.takeIf { u == null || it != u.right }
        invalidate()
        surSelection?.invoke()
    }

    /**
     * Efface le rôle actif seulement.
     *
     * Tout effacer d'un coup obligeait à tout redésigner pour corriger une
     * moitié. Le bouton dit lequel des deux il vide, pour qu'aucun doute ne
     * subsiste au moment de l'appuyer.
     */
    fun effacer() {
        if (role == Role.ANCRE) {
            ancreMots = emptyList()
        } else {
            numeroMots = emptyList()
            bordGauche = null
            bordDroit = null
        }
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
        // Tester l'appartenance **avant** de retirer, sinon le second appui ne
        // retire jamais rien : il retrouve une liste déjà nettoyée et rajoute.
        val deja = (if (role == Role.ANCRE) ancreMots else numeroMots).contains(touche)
        // Un même mot ne peut pas être à la fois le mot-clé et le numéro.
        ancreMots = ancreMots - touche
        numeroMots = numeroMots - touche
        if (!deja) {
            if (role == Role.ANCRE) ancreMots = ancreMots + touche
            else numeroMots = numeroMots + touche
        }
        // La sélection change, l'ajustement manuel ne veut plus rien dire.
        bordGauche = null
        bordDroit = null
        invalidate()
        surSelection?.invoke()
    }

    /**
     * Étirement horizontal de la zone SN.
     *
     * **Horizontal seulement, et sur la zone verte seulement.** La hauteur de la
     * boîte du mot-clé est l'unité d'échelle du gabarit : y toucher
     * redimensionnerait le repère entier. Et la boîte jaune doit rester
     * exactement ce que ML Kit détoure, puisque c'est à une boîte détourée par
     * ML Kit qu'elle sera comparée au scan — une ancre élargie à la main
     * décrirait un rectangle qui ne se reproduit jamais.
     */
    private var poignee = 0

    private val inverse = Matrix()

    private companion object {
        /** Rayon de prise d'une poignée, en pixels d'écran. Large : la zone est
         *  une bande fine et l'on vise au doigt, souvent debout. */
        const val PRISE = 60f

        /** Largeur minimale de la zone, en pixels d'image : un bord ne traverse
         *  pas l'autre. */
        const val MINI = 20
    }

    /** Un point de l'écran ramené dans le repère de l'image. */
    private fun versImage(x: Float, y: Float): FloatArray {
        transfo.invert(inverse)
        return floatArrayOf(x, y).also { inverse.mapPoints(it) }
    }

    /** -1 pour le bord gauche, +1 pour le droit, 0 si l'on n'en tient aucun. */
    private fun poigneeSous(x: Float, y: Float): Int {
        val zone = numero?.boite ?: return 0
        rect.set(
            zone.left.toFloat(), zone.top.toFloat(),
            zone.right.toFloat(), zone.bottom.toFloat()
        )
        transfo.mapRect(rect)
        // Une marge verticale généreuse : la zone est une bande fine, viser
        // dedans au doigt est déjà assez difficile.
        if (y < rect.top - PRISE || y > rect.bottom + PRISE) return 0
        return when {
            kotlin.math.abs(x - rect.left) <= PRISE -> -1
            kotlin.math.abs(x - rect.right) <= PRISE -> 1
            else -> 0
        }
    }

    private fun deplacer(x: Float) {
        val zone = numero?.boite ?: return
        val ix = versImage(x, 0f)[0].toInt()
        // Un bord ne traverse pas l'autre : la zone garde une largeur.
        if (poignee < 0) bordGauche = ix.coerceAtMost(zone.right - MINI)
        else bordDroit = ix.coerceAtLeast(zone.left + MINI)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                poignee = poigneeSous(event.x, event.y)
                if (poignee != 0) {
                    // Retenir l'évènement : sans ça, le déplacement de la photo
                    // s'empare du geste et la poignée ne suit jamais le doigt.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (poignee != 0) {
                deplacer(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (poignee != 0) {
                poignee = 0
                surSelection?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
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
            // Les mots du numéro ne sont pas entourés : la zone les englobe et
            // c'est elle qui sera analysée. Les entourer *aussi* faisait deux
            // rectangles verts, superposés tant qu'on n'avait pas étiré, puis
            // franchement distincts — sans qu'on sache lequel comptait.
            if (numeroMots.contains(m)) continue
            val pinceau = if (ancreMots.contains(m)) motAncre else motOrdinaire
            rect.set(
                m.boite.left.toFloat(), m.boite.top.toFloat(),
                m.boite.right.toFloat(), m.boite.bottom.toFloat()
            )
            transfo.mapRect(rect)
            canvas.drawRect(rect, pinceau)
        }
        dessinerPoignees(canvas)
    }

    /** Deux barres verticales aux bords de la zone SN, pour dire qu'elle
     *  s'attrape. Sans elles, l'etirement serait une fonction invisible. */
    private fun dessinerPoignees(canvas: Canvas) {
        val zone = numero?.boite ?: return
        rect.set(
            zone.left.toFloat(), zone.top.toFloat(),
            zone.right.toFloat(), zone.bottom.toFloat()
        )
        transfo.mapRect(rect)
        canvas.drawRect(rect, motNumero)
        val debord = rect.height() * 0.35f
        for (x in listOf(rect.left, rect.right)) {
            canvas.drawLine(x, rect.top - debord, x, rect.bottom + debord, poigneePinceau)
        }
    }
}
