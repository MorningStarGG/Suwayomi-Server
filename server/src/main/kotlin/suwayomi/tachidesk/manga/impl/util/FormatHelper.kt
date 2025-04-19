package suwayomi.tachidesk.manga.impl.util

import org.jetbrains.exposed.sql.ResultRow
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import xyz.nulldev.androidcompat.util.SafePath
import kotlin.math.floor

object FormatHelper {
    /**
     * Formats a string by replacing variables with their values
     */
    fun formatString(format: String, variables: Map<String, String>): String {
        var result = format
        variables.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        return SafePath.buildValidFilename(result)
    }

    /**
    * Creates a variables map for manga formatting
    */
    fun createMangaVariables(mangaEntry: ResultRow): Map<String, String> {
        val source = GetCatalogueSource.getCatalogueSourceOrStub(mangaEntry[MangaTable.sourceReference])
        val sourceDir = SafePath.buildValidFilename(source.toString())
        
        return mapOf(
            "title" to mangaEntry[MangaTable.title],
            "source" to sourceDir,
        )
    }

    /**
     * Creates a variables map for chapter formatting
     */
    fun createChapterVariables(chapterEntry: ResultRow): Map<String, String> {
        val chapterNumber = chapterEntry[ChapterTable.chapter_number]
        val volumeNumber = extractVolumeNumber(chapterEntry[ChapterTable.name])
        
        return mapOf(
            "title" to chapterEntry[ChapterTable.name],
            "number" to chapterNumber.toString(),
            "number_padded" to formatChapterNumber(chapterNumber, 2),
            "number_padded3" to formatChapterNumber(chapterNumber, 3),
            "chapter" to chapterEntry[ChapterTable.name],
            "volume" to volumeNumber,
            "volume_prefix" to if (volumeNumber.isNotEmpty()) "Vol.$volumeNumber " else "",
            "name" to chapterEntry[ChapterTable.name],
            "title_suffix" to extractTitleSuffix(chapterEntry[ChapterTable.name], chapterNumber),
            "scanlator" to (if (chapterEntry[ChapterTable.scanlator] != null) "${chapterEntry[ChapterTable.scanlator]}_" else "")
        )
    }

    /**
     * Creates a variables map for CBZ file formatting
     */
    fun createCbzVariables(mangaTitle: String, chapterEntry: ResultRow): Map<String, String> {
        val chapterVariables = createChapterVariables(chapterEntry)
        return chapterVariables + mapOf("manga_title" to mangaTitle)
    }

    /**
     * Formats a chapter number with proper padding for the integer part
     * while preserving the decimal part
     */
    private fun formatChapterNumber(number: Float, padding: Int): String {
        val intPart = number.toInt()
        val decimalPart = number - intPart
        
        return if (decimalPart > 0) {
            "${intPart.toString().padStart(padding, '0')}${decimalPart.toString().substring(1)}"
        } else {
            intPart.toString().padStart(padding, '0')
        }
    }

    /**
     * Extracts volume number from chapter name
     * Example: "Vol.1 Ch.5" -> "1"
     */
    private fun extractVolumeNumber(chapterName: String): String {
        val volRegex = Regex("(?i)vol\\.?\\s*([0-9]+)")
        val match = volRegex.find(chapterName)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

    /**
     * Extracts title suffix from chapter name
     * Example: "Chapter 5: The Battle" -> ": The Battle"
     */
    private fun extractTitleSuffix(chapterName: String, chapterNumber: Float): String {
        val numberStr = if (chapterNumber == floor(chapterNumber)) {
            chapterNumber.toInt().toString()
        } else {
            chapterNumber.toString()
        }
        
        val patterns = listOf(
            Regex("(?i)chapter\\s*$numberStr[:\\s]\\s*(.+)"),
            Regex("(?i)ch\\.?\\s*$numberStr[:\\s]\\s*(.+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(chapterName)
            if (match != null) {
                return ": ${match.groupValues[1]}"
            }
        }
        
        return ""
    }
}