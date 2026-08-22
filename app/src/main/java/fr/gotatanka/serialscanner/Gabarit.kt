package fr.gotatanka.serialscanner

/**
 * Le gabarit d'un lot : où trouver le numéro sur l'étiquette, géométriquement.
 *
 * Les profils marque disent à quoi un numéro *ressemble* — longueur, alphabet.
 * Le gabarit dit *où il est*, et surtout où il n'est pas : tout ce qui tombe
 * hors de la zone n'est pas analysé du tout. C'est un garde-fou d'une autre
 * nature que les précédents. Sur une étiquette Asus, `24M`, `2019-01` et
 * `MFD:` cessent d'être des candidats — non parce qu'ils échouent à un test de
 * format, mais parce qu'ils ne sont pas regardés.
 *
 * **Rien n'est stocké en pixels.** Une photo suivante est prise à une autre
 * distance et à un autre angle ; un rectangle absolu n'y voudrait rien dire.
 * La zone est donc exprimée en multiples de la hauteur de la boîte du point
 * clé, origine à son coin haut-gauche. Ce repère suit l'échelle : que
 * l'étiquette occupe 200 ou 900 pixels, les mêmes coefficients désignent le
 * même endroit.
 *
 * Kotlin pur, comme [ScanRoi] et pour la même raison : la transformation de
 * repère est l'endroit où les erreurs se logent sans se voir.
 */
data class Gabarit(
    val id: String,
    /** Ce que l'opérateur lit dans la liste — « Asus X512U », « MacBook Air ». */
    val nom: String,
    /** Le point clé, tel qu'imprimé sur l'étiquette — `SN:`, `Serial`, `S/N`. */
    val ancre: String,
    /** Décalage horizontal de la zone, en hauteurs de la boîte d'ancrage. */
    val dx: Float,
    /** Décalage vertical, même unité. Positif vers le bas. */
    val dy: Float,
    /** Largeur de la zone, même unité. */
    val w: Float,
    /** Hauteur de la zone, même unité. */
    val h: Float,
    /**
     * Nom du fichier de la photo de référence, dans `filesDir/gabarits`.
     *
     * Un gabarit sans sa photo ne se relit pas : ouvrir « Modifier » sur un
     * écran noir ne dit ni ce qui a été désigné ni pourquoi la zone tombe à
     * côté. La photo est la seule pièce qui rend l'étalonnage vérifiable après
     * coup — et c'est un étalonnage qui engage toute une palette.
     */
    val photo: String = "",
    /**
     * Les deux rectangles désignés sur la photo de référence, en pixels de
     * cette photo.
     *
     * Ils ne servent **jamais** à la lecture — c'est la géométrie relative qui
     * s'y emploie, seule capable de suivre l'échelle. Ils ne servent qu'à
     * rouvrir l'étalonnage et remontrer exactement ce qui avait été désigné.
     *
     * Retrouver l'ancre par son texte ne suffisait pas : une étiquette Lenovo
     * porte `Serial Number` et `Type Number`, donc le mot `Number` deux fois.
     * La présélection s'étirait alors d'un bout à l'autre de l'étiquette.
     */
    val refAncre: ScanRoi.Box? = null,
    val refSN: ScanRoi.Box? = null,
    /**
     * La longueur du numéro, en caractères utiles — ponctuation exclue.
     *
     * Relevée sur le numéro que l'opérateur a désigné : il l'a sous les yeux,
     * l'app n'a rien à deviner. C'est ce qui rend le choix d'une marque inutile
     * quand un gabarit est affecté, et ce qui ouvre l'app aux étiquettes dont
     * aucun profil ne connaît le format.
     */
    val longueur: Int = 0,
    /**
     * L'alphabet du fabricant emploie-t-il `O` et `I`.
     *
     * Déduit du numéro de référence : s'il n'en contient aucun, on tient pour
     * acquis qu'ils sont proscrits — comme chez Apple et Asus, où les y voir
     * est une erreur de lecture certaine. Un seul exemplaire ne le prouve pas,
     * mais la conséquence est mesurée : la lecture part en vérification avec
     * une correction proposée, jamais réécrite d'office.
     */
    val sansOI: Boolean = false
) {

    /**
     * Le format que ce gabarit annonce, ou null s'il n'en porte pas.
     *
     * Les gabarits étalonnés avant que le format y soit rangé rendent null : le
     * lot retombe alors sur son profil de marque, comme avant.
     */
    val format: SerialParser.Format?
        get() = if (longueur <= 0) null else SerialParser.Format(
            longueurs = setOf(longueur),
            premiereLettre = false,
            minChiffres = 1,
            minLettres = 1,
            sansOI = sansOI
        )

    /**
     * La zone SN projetée sur une image où le point clé a été trouvé.
     *
     * [marge] élargit la zone : l'impression varie d'une étiquette à l'autre et
     * les boîtes de ML Kit ne sont pas au pixel près. Une zone trop juste
     * couperait un caractère de bord, et un numéro tronqué qui passe le format
     * est un faux numéro d'aspect parfait.
     */
    fun projeter(boiteAncre: ScanRoi.Box, marge: Float = MARGE): ScanRoi.Box {
        val unite = boiteAncre.height.toFloat()
        val left = boiteAncre.left + dx * unite
        val top = boiteAncre.top + dy * unite
        val mx = w * unite * marge
        val my = h * unite * marge
        return ScanRoi.Box(
            (left - mx).toInt(),
            (top - my).toInt(),
            (left + w * unite + mx).toInt(),
            (top + h * unite + my).toInt()
        )
    }

    /**
     * Le gabarit désigne-t-il un endroit crédible.
     *
     * Un mot-clé et un numéro trouvés aux deux bouts d'une photo donneraient
     * des coefficients énormes et un gabarit qui ne retrouverait jamais rien.
     * Mieux vaut refuser la déduction et laisser l'opérateur tracer à la main.
     */
    val raisonnable: Boolean
        get() = kotlin.math.abs(dx) <= 30f && kotlin.math.abs(dy) <= 10f &&
            w in 1f..40f && h in 0.3f..5f

    /**
     * Deux gabarits désignent-ils le même endroit.
     *
     * Sert à confirmer une déduction sur deux trames avant de l'adopter, dans
     * le même esprit que la double lecture : une mesure unique ne prouve rien.
     * La tolérance est large — les boîtes de ML Kit bougent d'une trame à
     * l'autre —, elle n'écarte que les déductions franchement différentes,
     * celles où un autre élément a été pris pour le numéro.
     */
    fun proche(autre: Gabarit, tolerance: Float = 0.4f): Boolean =
        ancre.equals(autre.ancre, ignoreCase = true) &&
            kotlin.math.abs(dx - autre.dx) <= tolerance &&
            kotlin.math.abs(dy - autre.dy) <= tolerance &&
            kotlin.math.abs(w - autre.w) <= tolerance * 3 &&
            kotlin.math.abs(h - autre.h) <= tolerance

    /** Un mot reconnu et sa boîte — de quoi raisonner sur la géométrie sans
     *  dépendre des types ML Kit, donc en JVM. */
    data class Mot(val texte: String, val boite: ScanRoi.Box)

    companion object {
        /** Marge par défaut autour de la zone, en fraction de ses côtés. */
        const val MARGE = 0.15f

        /**
         * Le gabarit déduit tout seul d'une photo de référence.
         *
         * L'app cherche dans les mots reconnus **un** mot-clé et **un** numéro
         * plausible au format annoncé, et en tire la géométrie. La règle est
         * l'unicité : deux mots-clés ou deux candidats, et on ne déduit rien.
         * Trancher entre deux reviendrait à deviner, et un gabarit deviné
         * fausserait ensuite tout un lot — le pire défaut possible, puisqu'il
         * serait systématique.
         *
         * [format] vient de la marque déclarée ou du profil détecté. C'est la
         * limite assumée de la déduction automatique : sur une étiquette dont
         * on ne connaît pas la longueur, elle échoue, et c'est le tracé manuel
         * qui prend le relais. Elle couvre en revanche Apple et Asus, donc les
         * deux mises en page qu'on a sous la main.
         *
         * Rend null dès que le moindre doute existe : la déduction est un
         * confort, jamais une source de vérité.
         */
        fun auto(
            id: String,
            nom: String,
            mots: List<Mot>,
            format: SerialParser.Format
        ): Gabarit? {
            val ancre = mots.filter { SerialParser.voitAncre(it.texte) }.singleOrNull() ?: return null
            val numero = mots
                .filter { it !== ancre }
                .filter { SerialParser.isPlausible(nettoyer(it.texte), format) }
                .singleOrNull() ?: return null
            return depuisReference(id, nom, ancre.texte.trim(), ancre.boite, numero.boite, numero.texte)
                ?.takeIf { it.raisonnable }
        }

        /** Le mot réduit à ce qu'un numéro peut contenir : ML Kit rend parfois
         *  la ponctuation de l'étiquette collée au numéro. */
        private fun nettoyer(mot: String): String =
            SerialParser.fixPrefix(mot.uppercase().filter { it.isLetterOrDigit() })

        /**
         * Le gabarit déduit de ce que l'opérateur a tracé sur la photo de
         * référence : la boîte du point clé et celle du numéro, en pixels de
         * cette photo-là. C'est ici que l'absolu devient relatif.
         *
         * Rend `null` si la boîte d'ancrage est dégénérée — une hauteur nulle
         * ne peut pas servir d'unité, et le gabarit qui en sortirait
         * désignerait n'importe quoi.
         */
        fun depuisReference(
            id: String,
            nom: String,
            ancre: String,
            boiteAncre: ScanRoi.Box,
            boiteSN: ScanRoi.Box,
            /** Le numéro tel que lu sur la référence, d'où le format se déduit. */
            numero: String = ""
        ): Gabarit? {
            val unite = boiteAncre.height.toFloat()
            if (unite <= 0f || boiteSN.width <= 0 || boiteSN.height <= 0) return null
            val utile = SerialParser.canonique(numero)
            return Gabarit(
                id = id,
                nom = nom,
                refAncre = boiteAncre,
                refSN = boiteSN,
                longueur = utile.length,
                sansOI = utile.isNotEmpty() && utile.none { it == 'O' || it == 'I' },
                ancre = ancre,
                dx = (boiteSN.left - boiteAncre.left) / unite,
                dy = (boiteSN.top - boiteAncre.top) / unite,
                w = boiteSN.width / unite,
                h = boiteSN.height / unite
            )
        }
    }
}
