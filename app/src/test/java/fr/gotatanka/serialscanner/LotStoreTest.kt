package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LotStoreTest {

    private val lot = Lot(
        id = "abc",
        nom = "Palette 12",
        cree = 1755612000000L,
        marque = "Lenovo",
        readings = mutableListOf(
            Reading("content://media/1", Origine.IMPORT, "C02W61JZQ6LC", "A2337", "3598", false, controle = true, timestamp = 1L),
            Reading("", Origine.SCAN, null, null, null, true, timestamp = 2L)
        )
    )

    @Test fun `un lot survit a l'aller-retour JSON`() {
        val relu = LotStore.depuisJson(LotStore.versJson(listOf(lot)))
        assertEquals(1, relu.size)
        assertEquals(lot, relu[0])
    }

    /** Le champ vide et le champ absent doivent tous deux redonner null, pas
     *  la chaîne "null" — sinon un numéro fantôme apparaît dans l'export. */
    @Test fun `une lecture sans numero se relit a null`() {
        val relu = LotStore.depuisJson(LotStore.versJson(listOf(lot)))[0].readings[1]
        assertNull(relu.serial)
        assertNull(relu.model)
        assertEquals("", relu.photo)
        assertTrue(relu.needsReview)
    }

    @Test fun `plusieurs lots gardent leur ordre`() {
        val autre = lot.copy(id = "def", nom = "Palette 13", readings = mutableListOf())
        val relu = LotStore.depuisJson(LotStore.versJson(listOf(lot, autre)))
        assertEquals(listOf("abc", "def"), relu.map { it.id })
        assertEquals(0, relu[1].readings.size)
    }

    @Test fun `un lot vide se relit sans erreur`() {
        assertEquals(0, LotStore.depuisJson("[]").size)
    }

    /** Un lot enregistré avant l'arrivée des marques doit se relire en
     *  détection automatique, pas en marque vide. */
    @Test fun `un lot sans marque se relit en detection`() {
        val ancien = """[{"id":"x","nom":"Ancien","cree":1,"lectures":[]}]"""
        assertEquals(Lot.MARQUE_AUTO, LotStore.depuisJson(ancien)[0].marque)
    }

    @Test fun `la marque survit a l'aller-retour`() {
        assertEquals("Lenovo", LotStore.depuisJson(LotStore.versJson(listOf(lot)))[0].marque)
    }
}
