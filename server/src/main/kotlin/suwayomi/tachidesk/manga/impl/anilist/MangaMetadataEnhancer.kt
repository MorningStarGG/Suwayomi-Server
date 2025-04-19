package suwayomi.tachidesk.manga.impl.anilist

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.ResultRow
import org.json.JSONObject
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.serverConfig

/** Enhances manga metadata with AniList data */
class MangaMetadataEnhancer(
        private val aniListClient: AniListClient = AniListClient(),
        private val logger: KLogger = KotlinLogging.logger {}
) {
    /** Enhances manga metadata with AniList data if available */
    fun enhanceMangaMetadata(
            mangaRow: ResultRow,
            chapterRow: ResultRow,
            debug: Boolean = false
    ): Map<String, Any> {
        // Start with original manga data
        val enhancedData =
                mutableMapOf<String, Any>(
                        "title" to mangaRow[MangaTable.title],
                        "description" to (mangaRow[MangaTable.description] ?: ""),
                        "author" to (mangaRow[MangaTable.author] ?: ""),
                        "artist" to (mangaRow[MangaTable.artist] ?: ""),
                        "genre" to (mangaRow[MangaTable.genre]?.split(", ") ?: emptyList<String>()),
                        "year" to -1,
                        "month" to -1,
                        "day" to -1,
                        "status" to (mangaRow[MangaTable.status]),
                        "characters" to emptyList<String>(),
                        "teams" to emptyList<String>(),
                        "storyArc" to "",
                        "ageRating" to "Unknown",
                        "isRightToLeft" to true,
                        "publisher" to ""
                )

        if (debug) {
            logger.debug { "Base manga data: $enhancedData" }
        }

        // Add chapter-specific metadata
        enhancedData["chapter_number"] = chapterRow[ChapterTable.chapter_number]
        enhancedData["scanlator"] = chapterRow[ChapterTable.scanlator] ?: ""
        enhancedData["volume"] = "" // This seems to be missing in the DB schema

        if (debug) {
            logger.debug {
                "Added chapter metadata: chapter_number=${enhancedData["chapter_number"]}, scanlator=${enhancedData["scanlator"]}"
            }
        }

        // Don't proceed with AniList enhancement if not enabled or if Western comic
        val useAnilist = serverConfig.useAnilist.value
        val contentType = serverConfig.contentType.value

        if (!useAnilist || contentType == "comic") {
            if (contentType == "comic") {
                logger.info { "Skipping AniList lookup for Western comic content" }
                if (debug) {
                    logger.debug { "Skipping AniList lookup (content_type=comic)" }
                }
            } else {
                if (debug) {
                    logger.debug { "Skipping AniList lookup (use_anilist=false)" }
                }
            }
            return enhancedData
        }

        // Use AniList for manga, manhwa, manhua, and webtoons
        logger.info { "Using AniList API for $contentType content" }
        if (debug) {
            logger.debug { "Using AniList API for $contentType content" }
        }

        // Get AniList matching configuration
        val defaultUncertainAction = serverConfig.anilistDefaultUncertainAction.value

        if (debug) {
            logger.debug { "AniList config: default_uncertain_action=$defaultUncertainAction" }
        }

        // Try to find manga on AniList
        var anilistData: JSONObject? = null
        var confidence = "low"

        // First try to extract ID from URL if available (MOST RELIABLE)
        val mangaUrl = mangaRow[MangaTable.url]

        if (debug) {
            logger.debug { "Trying to extract AniList ID from URL: $mangaUrl" }
        }

        val mangaId = aniListClient.extractMangaIdFromUrl(mangaUrl)

        if (mangaId != null) {
            logger.info { "Found AniList ID in URL: $mangaId, fetching data..." }
            if (debug) {
                logger.debug { "Found AniList ID in URL: $mangaId" }
            }

            // We need to run a blocking call here because this function isn't suspend
            anilistData = runBlocking { aniListClient.searchMangaById(mangaId) }
            if (anilistData != null) {
                confidence = "high" // ID-based match is highly reliable
                if (debug) {
                    logger.debug { "Successfully retrieved data by ID with high confidence" }
                    val title =
                            anilistData.optJSONObject("title")?.optString("english")
                                    ?: anilistData.optJSONObject("title")?.optString("romaji") ?: ""
                    logger.debug { "AniList title: $title" }
                }
            } else {
                if (debug) {
                    logger.debug { "Failed to retrieve data for ID: $mangaId" }
                }
            }
        } else {
            if (debug) {
                logger.debug { "No AniList ID found in URL" }
            }
        }

        // If no ID found or lookup failed, try by title
        if (anilistData == null) {
            val originalTitle = mangaRow[MangaTable.title]
            logger.info { "Searching AniList for: $originalTitle" }
            if (debug) {
                logger.debug { "Searching AniList by title: $originalTitle" }
            }

            // We need to run a blocking call here because this function isn't suspend
            anilistData = runBlocking { aniListClient.searchMangaByTitle(originalTitle) }

            if (debug) {
                if (anilistData != null) {
                    val title =
                            anilistData.optJSONObject("title")?.optString("english")
                                    ?: anilistData.optJSONObject("title")?.optString("romaji") ?: ""
                    logger.debug { "Title search found: $title" }
                } else {
                    logger.debug { "Title search returned no results" }
                }
            }

            // Verify the match is reasonable
            if (anilistData != null) {
                // Extract all possible titles from the AniList data
                val anilistTitles = mutableListOf<String>()
                val titles = anilistData.optJSONObject("title")
                if (titles != null) {
                    for (titleType in listOf("english", "romaji", "native")) {
                        if (titles.has(titleType) && !titles.isNull(titleType)) {
                            anilistTitles.add(titles.optString(titleType))
                        }
                    }
                }

                // Add synonyms
                val synonyms = anilistData.optJSONArray("synonyms")
                if (synonyms != null) {
                    for (i in 0 until synonyms.length()) {
                        val synonym = synonyms.optString(i)
                        if (synonym.isNotEmpty()) {
                            anilistTitles.add(synonym)
                        }
                    }
                }

                if (debug) {
                    logger.debug { "AniList title candidates: $anilistTitles" }
                }

                // Normalize titles for comparison
                val normalizedOriginal = aniListClient.normalizeTitle(originalTitle)
                var matchFound = false

                if (debug) {
                    logger.debug { "Normalized original title: $normalizedOriginal" }
                }

                // Check if any of the AniList titles match the original
                for (title in anilistTitles) {
                    val normalizedTitle = aniListClient.normalizeTitle(title)
                    if (debug) {
                        logger.debug { "Comparing with '$title' (normalized: '$normalizedTitle')" }
                    }

                    if (normalizedOriginal == normalizedTitle) {
                        matchFound = true
                        confidence = "medium"
                        logger.info { "Title match found: '$originalTitle' matches '$title'" }
                        if (debug) {
                            logger.debug { "Title match found! Confidence set to medium" }
                        }
                        break
                    }
                }

                // If no match found, this could be a wrong manga
                // Replace the problematic user prompt code with this:
                if (!matchFound) {
                    val anilistTitle = anilistData.optJSONObject("title")?.optString("english") 
                        ?: anilistData.optJSONObject("title")?.optString("romaji") ?: ""
                    logger.warn { "Warning: AniList match might be incorrect. Title: $originalTitle vs AniList: $anilistTitle" }
                    if (debug) {
                        logger.debug { "No title match found, uncertain match" }
                    }
                    
                    // Use the default action from config
                    if (defaultUncertainAction.lowercase() == "skip") {
                        logger.warn { "Skipping uncertain AniList match based on configuration" }
                        if (debug) {
                            logger.debug { "Using default uncertain action: skip" }
                        }
                        anilistData = null
                    } else {
                        // Default is to use the match
                        logger.info { "Using uncertain AniList match based on configuration" }
                        if (debug) {
                            logger.debug { "Using default uncertain action: use" }
                        }
                        confidence = "low"
                    }
                }
            }
        }

        // If we found AniList data, enhance our metadata
        if (anilistData != null) {
            val anilistTitle =
                    anilistData.optJSONObject("title")?.optString("english")
                            ?: anilistData.optJSONObject("title")?.optString("romaji") ?: ""
            logger.info {
                "Found matching manga on AniList: $anilistTitle (confidence: $confidence)"
            }
            if (debug) {
                logger.debug { "Enhancing metadata with AniList data (confidence: $confidence)" }
            }

            // Store alternative titles from AniList
            val alternativeTitles = mutableListOf<String>()
            val titles = anilistData.optJSONObject("title")
            val mainTitle = enhancedData["title"] as String
            if (titles != null) {
                if (titles.has("english") && !titles.isNull("english")) {
                    val englishTitle = titles.optString("english")
                    if (englishTitle != mainTitle) {
                        alternativeTitles.add(englishTitle)
                    }
                }
                if (titles.has("romaji") && !titles.isNull("romaji")) {
                    val romajiTitle = titles.optString("romaji")
                    if (romajiTitle != mainTitle) {
                        alternativeTitles.add(romajiTitle)
                    }
                }
                if (titles.has("native") && !titles.isNull("native")) {
                    val nativeTitle = titles.optString("native")
                    if (nativeTitle != mainTitle) {
                        alternativeTitles.add(nativeTitle)
                    }
                }
            }

            // Add synonyms to alternative titles
            val synonyms = anilistData.optJSONArray("synonyms")
            if (synonyms != null) {
                for (i in 0 until synonyms.length()) {
                    val synonym = synonyms.optString(i)
                    if (synonym.isNotEmpty() &&
                                    synonym != mainTitle &&
                                    !alternativeTitles.contains(synonym)
                    ) {
                        alternativeTitles.add(synonym)
                    }
                }
            }

            enhancedData["alternative_titles"] = alternativeTitles

            if (debug) {
                logger.debug { "Added ${alternativeTitles.size} alternative titles" }
            }

            // Description
            if (anilistData.has("description") && !anilistData.isNull("description")) {
                // Clean up HTML tags from description
                var description = anilistData.optString("description")
                description = description.replace(Regex("<[^>]+>"), "") // Remove HTML tags
                enhancedData["description"] = description
                if (debug) {
                    logger.debug {
                        "Updated description from AniList (${description.length} chars)"
                    }
                }
            }

            // Staff (authors/artists)
            val staff = anilistData.optJSONObject("staff")?.optJSONArray("edges")
            if (staff != null) {
                val authors = mutableListOf<String>()
                val artists = mutableListOf<String>()

                if (debug && staff.length() > 0) {
                    logger.debug { "Processing ${staff.length()} staff members" }
                }

                for (i in 0 until staff.length()) {
                    val staffEdge = staff.optJSONObject(i)
                    val role = staffEdge?.optString("role", "")?.lowercase() ?: ""
                    val name =
                            staffEdge
                                    ?.optJSONObject("node")
                                    ?.optJSONObject("name")
                                    ?.optString("full")
                                    ?: ""

                    if (name.isNotEmpty()) {
                        if (role.contains("story") ||
                                        role.contains("author") ||
                                        role.contains("writer")
                        ) {
                            authors.add(name)
                            if (debug) {
                                logger.debug { "Found author: $name (role: $role)" }
                            }
                        } else if (role.contains("art") || role.contains("illustrat")) {
                            artists.add(name)
                            if (debug) {
                                logger.debug { "Found artist: $name (role: $role)" }
                            }
                        }
                    }
                }

                if (authors.isNotEmpty()) {
                    enhancedData["author"] = authors.joinToString(", ")
                    if (debug) {
                        logger.debug { "Set author: ${enhancedData["author"]}" }
                    }
                }

                if (artists.isNotEmpty()) {
                    enhancedData["artist"] = artists.joinToString(", ")
                    if (debug) {
                        logger.debug { "Set artist: ${enhancedData["artist"]}" }
                    }
                }
            }

            // Genres
            val genres = anilistData.optJSONArray("genres")
            if (genres != null && genres.length() > 0) {
                val genreList = mutableListOf<String>()
                for (i in 0 until genres.length()) {
                    genreList.add(genres.optString(i))
                }
                enhancedData["genre"] = genreList
                if (debug) {
                    logger.debug { "Set genres: ${enhancedData["genre"]}" }
                }
            }

            // Publication date
            val startDate = anilistData.optJSONObject("startDate")
            if (startDate != null) {
                if (startDate.has("year") && !startDate.isNull("year")) {
                    enhancedData["year"] = startDate.optInt("year")
                    if (debug) {
                        logger.debug { "Set year: ${enhancedData["year"]}" }
                    }
                }
                if (startDate.has("month") && !startDate.isNull("month")) {
                    enhancedData["month"] = startDate.optInt("month")
                    if (debug) {
                        logger.debug { "Set month: ${enhancedData["month"]}" }
                    }
                }
                if (startDate.has("day") && !startDate.isNull("day")) {
                    enhancedData["day"] = startDate.optInt("day")
                    if (debug) {
                        logger.debug { "Set day: ${enhancedData["day"]}" }
                    }
                }
            }

            // Status
            if (anilistData.has("status") && !anilistData.isNull("status")) {
                val statusMap =
                        mapOf(
                                "FINISHED" to "Completed",
                                "RELEASING" to "Ongoing",
                                "NOT_YET_RELEASED" to "Ongoing",
                                "CANCELLED" to "Cancelled",
                                "HIATUS" to "Hiatus"
                        )
                val status = anilistData.optString("status")
                enhancedData["status"] = statusMap.getOrDefault(status, status)
                if (debug) {
                    logger.debug { "Set status: ${enhancedData["status"]} (from $status)" }
                }
            }

            // Characters
            val characters = mutableListOf<String>()
            val charEdges = anilistData.optJSONObject("characters")?.optJSONArray("edges")
            if (charEdges != null) {
                if (debug && charEdges.length() > 0) {
                    logger.debug { "Processing ${charEdges.length()} characters" }
                }

                for (i in 0 until charEdges.length()) {
                    val charEdge = charEdges.optJSONObject(i)
                    val charName =
                            charEdge?.optJSONObject("node")
                                    ?.optJSONObject("name")
                                    ?.optString("full")
                                    ?: ""
                    if (charName.isNotEmpty()) {
                        characters.add(charName)
                        if (debug && characters.size <= 5) { // Log just the first few to avoid spam
                            logger.debug { "Added character: $charName" }
                        }
                    }
                }

                if (characters.isNotEmpty()) {
                    // Limit to top 10 characters
                    enhancedData["characters"] = characters.take(10)
                    if (debug) {
                        logger.debug {
                            "Set ${(enhancedData["characters"] as List<*>).size} characters (limited to 10)"
                        }
                    }
                }
            }

            // Country of origin
            if (anilistData.has("countryOfOrigin") && !anilistData.isNull("countryOfOrigin")) {
                val country = anilistData.optString("countryOfOrigin")
                if (country == "JP") {
                    enhancedData["isRightToLeft"] = true
                    if (debug) {
                        logger.debug { "Set isRightToLeft=true (country: JP)" }
                    }
                } else {
                    if (debug) {
                        logger.debug { "Country of origin: $country, keeping default orientation" }
                    }
                }
            }

            // Age rating (approximated from isAdult)
            if (anilistData.has("isAdult") &&
                            !anilistData.isNull("isAdult") &&
                            anilistData.optBoolean("isAdult")
            ) {
                enhancedData["ageRating"] = "Adults Only 18+"
                if (debug) {
                    logger.debug { "Set ageRating to 'Adults Only 18+' (isAdult=true)" }
                }
            }

            // Site URL
            if (anilistData.has("siteUrl") && !anilistData.isNull("siteUrl")) {
                enhancedData["siteUrl"] = anilistData.optString("siteUrl")
                if (debug) {
                    logger.debug { "Set siteUrl: ${enhancedData["siteUrl"]}" }
                }
            }

            // Store AniList ID for future reference
            if (anilistData.has("id") && !anilistData.isNull("id")) {
                enhancedData["anilist_id"] = anilistData.optInt("id")
                if (debug) {
                    logger.debug { "Set anilist_id: ${enhancedData["anilist_id"]}" }
                }
            }
        } else {
            if (debug) {
                logger.debug { "No AniList data found for enhancement" }
            }
        }

        if (debug) {
            logger.debug { "Metadata enhancement complete" }
        }

        return enhancedData
    }
}
