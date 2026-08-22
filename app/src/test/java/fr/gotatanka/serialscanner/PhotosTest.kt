package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotosTest {

    @get:Rule val dossierTmp = TemporaryFolder()

    private fun lecture(photo: String) =
        Reading(photo, Origine.SCAN, "C02W61JZQ6LC", null, null, false, timestamp = 0L)

    private fun lot(vararg lectures: Reading) =
        Lot("id", "Palette", 0L, Lot.MARQUE_AUTO, lectures.toMutableList())

    private fun fichier(dossier: File, nom: String) =
        File(dossier, nom).apply { writeText("jpeg") }

    @Test fun `le nom est extrait d'une vignette interne`() {
        assertEquals(
            "abc.jpg",
            Photos.nomFichier("file:/data/user/0/fr.gotatanka.serialscanner/files/photos/abc.jpg")
        )
    }

    /** Une lecture importée cite une photo de la galerie de l'utilisateur :
     *  elle ne nous appartient pas, la purge ne doit jamais la viser. */
    @Test fun `une photo de la galerie n'est pas un fichier a nous`() {
        assertNull(Photos.nomFichier("content://media/external/images/media/1042"))
        assertNull(Photos.nomFichier(""))
    }

    @Test fun `une vignette orpheline est supprimee`() {
        val d = dossierTmp.newFolder("photos")
        val gardee = fichier(d, "garde.jpg")
        val orpheline = fichier(d, "orpheline.jpg")

        val supprimes = Photos.purger(d, listOf(lot(lecture("file:${d.path}/garde.jpg"))))

        assertEquals(listOf("orpheline.jpg"), supprimes)
        assertTrue(gardee.exists())
        assertFalse(orpheline.exists())
    }

    /** Le cas du lot du 20/08 : 51 fichiers pour 23 lectures. */
    @Test fun `un lot supprime laisse partir toutes ses vignettes`() {
        val d = dossierTmp.newFolder("photos")
        repeat(5) { fichier(d, "v$it.jpg") }

        assertEquals(5, Photos.purger(d, emptyList()).size)
        assertEquals(0, d.listFiles()!!.size)
    }

    /** La même vignette citée par deux lots survit à la suppression d'un seul. */
    @Test fun `une vignette citee ailleurs survit`() {
        val d = dossierTmp.newFolder("photos")
        fichier(d, "commune.jpg")
        val cite = lecture("file:${d.path}/commune.jpg")

        assertTrue(Photos.purger(d, listOf(lot(), lot(cite))).isEmpty())
        assertTrue(File(d, "commune.jpg").exists())
    }

    @Test fun `un dossier absent ne fait pas echouer la purge`() {
        assertTrue(Photos.purger(File(dossierTmp.root, "jamais_cree"), emptyList()).isEmpty())
    }
}
