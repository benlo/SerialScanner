package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasqueTest {

    /**
     * Une grappe du lot du 21/08 : même code usine `C02`, même code modèle
     * `Q6LR`, seuls les caractères uniques varient. C'est la structure que le
     * masque exploite.
     */
    private val grappe = listOf(
        "C02W61JZQ6LR",
        "C02W61F3Q6LR",
        "C02W72KMQ6LR",
        "C02W83NPQ6LR",
        "C02W94RTQ6LR"
    )

    @Test fun `le masque retient les positions que le lot ne fait pas varier`() {
        val m = Masque.apprendre(grappe)!!
        assertEquals(12, m.longueur)
        // C, 0, 2 en tête ; Q, 6, L, R en queue ; W en position 3.
        assertEquals(listOf(0, 1, 2, 3, 8, 9, 10, 11), m.positions.keys.sorted())
        assertEquals('Q', m.positions[8])
        assertEquals('R', m.positions[11])
    }

    /**
     * **Ce que rien d'autre n'attrape.** `Q6IR` est une lecture fausse de
     * `Q6LR` — un `L` lu `I`. `Lot.suspects` ne la voit pas : il compare les
     * numéros entiers à un caractère près, et cette machine diffère aussi par
     * ses caractères uniques. Le masque compare par position.
     */
    @Test fun `une lecture qui casse le code modele est signalee`() {
        val m = Masque.apprendre(grappe)!!
        assertEquals(listOf(10), m.ecarts("C02W61JZQ6IR"))
        assertEquals("C02W61JZQ6LR", m.corriger("C02W61JZQ6IR"))
        // Le `L` lu `1`, et le `Q` lu `O` : mêmes causes, même remède.
        assertEquals(listOf(10), m.ecarts("C02W61JZQ61R"))
        assertEquals(listOf(8), m.ecarts("C02W61JZO6LR"))
        assertEquals("C02W61JZQ6LR", m.corriger("C02W61JZO6LR"))
    }

    /**
     * Le `6` lu `E` du 21/08 — `MD6M` rendu `MDEM` —, que les classes de
     * confusion optique ne couvrent pas. Le masque, lui, s'en moque : il ne
     * regarde pas *comment* le caractère s'est trompé, seulement qu'il n'est
     * pas celui que tout le lot porte.
     */
    @Test fun `le masque attrape aussi les confusions hors classes`() {
        val lot = listOf("C02D5G8GMD6M", "C02D7H2KMD6M", "C02D9J4LMD6M", "C02DBK6NMD6M")
        val m = Masque.apprendre(lot)!!
        assertEquals(listOf(10), m.ecarts("C02D5G8GMDEM"))
        assertEquals("C02D5G8GMD6M", m.corriger("C02D5G8GMDEM"))
    }

    @Test fun `un numero conforme ne signale rien`() {
        val m = Masque.apprendre(grappe)!!
        assertEquals(emptyList<Int>(), m.ecarts("C02WA5XYQ6LR"))
        assertEquals("C02WA5XYQ6LR", m.corriger("C02WA5XYQ6LR"))
    }

    /** Trop peu d'exemplaires : les positions coïncideraient par hasard et le
     *  masque signalerait des machines valides. */
    @Test fun `sous le minimum aucun masque`() {
        assertNull(Masque.apprendre(grappe.take(3)))
        assertNull(Masque.apprendre(emptyList()))
    }

    /**
     * Palette dépareillée : deux modèles mélangés font varier le code modèle,
     * qui quitte le masque de lui-même. Rien à débrancher — le masque est
     * recalculé, donc il se dément tout seul.
     */
    @Test fun `un lot depareille perd les positions qui varient`() {
        val melange = grappe + listOf("C02W61JZH05N", "C02W72KMH05N")
        val m = Masque.apprendre(melange)!!
        assertTrue("le code modèle ne doit plus être figé", 8 !in m.positions)
        assertTrue("ni aucune de ses positions", 11 !in m.positions)
        assertTrue("le code usine reste commun", 0 in m.positions)
    }

    /** Des numéros de longueurs différentes ne s'alignent pas position à
     *  position : seule la longueur majoritaire compte. */
    @Test fun `les longueurs minoritaires sont ignorees`() {
        val m = Masque.apprendre(grappe + listOf("PW0479Q1", "K1N0CV03K34002H"))!!
        assertEquals(12, m.longueur)
        assertEquals(emptyList<Int>(), m.ecarts("PW0479Q1"))
    }

    /** Un lot dont tous les numéros sont identiques ne donne pas de masque :
     *  ce sont des doublons, et c'est une autre alerte qui doit le dire. */
    @Test fun `des numeros tous identiques ne donnent pas de masque`() {
        assertNull(Masque.apprendre(List(5) { "C02W61JZQ6LR" }))
    }

    /** La correction est rendue avec sa ponctuation : c'est une proposition
     *  faite à l'œil, elle doit ressembler à l'étiquette. */
    @Test fun `la correction conserve la ponctuation`() {
        val lot = listOf("PW-0479Q1", "PW-1234Q1", "PW-5678Q1", "PW-9012Q1")
        val m = Masque.apprendre(lot)!!
        assertEquals("PW-0479Q1", m.corriger("PW-0479O1"))
    }
}
