package fr.gotatanka.serialscanner

/**
 * Un lot = une session de relevé : les machines d'une même palette, d'un même
 * client, d'une même journée. C'est l'unité qu'on exporte et qu'on remonte au
 * client, donc l'unité qui doit survivre à la fermeture de l'app.
 */
data class Lot(
    val id: String,
    val nom: String,
    val cree: Long,
    /** Nom du profil de lecture, ou [MARQUE_AUTO] pour déduire de l'étiquette.
     *  Déclarer la marque resserre le format attendu, donc écarte du bruit. */
    val marque: String = MARQUE_AUTO,
    /** Le gabarit affecté à ce lot, par son identifiant. Null quand le lot
     *  n'en a pas : on retombe alors sur l'ancrage textuel et le profil
     *  marque, qui marchent sans étalonnage.
     *
     *  Une référence et non l'objet : un gabarit se modifie, et la correction
     *  doit valoir pour tous les lots qui s'en servent. C'est aussi ce qui
     *  permet de refuser la suppression d'un gabarit encore utilisé. */
    val gabaritId: String? = null,
    val readings: MutableList<Reading> = mutableListOf()
) {
    val valides get() = readings.count { !it.needsReview }
    val aReprendre get() = readings.count { it.needsReview }

    /** Lignes qu'un opérateur a ouvertes et tranchées, photo à l'appui. */
    val controles get() = readings.count { it.controle }

    /** Tout le lot est passé sous l'œil. C'est la seule condition qui autorise
     *  à dire le contrôle fini : « lu » n'est pas « vérifié ». */
    val tousControles get() = readings.isNotEmpty() && readings.all { it.controle }

    /**
     * La prochaine ligne à contrôler après [depuis], en repartant du début
     * quand la fin est atteinte, ou null s'il n'en reste aucune.
     *
     * Le contrôle ne suit pas toujours l'ordre du lot : on ouvre la liste au
     * milieu, on tranche jusqu'au bout, et les lignes d'avant resteraient
     * grises sans que rien ne le dise. On revient les chercher.
     */
    fun prochainAControler(depuis: Int): Int? {
        if (readings.isEmpty()) return null
        for (pas in 1..readings.size) {
            val i = (depuis + pas).mod(readings.size)
            if (!readings[i].controle) return i
        }
        return null
    }

    /**
     * Les numéros qui en côtoient un autre à un caractère près dans ce lot.
     *
     * Calculé en une fois plutôt qu'à chaque ligne affichée : la comparaison
     * est croisée, donc quadratique, et la liste se redessine à chaque défilé.
     */
    val suspects: Set<String>
        get() {
            val tous = readings.mapNotNull { it.serial }
            return tous.filterTo(mutableSetOf()) { s -> tous.any { Controle.memeMachine(it, s) } }
        }

    /**
     * Numéros présents plus d'une fois.
     *
     * Un numéro de série identifie une machine : deux lignes identiques dans un
     * lot ne sont jamais deux machines, mais un capot relevé deux fois — ou pire,
     * une lecture attribuée à la mauvaise machine.
     */
    val doublons: Set<String>
        get() = readings.mapNotNull { it.serial }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

    /** Sans la ponctuation : `PW-0479Q1` et `PW0479Q1` sont le même capot, et
     *  le relever deux fois sous deux graphies serait un doublon invisible. */
    fun contient(serial: String) = readings.any {
        it.serial != null &&
            SerialParser.canonique(it.serial) == SerialParser.canonique(serial)
    }

    /**
     * Un numéro déjà au lot qui ne diffère de [serial] que d'un caractère, ou
     * null. C'est le doublon que l'égalité stricte laisse passer : le même
     * capot relevé deux fois avec un caractère lu autrement.
     *
     * On signale, on ne fusionne pas : deux machines réellement voisines
     * existent, et en perdre une coûterait plus cher qu'une ligne à vérifier.
     */
    fun proche(serial: String, saufIndex: Int = -1): String? = readings
        .filterIndexed { i, _ -> i != saufIndex }
        .mapNotNull { it.serial }
        .firstOrNull { Controle.memeMachine(it, serial) }

    /** Le profil à appliquer aux lectures de ce lot, null si détection. */
    fun profil() = SerialParser.profilParNom(marque)

    companion object {
        const val MARQUE_AUTO = "Auto"
    }
}
