package suwayomi.tachidesk.manga.impl.anilist

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Client for interacting with AniList API to get enhanced manga metadata. */
class AniListClient(
        private val applicationDirs: ApplicationDirs = Injekt.get(),
        private val logger: KLogger = KotlinLogging.logger {}
) { // Constants
    companion object {
        const val ANILIST_API = "https://graphql.anilist.co"
        const val CACHE_EXPIRY = 30 * 24 * 60 * 60 // 30 days in seconds
    }

    private val client = OkHttpClient.Builder().build()
    private var token: String? = null
    private var tokenExpiry: Long = 0
    private var clientId: String? = null
    private var clientSecret: String? = null
    private var redirectUrl: String = "https://anilist.co/api/v2/oauth/pin"

    // Define file paths
    private val dataPath: String = applicationDirs.dataRoot
    private val tokenFile = File("$dataPath/anilist_token.json")
    private val configFile = File("$dataPath/anilist_config.json")
    private val cacheFile = File("$dataPath/anilist_cache.json")

    // Cache for manga data
    private var idCache = mutableMapOf<String, JSONObject>()
    private var titleCache = mutableMapOf<String, JSONObject>()
    private var lastUpdated: Long = 0

    init {
        // Create directories if they don't exist
        File(dataPath).mkdirs()

        // Load token, config, and cache
        loadToken()
        loadConfig()
        loadCache()
    }

    private fun loadToken() {
        try {
            if (tokenFile.exists()) {
                val tokenJson = JSONObject(tokenFile.readText())
                val expiry = tokenJson.optLong("expiry", 0)

                if (expiry > Instant.now().epochSecond) {
                    logger.info { "Found valid cached AniList token" }
                    token = tokenJson.optString("token")
                    tokenExpiry = expiry
                } else {
                    logger.warn { "AniList token expired, will need to re-authorize" }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not load AniList token" }
        }
    }

    private fun saveToken(token: String, expiry: Long) {
        try {
            val tokenJson = JSONObject().put("token", token).put("expiry", expiry)

            tokenFile.writeText(tokenJson.toString())
            logger.info { "AniList token saved for future use" }
        } catch (e: Exception) {
            logger.warn(e) { "Could not save AniList token" }
        }
    }

    private fun loadConfig() {
        try {
            if (configFile.exists()) {
                val config = JSONObject(configFile.readText())
                clientId = config.optString("client_id")
                clientSecret = config.optString("client_secret")
                redirectUrl = config.optString("redirect_url", redirectUrl)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not load AniList config" }
        }
    }

    private fun saveConfig() {
        try {
            val configJson =
                    JSONObject()
                            .put("client_id", clientId)
                            .put("client_secret", clientSecret)
                            .put("redirect_url", redirectUrl)

            configFile.writeText(configJson.toString())
            logger.info { "AniList configuration saved" }
        } catch (e: Exception) {
            logger.warn(e) { "Could not save AniList config" }
        }
    }

    private fun loadCache() {
        try {
            if (cacheFile.exists()) {
                val cacheJson = JSONObject(cacheFile.readText())
                lastUpdated = cacheJson.optLong("last_updated", 0)
                val cacheAge = Instant.now().epochSecond - lastUpdated

                if (cacheAge < CACHE_EXPIRY) {
                    val idCacheJson = cacheJson.optJSONObject("id") ?: JSONObject()
                    val titleCacheJson = cacheJson.optJSONObject("title") ?: JSONObject()

                    // Convert to maps
                    idCache =
                            idCacheJson
                                    .keys()
                                    .asSequence()
                                    .associateWith { idCacheJson.getJSONObject(it) }
                                    .toMutableMap()

                    titleCache =
                            titleCacheJson
                                    .keys()
                                    .asSequence()
                                    .associateWith { titleCacheJson.getJSONObject(it) }
                                    .toMutableMap()

                    logger.info { "Using AniList cache (${idCache.size} manga entries)" }
                } else {
                    logger.warn { "AniList cache expired, will refresh data as needed" }
                    initializeCache()
                }
            } else {
                initializeCache()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not load AniList cache" }
            initializeCache()
        }
    }

    private fun initializeCache() {
        idCache = mutableMapOf()
        titleCache = mutableMapOf()
        lastUpdated = Instant.now().epochSecond
    }

    private fun saveCache() {
        try {
            // Update timestamp
            lastUpdated = Instant.now().epochSecond

            // Create cache JSON
            val idCacheJson = JSONObject()
            idCache.forEach { (key, value) -> idCacheJson.put(key, value) }

            val titleCacheJson = JSONObject()
            titleCache.forEach { (key, value) -> titleCacheJson.put(key, value) }

            val cacheJson =
                    JSONObject()
                            .put("id", idCacheJson)
                            .put("title", titleCacheJson)
                            .put("last_updated", lastUpdated)

            cacheFile.writeText(cacheJson.toString(2))

            // Only show message when cache is substantive
            if (idCache.isNotEmpty()) {
                logger.info { "AniList cache updated (${idCache.size} manga entries)" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not save AniList cache" }
        }
    }

    fun clearCache(): Boolean {
        return try {
            initializeCache()
            saveCache()
            logger.info { "AniList cache cleared successfully" }
            true
        } catch (e: Exception) {
            logger.warn(e) { "Error clearing AniList cache" }
            false
        }
    }

    fun setupAuth(): Boolean {
        // Check if we already have a valid token
        if (token != null && tokenExpiry > Instant.now().epochSecond) {
            logger.info { "Already authenticated with AniList" }
            return true
        }

        // Request client credentials if needed
        // Replace the credential prompting code with this:
        if (clientId.isNullOrEmpty() || clientSecret.isNullOrEmpty()) {
            logger.warn { "AniList OAuth credentials needed for authentication but not configured" }
            logger.info {
                "Please configure AniList credentials in the config file or through the settings interface"
            }
            return false
        }

        // Start OAuth flow
        logger.info { "Login to AniList in your browser and authorize the application" }
        val authUrl =
                "https://anilist.co/api/v2/oauth/authorize?client_id=$clientId&redirect_uri=$redirectUrl&response_type=code"

        try {
            Desktop.getDesktop().browse(URI(authUrl))
        } catch (e: Exception) {
            logger.info { "Please open this URL in your browser: $authUrl" }
        }

        print("Paste the authorization code from the browser: ")
        val code = readLine()

        if (code.isNullOrEmpty()) {
            logger.error { "Authorization code is required" }
            return false
        }

        // Exchange code for token
        logger.info { "Requesting access token from AniList..." }
        try {
            val requestJson =
                    JSONObject()
                            .put("grant_type", "authorization_code")
                            .put("client_id", clientId)
                            .put("client_secret", clientSecret)
                            .put("redirect_uri", redirectUrl)
                            .put("code", code)

            val request =
                    Request.Builder()
                            .url("https://anilist.co/api/v2/oauth/token")
                            .post(
                                    requestJson
                                            .toString()
                                            .toRequestBody("application/json".toMediaType())
                            )
                            .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseJson = JSONObject(response.body.string())
                    val accessToken = responseJson.optString("access_token")
                    val expiresIn = responseJson.optLong("expires_in", 3600L)

                    if (accessToken.isNotEmpty()) {
                        token = accessToken
                        // Calculate expiry timestamp
                        tokenExpiry = Instant.now().epochSecond + expiresIn
                        saveToken(accessToken, tokenExpiry)
                        logger.info { "Successfully authenticated with AniList!" }
                        return true
                    }
                }

                logger.error { "Failed to get access token: ${response.code} ${response.message}" }
                return false
            }
        } catch (e: Exception) {
            logger.error(e) { "Error requesting access token" }
            return false
        }
    }

    fun normalizeTitle(title: String?): String {
        if (title.isNullOrEmpty()) return ""
        return title.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    suspend fun searchMangaByTitle(title: String?): JSONObject? =
            withContext(Dispatchers.IO) {
                // Skip empty titles
                if (title.isNullOrEmpty()) {
                    return@withContext null
                }

                // Create normalized version for cache lookup
                val normalizedTitle = normalizeTitle(title)

                // Check cache first
                if (titleCache.containsKey(normalizedTitle)) {
                    logger.info { "Using cached AniList data for: $title" }
                    return@withContext titleCache[normalizedTitle]
                }

                // Not in cache, query AniList API
                val query =
                        """
            query (${'$'}search: String) {
              Media(type: MANGA, search: ${'$'}search) {
                id
                idMal
                title {
                  romaji
                  english
                  native
                }
                description
                format
                status
                chapters
                volumes
                genres
                tags {
                  name
                  category
                }
                synonyms
                startDate {
                  year
                  month
                  day
                }
                staff {
                  edges {
                    role
                    node {
                      name {
                        full
                      }
                    }
                  }
                }
                characters {
                  edges {
                    role
                    node {
                      name {
                        full
                      }
                    }
                  }
                }
                countryOfOrigin
                source
                averageScore
                isAdult
                siteUrl
              }
            }
        """.trimIndent()

                val variables = JSONObject().put("search", title)
                val requestJson = JSONObject().put("query", query).put("variables", variables)

                val requestBuilder =
                        Request.Builder()
                                .url(ANILIST_API)
                                .post(
                                        requestJson
                                                .toString()
                                                .toRequestBody("application/json".toMediaType())
                                )

                // Add auth if available
                if (!token.isNullOrEmpty()) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }

                try {
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseJson = JSONObject(response.body.string())

                            if (responseJson.has("data") &&
                                            responseJson.getJSONObject("data").has("Media")
                            ) {
                                // Found a result, cache it
                                val result =
                                        responseJson.getJSONObject("data").getJSONObject("Media")

                                // Cache by ID
                                val id = result.optString("id")
                                if (id.isNotEmpty()) {
                                    idCache[id] = result
                                }

                                // Cache by normalized title
                                titleCache[normalizedTitle] = result

                                // Cache by alternative titles too
                                val titles = result.optJSONObject("title")
                                if (titles != null) {
                                    for (titleType in listOf("english", "romaji", "native")) {
                                        if (titles.has(titleType) && !titles.isNull(titleType)) {
                                            val altTitle = titles.optString(titleType, "")
                                            val altNorm = normalizeTitle(altTitle)
                                            if (altNorm.isNotEmpty() && altNorm != normalizedTitle
                                            ) {
                                                titleCache[altNorm] = result
                                            }
                                        }
                                    }
                                }

                                // Cache by synonyms
                                val synonyms = result.optJSONArray("synonyms")
                                if (synonyms != null) {
                                    for (i in 0 until synonyms.length()) {
                                        val synonym = synonyms.optString(i, "")
                                        if (synonym.isNotEmpty()) {
                                            val synNorm = normalizeTitle(synonym)
                                            if (synNorm.isNotEmpty() && synNorm != normalizedTitle
                                            ) {
                                                titleCache[synNorm] = result
                                            }
                                        }
                                    }
                                }

                                // Save updated cache to disk
                                saveCache()

                                return@withContext result
                            }
                        }

                        logger.warn { "AniList search failed: ${response.code}" }
                        return@withContext null
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Error searching AniList" }
                    return@withContext null
                }
            }

    suspend fun searchMangaById(mangaId: Int?): JSONObject? =
            withContext(Dispatchers.IO) {
                // Skip invalid IDs
                if (mangaId == null || mangaId <= 0) {
                    return@withContext null
                }

                // Convert to string for cache lookup
                val idStr = mangaId.toString()

                // Check cache first
                if (idCache.containsKey(idStr)) {
                    logger.info { "Using cached AniList data for ID: $mangaId" }
                    return@withContext idCache[idStr]
                }

                // Not in cache, query AniList API
                val query =
                        """
            query (${'$'}id: Int) {
              Media(type: MANGA, id: ${'$'}id) {
                id
                idMal
                title {
                  romaji
                  english
                  native
                }
                description
                format
                status
                chapters
                volumes
                genres
                tags {
                  name
                  category
                }
                synonyms
                startDate {
                  year
                  month
                  day
                }
                staff {
                  edges {
                    role
                    node {
                      name {
                        full
                      }
                    }
                  }
                }
                characters {
                  edges {
                    role
                    node {
                      name {
                        full
                      }
                    }
                  }
                }
                countryOfOrigin
                source
                averageScore
                isAdult
                siteUrl
              }
            }
        """.trimIndent()

                val variables = JSONObject().put("id", mangaId)
                val requestJson = JSONObject().put("query", query).put("variables", variables)

                val requestBuilder =
                        Request.Builder()
                                .url(ANILIST_API)
                                .post(
                                        requestJson
                                                .toString()
                                                .toRequestBody("application/json".toMediaType())
                                )

                // Add auth if available
                if (!token.isNullOrEmpty()) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }

                try {
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseJson = JSONObject(response.body.string())

                            if (responseJson.has("data") &&
                                            responseJson.getJSONObject("data").has("Media")
                            ) {
                                // Found a result, cache it
                                val result =
                                        responseJson.getJSONObject("data").getJSONObject("Media")

                                // Cache by ID
                                idCache[idStr] = result

                                // Cache by normalized titles too
                                val titles = result.optJSONObject("title")
                                if (titles != null) {
                                    for (titleType in listOf("english", "romaji", "native")) {
                                        if (titles.has(titleType) && !titles.isNull(titleType)) {
                                            val titleText = titles.optString(titleType, "")
                                            val normTitle = normalizeTitle(titleText)
                                            if (normTitle.isNotEmpty()) {
                                                titleCache[normTitle] = result
                                            }
                                        }
                                    }
                                }

                                // Cache by synonyms
                                val synonyms = result.optJSONArray("synonyms")
                                if (synonyms != null) {
                                    for (i in 0 until synonyms.length()) {
                                        val synonym = synonyms.optString(i, "")
                                        if (synonym.isNotEmpty()) {
                                            val normSyn = normalizeTitle(synonym)
                                            if (normSyn.isNotEmpty()) {
                                                titleCache[normSyn] = result
                                            }
                                        }
                                    }
                                }

                                // Save updated cache to disk
                                saveCache()

                                return@withContext result
                            }
                        }

                        logger.warn { "AniList ID search failed: ${response.code}" }
                        return@withContext null
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Error searching AniList by ID" }
                    return@withContext null
                }
            }

    fun extractMangaIdFromUrl(url: String?): Int? {
        if (url.isNullOrEmpty()) {
            return null
        }

        // Match pattern like https://anilist.co/manga/12345
        val pattern = Pattern.compile("anilist\\.co/manga/(\\d+)")
        val matcher = pattern.matcher(url)

        if (matcher.find()) {
            try {
                return matcher.group(1).toInt()
            } catch (e: NumberFormatException) {
                // Ignore and return null
            }
        }

        return null
    }

    fun getCacheStats(): Map<String, Any> {
        return mapOf(
                "total_entries" to idCache.size,
                "total_title_lookups" to titleCache.size,
                "last_updated" to lastUpdated
        )
    }
}
