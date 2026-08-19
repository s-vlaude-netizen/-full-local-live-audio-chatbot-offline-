package de.localvoice.livechat.data

/**
 * Ein Modell, das die App anbieten darf.
 *
 * Die Auswahl orientiert sich an der Liste der Google-AI-Edge-Gallery. Der
 * genaue Dateiname steht bewusst nicht hier: die Ablagen benennen ihre Dateien
 * um, sobald eine neue Quantisierung dazukommt. Stattdessen fragt die App beim
 * Herunterladen das Dateiverzeichnis der Ablage ab und waehlt selbst.
 */
data class CatalogEntry(
    val repoId: String,
    val title: String,
    val sizeLabel: String,
    val note: String,
    /** Gated: erst nach Zustimmung zur Lizenz und nur mit Zugangstoken. */
    val gated: Boolean,
) {
    val licenseUrl: String get() = "https://huggingface.co/$repoId"
}

object ModelCatalog {

    /** Der Vorschlag beim ersten Start. */
    val DEFAULT: CatalogEntry = CatalogEntry(
        repoId = "litert-community/Gemma3-1B-IT",
        title = "Gemma 3 1B IT",
        sizeLabel = "ca. 0,6 GB",
        note = "Googles kleines Sprachmodell. Auf dem Telefon fluessig, " +
            "fuer ein gesprochenes Gespraech gut geeignet.",
        gated = true,
    )

    val ENTRIES: List<CatalogEntry> = listOf(
        DEFAULT,
        CatalogEntry(
            repoId = "litert-community/gemma-3-270m-it",
            title = "Gemma 3 270M IT",
            sizeLabel = "ca. 0,3 GB",
            note = "Sehr klein und schnell, dafuer inhaltlich duenn. " +
                "Gut, um die Schleife auf schwacher Hardware zu testen.",
            gated = true,
        ),
        CatalogEntry(
            repoId = "litert-community/Qwen2.5-1.5B-Instruct",
            title = "Qwen 2.5 1.5B Instruct",
            sizeLabel = "ca. 1,6 GB",
            note = "Groesser und langsamer, antwortet dafuer gehaltvoller. " +
                "Apache-Lizenz, laedt ohne Token.",
            gated = false,
        ),
    )
}

/**
 * Waehlt aus dem Dateiverzeichnis einer Ablage die Datei, die hier laufen soll.
 *
 * Die Ablagen enthalten meist mehrere Varianten desselben Modells: verschiedene
 * Quantisierungen und Bauten, die auf einen bestimmten Chip zugeschnitten sind.
 * Letztere laufen auf anderen Geraeten nicht und scheiden deshalb aus, solange
 * es eine allgemeine Variante gibt.
 */
object ModelFileChooser {

    private val CHIP_SPECIFIC = listOf(
        "google_tensor", "snapdragon", "exynos", "mediatek", "dimensity", "_npu",
    )

    fun pick(fileNames: List<String>): String? {
        val candidates = fileNames.filter { it.endsWith(".litertlm", ignoreCase = true) }
        if (candidates.isEmpty()) return null
        val generic = candidates.filterNot { isChipSpecific(it) }
        val pool = generic.ifEmpty { candidates }
        return pool.minWithOrNull(
            compareBy<String> { quantisationRank(it) }
                .thenBy { it.length }
                .thenBy { it },
        )
    }

    private fun isChipSpecific(name: String): Boolean {
        val lower = name.lowercase()
        return CHIP_SPECIFIC.any { it in lower }
    }

    /** Kleiner ist besser: q4 spart am meisten Speicher und rechnet am schnellsten. */
    private fun quantisationRank(name: String): Int {
        val lower = name.lowercase()
        return when {
            "q4" in lower -> 0
            "int4" in lower -> 0
            "q8" in lower -> 1
            "int8" in lower -> 1
            else -> 2
        }
    }
}
