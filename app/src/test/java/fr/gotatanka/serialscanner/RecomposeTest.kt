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

    // --- Le découpage de la zone d'un gabarit -----------------------------

    /** La zone du gabarit, taillée sur le numéro seul de la gravure Apple. */
    private val zoneSN = ScanRoi.Box(600, 90, 900, 150)

    /** Un mot de la ligne suivante : même repère, une ligne plus bas. */
    private fun mot2(t: String, l: Int, r: Int) = Gabarit.Mot(t, ScanRoi.Box(l, 200, r, 240))

    /**
     * **Le défaut du 27/08/2026.** ML Kit rend la gravure d'un bloc, donc une
     * seule `Text.Line` qui va de `Rated` au numéro. Au grain de la ligne, son
     * centre tombait à gauche du numéro, hors zone, et la trame entière était
     * jetée : le numéro y était pourtant, correctement lu. 1726 trames perdues
     * ainsi sur 1981 dans la journée.
     */
    @Test fun `une ligne fusionnee rend quand meme son numero`() {
        val lignes = listOf(listOf(
            mot("Rated", 100, 200), mot("20.3V", 210, 300), mot("3A", 310, 350),
            mot("max.", 360, 430), mot("Serial", 440, 560), mot("C02X50KKJHD3", 600, 900)
        ))
        assertEquals("C02X50KKJHD3", Recompose.texteDans(lignes, zoneSN))
    }

    /** Le numéro coupé en deux par ML Kit sort recollé de la zone, prêt pour
     *  [SerialParser.dansZone] qui essaiera toutes les suites de mots. */
    @Test fun `un numero coupe sort entier de la zone`() {
        val lignes = listOf(listOf(
            mot("Serial", 440, 560), mot("C02X50", 600, 740), mot("KKJHD3", 750, 900)
        ))
        assertEquals("C02X50 KKJHD3", Recompose.texteDans(lignes, zoneSN))
    }

    /** Ce que la zone existe pour écarter reste écarté : hors d'elle, rien
     *  n'est regardé — c'est un ancrage géométrique, pas un filtre de format. */
    @Test fun `ce qui est hors de la zone n'est pas regarde`() {
        val lignes = listOf(
            listOf(mot("SN:", 440, 560), mot("24M", 600, 700)),
            listOf(mot2("MFD:", 100, 200), mot2("2019-01", 210, 400))
        )
        assertEquals("24M", Recompose.texteDans(lignes, zoneSN))
    }

    /** Une ligne dont aucun mot n'entre dans la zone disparaît : elle ne laisse
     *  pas de ligne vide qui décalerait la lecture ligne à ligne. */
    @Test fun `les lignes sans mot dans la zone disparaissent`() {
        val lignes = listOf(
            listOf(mot("C02X50KKJHD3", 600, 900)),
            listOf(mot2("Designed", 100, 300), mot2("by", 310, 360))
        )
        assertEquals("C02X50KKJHD3", Recompose.texteDans(lignes, zoneSN))
        assertEquals("", Recompose.texteDans(listOf(listOf(mot("Designed", 100, 300))), zoneSN))
        assertEquals("", Recompose.texteDans(emptyList(), zoneSN))
    }

    /**
     * Le mot est indivisible : son centre décide, pas son débordement. Un
     * numéro qui déborde d'un côté reste entier — c'est ce qui garantit qu'on
     * ne fabrique jamais un numéro tronqué d'aspect parfait, le défaut le plus
     * cher des critères d'acceptation.
     */
    @Test fun `un mot a cheval sur le bord est pris ou laisse en entier`() {
        val dedans = listOf(listOf(mot("C02X50KKJHD3", 550, 880)))
        val dehors = listOf(listOf(mot("C02X50KKJHD3", 880, 1200)))
        assertEquals("C02X50KKJHD3", Recompose.texteDans(dedans, zoneSN))
        assertEquals("", Recompose.texteDans(dehors, zoneSN))
    }

    /** Deux lignes retenues restent deux lignes : `dansZone` les relit
     *  séparément et ne recompose jamais à travers un saut de ligne. */
    @Test fun `deux lignes retenues restent separees`() {
        val lignes = listOf(
            listOf(mot("C02X50", 600, 900)),
            listOf(mot2("KKJHD3", 620, 880))
        )
        assertEquals("C02X50", Recompose.texteDans(lignes, zoneSN))
        val zoneDeuxLignes = ScanRoi.Box(590, 90, 910, 400)
        assertEquals("C02X50\nKKJHD3", Recompose.texteDans(lignes, zoneDeuxLignes))
    }
}
