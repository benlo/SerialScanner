package fr.gotatanka.serialscanner

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistance des lots en JSON dans le stockage interne.
 *
 * Un lot fait quelques dizaines de lignes : une base relationnelle
 * apporterait ici un schéma et des migrations sans rien résoudre. Le fichier
 * est réécrit en entier via un temporaire renommé — une écriture interrompue
 * ne doit pas laisser un relevé tronqué.
 */
object LotStore {

    private const val FICHIER = "lots.json"

    fun charger(dossier: File): MutableList<Lot> {
        val f = File(dossier, FICHIER)
        if (!f.exists()) return mutableListOf()
        // Un JSON corrompu ne doit pas empêcher l'app de démarrer : mieux vaut
        // repartir vide que rester bloqué au lancement sur un poste d'atelier.
        return runCatching { depuisJson(f.readText()) }.getOrElse { mutableListOf() }
    }

    fun enregistrer(dossier: File, lots: List<Lot>) {
        val tmp = File(dossier, "$FICHIER.tmp")
        tmp.writeText(versJson(lots))
        val cible = File(dossier, FICHIER)
        if (cible.exists()) cible.delete()
        tmp.renameTo(cible)
    }

    fun versJson(lots: List<Lot>): String {
        val racine = JSONArray()
        for (lot in lots) {
            val lignes = JSONArray()
            for (r in lot.readings) {
                lignes.put(
                    JSONObject()
                        .put("photo", r.photo)
                        .put("origine", r.origine.name)
                        .put("serial", r.serial ?: JSONObject.NULL)
                        .put("model", r.model ?: JSONObject.NULL)
                        .put("emc", r.emc ?: JSONObject.NULL)
                        .put("aVerifier", r.needsReview)
                        .put("controle", r.controle)
                        .put("horodatage", r.timestamp)
                )
            }
            racine.put(
                JSONObject()
                    .put("id", lot.id)
                    .put("nom", lot.nom)
                    .put("cree", lot.cree)
                    .put("marque", lot.marque)
                    .put("lectures", lignes)
            )
        }
        return racine.toString()
    }

    fun depuisJson(texte: String): MutableList<Lot> {
        val racine = JSONArray(texte)
        val lots = mutableListOf<Lot>()
        for (i in 0 until racine.length()) {
            val o = racine.getJSONObject(i)
            val lignes = o.optJSONArray("lectures") ?: JSONArray()
            val readings = mutableListOf<Reading>()
            for (j in 0 until lignes.length()) {
                val l = lignes.getJSONObject(j)
                readings.add(
                    Reading(
                        photo = l.optString("photo", ""),
                        origine = runCatching { Origine.valueOf(l.optString("origine")) }
                            .getOrDefault(Origine.IMPORT),
                        serial = l.optStringOuNull("serial"),
                        model = l.optStringOuNull("model"),
                        emc = l.optStringOuNull("emc"),
                        needsReview = l.optBoolean("aVerifier", false),
                        // Lecture enregistrée avant l'état à trois valeurs :
                        // personne ne l'a contrôlée, c'est le défaut honnête.
                        controle = l.optBoolean("controle", false),
                        timestamp = l.optLong("horodatage", 0L)
                    )
                )
            }
            // Lot enregistré avant l'arrivée des marques : détection par défaut.
            val marque = o.optString("marque").ifEmpty { Lot.MARQUE_AUTO }
            lots.add(
                Lot(o.getString("id"), o.getString("nom"), o.optLong("cree", 0L), marque, readings)
            )
        }
        return lots
    }

    private fun JSONObject.optStringOuNull(cle: String): String? =
        if (isNull(cle)) null else optString(cle).takeIf { it.isNotEmpty() }
}
