package fr.gotatanka.macsn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Vignette du cadre au moment de la validation.
 *
 * Le CLAUDE.md en fait un élément de contrôle : la vignette permet de vérifier
 * une lecture sans retourner à la machine, et de justifier au client un numéro
 * contesté. Sans elle, un scan live ne laisse aucune trace vérifiable.
 */
object Snapshot {

    private const val TAG = "Snapshot"
    private const val QUALITE = 85

    /**
     * Enregistre le contenu du cadre en JPEG et rend son chemin, ou une chaîne
     * vide si la conversion échoue — une vignette manquante ne doit jamais
     * coûter la lecture elle-même.
     */
    fun capturer(ctx: Context, proxy: ImageProxy, roi: ScanRoi.Box, rotation: Int): String =
        runCatching {
            val redresse = enBitmap(proxy, rotation) ?: return ""
            val vignette = recadrer(redresse, roi)
            val dossier = File(ctx.filesDir, Photos.DOSSIER).apply { mkdirs() }
            val fichier = File(dossier, "${UUID.randomUUID()}.jpg")
            fichier.outputStream().use { vignette.compress(Bitmap.CompressFormat.JPEG, QUALITE, it) }
            if (vignette !== redresse) redresse.recycle()
            fichier.toURI().toString()
        }.getOrElse {
            Log.w(TAG, "vignette non enregistrée", it)
            ""
        }

    private fun enBitmap(proxy: ImageProxy, rotation: Int): Bitmap? {
        val jpeg = ByteArrayOutputStream()
        YuvImage(nv21(proxy), ImageFormat.NV21, proxy.width, proxy.height, null)
            .compressToJpeg(Rect(0, 0, proxy.width, proxy.height), QUALITE, jpeg)
        val octets = jpeg.toByteArray()
        val brut = BitmapFactory.decodeByteArray(octets, 0, octets.size) ?: return null
        if (rotation == 0) return brut
        // Redresser ici : la ROI est exprimée dans le repère redressé, celui de
        // ML Kit, et non dans celui du capteur.
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val tourne = Bitmap.createBitmap(brut, 0, 0, brut.width, brut.height, m, true)
        if (tourne !== brut) brut.recycle()
        return tourne
    }

    private fun recadrer(source: Bitmap, roi: ScanRoi.Box): Bitmap {
        // Le cadre déborde de l'image si le zoom a changé entre l'analyse et
        // la capture : ramener dans les bornes plutôt que de lever.
        val left = roi.left.coerceIn(0, source.width - 1)
        val top = roi.top.coerceIn(0, source.height - 1)
        val largeur = roi.width.coerceIn(1, source.width - left)
        val hauteur = roi.height.coerceIn(1, source.height - top)
        return Bitmap.createBitmap(source, left, top, largeur, hauteur)
    }

    /** YUV_420_888 vers NV21, en respectant les pas de ligne : les ignorer
     *  donne une image oblique sur les capteurs dont le stride excède la
     *  largeur, ce qui est le cas courant. */
    private fun nv21(proxy: ImageProxy): ByteArray {
        val l = proxy.width
        val h = proxy.height
        val sortie = ByteArray(l * h * 3 / 2)

        val y = proxy.planes[0]
        var pos = 0
        val tamponY = y.buffer
        for (ligne in 0 until h) {
            tamponY.position(ligne * y.rowStride)
            tamponY.get(sortie, pos, l)
            pos += l
        }

        val u = proxy.planes[1]
        val v = proxy.planes[2]
        val tamponU = u.buffer
        val tamponV = v.buffer
        val pasPixel = v.pixelStride
        for (ligne in 0 until h / 2) {
            for (col in 0 until l / 2) {
                val i = ligne * v.rowStride + col * pasPixel
                sortie[pos++] = tamponV.get(i)
                sortie[pos++] = tamponU.get(i)
            }
        }
        return sortie
    }
}
