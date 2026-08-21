package fr.gotatanka.macsn

/**
 * Les deux lectures d'une machine, **séparées par un délai**.
 *
 * La version d'origine comparait deux images consécutives : à 7 images par
 * seconde, c'est deux fois la même. ML Kit y refait la même erreur — sur le lot
 * du 20/08, `C02D5G8GXD6X` est revenu `…MDEM` deux fois de suite, puis a été
 * validé en vert.
 *
 * Un demi-tour de seconde suffit à changer l'échantillon : la main tremble,
 * l'autofocus respire, le bruit du capteur n'est plus le même. C'est moins
 * décorrélant qu'un changement de zoom — essayé, mais il faisait perdre la
 * ligne et bloquait la prise — et ça n'immobilise pas l'opérateur.
 *
 * Le filet reste derrière : rien n'est « confirmé » tant qu'un œil n'a pas
 * regardé la photo. Voir [Etat].
 *
 * Kotlin pur : la décision se teste en JVM, l'écran ne fait qu'appliquer.
 */
class DoubleLecture(private val delaiMs: Long = DELAI_MS) {

    sealed interface Etape {
        /** Première lecture retenue. Ne pas relire avant [pasAvant]. */
        data class Confirmer(val serial: String, val pasAvant: Long) : Etape

        /** Deux lectures concordantes. [incertain] quand la concordance ne vaut
         *  pas preuve : les deux ne coïncident qu'à une confusion optique près. */
        data class Valider(val serial: String, val incertain: Boolean) : Etape
    }

    private var retenue: String? = null

    /** Une première lecture attend sa confirmation. */
    val enCours get() = retenue != null

    fun oublier() {
        retenue = null
    }

    /**
     * Ce qu'il faut faire de ce candidat, lu à l'instant [instant].
     *
     * Deux lectures qui divergent ne se départagent pas : la dernière devient
     * la nouvelle première et le délai repart. Rien n'est validé tant que deux
     * images distinctes ne disent pas la même chose.
     */
    fun proposer(candidat: String, instant: Long): Etape {
        val avant = retenue
        if (avant == null || (avant != candidat &&
                SerialParser.normalise(avant) != SerialParser.normalise(candidat))
        ) {
            retenue = candidat
            return Etape.Confirmer(candidat, instant + delaiMs)
        }
        retenue = null
        // Même numéro à une confusion optique près (Q/O/0, 1/I/L…) : le numéro
        // est tenu, un caractère ne l'est pas.
        return Etape.Valider(candidat, avant != candidat)
    }

    companion object {
        /** Assez pour que l'image ne soit plus la même, assez peu pour que
         *  l'opérateur ne sente pas d'attente. À 7 images par seconde, c'est
         *  quatre images plus loin. */
        const val DELAI_MS = 600L

        /** Écart minimal entre les deux zooms pour que la seconde lecture
         *  apprenne quelque chose. En deçà, c'est la même image agrandie. */
        const val ECART = 1.5f

        /**
         * Le palier où retenter la lecture, ou null s'il n'y en a pas.
         *
         * **On élargit d'abord.** Serrer donne plus de pixels par caractère,
         * mais pousse le numéro hors du cadre — c'est ce qui faisait boucler la
         * version d'hier soir : le palier serré ne rendait rien, la séquence
         * repartait, sans fin. Élargir garde le numéro dans le cadre à coup sûr ;
         * s'il devient illisible, l'appelant revient au palier de départ et
         * confirme par le délai.
         */
        fun palierConfirmation(
            paliers: List<Float>,
            actuel: Float,
            facteur: Float = ECART
        ): Float? {
            val dispo = paliers.filter { it > 0f }.sorted()
            return dispo.lastOrNull { it <= actuel / facteur }
                ?: dispo.firstOrNull { it >= actuel * facteur }
        }
    }
}
