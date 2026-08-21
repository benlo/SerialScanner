package fr.gotatanka.macsn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialParserTest {

    /** Ligne réelle relevée sur un MacBook Air M1, vérité terrain vérifiée à l'œil. */
    private val LIGNE_REELLE =
        "Designed by Apple in California Assembled in China Rated 20.0V 1.5A or " +
        "20.3V 3.0A Model A2337 EMC 3598 Serial C02W61JZQ6LC"

    @Test fun `extrait le numero de la ligne complete`() {
        val r = SerialParser.parse(LIGNE_REELLE)
        assertEquals("C02W61JZQ6LC", r.serial)
        assertEquals("A2337", r.model)
        assertEquals("3598", r.emc)
    }

    @Test fun `ne confond pas le numero avec le modele ou l'EMC`() {
        assertNull(SerialParser.parse("Model A2337 EMC 3598").serial)
    }

    @Test fun `corrige la confusion O zero du code usine`() {
        assertEquals("C02W61JZQ6LC", SerialParser.fixPrefix("CO2W61JZQ6LC"))
    }

    @Test fun `rejette le bruit de reconnaissance`() {
        // Cas réellement produits par Tesseract sur ces photos
        assertFalse(SerialParser.isPlausible("3LUBE8IRRI3T"))   // commence par un chiffre
        assertFalse(SerialParser.isPlausible("AAABBBCCCDDD"))   // triplets
        assertFalse(SerialParser.isPlausible("C02W61JZQ"))      // longueur invalide
    }

    @Test fun `accepte les deux formats Apple`() {
        assertTrue(SerialParser.isPlausible("C02W61JZQ6LC"))    // 12 car., historique
        assertTrue(SerialParser.isPlausible("XW9W2X9A7Q"))      // 10 car., 2021+
    }

    /** Deux capots dans le cadre : le premier n'est pas forcément celui qu'on vise. */
    @Test fun `refuse de trancher entre deux numeros visibles`() {
        val deux = LIGNE_REELLE + " " + LIGNE_REELLE.replace("C02W61JZQ6LC", "C02W61F3Q6LC")
        assertNull(SerialParser.parse(deux).serial)
        assertEquals(
            listOf("C02W61JZQ6LC", "C02W61F3Q6LC"),
            SerialParser.allSerials(deux)
        )
    }

    /** Le même capot lu deux fois dans le champ reste un seul numéro. */
    @Test fun `dedoublonne le meme numero lu plusieurs fois`() {
        val r = SerialParser.parse(LIGNE_REELLE + " " + LIGNE_REELLE)
        assertEquals("C02W61JZQ6LC", r.serial)
    }

    /** Relevés dans logcat sur le Pixel 6 : le mot-clé lui-même se lit mal. */
    @Test fun `tolere les variantes du mot Serial`() {
        assertEquals("C02W61F3Q6LC", SerialParser.parse("Sedal C02W61F3Q6LC").serial)
        assertEquals("C02W61F3Q6LC", SerialParser.parse("Seral C02W61F3Q6LC").serial)
        assertEquals("C02W61F3Q6LC", SerialParser.parse("Serlal C02W61F3Q6LC").serial)
    }

    @Test fun `l'ancre reste obligatoire`() {
        // Le numéro seul, sans mot-clé : c'est le repli qui produisait des
        // numéros plausibles mais faux dans le prototype web.
        assertNull(SerialParser.parse("C02W61F3Q6LC").serial)
        assertFalse(SerialParser.voitAncre("Model A2337 EMC 3598 Assembled in China"))
        assertTrue(SerialParser.voitAncre("Sedal"))
    }

    /** Q lu 0, relevé réel : les deux lectures désignent le même numéro. */
    @Test fun `les confusions optiques se ramenent a la meme forme`() {
        assertEquals(
            SerialParser.normalise("C02W61F3Q6LC"),
            SerialParser.normalise("C02W61F306LC")
        )
        assertEquals(SerialParser.normalise("C02W61JZQ6LC"), SerialParser.normalise("C02W61JZO6LC"))
        // Deux numéros réellement différents ne doivent pas se confondre.
        assertNotEquals(
            SerialParser.normalise("C02W61JZQ6LC"),
            SerialParser.normalise("C02W61F3Q6LC")
        )
    }

    /**
     * Lot de référence fourni pour l'audit, miroir de `testdata/lot_test.csv`
     * et de la planche de test. Couvre les deux formats Apple, plusieurs codes
     * usine (C02, C17, DGK, FVF, HQ7, W80, YM0) et un numéro de 11 caractères
     * qui doit être refusé plutôt que rendu tronqué.
     */
    private data class Cas(
        val serial: String,
        val model: String,
        val emc: String,
        val lecture: Boolean
    )

    private val LOT = listOf(
        Cas("C02W61JZQ6LC", "A2337", "3598", true),
        Cas("C02W61F3Q6LC", "A2337", "3598", true),
        Cas("C02E89R1DCUK", "A1502", "2835", true),
        Cas("FVFJW2A8Q05Y", "A2179", "3302", true),
        Cas("C17P34X9WD5T", "A2338", "3578", true),
        Cas("DGKX72C6A9FM", "A2141", "3347", true),
        Cas("W8094BQ7HTX", "A1286", "2325", false),   // 11 caractères
        Cas("HQ7Y9WJ2A3", "A2681", "4074", true),     // format randomisé 2021+
        Cas("C02U48T5PU23", "A1932", "3184", true),
        Cas("YM0A26L3RT9B", "A2442", "3650", true)
    )

    private fun ligneGravee(c: Cas) =
        "Designed by Apple in California Assembled in China Rated 20.0V 3.0A " +
        "Model ${c.model} EMC ${c.emc} Serial ${c.serial}"

    @Test fun `le lot de reference est lu en entier`() {
        for (c in LOT.filter { it.lecture }) {
            val r = SerialParser.parse(ligneGravee(c))
            assertEquals("numéro de ${c.model}", c.serial, r.serial)
            assertEquals("modèle de ${c.serial}", c.model, r.model)
            assertEquals("EMC de ${c.serial}", c.emc, r.emc)
        }
    }

    @Test fun `un numero de longueur invalide est refuse et non tronque`() {
        for (c in LOT.filter { !it.lecture }) {
            val r = SerialParser.parse(ligneGravee(c))
            assertNull("${c.serial} fait ${c.serial.length} caractères", r.serial)
            // Le modèle et l'EMC restent lus : la ligne part à reprendre avec
            // son contexte, pas vide.
            assertEquals(c.model, r.model)
            assertEquals(c.emc, r.emc)
        }
    }

    /** Le lot ne doit pas contenir deux numéros que les confusions optiques
     *  rendraient indistinguables : sinon la planche ne teste pas ce qu'on croit. */
    @Test fun `les numeros du lot restent distincts apres normalisation`() {
        val formes = LOT.map { SerialParser.normalise(it.serial) }
        assertEquals(LOT.size, formes.toSet().size)
    }

    // --- déclencheurs autres que le mot « Serial » ---

    @Test fun `l'abreviation S sur N declenche comme Serial`() {
        assertEquals("C02W61JZQ6LC", SerialParser.parse("S/N C02W61JZQ6LC").serial)
        assertEquals("C02W61JZQ6LC", SerialParser.parse("S/N: C02W61JZQ6LC").serial)
        assertEquals("C02W61JZQ6LC", SerialParser.parse("S.N. C02W61JZQ6LC").serial)
    }

    /** « SN » nu n'est pas une ancre : ces deux lettres tombent au milieu de
     *  trop de mots pour servir de déclencheur. */
    @Test fun `SN sans separateur ne declenche pas`() {
        assertNull(SerialParser.parse("ASSN C02W61JZQ6LC").serial)
    }

    /** Service Tag Dell : 7 caractères, souvent ouverts par un chiffre — un
     *  format que le profil Apple refuserait. */
    @Test fun `le service tag declenche son propre format`() {
        assertEquals("5R8ZQ72", SerialParser.parse("Service Tag: 5R8ZQ72").serial)
        assertEquals("5R8ZQ72", SerialParser.parse("S/T 5R8ZQ72").serial)
        assertEquals("5R8ZQ72", SerialParser.parse("SNID 5R8ZQ72").serial)
    }

    /** Le point qui compte : le mot-clé commande la longueur. Un tag de 7
     *  annoncé par « Serial » reste refusé, et un numéro de 12 annoncé par
     *  « Service Tag » aussi. Sans cela, accepter les deux formats reviendrait
     *  à rendre presque toute chaîne plausible. */
    @Test fun `le mot-cle commande le format attendu`() {
        assertNull(SerialParser.parse("Serial 5R8ZQ72").serial)
        assertNull(SerialParser.parse("Service Tag C02W61JZQ6LC").serial)
        assertFalse(SerialParser.isPlausible("5R8ZQ72"))
        assertTrue(SerialParser.isPlausible("5R8ZQ72", SerialParser.TAG_COURT))
        assertFalse(SerialParser.isPlausible("C02W61JZQ6LC", SerialParser.TAG_COURT))
    }

    @Test fun `voit l'ancre sur toutes ses formes`() {
        assertTrue(SerialParser.voitAncre("S/N"))
        assertTrue(SerialParser.voitAncre("Service Tag"))
        assertTrue(SerialParser.voitAncre("S/T"))
        assertFalse(SerialParser.voitAncre("Model A2337 EMC 3598"))
    }
}
