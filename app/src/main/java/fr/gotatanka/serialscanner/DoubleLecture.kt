package fr.gotatanka.serialscanner

/**
 * Les deux lectures d'une machine, **séparées par un délai**.
 *
 * La version d'origine comparait deux images consécutives : à 7 images par
 * seconde, c'est deux fois la même. ML Kit y refait la même erreur — sur le lot
 * du 20/08, `C02D5G8GXD6X` est revenu `…MDEM` deux fois de suite, puis a été
 * validé en vert.
 *
 * Un demi-tour de seconde suffit à changer l'échantillon : la main tremble,
 * l'autofocus respire, le bruit du capteur n'est plus le même. Et ça
 * n'immobilise pas l'opérateur.
 *
 * **Le délai est seul, et c'est une mesure qui l'a décidé.** La confirmation a
 * un temps été tentée à un autre palier de zoom, l'idée étant que deux zooms
 * sont deux mesures franchement différentes. Logcat du 25/08 sur Pixel 6,
 * étiquette Asus : la première lecture tombe à 2×, le saut renvoie à 1× — le
 * palier que la rampe venait de quitter *parce qu'il rendait des trames
 * vides* —, les deux meilleures lectures (identiques, à 145 et 290 ms) sont
 * jetées par le délai d'établissement du zoom, et la validation finit par
 * opposer une lecture à 2× à une lecture à 1× qui divergent sur trois
 * caractères confondables. Le saut n'a pas fiabilisé : il a coûté 0,8 s et
 * produit un `incertain` que trois lectures concordantes n'auraient pas eu.
 * Sur une gravure Apple en lumière moyenne, le palier élargi ne rend jamais
 * rien et la séquence tourne en rond.
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
    }
}
