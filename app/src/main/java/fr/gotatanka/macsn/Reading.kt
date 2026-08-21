package fr.gotatanka.macsn

/** D'où vient la lecture — la traçabilité demandée à l'export. */
enum class Origine { SCAN, IMPORT, SAISIE }

/**
 * Une machine relevée.
 *
 * `photo` est une chaîne et non un `Uri` : l'Uri ne sert qu'à afficher la
 * vignette et à relire le fichier, alors qu'un type Android ici rendrait la
 * persistance et l'export invérifiables hors appareil.
 */
data class Reading(
    /** Uri de la photo d'origine sous forme de texte, vide pour un scan live. */
    val photo: String,
    val origine: Origine,
    val serial: String?,
    val model: String?,
    val emc: String?,
    val needsReview: Boolean,
    /** Un opérateur a ouvert la photo et tranché. Sans ce drapeau, « validé »
     *  ne veut dire que « ML Kit a lu deux fois pareil » — ce qui a laissé
     *  passer deux faux numéros sur le lot du 20/08. */
    val controle: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val etat: Etat
        get() = when {
            needsReview -> Etat.A_REPRENDRE
            controle -> Etat.CONTROLE
            else -> Etat.LU
        }
}

/**
 * Où en est une ligne.
 *
 * [LU] n'est pas une validation : c'est ce que la machine propose, personne ne
 * l'a encore regardé. La distinction avec [CONTROLE] est tout l'intérêt de
 * l'écran de contrôle — sans elle, le vert ment.
 */
enum class Etat { LU, CONTROLE, A_REPRENDRE }
