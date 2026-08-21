package fr.gotatanka.macsn

/**
 * Contrôle à l'œil d'un numéro déjà lu.
 *
 * L'appli garantit qu'elle n'invente pas de numéro, pas qu'elle lit juste.
 * Sur le lot du 20/08, deux numéros sur vingt-trois étaient faux **et** verts :
 * `C02D5G8GXD6X` rendu `…MDEM`, `C02DNP0DXD6X` rendu `…FHOD…`. La double
 * lecture ne les a pas écartés parce que ML Kit a commis deux fois la même
 * erreur — c'est le mode de défaillance que la relecture humaine doit couvrir.
 *
 * Kotlin pur : c'est ici, et non dans l'écran, que doit tenir la règle.
 */
object Controle {

    /** Ce qui se signale sous un numéro, du plus grave au plus anodin. */
    enum class Alerte { VIDE, LONGUEUR, AMBIGU, DOUBLON, PROCHE }

    /** Les lettres qu'un alphabet « sans O ni I » n'emploie jamais, avec le
     *  chiffre qu'elles sont forcément en train de masquer. */
    private val SUBSTITUTIONS = mapOf('O' to '0', 'I' to '1')

    /**
     * Un numéro qui contient une lettre proscrite par l'alphabet du fabricant.
     *
     * Apple exclut O et I de ses numéros précisément pour qu'on ne les confonde
     * pas avec 0 et 1 : les y voir n'est donc pas un numéro rare, c'est une
     * erreur de lecture certaine.
     */
    fun ambigu(serial: String?, format: SerialParser.Format): Boolean =
        format.sansOI && serial != null && serial.any { it in SUBSTITUTIONS }

    /** Le même numéro, lettres proscrites ramenées à leur chiffre. */
    fun desambiguise(serial: String): String =
        serial.map { SUBSTITUTIONS[it] ?: it }.joinToString("")

    /**
     * Distance d'édition entre deux numéros, plafonnée à [max].
     *
     * Deux relevés d'un même lot qui ne diffèrent que d'un caractère sont bien
     * plus probablement une machine lue deux fois qu'une paire de machines :
     * `DGKX72C6A9FM` et `DGKXT2C6A9FM` du lot « home » sont le même capot, ML
     * Kit ayant lu tantôt 7 tantôt T. La distance couvre aussi le caractère
     * manquant — un numéro de 12 rendu en 11.
     */
    fun distance(a: String, b: String, max: Int = 2): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var precedente = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val courante = IntArray(b.length + 1)
            courante[0] = i
            for (j in 1..b.length) {
                val remplacement = precedente[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                courante[j] = minOf(remplacement, precedente[j] + 1, courante[j - 1] + 1)
            }
            if (courante.min() > max) return max + 1
            precedente = courante
        }
        return precedente[b.length]
    }

    /**
     * Caractères qu'une gravure grise sur alu rend indistincts.
     *
     * Table plus large que [SerialParser.normalise], et pour un autre usage :
     * `normalise` sert à valider une lecture, où l'on veut rester strict ; ici
     * on cherche à reconnaître deux relevés du même capot, où l'on peut se
     * permettre de soupçonner. Constituée sur les erreurs réellement observées
     * — 7 lu T, 6 lu E, 0 lu O — et non sur une intuition typographique.
     */
    private val CONFUSIONS = listOf("0OQD", "1IL", "2Z", "5S", "6GE", "8B", "7T", "UV")

    private fun confondables(a: Char, b: Char) =
        a == b || CONFUSIONS.any { a in it && b in it }

    /**
     * Les deux numéros désignent-ils vraisemblablement la même machine.
     *
     * **Un caractère d'écart ne suffit pas.** Sur un lot homogène, huit
     * caractères sur douze sont structurels — `C02` + année/semaine + code
     * modèle — et deux machines du même arrivage ne se distinguent que par
     * trois caractères : `C02W61JZQ6LC` et `C02W61JCQ6LC` sont deux MacBook
     * bien réels. Ce qui trahit une relecture, c'est que le caractère qui
     * change soit **optiquement confondable** avec l'autre : 7 contre T, oui ;
     * Z contre R, non.
     *
     * Le caractère manquant, lui, est toujours un défaut de lecture : aucune
     * machine n'a un numéro plus court d'un cran que sa voisine.
     */
    fun memeMachine(a: String, b: String): Boolean {
        if (a == b || a.isEmpty() || b.isEmpty()) return false
        if (a.length == b.length) {
            val differences = a.indices.filter { a[it] != b[it] }
            return differences.size == 1 && confondables(a[differences[0]], b[differences[0]])
        }
        return kotlin.math.abs(a.length - b.length) == 1 && distance(a, b, 1) == 1
    }

    /**
     * Ce qu'il y a à dire sur ce numéro, ou une liste vide s'il est net.
     *
     * On ne corrige rien d'office : la correction est proposée à l'écran, la
     * photo sous les yeux. Un numéro corrigé sans qu'on le voie serait le même
     * défaut qu'un numéro inventé.
     */
    fun alertes(
        serial: String?,
        format: SerialParser.Format,
        doublon: Boolean = false,
        proche: String? = null
    ): List<Alerte> = buildList {
        if (serial.isNullOrBlank()) add(Alerte.VIDE)
        else {
            if (serial.length !in format.longueurs) add(Alerte.LONGUEUR)
            if (ambigu(serial, format)) add(Alerte.AMBIGU)
        }
        if (doublon) add(Alerte.DOUBLON)
        if (proche != null) add(Alerte.PROCHE)
    }
}
