package fr.gotatanka.macsn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Miroir de `testdata/planche_mixte.html` : le parc réel de l'atelier.
 *
 * Les longueurs non-Apple viennent de la documentation constructeur et restent
 * **à confirmer sur des étiquettes réelles** — c'est le seul paramètre de ces
 * profils qui puisse être faux sans que rien ne le signale.
 */
class ProfilsTest {

    private fun etiquette(modele: String, ancre: String, serial: String) =
        "$modele  $ancre $serial  Made in China"

    // --- détection de la marque d'après l'étiquette ---

    @Test fun `le modele designe la marque`() {
        assertEquals("Lenovo", SerialParser.profil("ThinkPad T14 Gen 2").nom)
        assertEquals("HP", SerialParser.profil("ProBook 450 G8").nom)
        assertEquals("Dell", SerialParser.profil("Latitude — Service Tag").nom)
        assertEquals("Asus", SerialParser.profil("ASUS ExpertBook B1").nom)
        assertEquals("Apple", SerialParser.profil("MacBook Air EMC 3598").nom)
    }

    /** Étiquette muette : on retombe sur le format le plus contraint plutôt
     *  que d'accepter toutes les longueurs. */
    @Test fun `sans marque reconnue le format reste celui d'Apple`() {
        assertEquals("Apple", SerialParser.profil("machine sans marque lisible").nom)
    }

    // --- lecture par marque ---

    @Test fun `chaque marque du parc est lue avec sa longueur`() {
        assertEquals(
            "5CD1234ABC",
            SerialParser.parse(etiquette("ProBook 450 G8", "S/N", "5CD1234ABC")).serial
        )
        assertEquals(
            "PF3WJ3X2",
            SerialParser.parse(etiquette("ThinkPad T14 Gen 2", "S/N", "PF3WJ3X2")).serial
        )
        assertEquals(
            "K9N0KU01D124956",
            SerialParser.parse(etiquette("ASUS ExpertBook B1", "S/N", "K9N0KU01D124956")).serial
        )
        assertEquals(
            "5R8ZQ72",
            SerialParser.parse(etiquette("Latitude 5420", "Service Tag", "5R8ZQ72")).serial
        )
    }

    /** Le cas qui justifie tout : un S/N HP de 10 caractères commence par un
     *  chiffre, ce que la règle Apple du code usine rejette. */
    @Test fun `un numero HP est refuse sous le profil Apple`() {
        val texte = "S/N 5CD1234ABC"
        assertNull(SerialParser.parse(texte, SerialParser.profilParNom("Apple")).serial)
        assertEquals("5CD1234ABC", SerialParser.parse(texte, SerialParser.profilParNom("HP")).serial)
    }

    /** La marque déclarée au lot prime sur l'étiquette : elle reste lisible
     *  quand le nom du fabricant est rayé ou hors cadre. */
    @Test fun `la marque du lot prime sur la detection`() {
        val sansMarque = "S/N PF3WJ3X2"
        assertNull(SerialParser.parse(sansMarque).serial)
        assertEquals(
            "PF3WJ3X2",
            SerialParser.parse(sansMarque, SerialParser.profilParNom("Lenovo")).serial
        )
    }

    @Test fun `un lot declare Lenovo ne valide pas un format Apple`() {
        val apple = "Serial C02W61JZQ6LC"
        assertNull(SerialParser.parse(apple, SerialParser.profilParNom("Lenovo")).serial)
    }

    // --- pièges de la planche ---

    @Test fun `les pieges de la planche sont tous refuses`() {
        assertNull(SerialParser.parse("ThinkPad T14  SN PF3WJ3X2").serial)
        assertNull(SerialParser.parse("Latitude 5420  Service Tag YM0A26L3RT9B").serial)
        assertNull(SerialParser.parse("MacBook Pro EMC 3598  C17P34X9WD5T").serial)
    }
}
