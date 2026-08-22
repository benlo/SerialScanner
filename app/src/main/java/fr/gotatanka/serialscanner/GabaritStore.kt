package fr.gotatanka.serialscanner

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * La bibliothèque de gabarits, en JSON dans le stockage interne.
 *
 * Fichier séparé des lots à dessein : un gabarit **survit aux lots qui s'en
 * servent**. Il est étalonné une fois sur un modèle de machine, puis affecté à
 * autant de lots qu'on veut — c'est tout l'intérêt, sinon il faudrait
 * réétalonner à chaque palette.
 *
 * Même discipline d'écriture que [LotStore] : temporaire renommé, et un
 * fichier corrompu repart vide plutôt que de bloquer l'app au lancement sur un
 * poste d'atelier.
 */
object GabaritStore {

    private const val FICHIER = "gabarits.json"

    fun charger(dossier: File): MutableList<Gabarit> {
        val f = File(dossier, FICHIER)
        if (!f.exists()) return mutableListOf()
        return runCatching { depuisJson(f.readText()) }.getOrElse { mutableListOf() }
    }

    fun enregistrer(dossier: File, gabarits: List<Gabarit>) {
        val tmp = File(dossier, "$FICHIER.tmp")
        tmp.writeText(versJson(gabarits))
        val cible = File(dossier, FICHIER)
        if (cible.exists()) cible.delete()
        tmp.renameTo(cible)
    }

    /**
     * Les lots qui se servent encore de ce gabarit.
     *
     * Un gabarit supprimé sous les pieds d'un lot laisserait ce lot à moitié
     * étalonné : ses lectures passées ont été faites avec, les suivantes sans,
     * et rien à l'écran ne dirait pourquoi. La suppression est donc refusée
     * tant que cette liste n'est pas vide — et la liste sert aussi à dire
     * *quels* lots, ce qu'un simple booléen ne permettrait pas.
     *
     * Kotlin pur : la règle se teste en JVM, pas sur un téléphone.
     */
    fun utilisePar(gabaritId: String, lots: List<Lot>): List<Lot> =
        lots.filter { it.gabaritId == gabaritId }

    /** Un rectangle de référence, ou `null` JSON s'il n'y en a pas. Quatre
     *  entiers plutôt qu'un objet : c'est de la provenance, pas un modèle. */
    private fun boiteJson(b: ScanRoi.Box?): Any =
        if (b == null) JSONObject.NULL
        else JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom)

    private fun boiteDepuis(a: JSONArray?): ScanRoi.Box? =
        if (a == null || a.length() < 4) null
        else ScanRoi.Box(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3))

    fun versJson(gabarits: List<Gabarit>): String {
        val racine = JSONArray()
        for (g in gabarits) {
            racine.put(
                JSONObject()
                    .put("id", g.id)
                    .put("nom", g.nom)
                    .put("ancre", g.ancre)
                    .put("dx", g.dx.toDouble())
                    .put("dy", g.dy.toDouble())
                    .put("w", g.w.toDouble())
                    .put("h", g.h.toDouble())
                    .put("photo", g.photo)
                    .put("refAncre", boiteJson(g.refAncre))
                    .put("refSN", boiteJson(g.refSN))
                    .put("longueur", g.longueur)
                    .put("sansOI", g.sansOI)
            )
        }
        return racine.toString()
    }

    fun depuisJson(texte: String): MutableList<Gabarit> {
        val racine = JSONArray(texte)
        val gabarits = mutableListOf<Gabarit>()
        for (i in 0 until racine.length()) {
            val o = racine.getJSONObject(i)
            val id = o.optString("id")
            val ancre = o.optString("ancre")
            val w = o.optDouble("w", 0.0).toFloat()
            val h = o.optDouble("h", 0.0).toFloat()
            // Un gabarit tronqué est écarté plutôt que chargé : une zone sans
            // largeur ni ancre désignerait n'importe quel coin de l'image, et
            // le lot vaut mieux sans gabarit qu'avec un faux.
            if (id.isEmpty() || ancre.isEmpty() || w <= 0f || h <= 0f) continue
            gabarits.add(
                Gabarit(
                    id = id,
                    nom = o.optString("nom").ifEmpty { ancre },
                    ancre = ancre,
                    dx = o.optDouble("dx", 0.0).toFloat(),
                    dy = o.optDouble("dy", 0.0).toFloat(),
                    w = w,
                    h = h,
                    photo = o.optString("photo"),
                    refAncre = boiteDepuis(o.optJSONArray("refAncre")),
                    refSN = boiteDepuis(o.optJSONArray("refSN")),
                    longueur = o.optInt("longueur", 0),
                    sansOI = o.optBoolean("sansOI", false)
                )
            )
        }
        return gabarits
    }
}
