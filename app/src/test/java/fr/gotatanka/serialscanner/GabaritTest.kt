package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GabaritTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = ScanRoi.Box(l, t, r, b)

    /**
     * Géométrie relevée sur la photo de référence du X512U : le mot-clé `SN:`,
     * et le numéro sur la ligne d'en dessous, aligné à gauche sur lui.
     * Coordonnées de l'image d'origine, 4032 × 2268.
     */
    private val ancreAsus = box(1495, 1141, 1596, 1192)
    private val snAsus = box(1495, 1222, 2040, 1277)

    private val asus = Gabarit.depuisReference("g1", "Asus X512U", "SN:", ancreAsus, snAsus)!!

    /** Le gabarit ne retient aucun pixel : il retient des rapports. */
    @Test fun `le gabarit est exprime en hauteurs d'ancre`() {
        // Le numéro commence au même bord gauche que le mot-clé.
        assertEquals(0f, asus.dx, 0.01f)
        // Une ligne et demie plus bas, et long d'une dizaine de hauteurs.
        assertTrue("dy=${asus.dy}", asus.dy in 1.4f..1.8f)
        assertTrue("w=${asus.w}", asus.w in 9f..12f)
        assertTrue("h=${asus.h}", asus.h in 0.9f..1.3f)
    }

    /** Projeté sur la photo dont il est tiré, le gabarit retombe sur la zone
     *  tracée — à la marge près, qui l'élargit volontairement. */
    @Test fun `le gabarit retombe sur sa propre reference`() {
        val zone = asus.projeter(ancreAsus, marge = 0f)
        assertEquals(snAsus.left, zone.left, 2)
        assertEquals(snAsus.top, zone.top, 2)
        assertEquals(snAsus.right, zone.right, 2)
        assertEquals(snAsus.bottom, zone.bottom, 2)
    }

    /**
     * **La propriété qui fait tenir l'idée.** Une photo prise deux fois plus
     * près donne une boîte d'ancrage deux fois plus grande ; la zone projetée
     * doit désigner le même endroit de l'étiquette, donc doubler elle aussi.
     * Sans cette invariance, un gabarit ne servirait que pour la photo dont il
     * est tiré.
     */
    @Test fun `la zone suit l'echelle de l'ancre`() {
        val facteur = 2
        val ancreProche = box(
            ancreAsus.left * facteur, ancreAsus.top * facteur,
            ancreAsus.right * facteur, ancreAsus.bottom * facteur
        )
        val zone = asus.projeter(ancreProche, marge = 0f)
        assertEquals(snAsus.left * facteur, zone.left, 4)
        assertEquals(snAsus.top * facteur, zone.top, 4)
        assertEquals(snAsus.width * facteur, zone.width, 4)
        assertEquals(snAsus.height * facteur, zone.height, 4)
    }

    /** L'étiquette ailleurs dans le cadre : la zone suit, sans se déformer. */
    @Test fun `la zone suit la translation de l'ancre`() {
        val d = 700
        val zone = asus.projeter(
            box(ancreAsus.left + d, ancreAsus.top - d, ancreAsus.right + d, ancreAsus.bottom - d),
            marge = 0f
        )
        assertEquals(snAsus.left + d, zone.left, 2)
        assertEquals(snAsus.top - d, zone.top, 2)
        assertEquals(snAsus.width, zone.width, 2)
    }

    /** La marge élargit, elle ne décale pas : un numéro qui affleure le bord
     *  perdrait un caractère sans que rien ne le signale. */
    @Test fun `la marge elargit la zone autour de son centre`() {
        val serree = asus.projeter(ancreAsus, marge = 0f)
        val large = asus.projeter(ancreAsus, marge = 0.15f)
        assertTrue(large.left < serree.left && large.right > serree.right)
        assertTrue(large.top < serree.top && large.bottom > serree.bottom)
        val centre = { b: ScanRoi.Box -> (b.left + b.right) / 2 }
        assertEquals(centre(serree), centre(large), 2)
    }

    /** Une ancre sans hauteur ne peut pas servir d'unité : plutôt aucun
     *  gabarit qu'un gabarit qui désigne n'importe quoi. */
    @Test fun `une ancre degeneree ne donne pas de gabarit`() {
        assertNull(Gabarit.depuisReference("g1", "Asus X512U", "SN:", box(10, 50, 100, 50), snAsus))
        assertNull(Gabarit.depuisReference("g1", "Asus X512U", "SN:", ancreAsus, box(10, 10, 10, 10)))
    }

    /**
     * Le cas Apple, où le numéro suit le mot-clé sur la même ligne : le même
     * gabarit l'exprime sans rien de particulier — `dy` proche de zéro, `dx`
     * positif. C'est ce qui rend la mise en page donnée plutôt que code.
     */
    @Test fun `le gabarit exprime aussi la mise en page Apple`() {
        val ancre = box(400, 300, 520, 330)
        val sn = box(540, 300, 900, 330)
        val g = Gabarit.depuisReference("g2", "MacBook Air", "Serial", ancre, sn)!!
        assertTrue("dy=${g.dy}", g.dy in -0.2f..0.2f)
        assertTrue("dx=${g.dx}", g.dx > 0f)
        val zone = g.projeter(ancre, marge = 0f)
        assertEquals(sn.left, zone.left, 2)
        assertEquals(sn.top, zone.top, 2)
    }

    // --- Déduction automatique ---------------------------------------------

    private fun mot(t: String, b: ScanRoi.Box) = Gabarit.Mot(t, b)

    /** Les mots de l'étiquette Asus, aux emplacements relevés sur la photo. */
    private val motsAsus = listOf(
        mot("Model:", box(700, 300, 830, 350)),
        mot("X512U", box(860, 300, 1010, 350)),
        mot("SN:", ancreAsus),
        mot("24M", box(1900, 1141, 2010, 1192)),
        mot("K1N0CV03K34002H", snAsus),
        mot("MFD:", box(1495, 1300, 1600, 1350)),
        mot("2019-01", box(1850, 1300, 2040, 1350))
    )

    @Test fun `la deduction automatique retrouve la geometrie asus`() {
        val g = Gabarit.auto("g1", "Asus X512U", motsAsus, SerialParser.ASUS)!!
        assertEquals("SN:", g.ancre)
        assertEquals(0f, g.dx, 0.05f)
        assertTrue("dy=${g.dy}", g.dy in 1.4f..1.8f)
        // Et elle retombe sur la zone du numéro.
        val zone = g.projeter(ancreAsus, marge = 0f)
        assertEquals(snAsus.left, zone.left, 2)
        assertEquals(snAsus.top, zone.top, 2)
    }

    /**
     * `24M` et `2019-01` sont là, à côté du mot-clé, et n'ont pas troublé la
     * déduction : ils échouent au format. C'est le même garde-fou que pour la
     * lecture — le format commande, la proximité ne suffit pas.
     */
    @Test fun `la garantie et la date ne sont pas prises pour le numero`() {
        val g = Gabarit.auto("g1", "Asus", motsAsus, SerialParser.ASUS)!!
        assertEquals(snAsus.left, g.projeter(ancreAsus, marge = 0f).left, 2)
    }

    /** Le format commande : sous profil Apple, le numéro de quinze caractères
     *  n'est pas plausible et rien n'est déduit. C'est la limite assumée de
     *  l'automatique, celle que le tracé manuel couvre. */
    @Test fun `sans le bon format la deduction echoue plutot que de deviner`() {
        assertNull(Gabarit.auto("g1", "Asus", motsAsus, SerialParser.APPLE))
    }

    /** Deux candidats, on ne tranche pas : un gabarit deviné fausserait tout
     *  un lot, systématiquement. */
    @Test fun `deux numeros plausibles ne donnent aucun gabarit`() {
        val ambigu = motsAsus + mot("K1N0CV03K34009Z", box(1495, 1400, 2040, 1455))
        assertNull(Gabarit.auto("g1", "Asus", ambigu, SerialParser.ASUS))
    }

    @Test fun `deux mots-cles ne donnent aucun gabarit`() {
        val ambigu = motsAsus + mot("S/N", box(300, 1141, 400, 1192))
        assertNull(Gabarit.auto("g1", "Asus", ambigu, SerialParser.ASUS))
    }

    @Test fun `sans mot-cle ni numero rien n'est deduit`() {
        assertNull(Gabarit.auto("g1", "X", motsAsus.filter { it.texte != "SN:" }, SerialParser.ASUS))
        assertNull(
            Gabarit.auto("g1", "X", motsAsus.filter { !it.texte.startsWith("K1N0") }, SerialParser.ASUS)
        )
    }

    /** Le capot Apple, mot-clé et numéro sur la même ligne : la même déduction
     *  marche, sans rien de spécifique à la marque. */
    @Test fun `la deduction automatique marche aussi sur un capot Apple`() {
        val ancre = box(400, 300, 520, 330)
        val sn = box(540, 300, 900, 330)
        val mots = listOf(
            mot("Serial", ancre),
            mot("C02W61JZQ6LC", sn),
            mot("Model", box(400, 200, 500, 230)),
            mot("A2337", box(520, 200, 640, 230)),
            mot("20.3V", box(400, 400, 520, 430))
        )
        val g = Gabarit.auto("g2", "MacBook Air", mots, SerialParser.APPLE)!!
        assertEquals("Serial", g.ancre)
        assertEquals(sn.left, g.projeter(ancre, marge = 0f).left, 2)
    }

    /** Un mot-clé et un numéro aux deux bouts de la photo ne sont pas sur la
     *  même étiquette : plutôt aucun gabarit qu'un qui ne retrouvera rien. */
    @Test fun `une geometrie invraisemblable est refusee`() {
        val eloigne = listOf(
            mot("SN:", box(100, 100, 200, 150)),
            mot("K1N0CV03K34002H", box(3000, 2000, 3600, 2055))
        )
        assertNull(Gabarit.auto("g1", "X", eloigne, SerialParser.ASUS))
    }

    /**
     * Deux déductions concordent quand elles désignent le même endroit, à la
     * bougeotte des boîtes de ML Kit près. C'est ce qui autorise à adopter un
     * gabarit sans lecture confirmée : on confirme la **géométrie**, pas les
     * caractères — un numéro mal lu a quand même la bonne boîte.
     */
    @Test fun `deux deductions du meme endroit concordent`() {
        val a = Gabarit("1", "n", "SN:", dx = 0f, dy = 1.6f, w = 10.7f, h = 1.1f)
        val jitter = a.copy(id = "2", dx = 0.1f, dy = 1.75f, w = 10.9f, h = 1.15f)
        assertTrue(a.proche(jitter))
        // Un autre élément pris pour le numéro : franchement ailleurs.
        assertFalse(a.proche(a.copy(id = "3", dy = 4.2f)))
        // Un autre mot-clé n'est pas le même point de départ.
        assertFalse(a.proche(a.copy(id = "4", ancre = "MFD:")))
    }

    /**
     * Le gabarit porte le format, relevé sur le numéro désigné.
     *
     * C'est ce qui rend la déclaration de marque inutile : l'opérateur a le
     * numéro sous les yeux quand il le touche, l'app n'a rien à deviner. Et ça
     * ouvre l'app aux étiquettes dont aucun profil ne connaît la longueur.
     */
    @Test fun `le gabarit releve le format du numero designe`() {
        val g = Gabarit.depuisReference(
            "g", "Yoga", "Serial", box(100, 100, 200, 140), box(220, 100, 500, 140), "PW-0479Q1"
        )!!
        // Huit caractères utiles : le tiret imprimé ne compte pas.
        assertEquals(8, g.longueur)
        assertEquals(setOf(8), g.format?.longueurs)
        // Ni O ni I dans la référence : on les tient pour proscrits.
        assertTrue(g.sansOI)
        assertTrue(SerialParser.isPlausible("PW-0479Q1", g.format!!))
        assertFalse(SerialParser.isPlausible("K1N0CV03K34002H", g.format!!))
    }

    /**
     * Un numéro entièrement numérique passe.
     *
     * Relevé sur une batterie externe : `Lot n°: 19_17_1900048`, onze chiffres
     * et pas une lettre. Les profils marque exigent un mélange pour écarter le
     * bruit d'un balayage de tout le texte ; dans une zone de gabarit ce
     * garde-fou n'a plus d'objet, et il refusait ce numéro-là.
     */
    @Test fun `un numero entierement numerique est accepte`() {
        val g = Gabarit.depuisReference(
            "g", "Batterie", "Lot", box(100, 100, 160, 140), box(180, 100, 500, 140),
            "19_17_1900048"
        )!!
        assertEquals(11, g.longueur)
        // `000` au milieu : la référence porte un triple, donc cette étiquette
        // en admet. La règle générale reste, elle est juste levée ici.
        assertTrue(g.tripleAdmis)
        assertTrue(SerialParser.isPlausible("19_17_1900048", g.format!!))
        // Le mot-clé est dans la zone et n'y change rien : trop court.
        // Et le numéro ressort **avec ses tirets bas**, comme imprimé.
        assertEquals(
            listOf("19_17_1900048"),
            SerialParser.dansZone("Lot n°: 19_17_1900048", g.format!!)
        )
    }

    /** La règle du triple tient partout ailleurs : une référence qui n'en porte
     *  pas continue de refuser trois caractères identiques, qui trahissent
     *  presque toujours une lecture en difficulté. */
    @Test fun `le triple reste refuse quand la reference n'en porte pas`() {
        val g = Gabarit.depuisReference(
            "g", "X", "Serial", box(100, 100, 200, 140), box(220, 100, 500, 140), "PW0479Q1"
        )!!
        assertFalse(g.tripleAdmis)
        assertFalse(SerialParser.isPlausible("PW00079Q", g.format!!))
    }

    /** Une référence qui emploie `O` ou `I` interdit la déduction inverse :
     *  on ne peut pas proscrire une lettre qu'on vient de voir. */
    @Test fun `une reference avec O ou I n'interdit pas ces lettres`() {
        val g = Gabarit.depuisReference(
            "g", "X", "Serial", box(100, 100, 200, 140), box(220, 100, 500, 140), "PWO479Q1"
        )!!
        assertFalse(g.sansOI)
    }

    /** Les gabarits étalonnés avant que le format y soit rangé n'en annoncent
     *  aucun : le lot retombe sur son profil, comme avant. */
    @Test fun `un gabarit sans longueur n'annonce aucun format`() {
        assertNull(asus.copy(longueur = 0).format)
    }

    /**
     * Le numéro de référence est la **source unique** du format.
     *
     * Longueur, alphabet et tolérance au triple s'en déduisent à la volée. Sans
     * ça, chaque nouveau trait de format obligeait à réétalonner tous les
     * gabarits existants — c'est arrivé deux fois de suite le 22/08/2026.
     */
    @Test fun `le format se deduit du numero de reference`() {
        val g = asus.copy(numero = "19_17_1900048", longueur = 0, sansOI = false, tripleAdmis = false)
        val f = g.format!!
        assertEquals(setOf(11), f.longueurs)
        assertTrue(f.sansOI)
        // `000` dans la référence : le triple est admis, malgré le champ à faux.
        assertTrue(SerialParser.isPlausible("19_17_1900048", f))
    }

    /**
     * Un gabarit d'avant ce champ ne refuse pas les triples.
     *
     * On ne peut pas savoir si son étiquette en admet, et refuser aurait
     * condamné des gabarits déjà étalonnés — ce qui a obligé à en refaire deux
     * fois. La zone et la longueur exacte contraignent déjà fortement.
     */
    @Test fun `un gabarit sans numero de reference ne refuse pas le triple`() {
        val ancien = Gabarit("g", "Ancien", "Lot", dx = 1f, dy = 0f, w = 8f, h = 1f, longueur = 11)
        assertTrue(SerialParser.isPlausible("19_17_1900048", ancien.format!!))
    }

    /**
     * L'ancre cherchée au scan est **celle du gabarit**, pas un mot-clé connu.
     *
     * Chercher `Serial`, `S/N` ou `SN:` revenait à n'accepter que les étiquettes
     * que l'application connaissait déjà — ce que le gabarit existe pour
     * dépasser. Relevé sur une batterie externe : aucun de ces mots, mais un
     * `Lot n°` que l'opérateur peut parfaitement désigner.
     */
    @Test fun `l'ancre cherchee est celle du gabarit`() {
        val g = Gabarit("g", "Batterie", "Lot", dx = 1f, dy = 0f, w = 8f, h = 1f)
        val mots = listOf(
            mot("Made", box(10, 10, 90, 40)),
            mot("Lot", box(100, 100, 160, 140)),
            mot("19_17_1900048", box(180, 100, 500, 140))
        )
        assertEquals(box(100, 100, 160, 140), g.ancreParmi(mots)?.boite)
    }

    /** ML Kit rend tantôt `Lot`, tantôt `Lotn°` : la comparaison ignore la
     *  ponctuation et accepte un mot qui commence par l'ancre. */
    @Test fun `l'ancre se retrouve malgre le bruit de reconnaissance`() {
        val g = Gabarit("g", "Batterie", "Lot", dx = 1f, dy = 0f, w = 8f, h = 1f)
        assertEquals(
            box(100, 100, 160, 140),
            g.ancreParmi(listOf(mot("Lotn°:", box(100, 100, 160, 140))))?.boite
        )
    }

    /** Sans mot correspondant, rien : plutôt aucune zone qu'une zone projetée
     *  depuis un mot qui n'est pas le point clé. */
    @Test fun `sans mot correspondant aucune ancre`() {
        val g = Gabarit("g", "Batterie", "Lot", dx = 1f, dy = 0f, w = 8f, h = 1f)
        assertNull(g.ancreParmi(listOf(mot("France", box(10, 10, 90, 40)))))
        assertNull(g.ancreParmi(emptyList()))
    }

    /** Une ancre de plusieurs mots se retrouve par le premier : `Serial Number`
     *  s'accroche à `Serial`, dont la boîte partage le bord gauche et la
     *  hauteur — les deux seules choses dont la projection se sert. */
    @Test fun `une ancre de plusieurs mots s'accroche au premier`() {
        val g = Gabarit("g", "Yoga", "Serial Number", dx = 0f, dy = 1f, w = 6f, h = 1f)
        val mots = listOf(
            mot("Serial", box(100, 100, 200, 140)),
            mot("Number", box(210, 100, 320, 140))
        )
        assertEquals(box(100, 100, 200, 140), g.ancreParmi(mots)?.boite)
    }

    private fun assertEquals(attendu: Int, obtenu: Int, tolerance: Int) =
        assertTrue("attendu $attendu ± $tolerance, obtenu $obtenu", kotlin.math.abs(attendu - obtenu) <= tolerance)
}
