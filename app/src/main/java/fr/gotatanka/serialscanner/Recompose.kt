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
}
