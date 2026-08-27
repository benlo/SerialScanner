package fr.gotatanka.serialscanner

/**
 * Retrouver, parmi les mots reconnus, celui — ou ceux — qui portent un numéro.
 *
 * ML Kit rend le numéro tantôt en un mot, tantôt en plusieurs : `PW-0479Q1`
 * peut sortir entier, ou coupé en `PW` et `0479Q1`. Il faut donc recomposer les
 * mots consécutifs d'une même ligne pour retrouver la boîte du numéro complet.
 *
 * **Kotlin pur, et c'est le point.** Cette recomposition vivait dans
 * `ScanActivity`, mêlée aux types ML Kit, donc hors de portée des tests. Elle
 * s'est cassée trois fois dans la même journée, chaque fois sur la ponctuation :
 * la lecture recollait mais pas la recherche de boîte, puis l'inverse. Une
 * divergence qui ne se voyait qu'en visant une étiquette, et qui se soldait par
 * un scan muet — la boîte introuvable, donc le cadrage invérifiable, donc rien.
 */
object Recompose {

    /**
     * La boîte du numéro [serial] parmi [lignes], ou null s'il n'y figure pas.
     *
     * La comparaison se fait sur la forme **sans ponctuation des deux côtés** :
     * c'est la seule qui reste vraie que l'étiquette imprime un séparateur ou
     * non, et quel que soit le découpage que ML Kit a choisi.
     */
    fun boite(lignes: List<List<Gabarit.Mot>>, serial: String): ScanRoi.Box? {
        val cible = SerialParser.fixPrefix(SerialParser.canonique(serial))
        if (cible.isEmpty()) return null
        for (mots in lignes) {
            for (debut in mots.indices) {
                var texte = ""
                for (fin in debut until mots.size) {
                    texte += SerialParser.canonique(mots[fin].texte)
                    if (texte.length > cible.length) break
                    if (SerialParser.fixPrefix(texte) != cible) continue
                    val boites = (debut..fin).map { mots[it].boite }
                    return ScanRoi.Box(
                        boites.minOf { it.left },
                        boites.minOf { it.top },
                        boites.maxOf { it.right },
                        boites.maxOf { it.bottom }
                    )
                }
            }
        }
        return null
    }

    /**
     * Le texte des mots dont le centre tombe dans [zone], ligne par ligne.
     *
     * **Au grain du mot, et c'est tout le sujet.** Le découpage se faisait au
     * grain de la ligne : une `Text.Line` était retenue si le centre de la
     * *ligne entière* tombait dans la zone. Or la zone d'un gabarit est taillée
     * pour le numéro seul, et ML Kit rend souvent la gravure d'un bloc —
     * `Rated 20.3V 3A max. Serial C02X50KKJHD3`. Le centre de cette ligne est
     * loin à gauche du numéro, donc hors zone : la trame était jetée alors que
     * le numéro y figurait, correctement lu.
     *
     * Mesuré sur le relevé du 27/08/2026, 2377 trames : le point clé était
     * retrouvé sur 83 % d'entre elles, mais la zone ne rendait du texte que sur
     * 12 % — moins que le viseur nu, à 27 %. Les zones qui attrapaient une
     * ligne entière faisaient 511 px de large en moyenne, contre 333 px pour
     * celles qui revenaient vides : seule une zone assez large pour englober le
     * centre d'une ligne fusionnée voyait quoi que ce soit. Le gabarit rendait
     * la lecture plus rare au lieu de la rendre plus sûre.
     *
     * Le mot est indivisible : la zone le prend ou le laisse, elle ne le coupe
     * jamais en son milieu. Un caractère de bord ne peut donc pas se perdre
     * ici — il se perdrait à la reconnaissance, ce que la longueur exacte du
     * gabarit attrape. C'est aussi le grain auquel [boite] recompose et auquel
     * [Gabarit] mesure son échelle : les trois raisonnent enfin pareil.
     */
    fun texteDans(
        lignes: List<List<Gabarit.Mot>>,
        zone: ScanRoi.Box,
        separateur: String = "\n"
    ): String =
        lignes.mapNotNull { mots ->
            mots.filter { zone.contains(it.boite.centreX, it.boite.centreY) }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ") { it.texte }
        }.joinToString(separateur)
}
