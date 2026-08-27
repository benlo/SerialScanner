package fr.gotatanka.serialscanner

import kotlin.math.abs

/**
 * La rampe de zoom : par quel palier commencer, et lequel essayer ensuite.
 *
 * Le zoom recadre le capteur, donc à cadre égal chaque caractère reçoit plus
 * de pixels — c'est ce qui fait la différence entre un Q et un O. Tant qu'aucune
 * ancre n'est en vue, on balaie les paliers ; dès qu'on en voit une, on se fige.
 *
 * **Le premier palier n'est plus toujours 1×.** Mesure du 25/08 sur Pixel 6,
 * étiquette Asus : 1,06 s de caméra avant la première trame, puis cinq trames
 * vides à 1× avant que la rampe passe à 2× et lise du premier coup. Sur un lot
 * de trente machines identiques, ces deux secondes se paient trente fois pour
 * retrouver chaque fois le même palier. On repart donc de celui qui a marché
 * la machine précédente, et la rampe reprend son balayage à partir de là s'il
 * ne donne rien — un lot dépareillé n'est pas bloqué pour autant.
 *
 * Kotlin pur : le choix du palier se teste en JVM, l'écran ne fait qu'appliquer.
 */
object Rampe {

    /** Ratios balayés, du cadrage au plus serré. */
    val PALIERS = listOf(1f, 2f, 3f, 4f, 5f, 6f)

    /**
     * Les paliers que l'appareil sait vraiment atteindre.
     *
     * Au-delà de son maximum, un palier mentirait : le zoom serait borné et
     * deux paliers de la rampe donneraient la même image.
     */
    fun disponibles(zoomMax: Float, paliers: List<Float> = PALIERS): List<Float> =
        paliers.filter { it > 0f && it <= zoomMax }.ifEmpty { listOf(1f) }

    /**
     * L'index du palier par lequel démarrer, pour un zoom `souhaite` retenu de
     * la machine précédente. Zéro ou négatif : pas de souvenir, on repart du
     * plan large, qui est la position de cadrage.
     *
     * Le souhait n'est pas forcément un palier de la rampe — l'opérateur a pu
     * poser une pastille à 4× sur un appareil qui plafonne à 3×. On prend donc
     * le palier disponible le plus proche, jamais un index hors liste.
     */
    fun depart(disponibles: List<Float>, souhaite: Float): Int {
        if (souhaite <= 0f || disponibles.isEmpty()) return 0
        return disponibles.indices.minByOrNull { abs(disponibles[it] - souhaite) } ?: 0
    }

    /** Le palier suivant, en boucle. `-1` — aucun palier encore appliqué —
     *  donne le premier. */
    fun suivant(index: Int, taille: Int): Int =
        if (taille <= 0) 0 else ((index + 1) % taille + taille) % taille

    /**
     * Le temps laissé à l'opérateur pour se caler sur la machine suivante.
     *
     * Relevé du 26/08 sur le Pixel 6, cinq capots Apple enchaînés : le palier
     * retenu était posé, puis poussé d'un cran **trois millisecondes plus
     * tard**, et la machine suivante repartait de ce palier-là. 2×, 3×, 4×,
     * 5×, 6× — le zoom montait d'un cran par machine sans qu'aucun d'eux
     * n'ait lu quoi que ce soit, jusqu'à cadrer trop serré pour retrouver la
     * gravure. Le raccourci censé faire gagner du temps en faisait perdre.
     *
     * Ôter la course ne suffisait pas : entre deux machines, le téléphone
     * traverse l'établi et ne voit rien, donc la rampe balaie pendant le
     * transport et arrive sur le capot suivant à un palier quelconque. On la
     * tient donc immobile le temps du déplacement.
     *
     * Ce n'est pas un gel : passé ce délai sans ancre en vue, le balayage
     * reprend depuis le palier posé — un lot dépareillé n'est pas bloqué, il
     * perd seulement ces trois secondes.
     */
    const val POSE_MS = 3000L

    /**
     * La rampe doit-elle changer de palier à l'instant [instant] ?
     *
     * [pasAvant] est la fin de la pose ; [surLaLigne] dit qu'une ancre a été
     * vue récemment, auquel cas le palier courant convient déjà et bouger
     * ferait perdre la ligne juste avant de la lire.
     *
     * Cette décision descend en Kotlin pur parce qu'elle s'est cassée : elle
     * vivait dans l'ordonnancement de deux messages du même looper, dont
     * l'ordre n'était garanti nulle part.
     */
    fun avance(instant: Long, pasAvant: Long, surLaLigne: Boolean): Boolean =
        !surLaLigne && instant >= pasAvant
}
