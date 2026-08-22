package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleLectureTest {

    private fun sequence() = DoubleLecture(delaiMs = 600L)

    @Test fun `la premiere lecture demande une seconde image plus tard`() {
        assertEquals(
            DoubleLecture.Etape.Confirmer("C02W61JZQ6LC", 1600L),
            sequence().proposer("C02W61JZQ6LC", 1000L)
        )
    }

    @Test fun `deux lectures identiques a deux instants valident`() {
        val s = sequence()
        s.proposer("C02W61JZQ6LC", 1000L)
        assertEquals(
            DoubleLecture.Etape.Valider("C02W61JZQ6LC", false),
            s.proposer("C02W61JZQ6LC", 1600L)
        )
        assertFalse(s.enCours)
    }

    /** Le cas du 20/08 : la seconde image lit le 6 que la première avait pris
     *  pour un E. Rien ne doit être validé, et le délai repart. */
    @Test fun `deux lectures divergentes ne valident pas`() {
        val s = sequence()
        s.proposer("C02D5G8GXDGX", 1000L)
        assertEquals(
            DoubleLecture.Etape.Confirmer("C02D5G8GXD6X", 2200L),
            s.proposer("C02D5G8GXD6X", 1600L)
        )
        assertTrue(s.enCours)
    }

    @Test fun `apres une divergence la nouvelle lecture peut etre confirmee`() {
        val s = sequence()
        s.proposer("C02D5G8GXDGX", 1000L)
        s.proposer("C02D5G8GXD6X", 1600L)
        assertEquals(
            DoubleLecture.Etape.Valider("C02D5G8GXD6X", false),
            s.proposer("C02D5G8GXD6X", 2200L)
        )
    }

    /** Q lu O : le numéro est tenu, ce caractère ne l'est pas. On rend, mais
     *  marqué — c'est une ligne qui doit ressortir en rouge dans le relevé. */
    @Test fun `une confusion optique valide mais marque la lecture`() {
        val s = sequence()
        s.proposer("C02W61JZQ6LC", 1000L)
        assertEquals(
            DoubleLecture.Etape.Valider("C02W6IJZQ6LC", true),
            s.proposer("C02W6IJZQ6LC", 1600L)
        )
    }

    @Test fun `oublier remet la sequence a zero`() {
        val s = sequence()
        s.proposer("C02W61JZQ6LC", 1000L)
        s.oublier()
        assertFalse(s.enCours)
        assertEquals(
            DoubleLecture.Etape.Confirmer("C02W61SCQ6LC", 2200L),
            s.proposer("C02W61SCQ6LC", 1600L)
        )
    }

    private val paliers = listOf(1f, 2f, 3f, 4f, 5f, 6f)

    /** On élargit d'abord : c'est le seul sens qui garde le numéro dans le
     *  cadre à coup sûr. Serrer est ce qui faisait boucler la version d'hier. */
    @Test fun `la confirmation se fait a un palier plus large`() {
        assertEquals(4f, DoubleLecture.palierConfirmation(paliers, 6f))
        assertEquals(2f, DoubleLecture.palierConfirmation(paliers, 4f))
        assertEquals(3f, DoubleLecture.palierConfirmation(paliers, 5f))
    }

    /** Déjà au plan large : il ne reste qu'à serrer. */
    @Test fun `au plan large on serre faute de mieux`() {
        assertEquals(2f, DoubleLecture.palierConfirmation(paliers, 1f))
    }

    /** Un écart trop faible n'apprend rien : 6× contre 5×, c'est la même image. */
    @Test fun `un palier trop proche n'est pas retenu`() {
        assertEquals(1f, DoubleLecture.palierConfirmation(listOf(1f, 5f, 6f), 6f))
        assertNull(DoubleLecture.palierConfirmation(listOf(5f, 6f), 6f))
    }

    @Test fun `sans palier disponible la confirmation se fera par le delai`() {
        assertNull(DoubleLecture.palierConfirmation(listOf(1f), 1f))
        assertNull(DoubleLecture.palierConfirmation(emptyList(), 2f))
    }
}
