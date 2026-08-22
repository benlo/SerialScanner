package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GabaritStoreTest {

    private val asus = Gabarit("g1", "Asus X512U", "SN:", dx = 0f, dy = 1.59f, w = 10.7f, h = 1.08f)
    private val apple = Gabarit("g2", "MacBook Air", "Serial", dx = 1.2f, dy = 0f, w = 8f, h = 1f)

    @Test fun `un gabarit survit a l'aller-retour JSON`() {
        val relu = GabaritStore.depuisJson(GabaritStore.versJson(listOf(asus, apple)))
        assertEquals(listOf("g1", "g2"), relu.map { it.id })
        assertEquals("Asus X512U", relu[0].nom)
        assertEquals("SN:", relu[0].ancre)
        assertEquals(1.59f, relu[0].dy, 0.001f)
        assertEquals(10.7f, relu[0].w, 0.001f)
    }

    /**
     * La photo et les deux rectangles désignés survivent : sans eux, « Modifier »
     * rouvre sur du noir et l'étalonnage n'est plus vérifiable.
     */
    @Test fun `la photo et les rectangles de reference survivent`() {
        val avecRef = asus.copy(
            photo = "g1.jpg",
            refAncre = ScanRoi.Box(1495, 1141, 1596, 1192),
            refSN = ScanRoi.Box(1495, 1222, 2040, 1277)
        )
        val relu = GabaritStore.depuisJson(GabaritStore.versJson(listOf(avecRef)))[0]
        assertEquals("g1.jpg", relu.photo)
        assertEquals(ScanRoi.Box(1495, 1141, 1596, 1192), relu.refAncre)
        assertEquals(ScanRoi.Box(1495, 1222, 2040, 1277), relu.refSN)
    }

    /** Un gabarit d'avant la photo se relit sans elle, et reste utilisable :
     *  seule la modification y perd, pas la lecture. */
    @Test fun `un gabarit sans photo ni rectangles se relit`() {
        val relu = GabaritStore.depuisJson(GabaritStore.versJson(listOf(asus)))[0]
        assertEquals("", relu.photo)
        assertEquals(null, relu.refAncre)
        assertEquals(null, relu.refSN)
    }

    @Test fun `une bibliotheque vide se relit sans erreur`() {
        assertEquals(0, GabaritStore.depuisJson("[]").size)
    }

    /**
     * Un gabarit tronqué est **écarté**, pas chargé avec des zéros : une zone
     * sans largeur désignerait un point, et la lecture qui en sortirait serait
     * vide sans que rien n'explique pourquoi.
     */
    @Test fun `un gabarit tronque est ecarte au chargement`() {
        val sansAncre = """[{"id":"x","w":9,"h":1}]"""
        val sansZone = """[{"id":"x","ancre":"SN:"}]"""
        val sansId = """[{"ancre":"SN:","w":9,"h":1}]"""
        assertEquals(0, GabaritStore.depuisJson(sansAncre).size)
        assertEquals(0, GabaritStore.depuisJson(sansZone).size)
        assertEquals(0, GabaritStore.depuisJson(sansId).size)
    }

    /** Sans nom, le mot-clé fait un intitulé passable — mieux qu'une ligne
     *  vide dans la liste des gabarits. */
    @Test fun `un gabarit sans nom prend son mot-cle`() {
        val g = GabaritStore.depuisJson("""[{"id":"x","ancre":"SN:","w":9,"h":1}]""")
        assertEquals("SN:", g[0].nom)
    }

    private fun lot(id: String, gabaritId: String?) =
        Lot(id, "Lot $id", 0L, Lot.MARQUE_AUTO, gabaritId)

    @Test fun `un gabarit affecte a un lot est declare utilise`() {
        val lots = listOf(lot("a", "g1"), lot("b", null), lot("c", "g1"))
        assertEquals(listOf("a", "c"), GabaritStore.utilisePar("g1", lots).map { it.id })
        assertEquals(emptyList<Lot>(), GabaritStore.utilisePar("g2", lots))
    }

    /**
     * **Le garde-fou qui protège Apple.** Un lot sans gabarit doit rester un
     * lot sans gabarit à travers la persistance : c'est ce qui garantit que le
     * chemin éprouvé sur les vingt-trois Mac reste intact, le gabarit
     * n'étant qu'une couche ajoutée par-dessus.
     */
    @Test fun `un lot sans gabarit le reste`() {
        val sans = lot("a", null)
        val relu = LotStore.depuisJson(LotStore.versJson(listOf(sans)))[0]
        assertTrue(relu.gabaritId == null)
        // Et un lot enregistré avant l'existence des gabarits aussi.
        val ancien = """[{"id":"x","nom":"Ancien","cree":1,"lectures":[]}]"""
        assertTrue(LotStore.depuisJson(ancien)[0].gabaritId == null)
    }

    @Test fun `l'affectation d'un gabarit a un lot survit a l'aller-retour`() {
        val relu = LotStore.depuisJson(LotStore.versJson(listOf(lot("a", "g1"))))[0]
        assertEquals("g1", relu.gabaritId)
    }
}
