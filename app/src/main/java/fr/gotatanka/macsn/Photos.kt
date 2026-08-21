package fr.gotatanka.macsn

import java.io.File

/**
 * Ménage dans les vignettes du stockage interne.
 *
 * Une vignette n'a de sens qu'attachée à une lecture : elle sert à contrôler
 * un numéro et à le justifier au client. Dès que la lecture disparaît — ligne
 * supprimée, lot supprimé, scan validé puis rejeté comme doublon — le fichier
 * ne prouve plus rien et occupe le stockage interne d'un poste d'atelier. Le
 * lot du 20/08 laissait 51 fichiers pour 23 lectures.
 *
 * La règle est unique et ne dépend pas du chemin qui a mené à la suppression :
 * **un fichier que plus aucune lecture ne cite est supprimé**. Un point de
 * suppression oublié dans le code ne laisse donc plus de déchet.
 *
 * Kotlin pur, sur `java.io.File` : testable en JVM.
 */
object Photos {

    const val DOSSIER = "photos"

    /**
     * Le nom du fichier cité par une lecture, ou null si elle ne cite pas un
     * fichier de ce dossier.
     *
     * Les lectures importées portent un `content://` de la galerie : ce sont
     * les photos de l'utilisateur, elles ne nous appartiennent pas.
     */
    fun nomFichier(photo: String): String? {
        if (!photo.startsWith("file:")) return null
        return photo.substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    /** Les fichiers encore cités par au moins une lecture. */
    fun aGarder(lots: List<Lot>): Set<String> =
        lots.flatMap { it.readings }.mapNotNull { nomFichier(it.photo) }.toSet()

    /**
     * Supprime les vignettes orphelines et rend leurs noms.
     *
     * Ne touche jamais un fichier cité, même par une lecture d'un autre lot :
     * la même vignette recopiée dans deux lots resterait vivante des deux côtés.
     */
    fun purger(dossier: File, lots: List<Lot>): List<String> {
        if (!dossier.isDirectory) return emptyList()
        val garder = aGarder(lots)
        return dossier.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name !in garder }
            .filter { it.delete() }
            .map { it.name }
    }
}
