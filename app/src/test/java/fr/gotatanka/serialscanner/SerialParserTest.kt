package fr.gotatanka.serialscanner

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
        assertTrue(SerialParser.voitAncre("SN:"))
        assertFalse(SerialParser.voitAncre("Model A2337 EMC 3598"))
    }

    /**
     * Étiquette Asus X512U, relevée sur machine réelle le 22/08/2026.
     *
     * Deux ruptures d'un coup par rapport aux capots Apple : le mot-clé est
     * `SN:` sans barre oblique, et le numéro n'est pas sur sa ligne — la ligne
     * du mot-clé se termine par la garantie, `24M`.
     *
     * ML Kit peut rendre `SN:` et `24M` dans une même `Text.Line` ou dans deux,
     * selon l'écart horizontal qu'il tolère. Les deux découpages sont testés :
     * la lecture ne doit dépendre d'aucun des deux.
     */
    private val asusMemeLigne = """
        Model: X512U Notebook PC
        Designed by ASUSTek Computer Inc. All rights reserved.
        SN: 24M
        K1N0CV03K34002H
        MFD: 2019-01
    """.trimIndent()

    private val asusLignesSeparees = """
        Model: X512U Notebook PC
        Designed by ASUSTek Computer Inc. All rights reserved.
        SN:
        K1N0CV03K34002H
        MFD: 2019-01
    """.trimIndent()

    @Test fun `l'etiquette asus se lit dans les deux decoupages`() {
        assertEquals("K1N0CV03K34002H", SerialParser.parse(asusMemeLigne).serial)
        assertEquals("K1N0CV03K34002H", SerialParser.parse(asusLignesSeparees).serial)
    }

    /** `ASUSTek` sur l'étiquette suffit à commander les 15 caractères : sans
     *  lui le format Apple s'appliquerait et refuserait le numéro. */
    @Test fun `l'etiquette asus commande le format asus`() {
        assertEquals("Asus", SerialParser.profil(asusMemeLigne).nom)
        assertTrue(SerialParser.isPlausible("K1N0CV03K34002H", SerialParser.ASUS))
        assertFalse(SerialParser.isPlausible("K1N0CV03K34002H", SerialParser.APPLE))
    }

    /** Le numéro vient de la ligne d'en dessous : il est bon à prendre, mais
     *  il doit partir en vérification plutôt qu'en validation muette. */
    @Test fun `un numero lu sous le mot-cle est signale hors ligne`() {
        assertTrue(SerialParser.horsLigne(asusMemeLigne, "K1N0CV03K34002H"))
        assertFalse(SerialParser.horsLigne("Serial C02W61JZQ6LC", "C02W61JZQ6LC"))
    }

    /** La garantie et la date de fabrication encadrent le numéro. Ni l'une ni
     *  l'autre ne doit être prise pour lui — c'est tout ce qui sépare ce repli
     *  du balayage de tout le texte au format. */
    @Test fun `le repli ne prend ni la garantie ni la date`() {
        assertEquals(listOf("K1N0CV03K34002H"), SerialParser.candidats(asusMemeLigne))
        // Une ligne suivante qui n'est pas nue ne donne rien.
        assertNull(SerialParser.parse("ASUSTek\nSN: 24M\nMFD: K1N0CV03K34002H").serial)
    }

    /** Le repli ne s'ouvre que si la ligne du mot-clé n'a rien donné : sinon
     *  un code quelconque sous une étiquette Apple normale ferait un second
     *  candidat, et l'écran de scan refuserait de trancher. */
    @Test fun `le repli reste ferme quand la ligne du mot-cle suffit`() {
        val texte = "Serial C02W61JZQ6LC\nC02W61F3Q6LC"
        assertEquals(listOf("C02W61JZQ6LC"), SerialParser.candidats(texte))
    }

    /**
     * Sorties ML Kit authentiques, capturées au logcat le 22/08/2026 en visant
     * l'étiquette du X512U. Sauts de ligne d'origine.
     *
     * Deux enseignements que la reconstitution ne donnait pas :
     * - ML Kit met `SN:` seul sur sa ligne, la garantie `24M` sur une autre ;
     * - l'ordre des lignes n'est pas garanti — sur la troisième, le numéro
     *   précède le mot-clé.
     */
    private val mlkit1 = "Windows\n244\nSN:\nKINOCV03K34002H\n2013-44\nMFD:"
    private val mlkit2 = "24M\nOA0 SN:\nKINOCV03K34002H\n2019-01\nMFD:"
    private val mlkit3 = "EMSF3\nWindowst\n24M\n2019-01\nKINOCVO3K34002H\nSN\nA\nMED:"

    /** Le mot-clé désigne bien le numéro sur ces sorties réelles. Asus doit
     *  être imposé : `ASUSTek` n'est pas dans le cadre resserré. */
    @Test fun `les sorties ML Kit reelles se lisent`() {
        val asus = SerialParser.profilParNom("Asus")
        assertEquals("KINOCV03K34002H", SerialParser.parse(mlkit1, asus).serial)
        assertEquals("KINOCV03K34002H", SerialParser.parse(mlkit2, asus).serial)
    }

    /**
     * Le cœur de l'affaire : ces deux lectures identiques sont **fausses**.
     * La gravure porte `K1N0CV03K34002H`, ML Kit a lu les lettres `I` et `O`
     * pour les chiffres `1` et `0`. La double lecture ne peut rien — seul
     * l'alphabet du fabricant tranche.
     */
    @Test fun `la lecture asus fautive est signalee et corrigee`() {
        val lu = "KINOCV03K34002H"
        assertTrue(Controle.ambigu(lu, SerialParser.ASUS))
        assertEquals("K1N0CV03K34002H", Controle.desambiguise(lu))
    }

    /** L'ordre des lignes n'étant pas garanti, le mot-clé arrive parfois après
     *  le numéro. Rien ne doit sortir de cette image-là : c'est le rôle de la
     *  double lecture d'attendre une image où l'ancrage tient. */
    @Test fun `une image ou le mot-cle suit le numero ne rend rien`() {
        assertNull(SerialParser.parse(mlkit3, SerialParser.profilParNom("Asus")).serial)
    }

    /**
     * Lecture dans une zone découpée par un gabarit.
     *
     * Le mot-clé est dehors par construction — c'est tout l'intérêt de la
     * découpe — donc on ne l'exige pas dedans. L'ancrage n'a pas disparu, il a
     * eu lieu géométriquement, en amont et plus fermement.
     */
    @Test fun `dans une zone de gabarit le mot-cle n'est plus exige`() {
        assertEquals(
            listOf("KINOCVO3K34002H"),
            SerialParser.dansZone("KINOCVO3K34002H", SerialParser.ASUS)
        )
        assertEquals(
            listOf("C02W61JZQ6LC"),
            SerialParser.dansZone("C02W61JZQ6LC", SerialParser.APPLE)
        )
    }

    /**
     * **Le format reste souverain dans la zone.** Sans lui, cette lecture
     * serait exactement le balayage au format qui a coûté si cher au prototype
     * web. Une zone mal projetée qui attrape la garantie ou la date ne doit
     * rien rendre.
     */
    @Test fun `une zone mal projetee ne rend rien de plausible`() {
        assertEquals(emptyList<String>(), SerialParser.dansZone("24M", SerialParser.ASUS))
        assertEquals(emptyList<String>(), SerialParser.dansZone("MFD: 2019-01", SerialParser.ASUS))
        assertEquals(emptyList<String>(), SerialParser.dansZone("", SerialParser.ASUS))
        // Le format Apple refuse le numéro Asus : la zone ne dispense pas de
        // savoir ce qu'on cherche.
        assertEquals(
            emptyList<String>(),
            SerialParser.dansZone("KINOCVO3K34002H", SerialParser.APPLE)
        )
    }

    /**
     * Numéro imprimé avec un séparateur, relevé sur un ThinkPad le 22/08/2026 :
     * ML Kit rend `PW-047901`. Découpé sur la ponctuation, il ne donne que `PW`
     * et `047901` — deux et six caractères, aucun plausible. Recollé, il fait
     * les huit du format Lenovo.
     */
    @Test fun `un numero a separateur se lit tel qu'imprime`() {
        // Ce qui est enregistré porte le tiret, comme l'étiquette.
        assertEquals(
            listOf("PW-0479Q1"),
            SerialParser.dansZone("PW-0479Q1", SerialParser.LENOVO)
        )
        // Et c'est la forme sans ponctuation qui a été jugée au format.
        assertEquals("PW0479Q1", SerialParser.canonique("PW-0479Q1"))
        assertTrue(SerialParser.isPlausible("PW-0479Q1", SerialParser.LENOVO))
    }

    /**
     * La ponctuation est **conservée dans ce qu'on enregistre et ignorée dans
     * tout ce qu'on calcule**. Enregistrer la forme recollée mettrait dans le
     * CSV du client une chaîne qui ne figure nulle part sur la machine ; la lui
     * imposer au format refuserait un numéro parfaitement valide.
     */
    @Test fun `la ponctuation ne compte pas dans les longueurs`() {
        assertFalse(SerialParser.isPlausible("PW-0479Q1", SerialParser.APPLE))
        // Deux graphies du même numéro se normalisent pareil : la double
        // lecture ne doit pas les prendre pour deux machines.
        assertEquals(
            SerialParser.normalise("PW0479Q1"),
            SerialParser.normalise("PW-0479Q1")
        )
    }

    /** ML Kit sépare parfois le numéro en deux mots : la ligne entière reste
     *  candidate, avec son séparateur. */
    @Test fun `un numero coupe en deux mots se recompose`() {
        assertEquals(
            listOf("PW 0479Q1"),
            SerialParser.dansZone("PW 0479Q1", SerialParser.LENOVO)
        )
    }

    /**
     * Mais on ne recolle jamais une ligne qui porte un mot-clé : `SN:` collé au
     * numéro fabriquerait une chaîne plus longue, qui pourrait tomber pile dans
     * la longueur d'un autre format. Le mot-clé annonce le numéro, il n'en fait
     * pas partie.
     */
    @Test fun `le mot-cle n'est jamais recolle au numero`() {
        // `SN` + `PW047901` ferait dix caractères, soit une longueur Apple.
        assertEquals(
            emptyList<String>(),
            SerialParser.dansZone("SN: PW-047901", SerialParser.APPLE)
        )
        // Le vrai numéro reste lisible sur cette même ligne, au bon format.
        assertEquals(
            listOf("PW047901"),
            SerialParser.dansZone("SN: PW047901", SerialParser.LENOVO)
        )
    }

    /** Une zone trop large attrape deux numéros : l'appelant refuse de
     *  trancher, comme sur le chemin textuel. */
    @Test fun `une zone trop large rend plusieurs candidats`() {
        assertEquals(
            listOf("C02W61JZQ6LC", "C02W61F3Q6LC"),
            SerialParser.dansZone("C02W61JZQ6LC C02W61F3Q6LC", SerialParser.APPLE)
        )
    }

    /** `SN` nu reste refusé sans ponctuation, et ne s'attrape pas en fin de
     *  mot : c'est la raison pour laquelle il avait été écarté au départ. */
    @Test fun `SN nu exige ses deux-points`() {
        assertEquals("K1N0CV03K34002H", SerialParser.parse("ASUSTek SN: K1N0CV03K34002H").serial)
        assertNull(SerialParser.parse("ASUSTek SN K1N0CV03K34002H").serial)
        assertNull(SerialParser.parse("ASUSTek ASN: K1N0CV03K34002H").serial)
    }
}
