package fr.gotatanka.macsn

/**
 * Extraction du numéro de série depuis le texte brut renvoyé par ML Kit.
 *
 * Kotlin pur, sans dépendance Android : testable en JVM, donc c'est ici que
 * doit se concentrer la couverture de tests plutôt que dans l'UI.
 *
 * Principe directeur : **le mot-clé commande le format**. On ne cherche jamais
 * un numéro au format seul — la ligne d'un capot contient aussi A2337, 3598,
 * 20.3V, tous candidats crédibles. Et on n'accepte pas l'union de tous les
 * formats connus : un mot-clé donné annonce une longueur donnée, sinon presque
 * n'importe quelle chaîne devient plausible et le garde-fou disparaît.
 */
object SerialParser {

    /**
     * Ce qu'un mot-clé donné annonce comme numéro.
     *
     * @param longueurs longueurs admises pour ce type d'étiquette
     * @param premiereLettre le premier caractère est-il toujours une lettre
     * @param minChiffres, minLettres mélange minimal attendu, qui écarte le bruit
     */
    data class Format(
        val longueurs: Set<Int>,
        val premiereLettre: Boolean,
        val minChiffres: Int,
        val minLettres: Int,
        /** L'alphabet du fabricant exclut-il O et I. Apple les proscrit pour
         *  qu'on ne les confonde pas avec 0 et 1 : un O lu dans un numéro Apple
         *  est donc une erreur de lecture, jamais un numéro rare. Laissé faux
         *  ailleurs, faute d'étiquettes pour le confirmer. */
        val sansOI: Boolean = false
    )

    /** Apple : 12 caractères jusqu'en 2021, 10 depuis, toujours un code usine
     *  en tête — vérifié sur le lot de référence. */
    val APPLE = Format(
        setOf(10, 12), premiereLettre = true, minChiffres = 3, minLettres = 3, sansOI = true
    )

    /** Service Tag Dell : 7 caractères, souvent ouverts par un chiffre.
     *  **À valider sur étiquettes réelles** avant de s'y fier en production. */
    val TAG_COURT = Format(setOf(7), premiereLettre = false, minChiffres = 1, minLettres = 1)

    /** HP ProBook / EliteBook : 10 caractères, souvent ouverts par un chiffre. */
    val HP = Format(setOf(10), premiereLettre = false, minChiffres = 1, minLettres = 1)

    /** Lenovo ThinkPad : 8 caractères. */
    val LENOVO = Format(setOf(8), premiereLettre = false, minChiffres = 1, minLettres = 1)

    /** Asus : 15 caractères. **Longueur à confirmer sur un parc réel.** */
    val ASUS = Format(setOf(15), premiereLettre = false, minChiffres = 1, minLettres = 1)

    /**
     * Ce que l'étiquette dit d'elle-même.
     *
     * `S/N` est employé par HP, Lenovo et Asus avec trois longueurs différentes :
     * le mot-clé seul ne suffit plus à commander le format. Le nom du fabricant
     * ou de la gamme, lui, figure sur l'étiquette — c'est le même principe
     * d'ancrage, remonté d'un cran.
     *
     * Sans marque reconnue on s'en tient au format Apple, le plus contraint :
     * mieux vaut refuser une étiquette inconnue que valider un numéro douteux.
     */
    data class Profil(val nom: String, val indices: Regex, val format: Format)

    val PROFILS = listOf(
        Profil("Dell", Regex("""DELL|SERV[I1]CE\s*TAG|EXPRESS\s*SERV[I1]CE"""), TAG_COURT),
        Profil("Lenovo", Regex("""LENOVO|TH[I1]NKPAD|TH[I1]NKB[O0]{2}K|MTM"""), LENOVO),
        Profil("HP", Regex("""PR[O0]B[O0]{2}K|EL[I1]TEB[O0]{2}K|HEWLETT|HP"""), HP),
        Profil("Asus", Regex("""ASUS"""), ASUS),
        Profil("Apple", Regex("""APPLE|MACB[O0]{2}K|EMC\s*\d{4}"""), APPLE)
    )

    /** Le profil que le texte désigne, ou Apple faute de mieux. */
    fun profil(rawText: String): Profil {
        val up = rawText.uppercase()
        return PROFILS.firstOrNull { it.indices.containsMatchIn(up) }
            ?: PROFILS.first { it.nom == "Apple" }
    }

    private data class Ancre(val motif: Regex, val format: Format?)

    /**
     * Les déclencheurs acceptés.
     *
     * `Serial` est gravé petit et se lit mal : ML Kit rend « Sedal », « Seral »,
     * « Serlal », d'où les classes de caractères. `S/N` exige son séparateur —
     * « SN » nu apparaîtrait au milieu de n'importe quel mot.
     *
     * La frontière finale `(?![A-Z0-9])` est essentielle : sans elle, un numéro
     * de 12 caractères annoncé par « Service Tag » verrait ses 7 premiers
     * capturés et validés — un numéro tronqué, accepté comme authentique.
     */
    private val ANCRES = listOf(
        // Serial, S/N, S.N, S-N → longueur donnée par le profil de l'étiquette
        Ancre(
            Regex("""(?:S[E38][RDPB][I1L]?A[L1I]|S\s*[/.\-]\s*N)\s*[:.#]?\s*([A-Z0-9]{7,15})(?![A-Z0-9])"""),
            null
        ),
        // Service Tag, S/T, SNID → le mot-clé suffit, il n'existe qu'en 7
        Ancre(
            Regex("""(?:SERV[I1]CE\s*TAG|S\s*[/.\-]\s*T|SN[I1]D)\s*[:.#]?\s*([A-Z0-9]{7})(?![A-Z0-9])"""),
            TAG_COURT
        )
    )

    /** Les mêmes mots-clés, sans le numéro derrière : signale qu'on regarde la
     *  bonne ligne même si les caractères ne sont pas encore lisibles. C'est ce
     *  qui dit à la rampe de zoom de s'arrêter là. */
    private val ANCRE_SEULE =
        Regex("""S[E38][RDPB][I1L]?A[L1I]|S\s*[/.\-]\s*[NT]|SERV[I1]CE\s*TAG|SN[I1]D""")

    private val MODEL = Regex("""M[O0]DEL\s*(A\d{4})""")
    private val EMC = Regex("""EMC\s*(\d{4})""")
    private val TRIPLE = Regex("""(.)\1\1""")

    data class Reading(val serial: String?, val model: String?, val emc: String?)

    /** Le profil nommé, ou null pour « détecter d'après l'étiquette ». */
    fun profilParNom(nom: String?): Profil? = PROFILS.firstOrNull { it.nom == nom }

    /** Le format effectivement appliqué à ce texte : celui du lot s'il est
     *  déclaré, sinon celui que l'étiquette laisse deviner. */
    fun formatPour(rawText: String, impose: Profil? = null): Format =
        (impose ?: profil(rawText)).format

    fun parse(rawText: String, impose: Profil? = null): Reading {
        val up = rawText.uppercase()
        val serials = allSerials(rawText, impose)
        // Un seul numéro visible, sinon rien : trancher entre deux candidats
        // reviendrait à rendre un numéro que l'opérateur ne visait pas.
        val serial = serials.singleOrNull()
        return Reading(serial, MODEL.find(up)?.groupValues?.get(1), EMC.find(up)?.groupValues?.get(1))
    }

    /**
     * Tous les numéros plausibles du texte, dédoublonnés.
     *
     * ML Kit rend l'intégralité du texte du champ : plusieurs capots dans le
     * cadre, ou une planche de test, produisent plusieurs ancres. L'appelant
     * décide quoi faire d'une liste de plus d'un élément — ici on ne choisit
     * pas à sa place.
     */
    fun allSerials(rawText: String, impose: Profil? = null): List<String> {
        val up = rawText.uppercase()
        // La marque déclarée au lot prime : l'opérateur sait ce qu'il a en main,
        // là où une étiquette rayée peut avoir perdu le nom du fabricant.
        val duProfil = (impose ?: profil(rawText)).format
        return ANCRES
            .flatMap { ancre ->
                val format = ancre.format ?: duProfil
                ancre.motif.findAll(up)
                    .map { fixPrefix(it.groupValues[1]) }
                    .filter { isPlausible(it, format) }
                    .toList()
            }
            .distinct()
    }

    fun voitAncre(rawText: String): Boolean = ANCRE_SEULE.containsMatchIn(rawText.uppercase())

    /** Les codes usine Apple sont C02, C17, F5K, FVF, DGK… jamais avec un O. */
    fun fixPrefix(s: String): String = s.replace(Regex("^C[O0]([2Z])"), "C02")

    /**
     * Classes de caractères optiquement indiscernables sur une gravure.
     *
     * Deux lectures qui ne diffèrent que là-dessus désignent le même numéro :
     * l'incertitude porte sur un caractère, pas sur le numéro. Sert à distinguer
     * « lu deux fois pareil » de « lu deux fois le même à une confusion près »,
     * le second devant partir en vérification plutôt qu'en validation muette.
     */
    fun normalise(s: String): String = s.map { c ->
        when (c) {
            'O', 'Q', '0' -> '0'
            'I', 'L', '1' -> '1'
            'B', '8' -> '8'
            'S', '5' -> '5'
            'Z', '2' -> '2'
            else -> c
        }
    }.joinToString("")

    /**
     * Le candidat a-t-il la forme annoncée par son mot-clé.
     *
     * Le triplet répété écarte le bruit de reconnaissance : aucun numéro
     * constructeur n'aligne trois fois le même caractère, alors qu'un OCR en
     * difficulté le fait volontiers.
     */
    fun isPlausible(s: String, format: Format = APPLE): Boolean {
        if (s.length !in format.longueurs) return false
        if (format.premiereLettre && !s.first().isLetter()) return false
        if (s.count { it.isDigit() } < format.minChiffres) return false
        if (s.count { it.isLetter() } < format.minLettres) return false
        if (TRIPLE.containsMatchIn(s)) return false
        return true
    }
}
