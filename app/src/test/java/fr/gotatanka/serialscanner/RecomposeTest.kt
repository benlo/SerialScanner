package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La couture qui s'est cassée trois fois le 22/08/2026, chaque fois sur la
 * ponctuation, et chaque fois invisible aux tests parce qu'elle vivait dans
 * `ScanActivity`. Le symptôme était toujours le même : un scan muet, la boîte
 * introuvable donc le cadrage invérifiable donc aucune lecture.
 */
class RecomposeTest {

    private fun mot(t: String, l: Int, r: Int) = Gabarit.Mot(t, ScanRoi.Box(l, 100, r, 140))

    /** Le numéro sort en un seul mot, avec son séparateur. */
    @Test fun `un numero en un mot est retrouve`() {
        val lignes = listOf(listOf(mot("SERIAL", 10, 90), mot("PW-0479Q1", 100, 300)))
        assertEquals(ScanRoi.Box(100, 100, 300, 140), Recompose.boite(lignes, "PW-0479Q1"))
    }

    /**
     * **Le cas qui a cassé.** Le numéro retenu porte le tiret de l'étiquette,
     * ML Kit l'a rendu sans, ou l'inverse. Les deux graphies doivent se
     * retrouver l'une l'autre : c'est la même machine.
     */
    @Test fun `les deux graphies se retrouvent l'une l'autre`() {
        val avecTiret = listOf(listOf(mot("PW-0479Q1", 100, 300)))
        val sansTiret = listOf(listOf(mot("PW0479Q1", 100, 300)))
        assertEquals(ScanRoi.Box(100, 100, 300, 140), Recompose.boite(avecTiret, "PW0479Q1"))
        assertEquals(ScanRoi.Box(100, 100, 300, 140), Recompose.boite(sansTiret, "PW-0479Q1"))
    }

    /** ML Kit coupe parfois le numéro : la boîte doit englober les deux mots. */
    @Test fun `un numero coupe en deux mots donne une boite qui les englobe`() {
        val lignes = listOf(listOf(mot("PW", 100, 150), mot("0479Q1", 160, 300)))
        assertEquals(ScanRoi.Box(100, 100, 300, 140), Recompose.boite(lignes, "PW-0479Q1"))
    }

    /** Le mot-clé qui précède ne doit pas entrer dans la boîte : elle sert à
     *  vérifier que le **numéro** tient dans le cadre, pas la ligne entière. */
    @Test fun `le mot-cle reste hors de la boite`() {
        val lignes = listOf(listOf(mot("SN:", 10, 60), mot("PW-0479Q1", 100, 300)))
        assertEquals(ScanRoi.Box(100, 100, 300, 140), Recompose.boite(lignes, "PW-0479Q1"))
    }

    /** La recomposition ne traverse pas les lignes : deux moitiés de numéro sur
     *  deux lignes ne sont pas un numéro. */
    @Test fun `la recomposition ne traverse pas les lignes`() {
        val lignes = listOf(listOf(mot("PW", 100, 150)), listOf(mot("0479Q1", 100, 300)))
        assertNull(Recompose.boite(lignes, "PW-0479Q1"))
    }

    @Test fun `un numero absent ne donne aucune boite`() {
        val lignes = listOf(listOf(mot("MADE", 10, 90), mot("IN", 100, 130), mot("CHINA", 140, 260)))
        assertNull(Recompose.boite(lignes, "PW-0479Q1"))
        assertNull(Recompose.boite(emptyList(), "PW-0479Q1"))
        assertNull(Recompose.boite(lignes, ""))
    }

    /** Le code usine Apple corrigé de part et d'autre : `CO2` lu pour `C02` ne
     *  doit pas empêcher de retrouver la boîte. */
    @Test fun `la correction du code usine vaut des deux cotes`() {
        val lignes = listOf(listOf(mot("CO2W61JZQ6LC", 100, 400)))
        assertEquals(ScanRoi.Box(100, 100, 400, 140), Recompose.boite(lignes, "C02W61JZQ6LC"))
    }
}
