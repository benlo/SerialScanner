package fr.gotatanka.serialscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRoiTest {

    /** Capteur en paysage, orientation par défaut du flux CameraX. */
    private val W = 640
    private val H = 480
    private val plein = ScanRoi.Box(0, 0, W, H)

    @Test fun `sans rotation le repere est inchange`() {
        assertEquals(plein, ScanRoi.toRotatedFrame(plein, 0, W, H))
    }

    @Test fun `une rotation d'un quart de tour echange les dimensions`() {
        val r = ScanRoi.toRotatedFrame(plein, 90, W, H)
        assertEquals(H, r.width)
        assertEquals(W, r.height)
        assertEquals(ScanRoi.Box(0, 0, H, W), r)
        assertEquals(r, ScanRoi.toRotatedFrame(plein, 270, W, H))
    }

    /** Téléphone tenu à la verticale : une bande horizontale du capteur
     *  doit devenir une bande verticale dans le repère redressé. */
    @Test fun `une bande horizontale devient verticale a 90 degres`() {
        val bande = ScanRoi.Box(0, 120, W, 360)
        assertEquals(ScanRoi.Box(120, 0, 360, W), ScanRoi.toRotatedFrame(bande, 90, W, H))
    }

    @Test fun `un demi-tour renvoie les memes dimensions`() {
        val r = ScanRoi.toRotatedFrame(plein, 180, W, H)
        assertEquals(plein, r)
    }

    @Test fun `le cadre est centre et aux bonnes proportions`() {
        val roi = ScanRoi.roi(plein, fracW = 0.5f, fracH = 0.2f)
        assertEquals(W / 2, roi.width)
        assertEquals(H / 5, roi.height)
        assertEquals(W / 2, (roi.left + roi.right) / 2)
        assertEquals(H / 2, (roi.top + roi.bottom) / 2)
    }

    @Test fun `le cadre exclut ce qui est en dehors`() {
        val roi = ScanRoi.roi(plein)
        assertTrue(roi.contains(W / 2, H / 2))          // centre
        assertFalse(roi.contains(W / 2, 10))            // haut de l'image
        assertFalse(roi.contains(W / 2, H - 10))        // bas de l'image
    }

    /** Condition de cadrage : le numéro doit être franchement dans le cadre.
     *  Un numéro qui affleure le bord peut avoir un caractère hors champ sans
     *  que rien ne le signale — c'est le numéro tronqué accepté comme vrai. */
    @Test fun `un numero franchement dans le cadre est bien cadre`() {
        val cadre = ScanRoi.Box(0, 0, 1000, 200)
        assertTrue(cadre.contientEntierement(ScanRoi.Box(100, 50, 900, 150)))
    }

    @Test fun `un numero qui affleure le bord n'est pas bien cadre`() {
        val cadre = ScanRoi.Box(0, 0, 1000, 200)
        assertFalse(cadre.contientEntierement(ScanRoi.Box(100, 50, 990, 150)))
        assertFalse(cadre.contientEntierement(ScanRoi.Box(10, 50, 900, 150)))
    }

    @Test fun `un numero qui deborde n'est pas bien cadre`() {
        val cadre = ScanRoi.Box(0, 0, 1000, 200)
        assertFalse(cadre.contientEntierement(ScanRoi.Box(-50, 50, 900, 150)))
        assertFalse(cadre.contientEntierement(ScanRoi.Box(100, 50, 1200, 150)))
    }

    private val visible = ScanRoi.Box(0, 0, 1440, 1920)

    /** Le cas du 25/08 : le gabarit lit hors de la bande du viseur, et la
     *  vignette doit suivre le numéro, pas le viseur. */
    @Test fun `la vignette entoure le numero, ou qu'il soit`() {
        val numero = ScanRoi.Box(700, 1500, 1300, 1560)
        val v = ScanRoi.vignette(numero, visible)
        assertTrue(v.left < numero.left && v.right > numero.right)
        assertTrue(v.top < numero.top && v.bottom > numero.bottom)
        // Hors de la bande centrale du viseur : c'est tout l'objet du correctif.
        assertFalse(ScanRoi.roi(visible).contains(numero.left, numero.top))
    }

    /** La marge est un multiple de la hauteur de la boîte : deux prises du
     *  même numéro à des zooms différents donnent la même vignette, à
     *  l'échelle près. */
    @Test fun `la marge suit la hauteur du numero`() {
        // Boîte de 40 de haut : 40 de marge. De 80 : 80.
        val petit = ScanRoi.vignette(ScanRoi.Box(700, 900, 1000, 940), visible)
        assertEquals(ScanRoi.Box(660, 860, 1040, 980), petit)
        val grand = ScanRoi.vignette(ScanRoi.Box(700, 900, 1000, 980), visible)
        assertEquals(ScanRoi.Box(620, 820, 1080, 1060), grand)
    }

    /** Un numéro contre le bord ne doit pas produire un rectangle hors image :
     *  `Snapshot` recadre dessus, et un coin négatif lèverait. */
    @Test fun `la vignette ne sort jamais du champ visible`() {
        val coin = ScanRoi.vignette(ScanRoi.Box(0, 0, 200, 60), visible)
        assertEquals(0, coin.left)
        assertEquals(0, coin.top)
        val bord = ScanRoi.vignette(ScanRoi.Box(1300, 1880, 1440, 1920), visible)
        assertEquals(1440, bord.right)
        assertEquals(1920, bord.bottom)
    }

    /** Une boîte de hauteur nulle ne doit pas rendre une vignette vide :
     *  `Bitmap.createBitmap` refuse une largeur ou une hauteur de zéro. */
    @Test fun `une boite degeneree garde au moins un pixel de marge`() {
        val v = ScanRoi.vignette(ScanRoi.Box(700, 900, 700, 900), visible)
        assertTrue(v.width >= 1 && v.height >= 1)
    }
}
