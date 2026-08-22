package fr.gotatanka.serialscanner

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Source unique des lots pour les deux écrans.
 *
 * Chargé une fois, réécrit sur disque à chaque modification : sur un poste
 * d'atelier l'app se fait tuer sans prévenir (batterie, appel entrant), et un
 * relevé perdu se repaie en démontant à nouveau la palette.
 */
object Depot {

    private var cache: MutableList<Lot>? = null

    fun lots(ctx: Context): MutableList<Lot> =
        cache ?: LotStore.charger(ctx.filesDir).also { cache = it }

    fun lot(ctx: Context, id: String?): Lot? = lots(ctx).firstOrNull { it.id == id }

    fun creer(ctx: Context, nom: String, marque: String, gabaritId: String? = null): Lot {
        val lot = Lot(
            UUID.randomUUID().toString(), nom, System.currentTimeMillis(), marque, gabaritId
        )
        lots(ctx).add(0, lot)
        sauver(ctx)
        return lot
    }

    fun renommer(ctx: Context, id: String, nom: String) {
        val liste = lots(ctx)
        val i = liste.indexOfFirst { it.id == id }
        if (i >= 0) {
            liste[i] = liste[i].copy(nom = nom)
            sauver(ctx)
        }
    }

    fun changerMarque(ctx: Context, id: String, marque: String) {
        val liste = lots(ctx)
        val i = liste.indexOfFirst { it.id == id }
        if (i >= 0) {
            liste[i] = liste[i].copy(marque = marque)
            sauver(ctx)
        }
    }

    /** Remet un lot modifié à sa place dans la liste. Les écrans travaillent
     *  sur une copie — sans ça, la modification ne serait ni vue ni écrite. */
    fun remplacer(ctx: Context, lot: Lot) {
        val liste = lots(ctx)
        val i = liste.indexOfFirst { it.id == lot.id }
        if (i >= 0) {
            liste[i] = lot
            sauver(ctx)
        }
    }

    fun supprimer(ctx: Context, id: String) {
        lots(ctx).removeAll { it.id == id }
        sauver(ctx)
    }

    fun sauver(ctx: Context) = LotStore.enregistrer(ctx.filesDir, lots(ctx))

    // --- Gabarits -----------------------------------------------------------
    // Une bibliothèque à part, qui survit aux lots : un modèle de machine
    // s'étalonne une fois, puis sert à toutes les palettes qui en contiennent.

    private var cacheGabarits: MutableList<Gabarit>? = null

    fun gabarits(ctx: Context): MutableList<Gabarit> =
        cacheGabarits ?: GabaritStore.charger(ctx.filesDir).also { cacheGabarits = it }

    fun gabarit(ctx: Context, id: String?): Gabarit? =
        id?.let { g -> gabarits(ctx).firstOrNull { it.id == g } }

    /** Le gabarit d'un lot, ou null s'il n'est pas étalonné — auquel cas la
     *  lecture repart sur l'ancrage textuel, qui n'a jamais eu besoin de lui. */
    fun gabaritDuLot(ctx: Context, lot: Lot): Gabarit? = gabarit(ctx, lot.gabaritId)

    fun ajouterGabarit(ctx: Context, gabarit: Gabarit): Gabarit {
        gabarits(ctx).add(0, gabarit)
        sauverGabarits(ctx)
        return gabarit
    }

    fun modifierGabarit(ctx: Context, gabarit: Gabarit) {
        val liste = gabarits(ctx)
        val i = liste.indexOfFirst { it.id == gabarit.id }
        if (i >= 0) {
            liste[i] = gabarit
            sauverGabarits(ctx)
        }
    }

    /**
     * Supprime un gabarit, sauf s'il sert encore.
     *
     * Rend les lots qui l'utilisent : vide, la suppression a eu lieu ; non
     * vide, rien n'a bougé et l'appelant a de quoi dire lesquels.
     */
    fun supprimerGabarit(ctx: Context, id: String): List<Lot> {
        val bloquants = GabaritStore.utilisePar(id, lots(ctx))
        if (bloquants.isNotEmpty()) return bloquants
        gabarits(ctx).removeAll { it.id == id }
        sauverGabarits(ctx)
        return emptyList()
    }

    fun sauverGabarits(ctx: Context) =
        GabaritStore.enregistrer(ctx.filesDir, gabarits(ctx))

    /**
     * Supprime les vignettes que plus aucune lecture ne cite.
     *
     * Appelé après chaque suppression et au lancement : une seule règle, donc
     * rien ne s'accumule même si un chemin de suppression est oublié.
     */
    fun purgerPhotos(ctx: Context): Int =
        Photos.purger(File(ctx.filesDir, Photos.DOSSIER), lots(ctx)).size
}
