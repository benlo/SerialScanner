package fr.gotatanka.serialscanner

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Génération du CSV du relevé. Kotlin pur : le format part chez le client, une
 * colonne décalée se voit tard et coûte cher, donc il se teste en JVM.
 *
 * Séparateur `;` — c'est ce qu'attend Excel en locale française, où la virgule
 * est le séparateur décimal.
 */
object CsvExport {

    const val ENTETE = "serial;model;emc;source;fiabilite;photo;horodatage"

    data class Ligne(
        val serial: String,
        val model: String,
        val emc: String,
        val source: String,
        val fiabilite: String,
        val photo: String,
        val horodatage: String
    )

    fun horodatage(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    fun ligne(r: Reading, zone: ZoneId = ZoneId.systemDefault()): Ligne = Ligne(
        serial = r.serial.orEmpty(),
        model = r.model.orEmpty(),
        emc = r.emc.orEmpty(),
        source = when (r.origine) {
            Origine.SCAN -> "scan"
            Origine.IMPORT -> "import"
            Origine.SAISIE -> "saisie"
        },
        // Le mot compte : c'est lui qui dit au client quelles lignes rouvrir.
        // « lu » n'est pas « confirmé » : la machine a lu, personne n'a vérifié.
        fiabilite = when (r.etat) {
            Etat.A_REPRENDRE -> "a_verifier"
            Etat.CONTROLE -> "confirme"
            Etat.LU -> "lu_non_controle"
        },
        photo = r.photo,
        horodatage = horodatage(r.timestamp, zone)
    )

    fun document(lignes: List<Ligne>): String = buildString {
        append(ENTETE).append("\r\n")
        for (l in lignes) {
            append(
                listOf(l.serial, l.model, l.emc, l.source, l.fiabilite, l.photo, l.horodatage)
                    .joinToString(";", transform = ::echappe)
            ).append("\r\n")
        }
    }

    /** RFC 4180 : un champ qui contient le séparateur, un guillemet ou un saut
     *  de ligne est encadré, et ses guillemets sont doublés. */
    fun echappe(champ: String): String =
        if (champ.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + champ.replace("\"", "\"\"") + "\""
        } else {
            champ
        }

    /** Nom de fichier proposé : le nom du lot sert de titre au client. */
    fun nomFichier(nomLot: String, epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val jour = Instant.ofEpochMilli(epochMs).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val base = nomLot.trim().ifBlank { "lot" }
            .replace(Regex("""[^\p{L}\p{N}_-]+"""), "_")
            .trim('_')
            .take(40)
        return "${base.ifBlank { "lot" }}_$jour.csv"
    }
}
