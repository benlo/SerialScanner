package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RampeTest {

    @Test fun `un appareil bride ne propose que les paliers qu'il atteint`() {
        assertEquals(listOf(1f, 2f, 3f), Rampe.disponibles(3f))
        assertEquals(Rampe.PALIERS, Rampe.disponibles(7f))
    }

    /** Un appareil sans zoom garde un palier : la rampe doit rester indexable,
     *  sinon le premier tour sort de la liste. */
    @Test fun `sans zoom il reste le plan large`() {
        assertEquals(listOf(1f), Rampe.disponibles(1f))
        assertEquals(listOf(1f), Rampe.disponibles(0f))
    }

    @Test fun `sans souvenir on repart du plan large`() {
        assertEquals(0, Rampe.depart(Rampe.PALIERS, 0f))
        assertEquals(0, Rampe.depart(Rampe.PALIERS, -1f))
    }

    /** Le cas visé : le lot précédent lisait à 2×, on y retourne directement
     *  au lieu de repasser par 1× et ses trames vides. */
    @Test fun `le palier retenu devient le point de depart`() {
        assertEquals(1, Rampe.depart(Rampe.PALIERS, 2f))
        assertEquals(3, Rampe.depart(Rampe.PALIERS, 4f))
    }

    /** Le souhait vient d'une pastille ou d'un appareil différent : il n'est
     *  pas forcément un palier. On ne veut jamais d'index hors liste. */
    @Test fun `un souhait hors rampe tombe sur le palier le plus proche`() {
        assertEquals(2, Rampe.depart(Rampe.PALIERS, 3.4f))
        assertEquals(2, Rampe.depart(listOf(1f, 2f, 3f), 6f))
        assertEquals(0, Rampe.depart(listOf(1f), 6f))
    }

    @Test fun `la rampe boucle sur les paliers disponibles`() {
        assertEquals(0, Rampe.suivant(-1, 3))
        assertEquals(1, Rampe.suivant(0, 3))
        assertEquals(0, Rampe.suivant(2, 3))
    }

    /** Le palier de départ peut dépasser la liste si la plage de zoom s'est
     *  rétrécie entre-temps : le tour suivant doit rester dans la liste. */
    @Test fun `un index devenu trop grand revient dans la liste`() {
        assertEquals(0, Rampe.suivant(5, 2))
        assertEquals(1, Rampe.suivant(4, 2))
    }

    /**
     * Le défaut du 26/08 : le palier retenu était posé, puis poussé d'un cran
     * trois millisecondes plus tard par un tour de rampe déjà en vol. Cinq
     * machines enchaînées, cinq paliers de plus.
     */
    @Test fun `la pose protege le palier qu'on vient de poser`() {
        val pose = 1_000L + Rampe.POSE_MS
        assertFalse(Rampe.avance(1_003L, pose, surLaLigne = false))
        assertFalse(Rampe.avance(pose - 1, pose, surLaLigne = false))
    }

    /** Ce n'est pas un gel : sans ancre en vue, le balayage reprend après la
     *  pose, sinon un lot dépareillé resterait bloqué au palier du lot d'avant. */
    @Test fun `le balayage reprend apres la pose`() {
        val pose = 1_000L + Rampe.POSE_MS
        assertTrue(Rampe.avance(pose, pose, surLaLigne = false))
        assertTrue(Rampe.avance(pose + 5_000L, pose, surLaLigne = false))
    }

    /** Une ancre en vue prime sur tout : bouger ferait perdre la ligne juste
     *  avant de la lire. */
    @Test fun `une ancre en vue fige la rampe meme la pose finie`() {
        assertFalse(Rampe.avance(9_000L, 0L, surLaLigne = true))
    }

    /** Sans souvenir de palier, rien n'est posé et la rampe balaie tout de
     *  suite : la première machine d'un lot ne doit pas attendre. */
    @Test fun `sans pose la rampe balaie des la premiere trame`() {
        assertTrue(Rampe.avance(0L, 0L, surLaLigne = false))
    }
}
