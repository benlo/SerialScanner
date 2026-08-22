package fr.gotatanka.serialscanner

/**
 * Les positions qu'un lot ne fait jamais varier.
 *
 * Un numéro de série n'est pas une chaîne quelconque : sur une palette de
 * machines du même modèle, une partie des caractères est commune à toutes.
 * Chez Apple, le code usine ouvre le numéro et le code modèle le ferme — sur le
 * lot du 21/08, `Q6LR` revient sept fois et `C02` dix-neuf fois.
 *
 * **Ce que ça attrape, et que rien d'autre ne voit.** Toujours sur ce lot,
 * `Q6IR`, `Q61R` et `O6LR` sont trois lectures fausses du même `Q6LR` — un `L`
 * lu `I` puis `1`, un `Q` lu `O`. [Lot.suspects] ne peut pas les signaler : il
 * compare les numéros **entiers** à un caractère près, et ces machines diffèrent
 * aussi par leurs caractères uniques. Le masque compare par position, c'est
 * exactement le trou qu'il comble.
 *
 * **Appris des seules lignes confirmées à l'œil.** Une lecture brute qui entre
 * dans le masque le fausse pour tout le reste du lot, et l'erreur devient
 * systématique — le pire défaut possible ici. Voir [Reading.controle].
 *
 * Une position n'est statique que *par hypothèse* : si elle se met à varier —
 * palette dépareillée, deux modèles mélangés —, elle quitte le masque d'
 * elle-même, puisqu'il est recalculé à chaque fois. Le masque **signale et
 * propose**, il ne réécrit ni ne refuse jamais.
 *
 * Kotlin pur : c'est ici que doit tenir la règle, pas dans l'écran.
 */
data class Masque(
    /** Longueur des numéros dont ce masque parle, en caractères utiles. */
    val longueur: Int,
    /** Position (dans la forme sans ponctuation) → caractère attendu. */
    val positions: Map<Int, Char>
) {

    /** Les positions où [serial] s'écarte du masque, sur sa forme canonique. */
    fun ecarts(serial: String): List<Int> {
        val c = SerialParser.canonique(serial)
        if (c.length != longueur) return emptyList()
        return positions.filter { (i, attendu) -> c[i] != attendu }.keys.sorted()
    }

    /**
     * Le même numéro, positions statiques ramenées à ce que le lot dit.
     *
     * Rendu **avec sa ponctuation** : c'est une proposition faite à
     * l'opérateur, elle doit ressembler à ce qu'il a sous les yeux.
     */
    fun corriger(serial: String): String {
        val c = SerialParser.canonique(serial)
        if (c.length != longueur) return serial
        val sortie = serial.toCharArray()
        var utile = 0
        for (i in sortie.indices) {
            if (!sortie[i].isLetterOrDigit()) continue
            positions[utile]?.let { sortie[i] = it }
            utile++
        }
        return String(sortie)
    }

    companion object {
        /**
         * Nombre de numéros confirmés avant qu'un masque se forme.
         *
         * En dessous, trop de positions coïncident par hasard : deux numéros
         * pris au hasard partagent déjà quelques caractères, et le masque
         * signalerait des machines parfaitement valides. Quatre est le premier
         * seuil où la coïncidence devient improbable sur douze positions.
         */
        const val MINIMUM = 4

        /**
         * Le masque d'une liste de numéros confirmés, ou null s'il n'y en a pas
         * assez — ou s'ils n'ont pas tous la même longueur.
         *
         * Les longueurs différentes ne se comparent pas position à position :
         * on ne retient donc que la longueur majoritaire, et on ignore le reste
         * plutôt que d'aligner des numéros qui n'ont rien à voir.
         */
        fun apprendre(serials: List<String>, minimum: Int = MINIMUM): Masque? {
            val propres = serials.map { SerialParser.canonique(it) }.filter { it.isNotEmpty() }
            val longueur = propres.groupingBy { it.length }.eachCount()
                .maxByOrNull { it.value }?.key ?: return null
            val retenus = propres.filter { it.length == longueur }
            if (retenus.size < minimum) return null
            val positions = (0 until longueur).mapNotNull { i ->
                val premier = retenus[0][i]
                if (retenus.all { it[i] == premier }) i to premier else null
            }.toMap()
            // Un masque qui fige tout ne dit rien : ce sont les mêmes numéros,
            // donc des doublons, et c'est une autre alerte qui doit le dire.
            if (positions.size >= longueur) return null
            return if (positions.isEmpty()) null else Masque(longueur, positions)
        }
    }
}
