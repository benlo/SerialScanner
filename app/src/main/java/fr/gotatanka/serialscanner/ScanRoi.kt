package fr.gotatanka.serialscanner

/**
 * Géométrie de la zone de visée.
 *
 * Kotlin pur, sans `android.graphics.Rect` : c'est la seule façon de tester
 * la transformation de repère en JVM, et c'est là que sont les erreurs —
 * un signe inversé ne se voit pas à l'œil sur un flux caméra.
 */
object ScanRoi {

    /** Fractions du champ visible occupées par le cadre. La gravure est une
     *  ligne longue et fine : large en X, étroite en Y. */
    const val FRAC_W = 0.88f
    const val FRAC_H = 0.16f

    /** Marge intérieure exigée autour du numéro pour dire le cadrage bon. Un
     *  numéro qui affleure le bord est un numéro dont le dernier caractère
     *  peut être hors champ sans que rien ne le signale. */
    const val MARGE = 0.03f

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun contains(x: Int, y: Int) = x in left..right && y in top..bottom

        /**
         * Le rectangle tient-il entièrement dans le cadre, marge comprise.
         *
         * C'est la condition de cadrage : on ne retient une lecture que si le
         * numéro est franchement dans le cadre, pas s'il le chevauche.
         */
        fun contientEntierement(autre: Box, marge: Float = MARGE): Boolean {
            val mx = (width * marge).toInt()
            val my = (height * marge).toInt()
            return autre.left >= left + mx && autre.right <= right - mx &&
                autre.top >= top + my && autre.bottom <= bottom - my
        }
    }

    /**
     * Passe un rectangle du repère capteur au repère redressé.
     *
     * `ImageProxy.cropRect` est exprimé sur l'image telle que sortie du
     * capteur ; ML Kit rend ses `boundingBox` sur l'image une fois pivotée
     * de `rotationDegrees`. Sans cette conversion on compare des coordonnées
     * de deux repères différents — et le cadre ne filtre pas ce qu'il montre.
     */
    fun toRotatedFrame(crop: Box, rotationDegrees: Int, imgW: Int, imgH: Int): Box =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> Box(imgH - crop.bottom, crop.left, imgH - crop.top, crop.right)
            180 -> Box(imgW - crop.right, imgH - crop.bottom, imgW - crop.left, imgH - crop.top)
            270 -> Box(crop.top, imgW - crop.right, crop.bottom, imgW - crop.left)
            else -> crop
        }

    /** Le cadre, centré dans le champ visible. */
    fun roi(visible: Box, fracW: Float = FRAC_W, fracH: Float = FRAC_H): Box {
        val cx = (visible.left + visible.right) / 2
        val cy = (visible.top + visible.bottom) / 2
        val halfW = (visible.width * fracW / 2f).toInt()
        val halfH = (visible.height * fracH / 2f).toInt()
        return Box(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }
}
