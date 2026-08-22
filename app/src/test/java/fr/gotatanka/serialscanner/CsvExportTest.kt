package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CsvExportTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val t = 1755612000000L   // 2025-08-19 16:00:00 heure de Paris

    private val lu = Reading(
        photo = "content://media/1042",
        origine = Origine.IMPORT,
        serial = "C02W61JZQ6LC",
        model = "A2337",
        emc = "3598",
        needsReview = false,
        controle = true,
        timestamp = t
    )

    /** Le cas qui a coûté cher : ML Kit a lu, personne n'a vérifié. L'export
     *  ne doit pas le dire « confirmé » — c'est la colonne que le client lit
     *  pour décider quelles machines rouvrir. */
    @Test fun `une lecture non controlee n'est pas confirmee`() {
        assertEquals("lu_non_controle", CsvExport.ligne(lu.copy(controle = false), paris).fiabilite)
    }

    @Test fun `l'entete est celle attendue par le client`() {
        assertEquals("serial;model;emc;source;fiabilite;photo;horodatage", CsvExport.ENTETE)
    }

    @Test fun `une lecture confirmee sort toutes ses colonnes`() {
        val l = CsvExport.ligne(lu, paris)
        assertEquals("C02W61JZQ6LC", l.serial)
        assertEquals("A2337", l.model)
        assertEquals("3598", l.emc)
        assertEquals("import", l.source)
        assertEquals("confirme", l.fiabilite)
        assertEquals("content://media/1042", l.photo)
        assertEquals("2025-08-19 16:00:00", l.horodatage)
    }

    /** Une ligne à reprendre doit rester dans l'export, marquée : c'est elle
     *  qui justifie l'interruption de l'audit auprès du client. */
    @Test fun `une lecture douteuse est exportee et marquee`() {
        val l = CsvExport.ligne(lu.copy(serial = null, needsReview = true, origine = Origine.SCAN), paris)
        assertEquals("", l.serial)
        assertEquals("a_verifier", l.fiabilite)
        assertEquals("scan", l.source)
    }

    @Test fun `le document commence par l'entete et compte une ligne par lecture`() {
        val doc = CsvExport.document(listOf(CsvExport.ligne(lu, paris), CsvExport.ligne(lu, paris)))
        val lignes = doc.trim().split("\r\n")
        assertEquals(3, lignes.size)
        assertEquals(CsvExport.ENTETE, lignes[0])
    }

    @Test fun `un champ contenant le separateur est echappe`() {
        assertEquals("simple", CsvExport.echappe("simple"))
        assertEquals("\"a;b\"", CsvExport.echappe("a;b"))
        assertEquals("\"il a dit \"\"oui\"\"\"", CsvExport.echappe("il a dit \"oui\""))
    }

    @Test fun `le nom de fichier reprend le lot et la date`() {
        assertEquals("Palette_12_20250819-1600.csv", CsvExport.nomFichier("Palette 12", t, paris))
        // Un nom qui ne donnerait rien d'utilisable reste un nom de fichier valide.
        assertTrue(CsvExport.nomFichier("///", t, paris).endsWith(".csv"))
        assertEquals("lot_20250819-1600.csv", CsvExport.nomFichier("   ", t, paris))
    }
}
