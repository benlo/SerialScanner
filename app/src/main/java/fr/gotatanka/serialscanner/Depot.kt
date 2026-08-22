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

    fun creer(ctx: Context, nom: String, marque: String): Lot {
        val lot = Lot(UUID.randomUUID().toString(), nom, System.currentTimeMillis(), marque)
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

    fun supprimer(ctx: Context, id: String) {
        lots(ctx).removeAll { it.id == id }
        sauver(ctx)
    }

    fun sauver(ctx: Context) = LotStore.enregistrer(ctx.filesDir, lots(ctx))

    /**
     * Supprime les vignettes que plus aucune lecture ne cite.
     *
     * Appelé après chaque suppression et au lancement : une seule règle, donc
     * rien ne s'accumule même si un chemin de suppression est oublié.
     */
    fun purgerPhotos(ctx: Context): Int =
        Photos.purger(File(ctx.filesDir, Photos.DOSSIER), lots(ctx)).size
}
