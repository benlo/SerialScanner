package fr.gotatanka.serialscanner

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
     * L'image dans le repère où ML Kit rend ses coordonnées : orientation EXIF
     * appliquée, comme le fait `InputImage.fromFilePath`.
     *
     * **À utiliser dès qu'on affiche une photo et qu'on dessine par-dessus ce
     * que ML Kit y a trouvé.** Une `ImageView` alimentée par l'URI montre le
     * bitmap brut, alors que ML Kit travaille sur l'image redressée : sur une
     * photo de téléphone, les deux repères diffèrent d'un quart de tour et les
     * boîtes tombent à côté des mots. Passer le même bitmap aux deux — affichage
     * et reconnaissance — est la seule façon de les garder d'accord.
     */
    fun redresse(ctx: android.content.Context, uri: android.net.Uri): android.graphics.Bitmap? {
        val resolver = ctx.contentResolver
        val brut = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it)
        } ?: return null
        val orientation = resolver.openInputStream(uri)?.use {
            androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
        } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        val degres = when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degres == 0f) return brut
        val m = android.graphics.Matrix().apply { postRotate(degres) }
        val tourne = android.graphics.Bitmap.createBitmap(
            brut, 0, 0, brut.width, brut.height, m, true
        )
        if (tourne !== brut) brut.recycle()
        return tourne
    }

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
