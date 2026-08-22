package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vérité terrain : les deux numéros du lot du 20/08 que la double lecture a
 * validés à tort, relevés sur leur photo.
 */
class ControleTest {

    @Test
    fun `un O dans un numero Apple est une erreur de lecture`() {
        // Photo à l'appui : la gravure porte C02DNP0DXD6X, avec un zéro.
        assertTrue(Controle.ambigu("C02DNPODXD6X", SerialParser.APPLE))
        assertEquals("C02DNP0DXD6X", Controle.desambiguise("C02DNPODXD6X"))
    }

    @Test
    fun `un numero Apple sans lettre proscrite ne declenche rien`() {
        assertFalse(Controle.ambigu("C02W61JZQ6LC", SerialParser.APPLE))
        assertTrue(Controle.alertes("C02W61JZQ6LC", SerialParser.APPLE).isEmpty())
    }

    @Test
    fun `le I devient 1`() {
        assertEquals("C02W61JZQ61C", Controle.desambiguise("C02W6IJZQ6IC"))
    }

    @Test
    fun `un format sans regle d'alphabet ne signale pas les O`() {
        // Le Service Tag Dell n'a pas été vérifié sur étiquette réelle : ne pas
        // lui appliquer une contrainte qu'on ne tient pas de la matière.
        assertFalse(Controle.ambigu("9O2K61X", SerialParser.TAG_COURT))
    }

    @Test
    fun `une longueur hors format est signalee`() {
        assertEquals(
            listOf(Controle.Alerte.LONGUEUR),
            Controle.alertes("C02W61JZQ6L", SerialParser.APPLE)
        )
    }

    @Test
    fun `un numero absent est signale`() {
        assertEquals(listOf(Controle.Alerte.VIDE), Controle.alertes(null, SerialParser.APPLE))
        assertEquals(listOf(Controle.Alerte.VIDE), Controle.alertes("  ", SerialParser.APPLE))
    }

    @Test
    fun `le doublon s'ajoute aux autres alertes`() {
        assertEquals(
            listOf(Controle.Alerte.AMBIGU, Controle.Alerte.DOUBLON),
            Controle.alertes("C02DNPODXD6X", SerialParser.APPLE, doublon = true)
        )
    }

    @Test
    fun `le format applique est celui du lot quand il est declare`() {
        val dell = SerialParser.profilParNom("Dell")
        assertEquals(SerialParser.TAG_COURT, SerialParser.formatPour("MacBook Air EMC 3598", dell))
        assertEquals(SerialParser.APPLE, SerialParser.formatPour("MacBook Air EMC 3598", null))
    }

    /** Le cas du lot « home » : le même capot relevé deux fois, ML Kit ayant
     *  lu tantôt 7 tantôt T. L'égalité stricte ne voyait rien. */
    @Test fun `deux lectures a un caractere pres sont la meme machine`() {
        assertTrue(Controle.memeMachine("DGKX72C6A9FM", "DGKXT2C6A9FM"))
    }

    /** Le cas de l'A1502 : douze caractères rendus en onze. */
    @Test fun `un caractere manquant reste la meme machine`() {
        assertTrue(Controle.memeMachine("C02E8911DCUK", "C02E891DCUK"))
    }

    @Test fun `deux numeros distincts ne sont pas la meme machine`() {
        assertFalse(Controle.memeMachine("C02W61JZQ6LC", "C02W61F3Q6LC"))
        assertFalse(Controle.memeMachine("C02W61JZQ6LC", "C02W61JZQ6LC"))
    }

    /**
     * Deux MacBook du même arrivage, tous deux relevés le 20/08 : `C02` +
     * `K61` (année, semaine) + `Q6LR` (modèle) leur sont communs, il ne reste
     * que trois caractères pour les distinguer. Un écart d'un caractère est
     * donc leur cas ordinaire — le signaler comme doublon rendrait l'alerte
     * inutilisable sur un lot homogène.
     */
    @Test fun `deux machines voisines d'un arrivage ne sont pas un doublon`() {
        assertFalse(Controle.memeMachine("C02W61JZQ6LC", "C02W61JCQ6LC"))
        assertFalse(Controle.memeMachine("C02W61SCQ6LC", "C02W61JCQ6LC"))
        assertFalse(Controle.memeMachine("C02NWB3PXD6X", "C02NQB3QXD6X"))
    }

    /** Ce qui trahit une relecture, c'est que le caractère qui change soit
     *  confondable à l'œil : 7 contre T oui, Z contre R non. */
    @Test fun `seul un caractere confondable trahit une relecture`() {
        assertTrue(Controle.memeMachine("C02D5G8GXD6X", "C02D5G8GXDGX"))
        assertTrue(Controle.memeMachine("C02DNP0DXD6X", "C02DNPODXD6X"))
        assertFalse(Controle.memeMachine("C02W61JZQ6LC", "C02W61JZQ6LT"))
    }

    @Test fun `la distance est plafonnee sans parcourir tout le calcul`() {
        assertEquals(0, Controle.distance("C02W61JZQ6LC", "C02W61JZQ6LC"))
        assertEquals(1, Controle.distance("DGKX72C6A9FM", "DGKXT2C6A9FM"))
        assertEquals(3, Controle.distance("ABCDEFGHIJKL", "ZZZZZZZZZZZZ", max = 2))
    }

    @Test fun `la proximite s'ajoute aux alertes`() {
        assertEquals(
            listOf(Controle.Alerte.PROCHE),
            Controle.alertes("DGKXT2C6A9FM", SerialParser.APPLE, proche = "DGKX72C6A9FM")
        )
    }
}
