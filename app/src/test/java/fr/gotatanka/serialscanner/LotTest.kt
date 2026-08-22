package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LotTest {

    private fun lecture(serial: String?, aVerifier: Boolean = false, controle: Boolean = false) =
        Reading("", Origine.SCAN, serial, null, null, aVerifier, controle, timestamp = 0L)

    private fun lot(vararg lectures: Reading) =
        Lot("id", "Palette 12", 0L, Lot.MARQUE_AUTO, lectures.toMutableList())

    /** Cas réel : la même ligne rescannée trois fois d'affilée. */
    @Test fun `un numero releve plusieurs fois est un doublon`() {
        val l = lot(
            lecture("DGKX72C6A9FM"),
            lecture("DGKX72C6A9FM"),
            lecture("DGKX72C6A9FM"),
            lecture("C02W61JZQ6LC")
        )
        assertEquals(setOf("DGKX72C6A9FM"), l.doublons)
    }

    @Test fun `un lot sain n'a pas de doublon`() {
        assertEquals(emptySet<String>(), lot(lecture("A"), lecture("B")).doublons)
    }

    /** Plusieurs lignes vides ne sont pas des doublons entre elles : ce sont
     *  des machines distinctes qui restent à saisir. */
    @Test fun `les lignes sans numero ne se doublonnent pas`() {
        val l = lot(lecture(null, true), lecture(null, true))
        assertEquals(emptySet<String>(), l.doublons)
        assertEquals(2, l.aReprendre)
    }

    @Test fun `contient reconnait un numero deja releve`() {
        val l = lot(lecture("DGKX72C6A9FM"))
        assertTrue(l.contient("DGKX72C6A9FM"))
        assertFalse(l.contient("C02W61JZQ6LC"))
    }

    /** Le doublon que l'égalité stricte laisse passer. */
    @Test fun `un numero a un caractere pres est signale comme proche`() {
        val l = lot(lecture("DGKX72C6A9FM"), lecture("C02W61JZQ6LC"))
        assertEquals("DGKX72C6A9FM", l.proche("DGKXT2C6A9FM"))
        assertNull(l.proche("FVFR9AE4Q6LC"))
    }

    /** Corriger un caractère sur une ligne ne doit pas la déclarer voisine
     *  d'elle-même : c'est ce que fait l'écran de contrôle à chaque frappe. */
    @Test fun `la ligne en cours d'edition ne se compare pas a elle-meme`() {
        val l = lot(lecture("DGKX72C6A9FM"))
        assertNull(l.proche("DGKXT2C6A9FM", saufIndex = 0))
        assertEquals("DGKX72C6A9FM", l.proche("DGKXT2C6A9FM"))
    }

    @Test fun `les deux lectures d'un meme capot sont toutes deux suspectes`() {
        val l = lot(lecture("DGKX72C6A9FM"), lecture("DGKXT2C6A9FM"))
        assertEquals(setOf("DGKX72C6A9FM", "DGKXT2C6A9FM"), l.suspects)
    }

    @Test fun `un lot sans voisins n'a pas de suspects`() {
        assertTrue(lot(lecture("C02W61JZQ6LC"), lecture("C02W61F3Q6LC")).suspects.isEmpty())
    }

    @Test fun `le lot n'est controle que quand toutes ses lignes le sont`() {
        assertFalse(lot(lecture("A", controle = true), lecture("B")).tousControles)
        assertTrue(lot(lecture("A", controle = true), lecture("B", controle = true)).tousControles)
    }

    /** Une ligne rouge est tranchée, elle aussi : « à reprendre » est une
     *  décision d'opérateur, pas une ligne en attente de contrôle. */
    @Test fun `une ligne a reprendre compte comme controlee`() {
        assertTrue(lot(lecture("A", aVerifier = true, controle = true)).tousControles)
    }

    /** Un lot vide n'est pas un lot fini : il n'y a rien eu à regarder. */
    @Test fun `un lot vide n'est pas controle`() {
        assertFalse(lot().tousControles)
        assertNull(lot().prochainAControler(0))
    }

    @Test fun `le controle enchaine sur la ligne suivante non tranchee`() {
        val l = lot(lecture("A", controle = true), lecture("B"), lecture("C"))
        assertEquals(1, l.prochainAControler(0))
        assertEquals(2, l.prochainAControler(1))
    }

    /** Ouvrir la liste au milieu et trancher jusqu'au bout laisserait des trous
     *  gris sans que rien ne le dise : arrivé à la fin, on revient les chercher. */
    @Test fun `arrive au bout on repart sur les lignes laissees`() {
        val l = lot(lecture("A"), lecture("B", controle = true), lecture("C", controle = true))
        assertEquals(0, l.prochainAControler(2))
    }

    @Test fun `plus rien a controler quand tout est tranche`() {
        val l = lot(lecture("A", controle = true), lecture("B", controle = true))
        assertNull(l.prochainAControler(1))
        assertNull(l.prochainAControler(0))
    }
}
