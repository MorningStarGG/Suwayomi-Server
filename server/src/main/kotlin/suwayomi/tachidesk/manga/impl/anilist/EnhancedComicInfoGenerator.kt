package suwayomi.tachidesk.manga.impl.anilist

import eu.kanade.tachiyomi.source.local.metadata.COMIC_INFO_FILE
import eu.kanade.tachiyomi.source.local.metadata.ComicInfo
import eu.kanade.tachiyomi.source.local.metadata.ComicInfoPublishingStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import nl.adaptivity.xmlutil.serialization.XML
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import org.w3c.dom.Element
import suwayomi.tachidesk.manga.impl.util.getComicInfo
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.StringWriter
import java.nio.file.Path
import java.time.Instant
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.math.floor

/**
 * Creates enhanced ComicInfo.xml files with AniList data
 */
class EnhancedComicInfoGenerator {
    private val logger = KotlinLogging.logger {}

    /**
     * Creates a ComicInfo.xml file with enhanced metadata
     */
    fun createEnhancedComicInfoFile(
        dir: Path,
        mangaRow: ResultRow,
        chapterRow: ResultRow,
        debug: Boolean = false
    ) {
        try {
            // Check if AniList enhancement is enabled
            if (!serverConfig.useAnilist.value) {
                logger.info { "AniList integration disabled, using standard ComicInfo.xml" }
                // Use the standard ComicInfo.xml generation
                createStandardComicInfoFile(dir, mangaRow, chapterRow)
                return
            }
            
            if (debug) {
                logger.debug { "Creating enhanced ComicInfo.xml with AniList data for Chapter ${chapterRow[ChapterTable.chapter_number]}" }
            }
            
            // Get enhanced metadata
            val metadataEnhancer = MangaMetadataEnhancer()
            val enhancedData = metadataEnhancer.enhanceMangaMetadata(mangaRow, chapterRow, debug)
            
            // Generate the XML document
            val xmlContent = createComicInfoXml(chapterRow, enhancedData, debug)
            
            // Remove the old file if it exists
            (dir / COMIC_INFO_FILE).deleteIfExists()
            
            // Write the new file
            (dir / COMIC_INFO_FILE).outputStream().use { outputStream ->
                outputStream.write(xmlContent.toByteArray())
            }
            
            logger.info { "Enhanced ComicInfo.xml created successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error creating enhanced ComicInfo.xml" }
            
            // Fall back to standard ComicInfo generation
            logger.warn { "Falling back to standard ComicInfo.xml generation" }
            createStandardComicInfoFile(dir, mangaRow, chapterRow)
        }
    }
    
    /**
     * Fallback to create a standard ComicInfo.xml file
     */
    private fun createStandardComicInfoFile(
        dir: Path,
        mangaRow: ResultRow,
        chapterRow: ResultRow
    ) {
        // Get chapter URL
        val chapterUrl = chapterRow[ChapterTable.url]
        
        // Get categories
        val categories = transaction {
            CategoryMangaTable
                .innerJoin(CategoryTable)
                .selectAll()
                .where {
                    CategoryMangaTable.manga eq mangaRow[MangaTable.id]
                }.orderBy(CategoryTable.order to SortOrder.ASC)
                .map {
                    it[CategoryTable.name]
                }
        }.takeUnless { it.isEmpty() }
        
        // Create standard ComicInfo
        val comicInfo = getComicInfo(mangaRow, chapterRow, chapterUrl, categories)
        
        // Remove the old file
        (dir / COMIC_INFO_FILE).deleteIfExists()
        
        // Write the new file
        (dir / COMIC_INFO_FILE).outputStream().use {
            val comicInfoString = Injekt.get<XML>().encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }
    
    /**
     * Create ComicInfo.xml content with enhanced metadata
     */
    private fun createComicInfoXml(
        chapterRow: ResultRow,
        enhancedData: Map<String, Any>,
        debug: Boolean
    ): String {
        // Create XML document
        val documentFactory = DocumentBuilderFactory.newInstance()
        val documentBuilder = documentFactory.newDocumentBuilder()
        val document = documentBuilder.newDocument()
        
        // Create root element with proper namespaces
        val root = document.createElement("ComicInfo")
        root.setAttribute("xmlns:xsd", "http://www.w3.org/2001/XMLSchema")
        root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
        document.appendChild(root)
        
        // Add series info
        addElement(document, root, "Series", enhancedData["title"] as String)
        
        // Extract just the actual title, not the "Vol.X Ch.Y - " part
        val chapterName = chapterRow[ChapterTable.name]
        var actualTitle = ""
        
        if (chapterName.isNotEmpty()) {
            val pattern = Pattern.compile("(?:Vol\\.\\d+\\s*)?(?:Ch\\.\\d+(?:\\.\\d+)?)?(?:\\s*-\\s*\"?(.+?)\"?)?$")
            val matcher = pattern.matcher(chapterName)
            if (matcher.find() && matcher.group(1) != null) {
                actualTitle = matcher.group(1).trim()
                // Remove quotes if they exist
                actualTitle = actualTitle.replace(Regex("^\"|\"$"), "").trim()
            }
        }
        
        // Use the clean title or the full name if no clean title was extracted
        addElement(document, root, "Title", if (actualTitle.isNotEmpty()) actualTitle else chapterName)
        
        // Add chapter number
        val chapterNum = enhancedData["chapter_number"] as Float
        if (chapterNum >= 0) {
            val chapterNumStr = if (chapterNum == floor(chapterNum)) {
                chapterNum.toInt().toString()
            } else {
                chapterNum.toString()
            }
            addElement(document, root, "Number", chapterNumStr)
        }
        
        // Add volume info if available
        val volume = enhancedData["volume"] as String
        if (volume.isNotEmpty()) {
            addElement(document, root, "Volume", volume)
        }
        
        // Add summary/description
        val description = enhancedData["description"] as String
        if (description.isNotEmpty()) {
            addElement(document, root, "Summary", description)
        }
        
        // Collect Notes content in the appropriate order
        val notesContent = mutableListOf<String>()
        
        // 1. Alternative titles should always be first
        val alternativeTitles = enhancedData["alternative_titles"]
        if (alternativeTitles is List<*> && alternativeTitles.isNotEmpty()) {
            // Safe to use as strings since we know they're titles
            val titles = alternativeTitles.filterIsInstance<String>()
            if (titles.isNotEmpty()) {
                notesContent.add("Alternative Titles:\n${titles.joinToString("\n")}")
            }
        }
        
        // 2. Status info next
        val status = enhancedData["status"]?.toString()
        if (!status.isNullOrEmpty()) {
            notesContent.add("Status: $status")
        }
        
        // 3. AniList ID
        val anilistId = enhancedData["anilist_id"]
        if (anilistId != null) {
            notesContent.add("AniList ID: $anilistId")
        }
        
        // 4. AniList URL last
        val siteUrl = enhancedData["siteUrl"] as? String
        if (!siteUrl.isNullOrEmpty()) {
            notesContent.add("AniList URL: $siteUrl")
        }
        
        // Now create the Notes element with all content in the correct order
        if (notesContent.isNotEmpty()) {
            addElement(document, root, "Notes", notesContent.joinToString("\n"))
        }
        
        // Add date information
        val year = enhancedData["year"] as Int
        if (year > 0) {
            addElement(document, root, "Year", year.toString())
        } else {
            // Try to use upload date if no year from AniList
            try {
                val uploadDate = chapterRow[ChapterTable.date_upload]
                if (uploadDate > 0) {
                    val date = Instant.ofEpochMilli(uploadDate)
                    val yearValue = date.atZone(java.time.ZoneId.systemDefault()).year
                    addElement(document, root, "Year", yearValue.toString())
                }
            } catch (e: Exception) {
                // Skip date if we can't parse it
                if (debug) {
                    logger.debug(e) { "Could not parse upload date for year" }
                }
            }
        }
        
        val month = enhancedData["month"] as Int
        if (month > 0) {
            addElement(document, root, "Month", month.toString())
        }
        
        val day = enhancedData["day"] as Int
        if (day > 0) {
            addElement(document, root, "Day", day.toString())
        }
        
        // Add author information if available
        val author = enhancedData["author"] as String
        if (author.isNotEmpty()) {
            addElement(document, root, "Writer", author)
        }
        
        // Add artist information if available
        val artist = enhancedData["artist"] as String
        if (artist.isNotEmpty()) {
            addElement(document, root, "Penciller", artist)
            addElement(document, root, "Inker", artist)
            addElement(document, root, "Colorist", artist)
        }
        
        // Add publisher information
        val publisher = enhancedData["publisher"] as String
        if (publisher.isNotEmpty()) {
            addElement(document, root, "Publisher", publisher)
        }
        
        // Add genre and tags
        val genres = when (val genreValue = enhancedData["genre"]) {
            is List<*> -> genreValue.filterIsInstance<String>().joinToString(", ")
            is String -> genreValue
            else -> ""
        }
        
        if (genres.isNotEmpty()) {
            addElement(document, root, "Genre", genres)
        }
        
        // Add page count
        val pageCount = chapterRow[ChapterTable.pageCount]
        if (pageCount > 0) {
            addElement(document, root, "PageCount", pageCount.toString())
        }
        
        // Add characters if available
        val characters = when (val charactersValue = enhancedData["characters"]) {
            is List<*> -> charactersValue.filterIsInstance<String>().joinToString(", ")
            is String -> charactersValue
            else -> ""
        }
        
        if (characters.isNotEmpty()) {
            addElement(document, root, "Characters", characters)
        }
        
        // Add teams if available
        val teams = when (val teamsValue = enhancedData["teams"]) {
            is List<*> -> teamsValue.filterIsInstance<String>().joinToString(", ")
            is String -> teamsValue
            else -> ""
        }
        
        if (teams.isNotEmpty()) {
            addElement(document, root, "Teams", teams)
        }
        
        // Add age rating if available
        addElement(document, root, "AgeRating", enhancedData["ageRating"] as String)
        
        addElement(document, root, "BlackAndWhite", "Yes")
        
        // Use scanlator information if available
        val scanlator = enhancedData["scanlator"] as String
        val scanInfo = if (scanlator.isNotEmpty()) {
            "Released by $scanlator"
        } else {
            "Unknown"
        }
        
        addElement(document, root, "ScanInformation", scanInfo)
        
        // Add story arc if available
        val storyArc = enhancedData["storyArc"] as String
        if (storyArc.isNotEmpty()) {
            addElement(document, root, "StoryArc", storyArc)
        }
        
        // Add Translator element from the scanlator field
        if (scanlator.isNotEmpty()) {
            addElement(document, root, "Translator", scanlator)
        }
        
        // Add Web element from the chapter's URL
        val webUrl = chapterRow[ChapterTable.url]
        if (webUrl.isNotEmpty()) {
            addElement(document, root, "Web", webUrl)
        }
        
        // Add status mapping for correct case
        val statusMap = mapOf(
            "ONGOING" to "Ongoing",
            "COMPLETED" to "Completed",
            "LICENSED" to "Licensed",
            "PUBLISHING_FINISHED" to "Publishing finished",
            "CANCELLED" to "Cancelled",
            "ON_HIATUS" to "On hiatus",
            "UNKNOWN" to "Unknown"
        )
        
        // Get the status value and apply the mapping
        val statusValue = enhancedData["status"]?.toString() ?: "Unknown"
        val mappedStatus = statusMap.getOrDefault(statusValue.uppercase(), statusValue)
        
        // Add PublishingStatusTachiyomi
        if (mappedStatus.isNotEmpty()) {
            val statusElement = document.createElement("ty:PublishingStatusTachiyomi")
            statusElement.setAttribute("xmlns:ty", "http://www.w3.org/2001/XMLSchema")
            statusElement.textContent = mappedStatus
            root.appendChild(statusElement)
        }
        
        // Convert to string with pretty formatting
        return formatXmlDocument(document)
    }
    
    /**
     * Helper to add an element to the XML document
     */
    private fun addElement(document: Document, parent: Element, name: String, value: String) {
        if (value.isNotEmpty()) {
            val element = document.createElement(name)
            element.textContent = value
            parent.appendChild(element)
        }
    }
    
    /**
     * Format XML document to pretty-printed string
     */
    private fun formatXmlDocument(document: Document): String {
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        
        val source = DOMSource(document)
        val writer = StringWriter()
        val result = StreamResult(writer)
        transformer.transform(source, result)
        
        return writer.toString()
    }
}