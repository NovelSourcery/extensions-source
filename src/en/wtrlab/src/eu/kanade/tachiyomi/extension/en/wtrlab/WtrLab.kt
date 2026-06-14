package eu.kanade.tachiyomi.novelextension.en.wtrlab

import android.app.Application
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.chapterutils.mergeChapters
import keiyoushi.lib.chapterutils.shouldReturnExisting
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WtrLab :
    HttpSource(),
    NovelSource,
    ConfigurableSource,
    SourceTracker {

    override val name = "WTR-LAB"
    override val baseUrl = "https://wtr-lab.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()
    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private val apiHeaders by lazy {
        headersBuilder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .build()
    }

    private val imgBaseUrl = "https://wtr-lab.com/api/v2/img"

    // Example: "https://img.wtr-lab.com/cdn/series/JtiyvIDCk8L3qSgbIIAO2BPQ-xP-szSp7LbLKfz7yJ8.jpg"
    // becomes: "https://wtr-lab.com/api/v2/img?src=s3://wtrimg/series/JtiyvIDCk8L3qSgbIIAO2BPQ-xP-szSp7LbLKfz7yJ8.jpg&w=344"
    private fun transformImageUrl(imageUrl: String?): String? {
        if (imageUrl.isNullOrBlank()) return null

        if (imageUrl.startsWith("$imgBaseUrl")) return imageUrl

        val path = when {
            imageUrl.contains("img.wtr-lab.com/cdn/") -> {
                imageUrl.substringAfter("img.wtr-lab.com/cdn/")
            }

            imageUrl.contains("wtrimg/") -> {
                imageUrl.substringAfter("wtrimg/")
            }

            imageUrl.startsWith("/") -> {
                // Relative path
                imageUrl.removePrefix("/")
            }

            else -> return imageUrl // Unknown format, return as-is
        }

        val s3Path = "s3://wtrimg/$path"
        val encodedPath = java.net.URLEncoder.encode(s3Path, "UTF-8")
        return "$imgBaseUrl?src=$encodedPath&w=344"
    }

    private var cachedBuildId: String? = null

    private fun getBuildId(): String {
        cachedBuildId?.let { return it }

        val response = client.newCall(GET("$baseUrl/en/novel-finder", headers)).execute()
        val html = response.body.string()
        val doc = Jsoup.parse(html)
        val nextData = doc.selectFirst("#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not find __NEXT_DATA__ on page")

        val jsonData = json.parseToJsonElement(nextData).jsonObject
        val buildId = jsonData["buildId"]?.jsonPrimitive?.content
            ?: throw Exception("Could not extract buildId")

        cachedBuildId = buildId
        return buildId
    }

    private fun decryptContent(encryptedData: String): String {
        val parts = encryptedData.split(":")
        if (parts.size != 4 || parts[0] != "arr") {
            throw Exception("Invalid encrypted data format")
        }

        val iv = Base64.getDecoder().decode(parts[1])
        val ciphertext = Base64.getDecoder().decode(parts[2])
        val tag = Base64.getDecoder().decode(parts[3])

        val ciphertextWithTag = tag + ciphertext

        val encryptionKey = getDecryptionKey()
        if (encryptionKey.isEmpty()) {
            throw Exception("Decryption key not configured. Please set it in extension settings.")
        }
        val keyBytes = encryptionKey.toByteArray().copyOf(32)
        val key = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv) // 128-bit auth tag length
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        val decryptedBytes = cipher.doFinal(ciphertextWithTag)
        val rawArray = String(decryptedBytes, Charsets.UTF_8)
        Log.d("WtrLab", "Decrypted content length: ${rawArray.length}")
        Log.d("WtrLab", "Decrypted content: ${rawArray.take(1000)}")
        return rawArray
    }

    private fun translateWithGoogle(paragraphs: List<String>): String {
        val apiKey = getGoogleApiKey()
        if (apiKey.isEmpty()) {
            return paragraphs.joinToString("") { "<p>$it</p>" }
        }

        val formattedParagraphs = paragraphs.mapIndexed { index, text ->
            "<a i=$index>$text</a>"
        }

        val innerArray = buildJsonArray {
            add(
                buildJsonArray {
                    formattedParagraphs.forEach { add(it) }
                },
            )
            add("zh-CN")
            add("en")
        }
        val fullBody = buildJsonArray {
            add(innerArray)
            add("te_lib")
        }
        val bodyString = json.encodeToString(JsonArray.serializer(), fullBody)
        Log.d("WtrLab", "req body content: ${bodyString.take(1000)}")

        val request = Request.Builder()
            .url("https://translate-pa.googleapis.com/v1/translateHtml")
            .header("X-Goog-Api-Key", apiKey)
            .header("Origin", "https://wtr-lab.com")
            .header("Referer", "https://wtr-lab.com/")
            .post(bodyString.toRequestBody("application/json+protobuf".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return paragraphs.joinToString("") { "<p>$it</p>" }
        }

        val responseBody = response.body.string()
        Log.d("WtrLab", "Google Translate Response: $responseBody")

        return try {
            val jsonResponse = json.parseToJsonElement(responseBody).jsonArray

            if (jsonResponse.isEmpty()) {
                throw Exception("Empty response from Google Translate")
            }

            val innerArray = jsonResponse[0]
            if (innerArray !is JsonArray || innerArray.isEmpty()) {
                throw Exception("Invalid response structure: expected array inside array")
            }

            // Each element is a string like "<a i=N>translated text</a>"
            val translatedParagraphs = innerArray.mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.trim()
            }.filter { it.isNotEmpty() }

            translatedParagraphs.joinToString("") { "<p>$it</p>" }
        } catch (e: Exception) {
            Log.e("WtrLab", "Failed to parse Google Translate response: ${e.message}", e)
            paragraphs.joinToString("") { "<p>$it</p>" }
        }
    }

    override suspend fun fetchPageText(page: Page): String {
        val urlPath = page.url.removePrefix(baseUrl).removePrefix("/")
        val match = Regex("""(?:serie-|novel/)(\d+)/[^/]+/chapter-(\d+)""").find(urlPath)
            ?: throw Exception("Invalid chapter URL format: ${page.url}")

        val rawId = match.groupValues[1].toInt()
        val chapterNo = match.groupValues[2].toInt()

        var translationMode = getTranslationMode()

        // - "ai" mode: server-side AI translation (TS plugin uses this)
        // - "web" mode: encrypted content intended for web-translation flow
        // - "raw" mode: encrypted raw content
        var apiTranslateParam = when (translationMode) {
            "ai" -> "ai"
            "web" -> "web"
            "raw" -> "raw"
            else -> "ai"
        }

        fun createRequest(mode: String): okhttp3.Request {
            val body = buildJsonObject {
                put("translate", mode)
                put("language", "en")
                put("raw_id", rawId)
                put("chapter_no", chapterNo)
                put("retry", false)
                put("force_retry", false)
            }.toString().toRequestBody("application/json".toMediaType())
            return POST("$baseUrl/api/reader/get", apiHeaders, body)
        }

        var response = client.newCall(createRequest(apiTranslateParam)).execute()
        var responseBody = response.body.string()

        if (apiTranslateParam == "ai") {
            val isFailure = !response.isSuccessful || try {
                val j = json.parseToJsonElement(responseBody).jsonObject
                j["success"]?.jsonPrimitive?.content != "true" && j["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() != true
            } catch (e: Exception) {
                true
            }

            if (isFailure) {
                translationMode = "raw"
                apiTranslateParam = "raw"
                response = client.newCall(createRequest(apiTranslateParam)).execute()
                responseBody = response.body.string()
            }
        }

        if (!response.isSuccessful) {
            throw Exception("API request failed: ${response.code} ${response.message}")
        }

        val jsonResult = json.parseToJsonElement(responseBody).jsonObject

        if (jsonResult["success"]?.jsonPrimitive?.content != "true" && jsonResult["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() != true) {
            val error = jsonResult["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            throw Exception("API returned error: $error")
        }

        val dataObj = jsonResult["data"]?.jsonObject
            ?.get("data")?.jsonObject
            ?: throw Exception("Could not find chapter data in API response")

        val body = dataObj["body"]
            ?: throw Exception("Could not find chapter content in API response")

        // Extract image URLs for [image] tag replacement
        val imageUrls = dataObj["images"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

        val paragraphs = if (body is JsonArray) {
            body.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        } else {
            val encryptedData = body.jsonPrimitive.content
            val decryptedText = decryptContent(encryptedData)

            try {
                val decryptedJson = json.parseToJsonElement(decryptedText).jsonArray
                decryptedJson.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                decryptedText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        // Replace [image] tags with actual <img> elements
        var imageIndex = 0
        val processedParagraphs = paragraphs.map { paragraph ->
            if (paragraph == "[image]" && imageIndex < imageUrls.size) {
                val url = imageUrls[imageIndex++]
                "<img src=\"$url\" />"
            } else {
                "<p>$paragraph</p>"
            }
        }

        val htmlContent = when (translationMode) {
            "web" -> {
                // Translate text paragraphs, then re-insert images at original positions
                val textParagraphs = paragraphs.filter { it != "[image]" }
                val translatedHtml = translateWithGoogle(textParagraphs)
                if (imageUrls.isEmpty()) {
                    translatedHtml
                } else {
                    // Parse translated paragraphs back and interleave with images
                    val translatedParts = Jsoup.parse(translatedHtml).select("p, a").map { it.outerHtml() }
                    var textIdx = 0
                    var imgIdx = 0
                    paragraphs.joinToString("") { paragraph ->
                        if (paragraph == "[image]" && imgIdx < imageUrls.size) {
                            "<img src=\"${imageUrls[imgIdx++]}\" />"
                        } else if (textIdx < translatedParts.size) {
                            translatedParts[textIdx++]
                        } else {
                            "<p>$paragraph</p>"
                        }
                    }
                }
            }

            else -> processedParagraphs.joinToString("")
        }

        if (translationMode == "ai") {
            val glossaryTerms = jsonResult["data"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("glossary_data")?.jsonObject
                ?.get("terms")?.jsonArray

            if (glossaryTerms != null) {
                return replaceGlossarySymbols(htmlContent, glossaryTerms)
            }
        }

        return htmlContent
    }

    private fun replaceGlossarySymbols(content: String, terms: JsonArray): String {
        var result = content

        terms.forEachIndexed { index, term ->
            try {
                // Safely extract the term array
                val termArray = when (term) {
                    is JsonElement -> {
                        try {
                            term.jsonArray
                        } catch (e: Exception) {
                            Log.w("WtrLab", "Term at index $index is not a JsonArray")
                            return@forEachIndexed
                        }
                    }
                    else -> return@forEachIndexed
                }

                // Extract English translation (first element)
                val englishTranslations = termArray.getOrNull(0)
                val english = when (englishTranslations) {
                    is JsonArray -> englishTranslations.firstOrNull()?.jsonPrimitive?.contentOrNull?.trim()
                    is JsonElement -> englishTranslations.jsonPrimitive.contentOrNull?.trim()
                    else -> null
                }

                if (english.isNullOrBlank()) {
                    return@forEachIndexed
                }

                // Create pattern to match symbols with BOTH endings: ⛬ and 〓
                // The regex pattern captures both characters in a character class
                val symbolPatterns = listOf(
                    // Direct Unicode variants (⛬ = U+26EC, 〓 = U+3013)
                    "※$index[⛬〓]", // No whitespace
                    "※\\s*$index\\s*[⛬〓]", // With optional whitespace

                    // HTML entity encoding for ⛬ (&#x26ec; or &#x26EC;)
                    "&#x203b;$index&#x26ec;",
                    "&#x203b;\\s*$index\\s*&#x26ec;",
                    "&#x203b;$index&#x26EC;",
                    "&#x203b;\\s*$index\\s*&#x26EC;",

                    // HTML entity encoding for 〓 (&#x3013; or &#x3013;)
                    "&#x203b;$index&#x3013;",
                    "&#x203b;\\s*$index\\s*&#x3013;",
                )

                // Replace all pattern variations
                for (pattern in symbolPatterns) {
                    result = result.replace(
                        Regex(pattern, RegexOption.IGNORE_CASE),
                        Regex.escapeReplacement(english),
                    )
                }
            } catch (e: Exception) {
                Log.w("WtrLab", "Error processing glossary term at index $index: ${e.message}")
            }
        }

        return result
    }

    override fun popularMangaRequest(page: Int): Request {
        val buildId = getBuildId()
        val url = "$baseUrl/_next/data/$buildId/en/novel-finder.json?orderBy=views&order=desc&page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonResult = json.parseToJsonElement(response.body.string()).jsonObject
        val pageProps = jsonResult["pageProps"]?.jsonObject
        val series = pageProps?.get("series")?.jsonArray
            ?: return MangasPage(emptyList(), false)

        val count = pageProps["count"]?.jsonPrimitive?.intOrNull ?: 0
        Log.d("WtrLab", "Popular/Search: series.size=${series.size}, count=$count")

        // novel-finder responses carry the full live tag/genre taxonomy — cache it for free
        cacheTaxonomy(pageProps)
        cacheUserFolders()

        val seenIds = mutableSetOf<Int>()
        val mangas = series.mapNotNull { element ->
            val obj = element.jsonObject
            val rawId = obj["raw_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null

            if (rawId in seenIds) return@mapNotNull null
            seenIds.add(rawId)

            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val data = obj["data"]?.jsonObject

            SManga.create().apply {
                title = data?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
                thumbnail_url = transformImageUrl(data?.get("image")?.jsonPrimitive?.contentOrNull)
                url = "/en/novel/$rawId/$slug"
            }
        }

        val hasNextPage = series.isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val requestBody = buildJsonObject {
            put("page", page)
        }.toString().toRequestBody("application/json".toMediaType())

        return POST("$baseUrl/api/home/recent", apiHeaders, requestBody)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val jsonResult = json.parseToJsonElement(response.body.string()).jsonObject
        val data = jsonResult["data"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val mangas = data.mapNotNull { element ->
            val datum = element.jsonObject
            val serie = datum["serie"]?.jsonObject ?: return@mapNotNull null
            val rawId = serie["raw_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val slug = serie["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val serieData = serie["data"]?.jsonObject

            SManga.create().apply {
                title = serieData?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
                thumbnail_url = transformImageUrl(serieData?.get("image")?.jsonPrimitive?.contentOrNull)
                url = "/en/novel/$rawId/$slug"
            }
        }

        val hasNextPage = mangas.isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val buildId = getBuildId()

        val params = mutableListOf<String>()

        if (query.isNotEmpty()) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            params.add("text=$encodedQuery")
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> params.add("orderBy=${filter.selected}")

                is OrderFilter -> params.add("order=${filter.selected}")

                is StatusFilter -> {
                    val value = filter.selected
                    if (value != "all") params.add("status=$value")
                }

                is ReleaseStatusFilter -> {
                    val value = filter.selected
                    if (value != "all") params.add("release_status=$value")
                }

                is AdditionAgeFilter -> {
                    val value = filter.selected
                    if (value != "all") params.add("addition_age=$value")
                }

                is MinChaptersFilter -> {
                    val value = filter.selected
                    if (value.isNotEmpty()) params.add("minc=$value")
                }

                is MinRatingFilter -> {
                    val value = filter.selected
                    if (value.isNotEmpty()) params.add("minr=$value")
                }

                is MinReviewCountFilter -> {
                    val value = filter.selected
                    if (value.isNotEmpty()) params.add("minrc=$value")
                }

                is GenreOperatorFilter -> params.add("gc=${filter.selected}")

                is GenreFilter -> {
                    val included = filter.state.filter { it.isIncluded() }.map { it.value }
                    val excluded = filter.state.filter { it.isExcluded() }.map { it.value }
                    if (included.isNotEmpty()) params.add("gi=${included.joinToString(",")}")
                    if (excluded.isNotEmpty()) params.add("ge=${excluded.joinToString(",")}")
                }

                is TagOperatorFilter -> params.add("tc=${filter.selected}")

                is TagFilter -> {
                    val included = filter.state.filter { it.isIncluded() }.map { it.value }
                    val excluded = filter.state.filter { it.isExcluded() }.map { it.value }
                    if (included.isNotEmpty()) params.add("ti=${included.joinToString(",")}")
                    if (excluded.isNotEmpty()) params.add("te=${excluded.joinToString(",")}")
                }

                is FoldersFilter -> {
                    val value = filter.selected
                    if (value.isNotEmpty()) params.add("folders=$value")
                }

                is LibraryExcludeFilter -> {
                    val value = filter.selected
                    if (value.isNotEmpty()) params.add("le=$value")
                }

                else -> {}
            }
        }

        params.add("locale=en")
        params.add("page=$page")

        val url = "$baseUrl/_next/data/$buildId/en/novel-finder.json?${params.joinToString("&")}"
        return GET(url, headers)
    } override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val buildId = getBuildId()
        // url shape: /en/novel/<raw_id>/<slug> — getServerSideProps needs these as query params
        val match = Regex("""novel/(\d+)/([^/?#]+)""").find(manga.url)
        val rawId = match?.groupValues?.get(1) ?: ""
        val slug = match?.groupValues?.get(2) ?: ""
        val url = "$baseUrl/_next/data/$buildId${manga.url}.json" +
            "?locale=en&raw_id=$rawId&serie_slug=$slug"
        return GET(url, headers)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = SManga.create()
        val root = json.parseToJsonElement(response.body.string()).jsonObject

        val pageProps = root["pageProps"]?.jsonObject
            ?: root["props"]?.jsonObject?.get("pageProps")?.jsonObject
            ?: return manga

        val serie = pageProps["serie"]?.jsonObject
        val serieData = serie?.get("serie_data")?.jsonObject

        val jsonGenres = mutableListOf<String>()
        val jsonTags = mutableListOf<String>()
        var altTitles = listOf<String>()

        if (serieData != null) {
            val sid = serieData["id"]?.jsonPrimitive?.intOrNull
            val rid = serieData["raw_id"]?.jsonPrimitive?.intOrNull
            if (sid != null && rid != null) cacheSerieId(rid, sid)

            val data = serieData["data"]?.jsonObject
            manga.title = data?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
            manga.thumbnail_url = transformImageUrl(data?.get("image")?.jsonPrimitive?.contentOrNull)
            manga.author = data?.get("author")?.jsonPrimitive?.contentOrNull

            val description = (
                data?.get("description")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: data?.get("raw")?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
                )?.replace("\\n", "\n")?.trim() ?: ""

            val rating = serieData["rating"]?.jsonPrimitive?.contentOrNull ?: "N/A"
            val charCount = serieData["char_count"]?.jsonPrimitive?.intOrNull ?: 0
            val readers = serieData["in_library"]?.jsonPrimitive?.intOrNull ?: 0
            val reviewCount = serieData["total_rate"]?.jsonPrimitive?.intOrNull ?: 0

            val metadata = buildString {
                append("Rating: $rating\n")
                append("Readers: $readers\n")
                append("Reviews: $reviewCount")
                if (charCount > 0) {
                    append("\n")
                    append("Characters: $charCount")
                }
            }
            manga.description = if (description.isNotEmpty()) "$metadata\n\n$description" else metadata

            manga.status = when (serieData["status"]?.jsonPrimitive?.intOrNull) {
                0 -> SManga.ONGOING
                1 -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            serieData["genres"]?.jsonArray?.forEach { el ->
                el.jsonPrimitive.intOrNull?.let { id ->
                    genreLabel(id)?.let { jsonGenres.add(it) }
                }
            }
            serieData["tags"]?.jsonArray?.forEach { el ->
                el.jsonPrimitive.intOrNull?.let { id ->
                    tagLabel(id)?.let { jsonTags.add(it) }
                }
            }
        }

        pageProps["tags"]?.jsonArray?.forEach { el ->
            el.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { jsonTags.add(it) }
        }

        serie?.get("names")?.jsonArray?.let { names ->
            altTitles = names.flatMap { nameElem ->
                val obj = nameElem.jsonObject
                listOfNotNull(
                    obj["raw_title"]?.jsonPrimitive?.contentOrNull,
                    obj["title"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }

        val combined = (jsonGenres + jsonTags)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (combined.isNotEmpty()) {
            manga.genre = combined.joinToString(", ")
        }

        val finalAltTitles = altTitles
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != manga.title }
            .distinct()
        if (finalAltTitles.isNotEmpty()) {
            manga.setAltTitles(finalAltTitles)
        }

        return manga
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override suspend fun getChapterList(manga: SManga, context: RefreshContext): List<SChapter> {
        val response = client.newCall(chapterListRequest(manga)).execute()
        val html = response.body.string()
        val doc = Jsoup.parse(html)
        val url = response.request.url.toString()

        val urlMatch = Regex("""(?:serie-|novel/)(\d+)/([^/]+)""").find(url)
            ?: return emptyList()

        val rawId = urlMatch.groupValues[1].toInt()
        val slug = urlMatch.groupValues[2]
        val chapterCount = extractChapterCount(doc)

        if (chapterCount == 0) return emptyList()

        Log.d(TAG, "getChapterList: rawId=$rawId existing=${context.existingChapters.size} siteTotal=$chapterCount")

        if (!context.forceRefresh && shouldReturnExisting(context.existingChapters.size, chapterCount)) {
            Log.d(TAG, "getChapterList: count unchanged — returning existing")
            return context.existingChapters
        }

        val existingCount = context.existingChapters.size
        // On a normal refresh, align the start to the 250-chapter batch boundary the site uses, so the
        // current (possibly partial) batch is re-fetched and we don't request odd offsets. A force
        // refresh re-fetches everything from the start.
        val (startOrder, keepCount) = if (context.forceRefresh || existingCount == 0) {
            1 to 0
        } else {
            val alignedStart = (existingCount / CHAPTER_BATCH) * CHAPTER_BATCH + 1
            alignedStart to (alignedStart - 1)
        }

        Log.d(TAG, "getChapterList: force=${context.forceRefresh} startOrder=$startOrder keepCount=$keepCount")

        val fresh = fetchAllChapters(rawId, chapterCount, slug, startOrder)
        return mergeChapters(context.existingChapters, fresh, keepCount).reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val doc = Jsoup.parse(html)
        val url = response.request.url.toString()

        val urlMatch = Regex("""(?:serie-|novel/)(\d+)/([^/]+)""").find(url)
            ?: return emptyList()

        val rawId = urlMatch.groupValues[1].toInt()
        val slug = urlMatch.groupValues[2]
        val chapterCount = extractChapterCount(doc)

        if (chapterCount == 0) return emptyList()

        return fetchAllChapters(rawId, chapterCount, slug).reversed()
    }

    private fun extractChapterCount(doc: Document): Int {
        // Prefer the structured JSON data — it's precise and unambiguous
        val nextDataText = doc.selectFirst("#__NEXT_DATA__")?.data()
        if (nextDataText != null) {
            try {
                val jsonData = json.parseToJsonElement(nextDataText).jsonObject
                val count = jsonData["props"]?.jsonObject
                    ?.get("pageProps")?.jsonObject
                    ?.get("serie")?.jsonObject
                    ?.get("serie_data")?.jsonObject
                    ?.get("chapter_count")?.jsonPrimitive?.intOrNull ?: 0
                if (count > 0) return count
            } catch (e: Exception) {
            }
        }

        // Fallback: scan page text for "N Chapters" (less reliable but covers older layouts)
        return Regex("""(\d+)\s+Chapters?""", RegexOption.IGNORE_CASE)
            .find(doc.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun fetchAllChapters(rawId: Int, totalChapters: Int, slug: String, startOrder: Int = 1): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        val batchSize = CHAPTER_BATCH

        var start = startOrder
        while (start <= totalChapters) {
            val end = minOf(start + batchSize - 1, totalChapters)

            try {
                val response = client.newCall(
                    GET("$baseUrl/api/chapters/$rawId?start=$start&end=$end", headers),
                ).execute()

                val jsonResult = json.parseToJsonElement(response.body.string()).jsonObject
                val chapters = jsonResult["chapters"]?.jsonArray ?: break

                chapters.forEach { element ->
                    val obj = element.jsonObject
                    val order = obj["order"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter $order"
                    val updatedAt = obj["updated_at"]?.jsonPrimitive?.contentOrNull

                    allChapters.add(
                        SChapter.create().apply {
                            name = title
                            url = "/en/novel/$rawId/$slug/chapter-$order"
                            chapter_number = order.toFloat()
                            date_upload = parseDate(updatedAt)
                        },
                    )
                }

                if (chapters.size < batchSize) break
                start += batchSize
            } catch (e: Exception) {
                Log.w(TAG, "fetchAllChapters: batch start=$start failed — ${e.message}")
                break
            }
        }

        return allChapters.sortedBy { it.chapter_number }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            format.parse(dateStr.substring(0, 10))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        // fetchPageText will parse the URL to extract rawId and chapterNo
        return listOf(Page(0, response.request.url.toString(), null))
    }

    override fun imageUrlParse(response: Response) = ""

    private fun getTranslationMode() = preferences.getString(TRANSLATION_MODE_KEY, "ai") ?: "ai"
    private fun getDecryptionKey() = preferences.getString(DECRYPTION_KEY, "") ?: ""
    private fun getGoogleApiKey() = preferences.getString(GOOGLE_API_KEY, "") ?: ""

    // ======================== Tracking (SourceTracker) ========================

    private val trackingEnabled: Boolean
        get() = preferences.getBoolean(PREF_ENABLE_TRACKING, false)

    override val supportsChapterTracking: Boolean
        get() = trackingEnabled
    override val supportsFavoritesTracking: Boolean
        get() = trackingEnabled

    private val serieIdCache = mutableMapOf<Int, Int>()
    private val chapterIdCache = mutableMapOf<Pair<Int, Int>, Int>()
    private val jsonMime = "application/json".toMediaType()

    private fun folderReading() = preferences.getString(PREF_FOLDER_READING, "1")?.toIntOrNull() ?: 1
    private fun folderCompleted() = preferences.getString(PREF_FOLDER_COMPLETED, "3")?.toIntOrNull() ?: 3
    private fun folderTrash() = preferences.getString(PREF_FOLDER_TRASH, "5")?.toIntOrNull() ?: 5
    private fun managedFolders() = listOf(folderReading(), FOLDER_READ_LATER, folderCompleted(), folderTrash()).distinct()

    private data class SerieIds(val sid: Int, val rawId: Int)

    private fun SChapter.isRead(): Boolean = try {
        this::class.java.getMethod("getRead").invoke(this) as? Boolean ?: false
    } catch (_: Exception) {
        false
    }

    private fun cacheSerieId(rawId: Int, sid: Int) {
        serieIdCache[rawId] = sid
        preferences.edit().putInt("$SID_PREFIX$rawId", sid).apply()
    }

    private fun rawIdOf(url: String): Int? = Regex("""novel/(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun serieIds(manga: SManga): SerieIds? {
        val rawId = rawIdOf(manga.url) ?: return null
        val cached = serieIdCache[rawId] ?: preferences.getInt("$SID_PREFIX$rawId", -1).takeIf { it > 0 }
        val sid = cached ?: fetchSerieId(manga, rawId) ?: return null
        return SerieIds(sid, rawId)
    }

    private fun fetchSerieId(manga: SManga, rawId: Int): Int? = try {
        val response = client.newCall(mangaDetailsRequest(manga)).execute()
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val sid = root["pageProps"]?.jsonObject
            ?.get("serie")?.jsonObject
            ?.get("serie_data")?.jsonObject
            ?.get("id")?.jsonPrimitive?.intOrNull
        sid?.also { cacheSerieId(rawId, it) }
    } catch (e: Exception) {
        Log.w(TAG, "fetchSerieId failed: ${e.message}")
        null
    }

    private fun chapterIdFor(rawId: Int, order: Int): Int? {
        chapterIdCache[rawId to order]?.let { return it }
        return try {
            val start = ((order - 1) / CHAPTER_BATCH) * CHAPTER_BATCH + 1
            val end = start + CHAPTER_BATCH - 1
            val response = client.newCall(
                GET("$baseUrl/api/chapters/$rawId?start=$start&end=$end", headers),
            ).execute()
            val chapters = json.parseToJsonElement(response.body.string()).jsonObject["chapters"]?.jsonArray
            chapters?.forEach { el ->
                val o = el.jsonObject
                val ord = o["order"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                chapterIdCache[rawId to ord] = id
            }
            chapterIdCache[rawId to order]
        } catch (e: Exception) {
            Log.w(TAG, "chapterIdFor failed: ${e.message}")
            null
        }
    }

    private fun postFolder(action: String, ids: SerieIds, folder: Int) {
        val body = buildJsonObject {
            put("sid", ids.sid)
            put("raw_id", ids.rawId)
            put("folder", folder)
        }.toString().toRequestBody(jsonMime)
        runCatching {
            client.newCall(POST("$baseUrl/api/library/folder/$action", apiHeaders, body)).execute().close()
        }
    }

    private fun moveToFolder(ids: SerieIds, target: Int) {
        postFolder("add", ids, target)
        managedFolders().filter { it != target }.forEach { postFolder("delete", ids, it) }
    }

    override suspend fun onFavorited(manga: SManga, categories: List<String>) {
        if (!trackingEnabled) return
        val ids = serieIds(manga) ?: return
        val target = if (categories.any { it.equals("trash", ignoreCase = true) }) folderTrash() else folderReading()
        postFolder("add", ids, target)
    }

    override suspend fun onUnfavorited(manga: SManga, categories: List<String>) {
        if (!trackingEnabled) return
        val ids = serieIds(manga) ?: return
        managedFolders().forEach { postFolder("delete", ids, it) }
    }

    override suspend fun onChaptersRead(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) {
        if (!trackingEnabled) return
        val ids = serieIds(manga) ?: return

        val furthest = allChapters.filter { it.isRead() }.maxByOrNull { it.chapter_number }
            ?: changedChapters.maxByOrNull { it.chapter_number }
        furthest?.let { postReadEvent(ids, it.chapter_number.toInt()) }

        val allRead = allChapters.isNotEmpty() && allChapters.all { it.isRead() }
        when {
            allRead -> moveToFolder(ids, folderCompleted())
            categories.any { it.equals("trash", ignoreCase = true) } -> moveToFolder(ids, folderTrash())
        }
    }

    private fun postReadEvent(ids: SerieIds, order: Int) {
        if (order <= 0) return
        val chapterId = chapterIdFor(ids.rawId, order)
        val body = buildJsonObject {
            put("serie_id", ids.sid)
            put("raw_id", ids.rawId)
            put("language", "en")
            put("translate", getTranslationMode())
            if (chapterId != null) put("chapter_id", chapterId)
            put("order", order)
            put("new_event", true)
            put("version", 2)
        }.toString().toRequestBody(jsonMime)
        runCatching {
            client.newCall(POST("$baseUrl/api/event/add", apiHeaders, body)).execute().close()
        }
    }

    @Volatile private var userFoldersFetched = false

    private fun cacheUserFolders() {
        if (!preferences.getBoolean(PREF_CACHE_USER_FOLDERS, true)) return
        if (userFoldersFetched) return
        userFoldersFetched = true
        runCatching {
            val response = client.newCall(GET("$baseUrl/api/user/config/all", apiHeaders)).execute()
            val folders = json.parseToJsonElement(response.body.string()).jsonObject["data"]
                ?.jsonObject?.get("user_folders")?.jsonArray
            if (folders != null) {
                preferences.edit().putString(USER_FOLDERS_KEY, folders.toString()).apply()
                Log.d(TAG, "cacheUserFolders: ${folders.size} custom folders")
            }
        }.onFailure { Log.w(TAG, "cacheUserFolders failed: ${it.message}") }
    }

    private fun folderFilterOptions(): Array<Pair<String, String>> {
        val options = DEFAULT_FOLDER_OPTIONS.toMutableList()
        val cached = preferences.getString(USER_FOLDERS_KEY, null) ?: return options.toTypedArray()
        runCatching {
            json.parseToJsonElement(cached).jsonArray.forEach { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val title = o["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                options.add(Pair("$title ($id)", id.toString()))
            }
        }
        return options.toTypedArray()
    }
    // ======================== End tracking ========================

    @Volatile private var taxTagMap: Map<Int, String>? = null

    @Volatile private var taxGenreMap: Map<Int, String>? = null

    @Volatile private var taxTagBoxes: List<ExcludableCheckBox>? = null

    @Volatile private var taxGenreBoxes: List<ExcludableCheckBox>? = null

    @Volatile private var taxLoaded = false

    private fun cacheTaxonomy(pageProps: JsonObject) {
        val build = cachedBuildId ?: return
        if (preferences.getString(TAX_BUILD_KEY, null) == build && taxLoaded) return

        val tagsObj = pageProps["tags"]?.jsonObject
        val genresEl = pageProps["genres"]
        if (tagsObj == null && genresEl == null) return

        preferences.edit()
            .putString(TAX_BUILD_KEY, build)
            .putString(TAX_TAGS_KEY, tagsObj?.toString())
            .putString(TAX_GENRES_KEY, genresEl?.toString())
            .apply()

        applyTaxonomy(tagsObj, genresEl)
        Log.d(TAG, "cacheTaxonomy: build=$build tags=${taxTagBoxes?.size} genres=${taxGenreBoxes?.size}")
    }

    private fun applyTaxonomy(tagsObj: JsonObject?, genresEl: JsonElement?) {
        // tags shape: { ungrouped: [{ value, label, category_id }], groups: [...] }
        tagsObj?.get("ungrouped")?.jsonArray
            ?.mapNotNull { el ->
                val o = el.jsonObject
                val v = o["value"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val l = o["label"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                v to l
            }
            ?.takeIf { it.isNotEmpty() }
            ?.let { pairs ->
                taxTagMap = pairs.toMap()
                taxTagBoxes = pairs.distinctBy { it.first }.sortedBy { it.second.lowercase() }
                    .map { ExcludableCheckBox(it.second, it.first.toString()) }
            }

        // genres array shape varies across builds — accept {value|id} + {label|name}
        (genresEl as? JsonArray)
            ?.mapNotNull { el ->
                val o = el.jsonObject
                val v = (o["value"] ?: o["id"])?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val l = (o["label"] ?: o["name"])?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                v to l
            }
            ?.takeIf { it.isNotEmpty() }
            ?.let { pairs ->
                taxGenreMap = pairs.toMap()
                taxGenreBoxes = pairs.distinctBy { it.first }.sortedBy { it.second.lowercase() }
                    .map { ExcludableCheckBox(it.second, it.first.toString()) }
            }

        taxLoaded = true
    }

    private fun ensureTaxonomyLoaded() {
        if (taxLoaded) return
        val tagsStr = preferences.getString(TAX_TAGS_KEY, null)
        val genresStr = preferences.getString(TAX_GENRES_KEY, null)
        if (tagsStr == null && genresStr == null) {
            taxLoaded = true
            return
        }
        try {
            applyTaxonomy(
                tagsStr?.let { json.parseToJsonElement(it).jsonObject },
                genresStr?.let { json.parseToJsonElement(it) },
            )
        } catch (e: Exception) {
            Log.w(TAG, "ensureTaxonomyLoaded failed: ${e.message}")
            taxLoaded = true
        }
    }

    private fun tagLabel(id: Int): String? {
        ensureTaxonomyLoaded()
        return taxTagMap?.get(id) ?: tagIdToName[id.toString()]
    }

    private fun genreLabel(id: Int): String? {
        ensureTaxonomyLoaded()
        return taxGenreMap?.get(id) ?: genreIdToName[id.toString()]
    }

    private fun tagFilterBoxes(): List<ExcludableCheckBox> {
        ensureTaxonomyLoaded()
        return taxTagBoxes ?: DEFAULT_TAG_BOXES
    }

    private fun genreFilterBoxes(): List<ExcludableCheckBox> {
        ensureTaxonomyLoaded()
        return taxGenreBoxes ?: DEFAULT_GENRE_BOXES
    }

    private fun clearTaxonomyCache() {
        preferences.edit()
            .remove(TAX_BUILD_KEY)
            .remove(TAX_TAGS_KEY)
            .remove(TAX_GENRES_KEY)
            .remove(USER_FOLDERS_KEY)
            .apply()
        taxTagMap = null
        taxGenreMap = null
        taxTagBoxes = null
        taxGenreBoxes = null
        taxLoaded = false
        userFoldersFetched = false
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TRANSLATION_MODE_KEY
            title = "Translation Mode"
            entries = arrayOf("Raw (No Translation)", "Web (Google Translate)", "AI Translation")
            entryValues = arrayOf("raw", "web", "ai")
            summary = "Raw: Decrypted Chinese text. Web: Google Translate (requires API key). AI: Server-side AI translation (best quality)."
            setDefaultValue("ai")
        }.also(screen::addPreference)

        androidx.preference.EditTextPreference(screen.context).apply {
            key = DECRYPTION_KEY
            title = "Decryption Key"
            summary = "Required for Raw/Web modes. Enter the AES encryption key."
            dialogTitle = "Decryption Key"
            dialogMessage = "Enter the 32-character decryption key for encrypted content."
            setDefaultValue("IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru")
        }.also(screen::addPreference)

        androidx.preference.EditTextPreference(screen.context).apply {
            key = GOOGLE_API_KEY
            title = "Google API Key"
            summary = "Optional. Google Translate API key for future use."
            dialogTitle = "Google API Key"
            dialogMessage = "Enter your Google Translate API key."
            setDefaultValue("AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENABLE_TRACKING
            title = "Sync library & progress to WTR-LAB"
            summary = "Mirror your library folder and reading progress to your WTR-LAB account when you add/remove novels or mark chapters read. Requires being logged in via WebView."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_FOLDER_READING
            title = "Folder id: Reading"
            summary = "Folder a novel is added to when favorited. Default 1."
            dialogTitle = "Reading folder id"
            setDefaultValue("1")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_FOLDER_COMPLETED
            title = "Folder id: Completed"
            summary = "Folder a novel is moved to once every chapter is read. Default 3."
            dialogTitle = "Completed folder id"
            setDefaultValue("3")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_FOLDER_TRASH
            title = "Folder id: Trash"
            summary = "Folder a novel is moved to when it's in a 'Trash' category. Default 5."
            dialogTitle = "Trash folder id"
            setDefaultValue("5")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_CACHE_USER_FOLDERS
            title = "Show your custom folders in filters"
            summary = "Fetch your account's custom library folders and offer them in the Library Folders filter. Requires being logged in via WebView."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = CLEAR_TAX_KEY
            title = "Clear tag/genre name cache"
            summary = "Toggle to drop the cached tag and genre lists. They are re-fetched from the site on the next browse."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                clearTaxonomyCache()
                false
            }
        }.also(screen::addPreference)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("Note: Filters are applied together with search"),
        OrderByFilter(),
        OrderFilter(),
        StatusFilter(),
        ReleaseStatusFilter(),
        AdditionAgeFilter(),
        MinChaptersFilter(),
        MinRatingFilter(),
        MinReviewCountFilter(),
        GenreOperatorFilter(),
        GenreFilter(genreFilterBoxes()),
        TagOperatorFilter(),
        TagFilter(tagFilterBoxes()),
        FoldersFilter(folderFilterOptions()),
        LibraryExcludeFilter(),
    )

    companion object {
        private const val TAG = "WtrLab"
        private const val CHAPTER_BATCH = 250

        private val genreIdToName: Map<String, String> by lazy {
            DEFAULT_GENRE_BOXES.associate { it.value to it.name }
        }
        private val tagIdToName: Map<String, String> by lazy {
            DEFAULT_TAG_BOXES.associate { it.value to it.name }
        }
    }
}

private const val TRANSLATION_MODE_KEY = "translationMode"
private const val DECRYPTION_KEY = "decryptionKey"
private const val GOOGLE_API_KEY = "googleApiKey"
private const val TAX_BUILD_KEY = "taxonomyBuildId"
private const val TAX_TAGS_KEY = "taxonomyTags"
private const val TAX_GENRES_KEY = "taxonomyGenres"
private const val CLEAR_TAX_KEY = "clearTaxonomyCache"
private const val PREF_ENABLE_TRACKING = "pref_enable_tracking"
private const val SID_PREFIX = "serieId_"

private const val PREF_FOLDER_READING = "pref_folder_reading"
private const val PREF_FOLDER_COMPLETED = "pref_folder_completed"
private const val PREF_FOLDER_TRASH = "pref_folder_trash"
private const val PREF_CACHE_USER_FOLDERS = "pref_cache_user_folders"
private const val USER_FOLDERS_KEY = "userFoldersJson"
private const val FOLDER_READ_LATER = 2

private val DEFAULT_FOLDER_OPTIONS = arrayOf(
    Pair("No Filter", ""),
    Pair("Reading (1)", "1"),
    Pair("Read Later (2)", "2"),
    Pair("Completed (3)", "3"),
    Pair("Trash (5)", "5"),
)

private class OrderByFilter :
    SelectFilter(
        "Order by",
        arrayOf(
            Pair("Update Date", "update"),
            Pair("Addition Date", "date"),
            Pair("Random", "random"),
            Pair("Daily View", "daily_rank"),
            Pair("Weekly View", "weekly_rank"),
            Pair("Monthly View", "monthly_rank"),
            Pair("All-Time View", "view"),
            Pair("Name", "name"),
            Pair("Reader", "reader"),
            Pair("Chapter", "chapter"),
            Pair("Rating", "rating"),
            Pair("Review Count", "total_rate"),
            Pair("Vote Count", "vote"),
            Pair("Weighted Rating [Beta]", "weighted"),
        ),
    )

private class OrderFilter :
    SelectFilter(
        "Order",
        arrayOf(
            Pair("Descending", "desc"),
            Pair("Ascending", "asc"),
        ),
    )

private class StatusFilter :
    SelectFilter(
        "Status",
        arrayOf(
            Pair("All", "all"),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
            Pair("Hiatus", "hiatus"),
            Pair("Dropped", "dropped"),
        ),
    )

private class ReleaseStatusFilter :
    SelectFilter(
        "Release Status",
        arrayOf(
            Pair("All", "all"),
            Pair("Released", "released"),
            Pair("On Voting", "voting"),
        ),
    )

private class AdditionAgeFilter :
    SelectFilter(
        "Addition Age",
        arrayOf(
            Pair("All", "all"),
            Pair("< 2 Days", "day"),
            Pair("< 1 Week", "week"),
            Pair("< 1 Month", "month"),
            Pair("< 3 Months", "3month"),
            Pair("< 6 Months", "6month"),
            Pair("< 1 Year", "year"),
        ),
    )

private class MinChaptersFilter :
    SelectFilter(
        "Minimum Chapters",
        arrayOf(
            Pair("Any Chapters", ""),
            Pair("100+ Chapters", "100"),
            Pair("150+ Chapters", "150"),
            Pair("200+ Chapters", "200"),
            Pair("250+ Chapters", "250"),
            Pair("500+ Chapters", "500"),
            Pair("750+ Chapters", "750"),
            Pair("1000+ Chapters", "1000"),
            Pair("1500+ Chapters", "1500"),
            Pair("2000+ Chapters", "2000"),
            Pair("2500+ Chapters", "2500"),
        ),
    )

private class MinRatingFilter :
    SelectFilter(
        "Minimum Rating",
        arrayOf(
            Pair("Any", ""),
            Pair("1.0+ Stars", "1.0"),
            Pair("1.5+ Stars", "1.5"),
            Pair("2.0+ Stars", "2.0"),
            Pair("2.5+ Stars", "2.5"),
            Pair("3.0+ Stars", "3.0"),
            Pair("3.5+ Stars", "3.5"),
            Pair("4.0+ Stars", "4.0"),
            Pair("4.5+ Stars", "4.5"),
            Pair("5.0+ Stars", "5.0"),
        ),
    )

private class MinReviewCountFilter :
    SelectFilter(
        "Minimum Review Count",
        arrayOf(
            Pair("Any", ""),
            Pair("5+ Reviews", "5"),
            Pair("10+ Reviews", "10"),
            Pair("15+ Reviews", "15"),
            Pair("20+ Reviews", "20"),
            Pair("25+ Reviews", "25"),
            Pair("30+ Reviews", "30"),
            Pair("35+ Reviews", "35"),
            Pair("40+ Reviews", "40"),
            Pair("45+ Reviews", "45"),
            Pair("50+ Reviews", "50"),
        ),
    )

private class GenreOperatorFilter :
    SelectFilter(
        "Genre (And/Or)",
        arrayOf(
            Pair("And", "and"),
            Pair("Or", "or"),
        ),
    )

private class GenreFilter(boxes: List<ExcludableCheckBox> = DEFAULT_GENRE_BOXES) : Filter.Group<ExcludableCheckBox>("Genres", boxes)

private val DEFAULT_GENRE_BOXES: List<ExcludableCheckBox> =
    listOf(
        ExcludableCheckBox("Action", "1"),
        ExcludableCheckBox("Adult", "2"),
        ExcludableCheckBox("Adventure", "3"),
        ExcludableCheckBox("Comedy", "4"),
        ExcludableCheckBox("Drama", "5"),
        ExcludableCheckBox("Ecchi", "6"),
        ExcludableCheckBox("Erciyuan", "7"),
        ExcludableCheckBox("Fan-fiction", "8"),
        ExcludableCheckBox("Fantasy", "9"),
        ExcludableCheckBox("Game", "10"),
        ExcludableCheckBox("Gender Bender", "11"),
        ExcludableCheckBox("Harem", "12"),
        ExcludableCheckBox("Historical", "13"),
        ExcludableCheckBox("Horror", "14"),
        ExcludableCheckBox("Josei", "15"),
        ExcludableCheckBox("Martial Arts", "16"),
        ExcludableCheckBox("Mature", "17"),
        ExcludableCheckBox("Mecha", "18"),
        ExcludableCheckBox("Military", "19"),
        ExcludableCheckBox("Mystery", "20"),
        ExcludableCheckBox("Psychological", "21"),
        ExcludableCheckBox("Romance", "22"),
        ExcludableCheckBox("School Life", "23"),
        ExcludableCheckBox("Sci-fi", "24"),
        ExcludableCheckBox("Seinen", "25"),
        ExcludableCheckBox("Shoujo", "26"),
        ExcludableCheckBox("Shoujo Ai", "27"),
        ExcludableCheckBox("Shounen", "28"),
        ExcludableCheckBox("Shounen Ai", "29"),
        ExcludableCheckBox("Slice of Life", "30"),
        ExcludableCheckBox("Smut", "31"),
        ExcludableCheckBox("Sports", "32"),
        ExcludableCheckBox("Supernatural", "33"),
        ExcludableCheckBox("Tragedy", "34"),
        ExcludableCheckBox("Urban Life", "35"),
        ExcludableCheckBox("Wuxia", "36"),
        ExcludableCheckBox("Xianxia", "37"),
        ExcludableCheckBox("Xuanhuan", "38"),
        ExcludableCheckBox("Yaoi", "39"),
        ExcludableCheckBox("Yuri", "40"),
    )

private class TagOperatorFilter :
    SelectFilter(
        "Tag (And/Or)",
        arrayOf(
            Pair("And", "and"),
            Pair("Or", "or"),
        ),
    )

private class TagFilter(boxes: List<ExcludableCheckBox> = DEFAULT_TAG_BOXES) : Filter.Group<ExcludableCheckBox>("Tags", boxes)

private val DEFAULT_TAG_BOXES: List<ExcludableCheckBox> =
    listOf(
        ExcludableCheckBox("Male Protagonist", "417"),
        ExcludableCheckBox("Transmigration", "717"),
        ExcludableCheckBox("System", "696"),
        ExcludableCheckBox("Cultivation", "169"),
        ExcludableCheckBox("Special Abilities", "667"),
        ExcludableCheckBox("Female Protagonist", "275"),
        ExcludableCheckBox("Fanfiction", "263"),
        ExcludableCheckBox("Weak to Strong", "750"),
        ExcludableCheckBox("Handsome Male Lead", "327"),
        ExcludableCheckBox("Beautiful Female Lead", "81"),
        ExcludableCheckBox("Game Elements", "297"),
        ExcludableCheckBox("Cheats", "122"),
        ExcludableCheckBox("Genius Protagonist", "306"),
        ExcludableCheckBox("Reincarnation", "578"),
        ExcludableCheckBox("Harem-seeking Protagonist", "329"),
        ExcludableCheckBox("Time Travel", "710"),
        ExcludableCheckBox("Overpowered Protagonist", "506"),
        ExcludableCheckBox("Modern Day", "446"),
        ExcludableCheckBox("Business Management", "108"),
        ExcludableCheckBox("Calm Protagonist", "111"),
        ExcludableCheckBox("Magic", "410"),
        ExcludableCheckBox("Immortals", "357"),
        ExcludableCheckBox("Clever Protagonist", "134"),
        ExcludableCheckBox("Ruthless Protagonist", "595"),
        ExcludableCheckBox("Apocalypse", "47"),
        ExcludableCheckBox("World Hopping", "756"),
        ExcludableCheckBox("Poor to Rich", "540"),
        ExcludableCheckBox("Douluo Dalu", "772"),
        ExcludableCheckBox("Naruto", "769"),
        ExcludableCheckBox("Farming", "266"),
        ExcludableCheckBox("Fantasy World", "265"),
        ExcludableCheckBox("Kingdom Building", "379"),
        ExcludableCheckBox("Fast Cultivation", "267"),
        ExcludableCheckBox("Protagonist Strong from the Start", "560"),
        ExcludableCheckBox("Cunning Protagonist", "171"),
        ExcludableCheckBox("Nationalism", "476"),
        ExcludableCheckBox("Schemes And Conspiracies", "601"),
        ExcludableCheckBox("Survival", "692"),
        ExcludableCheckBox("Post-apocalyptic", "544"),
        ExcludableCheckBox("Hard-Working Protagonist", "328"),
        ExcludableCheckBox("Showbiz", "640"),
        ExcludableCheckBox("Unlimited Flow", "735"),
        ExcludableCheckBox("Demons", "191"),
        ExcludableCheckBox("Monsters", "452"),
        ExcludableCheckBox("Dragons", "216"),
        ExcludableCheckBox("Romantic Subplot", "592"),
        ExcludableCheckBox("Polygamy", "538"),
        ExcludableCheckBox("Beast Companions", "78"),
        ExcludableCheckBox("Marvel", "766"),
        ExcludableCheckBox("Evolution", "248"),
        ExcludableCheckBox("One Piece", "767"),
        ExcludableCheckBox("Leadership", "388"),
        ExcludableCheckBox("Alternate World", "30"),
        ExcludableCheckBox("Pets", "520"),
        ExcludableCheckBox("World Travel", "757"),
        ExcludableCheckBox("Celebrities", "117"),
        ExcludableCheckBox("Strong to Stronger", "682"),
        ExcludableCheckBox("Game Ranking System", "298"),
        ExcludableCheckBox("Alchemy", "27"),
        ExcludableCheckBox("Arrogant Characters", "56"),
        ExcludableCheckBox("Multiple Realms", "459"),
        ExcludableCheckBox("Army Building", "54"),
        ExcludableCheckBox("Magical Space", "414"),
        ExcludableCheckBox("Wealthy Characters", "751"),
        ExcludableCheckBox("Early Romance", "225"),
        ExcludableCheckBox("Racism", "570"),
        ExcludableCheckBox("Devoted Love Interests", "198"),
        ExcludableCheckBox("Comedic Undertone", "146"),
        ExcludableCheckBox("Businessmen", "109"),
        ExcludableCheckBox("Second Chance", "606"),
        ExcludableCheckBox("Revenge", "585"),
        ExcludableCheckBox("Wizards", "755"),
        ExcludableCheckBox("Pregnancy", "549"),
        ExcludableCheckBox("Ancient China", "34"),
        ExcludableCheckBox("Black Belly", "87"),
        ExcludableCheckBox("Evil Protagonist", "246"),
        ExcludableCheckBox("Love Interest Falls in Love First", "403"),
        ExcludableCheckBox("Evil Gods", "244"),
        ExcludableCheckBox("Academy", "5"),
        ExcludableCheckBox("Outer Space", "505"),
        ExcludableCheckBox("Zombies", "765"),
        ExcludableCheckBox("Single Female Lead", "787"),
        ExcludableCheckBox("Mythology", "473"),
        ExcludableCheckBox("Gods", "316"),
        ExcludableCheckBox("Harry Potter", "768"),
        ExcludableCheckBox("Sword Wielder", "695"),
        ExcludableCheckBox("Shameless Protagonist", "630"),
        ExcludableCheckBox("Futuristic Setting", "294"),
        ExcludableCheckBox("Pokemon", "771"),
        ExcludableCheckBox("Parallel Worlds", "510"),
        ExcludableCheckBox("Level System", "390"),
        ExcludableCheckBox("Beasts", "80"),
        ExcludableCheckBox("Strong Love Interests", "681"),
        ExcludableCheckBox("Fantasy Creatures", "264"),
        ExcludableCheckBox("Modern Knowledge", "447"),
        ExcludableCheckBox("Hiding True Identity", "343"),
        ExcludableCheckBox("Loyal Subordinates", "408"),
        ExcludableCheckBox("Slow Romance", "659"),
        ExcludableCheckBox("Family", "257"),
        ExcludableCheckBox("Politics", "536"),
        ExcludableCheckBox("Determined Protagonist", "197"),
        ExcludableCheckBox("Hiding True Abilities", "342"),
        ExcludableCheckBox("Cosmic Wars", "156"),
        ExcludableCheckBox("Ancient Times", "35"),
        ExcludableCheckBox("Arranged Marriage", "55"),
        ExcludableCheckBox("Complex Family Relationships", "148"),
        ExcludableCheckBox("Cold Protagonist", "142"),
        ExcludableCheckBox("Ghosts", "307"),
        ExcludableCheckBox("Sword And Magic", "694"),
        ExcludableCheckBox("Based on an Anime", "74"),
        ExcludableCheckBox("Wars", "748"),
        ExcludableCheckBox("Survival Game", "693"),
        ExcludableCheckBox("Military", "437"),
        ExcludableCheckBox("Betrayal", "83"),
        ExcludableCheckBox("Misunderstandings", "442"),
        ExcludableCheckBox("Time Skip", "709"),
        ExcludableCheckBox("Bloodlines", "93"),
        ExcludableCheckBox("Transported to Another World", "721"),
        ExcludableCheckBox("Cautious Protagonist", "116"),
        ExcludableCheckBox("Nobles", "485"),
        ExcludableCheckBox("Technological Gap", "699"),
        ExcludableCheckBox("Doting Love Interests", "211"),
        ExcludableCheckBox("Antihero Protagonist", "43"),
        ExcludableCheckBox("Godly Powers", "315"),
        ExcludableCheckBox("Reincarnated in Another World", "577"),
        ExcludableCheckBox("Lucky Protagonist", "409"),
        ExcludableCheckBox("Virtual Reality", "742"),
        ExcludableCheckBox("Medical Knowledge", "433"),
        ExcludableCheckBox("God Protagonist", "312"),
        ExcludableCheckBox("Adapted to Manhua", "15"),
        ExcludableCheckBox("Fast Learner", "268"),
        ExcludableCheckBox("Childcare", "126"),
        ExcludableCheckBox("Kingdoms", "380"),
        ExcludableCheckBox("Scientists", "603"),
        ExcludableCheckBox("Underestimated Protagonist", "731"),
        ExcludableCheckBox("Multiple Identities", "455"),
        ExcludableCheckBox("Naive Protagonist", "474"),
        ExcludableCheckBox("Doctors", "208"),
        ExcludableCheckBox("Artifacts", "58"),
        ExcludableCheckBox("Older Love Interests", "492"),
        ExcludableCheckBox("Elves", "233"),
        ExcludableCheckBox("Hidden Abilities", "341"),
        ExcludableCheckBox("Power Couple", "545"),
        ExcludableCheckBox("Cooking", "154"),
        ExcludableCheckBox("Unique Cultivation Technique", "732"),
        ExcludableCheckBox("Body Tempering", "95"),
        ExcludableCheckBox("Chat Rooms", "121"),
        ExcludableCheckBox("Eye Powers", "251"),
        ExcludableCheckBox("Artificial Intelligence", "59"),
        ExcludableCheckBox("Master-Disciple Relationship", "428"),
        ExcludableCheckBox("Interdimensional Travel", "368"),
        ExcludableCheckBox("Famous Protagonist", "261"),
        ExcludableCheckBox("Royalty", "594"),
        ExcludableCheckBox("Low-key Protagonist", "407"),
        ExcludableCheckBox("Late Romance", "385"),
        ExcludableCheckBox("Gamers", "299"),
        ExcludableCheckBox("Monster Tamer", "451"),
        ExcludableCheckBox("Possessive Characters", "543"),
        ExcludableCheckBox("Aliens", "28"),
        ExcludableCheckBox("Multiple POV", "457"),
        ExcludableCheckBox("Mythical Beasts", "472"),
        ExcludableCheckBox("Familial Love", "255"),
        ExcludableCheckBox("Confident Protagonist", "150"),
        ExcludableCheckBox("Mature Protagonist", "432"),
        ExcludableCheckBox("Rape", "571"),
        ExcludableCheckBox("Reincarnated as a Monster", "574"),
        ExcludableCheckBox("Slow Growth at Start", "658"),
        ExcludableCheckBox("Cold Love Interests", "141"),
        ExcludableCheckBox("Character Growth", "118"),
        ExcludableCheckBox("Sect Development", "613"),
        ExcludableCheckBox("Summoning Magic", "691"),
        ExcludableCheckBox("Acting", "7"),
        ExcludableCheckBox("Ability Steal", "2"),
        ExcludableCheckBox("Movies", "453"),
        ExcludableCheckBox("Ninjas", "484"),
        ExcludableCheckBox("Previous Life Talent", "551"),
        ExcludableCheckBox("Gate to Another World", "301"),
        ExcludableCheckBox("Money Grubber", "448"),
        ExcludableCheckBox("Non-humanoid Protagonist", "486"),
        ExcludableCheckBox("Dark", "181"),
        ExcludableCheckBox("Strength-based Social Hierarchy", "680"),
        ExcludableCheckBox("Industrialization", "362"),
        ExcludableCheckBox("Mysterious Past", "470"),
        ExcludableCheckBox("Caring Protagonist", "115"),
        ExcludableCheckBox("Pirates", "529"),
        ExcludableCheckBox("Pill Concocting", "527"),
        ExcludableCheckBox("European Ambience", "243"),
        ExcludableCheckBox("Cruel Characters", "167"),
        ExcludableCheckBox("Charismatic Protagonist", "119"),
        ExcludableCheckBox("Strategist", "679"),
        ExcludableCheckBox("Assassins", "61"),
        ExcludableCheckBox("Secret Organizations", "609"),
        ExcludableCheckBox("Knights", "381"),
        ExcludableCheckBox("Vampires", "740"),
        ExcludableCheckBox("Firearms", "278"),
        ExcludableCheckBox("Army", "53"),
        ExcludableCheckBox("Dao Comprehension", "179"),
        ExcludableCheckBox("Absent Parents", "3"),
        ExcludableCheckBox("Clan Building", "132"),
        ExcludableCheckBox("Detectives", "196"),
        ExcludableCheckBox("Heroes", "339"),
        ExcludableCheckBox("Friendship", "291"),
        ExcludableCheckBox("Charming Protagonist", "120"),
        ExcludableCheckBox("Accelerated Growth", "6"),
        ExcludableCheckBox("College/University", "144"),
        ExcludableCheckBox("Depictions of Cruelty", "193"),
        ExcludableCheckBox("Artifact Crafting", "57"),
        ExcludableCheckBox("Doting Parents", "213"),
        ExcludableCheckBox("Past Plays a Big Role", "515"),
        ExcludableCheckBox("MMORPG", "443"),
        ExcludableCheckBox("Card Games", "113"),
        ExcludableCheckBox("Magic Beasts", "411"),
        ExcludableCheckBox("Tragic Past", "715"),
        ExcludableCheckBox("First-time Intercourse", "280"),
        ExcludableCheckBox("Transported into a Game World", "719"),
        ExcludableCheckBox("Mysterious Family Background", "468"),
        ExcludableCheckBox("Management", "420"),
        ExcludableCheckBox("Secret Identity", "608"),
        ExcludableCheckBox("Earth Invasion", "226"),
        ExcludableCheckBox("Clones", "136"),
        ExcludableCheckBox("Based on a Video Game", "72"),
        ExcludableCheckBox("Swallowed Star", "785"),
        ExcludableCheckBox("Magic Formations", "412"),
        ExcludableCheckBox("Gao Wu", "781"),
        ExcludableCheckBox("Genetic Modifications", "304"),
        ExcludableCheckBox("Male Yandere", "419"),
        ExcludableCheckBox("Writers", "759"),
        ExcludableCheckBox("Based on a Movie", "69"),
        ExcludableCheckBox("Elemental Magic", "232"),
        ExcludableCheckBox("Discrimination", "201"),
        ExcludableCheckBox("Marriage", "424"),
        ExcludableCheckBox("Evil Organizations", "245"),
        ExcludableCheckBox("Younger Sisters", "764"),
        ExcludableCheckBox("Sudden Wealth", "688"),
        ExcludableCheckBox("Doting Older Siblings", "212"),
        ExcludableCheckBox("Cute Children", "174"),
        ExcludableCheckBox("Manipulative Characters", "422"),
        ExcludableCheckBox("Age Progression", "24"),
        ExcludableCheckBox("Hunters", "353"),
        ExcludableCheckBox("Adventurers", "22"),
        ExcludableCheckBox("Threesome", "704"),
        ExcludableCheckBox("Mystery Solving", "471"),
        ExcludableCheckBox("Perverted Protagonist", "519"),
        ExcludableCheckBox("Jack of All Trades", "372"),
        ExcludableCheckBox("Battle Competition", "76"),
        ExcludableCheckBox("Multiple Reincarnated Individuals", "460"),
        ExcludableCheckBox("Sex Slaves", "627"),
        ExcludableCheckBox("Soul Power", "663"),
        ExcludableCheckBox("Orphans", "500"),
        ExcludableCheckBox("Martial Spirits", "426"),
        ExcludableCheckBox("Dense Protagonist", "192"),
        ExcludableCheckBox("Family Conflict", "259"),
        ExcludableCheckBox("Magical Technology", "415"),
        ExcludableCheckBox("Warhammer", "775"),
        ExcludableCheckBox("Smart Couple", "660"),
        ExcludableCheckBox("Teachers", "697"),
        ExcludableCheckBox("Police", "534"),
        ExcludableCheckBox("Selfish Protagonist", "616"),
        ExcludableCheckBox("Simulator", "786"),
        ExcludableCheckBox("Demonic Cultivation Technique", "190"),
        ExcludableCheckBox("Rape Victim Becomes Lover", "572"),
        ExcludableCheckBox("Hackers", "324"),
        ExcludableCheckBox("Sudden Strength Gain", "687"),
        ExcludableCheckBox("Imperial Harem", "358"),
        ExcludableCheckBox("Family Business", "258"),
        ExcludableCheckBox("Cute Protagonist", "175"),
        ExcludableCheckBox("Apathetic Protagonist", "46"),
        ExcludableCheckBox("Lack of Common Sense", "383"),
        ExcludableCheckBox("Aristocracy", "51"),
        ExcludableCheckBox("Death of Loved Ones", "184"),
        ExcludableCheckBox("Enemies Become Lovers", "237"),
        ExcludableCheckBox("Empires", "235"),
        ExcludableCheckBox("Dungeons", "221"),
        ExcludableCheckBox("Male to Female", "418"),
        ExcludableCheckBox("Lazy Protagonist", "387"),
        ExcludableCheckBox("Evil Religions", "247"),
        ExcludableCheckBox("Obsessive Love", "490"),
        ExcludableCheckBox("Easy Going Life", "227"),
        ExcludableCheckBox("Appearance Changes", "48"),
        ExcludableCheckBox("Demon Lord", "189"),
        ExcludableCheckBox("Carefree Protagonist", "114"),
        ExcludableCheckBox("Mutations", "466"),
        ExcludableCheckBox("Student-Teacher Relationship", "685"),
        ExcludableCheckBox("R-18", "568"),
        ExcludableCheckBox("Abusive Characters", "4"),
        ExcludableCheckBox("Appearance Different from Actual Age", "49"),
        ExcludableCheckBox("Football", "780"),
        ExcludableCheckBox("Human-Nonhuman Relationship", "351"),
        ExcludableCheckBox("Pragmatic Protagonist", "547"),
        ExcludableCheckBox("Hot-blooded Protagonist", "348"),
        ExcludableCheckBox("Necromancer", "478"),
        ExcludableCheckBox("Battle Academy", "75"),
        ExcludableCheckBox("Witches", "754"),
        ExcludableCheckBox("Yandere", "760"),
        ExcludableCheckBox("Dragon Ball", "773"),
        ExcludableCheckBox("Childhood Friends", "127"),
        ExcludableCheckBox("Based on a TV Show", "71"),
        ExcludableCheckBox("Dwarfs", "222"),
        ExcludableCheckBox("Inheritance", "364"),
        ExcludableCheckBox("Child Protagonist", "125"),
        ExcludableCheckBox("Honkai", "818"),
        ExcludableCheckBox("Daoism", "180"),
        ExcludableCheckBox("Heavenly Tribulation", "335"),
        ExcludableCheckBox("Netori", "482"),
        ExcludableCheckBox("Sexual Cultivation Technique", "629"),
        ExcludableCheckBox("Buddhism", "106"),
        ExcludableCheckBox("Broken Engagement", "103"),
        ExcludableCheckBox("Reverse Rape", "587"),
        ExcludableCheckBox("Time Manipulation", "707"),
        ExcludableCheckBox("DC Universe", "778"),
        ExcludableCheckBox("Eidetic Memory", "230"),
        ExcludableCheckBox("Clingy Lover", "135"),
        ExcludableCheckBox("Live Streaming", "782"),
        ExcludableCheckBox("Mutated Creatures", "465"),
        ExcludableCheckBox("Phoenixes", "524"),
        ExcludableCheckBox("Sharp-tongued Characters", "633"),
        ExcludableCheckBox("Souls", "664"),
        ExcludableCheckBox("Poor Protagonist", "539"),
        ExcludableCheckBox("Angels", "38"),
        ExcludableCheckBox("Singers", "648"),
        ExcludableCheckBox("Proactive Protagonist", "555"),
        ExcludableCheckBox("Heartwarming", "333"),
        ExcludableCheckBox("Fellatio", "273"),
        ExcludableCheckBox("Spatial Manipulation", "665"),
        ExcludableCheckBox("Tsundere", "725"),
        ExcludableCheckBox("Enemies Become Allies", "236"),
        ExcludableCheckBox("e-Sports", "224"),
        ExcludableCheckBox("Mind Control", "439"),
        ExcludableCheckBox("Mercenaries", "435"),
        ExcludableCheckBox("Adopted Protagonist", "20"),
        ExcludableCheckBox("Average-looking Protagonist", "65"),
        ExcludableCheckBox("Master-Servant Relationship", "429"),
        ExcludableCheckBox("Gore", "318"),
        ExcludableCheckBox("Store Owner", "675"),
        ExcludableCheckBox("Amnesia", "31"),
        ExcludableCheckBox("Human Experimentation", "349"),
        ExcludableCheckBox("Strategic Battles", "678"),
        ExcludableCheckBox("Goddesses", "314"),
        ExcludableCheckBox("Skill Assimilation", "651"),
        ExcludableCheckBox("Abandoned Children", "1"),
        ExcludableCheckBox("Bleach", "770"),
        ExcludableCheckBox("Death", "183"),
        ExcludableCheckBox("Emotionally Weak Protagonist", "234"),
        ExcludableCheckBox("Aggressive Characters", "26"),
        ExcludableCheckBox("Resurrection", "583"),
        ExcludableCheckBox("Cross-dressing", "165"),
        ExcludableCheckBox("Transformation Ability", "716"),
        ExcludableCheckBox("Villainess Noble Girls", "741"),
        ExcludableCheckBox("Insects", "366"),
        ExcludableCheckBox("Thriller", "705"),
        ExcludableCheckBox("Orcs", "497"),
        ExcludableCheckBox("Boss-Subordinate Relationship", "100"),
        ExcludableCheckBox("Fated Lovers", "271"),
        ExcludableCheckBox("Music", "464"),
        ExcludableCheckBox("Economics", "228"),
        ExcludableCheckBox("Loli", "395"),
        ExcludableCheckBox("Couple Growth", "158"),
        ExcludableCheckBox("Incest", "359"),
        ExcludableCheckBox("Multiple Transported Individuals", "462"),
        ExcludableCheckBox("Protagonist with Multiple Bodies", "561"),
        ExcludableCheckBox("Religions", "579"),
        ExcludableCheckBox("Game Creator", "784"),
        ExcludableCheckBox("Soldiers", "662"),
        ExcludableCheckBox("Righteous Protagonist", "590"),
        ExcludableCheckBox("Blacksmith", "89"),
        ExcludableCheckBox("Adopted Children", "19"),
        ExcludableCheckBox("Yu-Gi-Oh!", "774"),
        ExcludableCheckBox("Twins", "726"),
        ExcludableCheckBox("Crossover", "166"),
        ExcludableCheckBox("Power Struggle", "546"),
        ExcludableCheckBox("Otaku", "501"),
        ExcludableCheckBox("Saints", "597"),
        ExcludableCheckBox("Teamwork", "698"),
        ExcludableCheckBox("Age Regression", "25"),
        ExcludableCheckBox("Honghuang", "801"),
        ExcludableCheckBox("Siblings Not Related by Blood", "645"),
        ExcludableCheckBox("Reincarnated in a Game World", "576"),
        ExcludableCheckBox("Poisons", "533"),
        ExcludableCheckBox("Fox Spirits", "289"),
        ExcludableCheckBox("Adapted to Manga", "14"),
        ExcludableCheckBox("Sexual Abuse", "628"),
        ExcludableCheckBox("Dolls/Puppets", "209"),
        ExcludableCheckBox("Long Separations", "398"),
        ExcludableCheckBox("Proficiency", "793"),
        ExcludableCheckBox("Skill Creation", "653"),
        ExcludableCheckBox("Gangs", "300"),
        ExcludableCheckBox("Gunfighters", "323"),
        ExcludableCheckBox("Journey to the West", "796"),
        ExcludableCheckBox("Detective Conan", "804"),
        ExcludableCheckBox("Popular Love Interests", "541"),
        ExcludableCheckBox("Pill Based Cultivation", "526"),
        ExcludableCheckBox("Destiny", "195"),
        ExcludableCheckBox("Parody", "513"),
        ExcludableCheckBox("Multiple Timelines", "461"),
        ExcludableCheckBox("Personality Changes", "518"),
        ExcludableCheckBox("Psychic Powers", "562"),
        ExcludableCheckBox("Generals", "303"),
        ExcludableCheckBox("Narcissistic Protagonist", "475"),
        ExcludableCheckBox("Transplanted Memories", "718"),
        ExcludableCheckBox("Crime", "163"),
        ExcludableCheckBox("Domestic Affairs", "210"),
        ExcludableCheckBox("Murders", "463"),
        ExcludableCheckBox("Guilds", "322"),
        ExcludableCheckBox("Books", "98"),
        ExcludableCheckBox("Chefs", "123"),
        ExcludableCheckBox("Mortal Flow", "792"),
        ExcludableCheckBox("Loner Protagonist", "397"),
        ExcludableCheckBox("Contracts", "153"),
        ExcludableCheckBox("Quirky Characters", "566"),
        ExcludableCheckBox("Adapted to Anime", "10"),
        ExcludableCheckBox("Beastkin", "79"),
        ExcludableCheckBox("Archery", "50"),
        ExcludableCheckBox("Adultery", "21"),
        ExcludableCheckBox("Harsh Training", "330"),
        ExcludableCheckBox("Organized Crime", "498"),
        ExcludableCheckBox("Biochip", "85"),
        ExcludableCheckBox("Fairies", "252"),
        ExcludableCheckBox("Psychopaths", "563"),
        ExcludableCheckBox("Multiple Protagonists", "458"),
        ExcludableCheckBox("Ugly to Beautiful", "729"),
        ExcludableCheckBox("Playful Protagonist", "531"),
        ExcludableCheckBox("Minecraft", "790"),
        ExcludableCheckBox("Medieval", "434"),
        ExcludableCheckBox("Divination", "205"),
        ExcludableCheckBox("Younger Love Interests", "763"),
        ExcludableCheckBox("Sister Complex", "650"),
        ExcludableCheckBox("Maids", "416"),
        ExcludableCheckBox("Protagonist Falls in Love First", "559"),
        ExcludableCheckBox("Dreams", "217"),
        ExcludableCheckBox("Persistent Love Interests", "517"),
        ExcludableCheckBox("Hunter x Hunter", "777"),
        ExcludableCheckBox("Brother Complex", "104"),
        ExcludableCheckBox("Humanoid Protagonist", "352"),
        ExcludableCheckBox("Brotherhood", "105"),
        ExcludableCheckBox("Playboys", "530"),
        ExcludableCheckBox("Jealousy", "373"),
        ExcludableCheckBox("Tribal Society", "723"),
        ExcludableCheckBox("Secrets", "612"),
        ExcludableCheckBox("Saving the World", "600"),
        ExcludableCheckBox("Slaves", "656"),
        ExcludableCheckBox("Three Kingdoms", "795"),
        ExcludableCheckBox("Childhood Love", "128"),
        ExcludableCheckBox("Thieves", "703"),
        ExcludableCheckBox("Demi-Humans", "188"),
        ExcludableCheckBox("Dao Companion", "178"),
        ExcludableCheckBox("Sign In", "811"),
        ExcludableCheckBox("Race Change", "569"),
        ExcludableCheckBox("Crafting", "162"),
        ExcludableCheckBox("First Love", "279"),
        ExcludableCheckBox("Cyberpunk 2077", "783"),
        ExcludableCheckBox("Curses", "173"),
        ExcludableCheckBox("Spirit Advisor", "669"),
        ExcludableCheckBox("Marriage of Convenience", "425"),
        ExcludableCheckBox("Near-Death Experience", "477"),
        ExcludableCheckBox("Lost Civilizations", "400"),
        ExcludableCheckBox("Prophecies", "557"),
        ExcludableCheckBox("Forced Marriage", "286"),
        ExcludableCheckBox("Episodic", "241"),
        ExcludableCheckBox("Conferred Gods", "800"),
        ExcludableCheckBox("Artists", "60"),
        ExcludableCheckBox("Animal Characteristics", "39"),
        ExcludableCheckBox("Cannibalism", "112"),
        ExcludableCheckBox("Fearless Protagonist", "272"),
        ExcludableCheckBox("Dark Fantasy", "789"),
        ExcludableCheckBox("Secretive Protagonist", "611"),
        ExcludableCheckBox("God-human Relationship", "313"),
        ExcludableCheckBox("Child Abuse", "124"),
        ExcludableCheckBox("Cowardly Protagonist", "161"),
        ExcludableCheckBox("Anti-social Protagonist", "42"),
        ExcludableCheckBox("Prison", "554"),
        ExcludableCheckBox("Female Master", "274"),
        ExcludableCheckBox("Hollywood", "779"),
        ExcludableCheckBox("Past Trauma", "516"),
        ExcludableCheckBox("Torture", "713"),
        ExcludableCheckBox("Adapted to Drama", "11"),
        ExcludableCheckBox("Bullying", "107"),
        ExcludableCheckBox("Androgynous Characters", "36"),
        ExcludableCheckBox("Class Awakening", "827"),
        ExcludableCheckBox("Multiple Personalities", "456"),
        ExcludableCheckBox("Corruption", "155"),
        ExcludableCheckBox("Merchants", "436"),
        ExcludableCheckBox("Animal Rearing", "40"),
        ExcludableCheckBox("Werebeasts", "752"),
        ExcludableCheckBox("Exorcism", "250"),
        ExcludableCheckBox("Bodyguards", "97"),
        ExcludableCheckBox("Hell", "336"),
        ExcludableCheckBox("Bickering Couple", "84"),
        ExcludableCheckBox("Honest Protagonist", "346"),
        ExcludableCheckBox("Fairy Tail", "814"),
        ExcludableCheckBox("Divorce", "207"),
        ExcludableCheckBox("Spirits", "671"),
        ExcludableCheckBox("Unconditional Love", "730"),
        ExcludableCheckBox("Reverse Harem", "586"),
        ExcludableCheckBox("World Tree", "758"),
        ExcludableCheckBox("Criminals", "164"),
        ExcludableCheckBox("Skill Books", "652"),
        ExcludableCheckBox("Investigations", "370"),
        ExcludableCheckBox("Succubus", "686"),
        ExcludableCheckBox("Blackmail", "88"),
        ExcludableCheckBox("Sentient Objects", "620"),
        ExcludableCheckBox("Goblins", "311"),
        ExcludableCheckBox("Different Social Status", "199"),
        ExcludableCheckBox("Hospital", "347"),
        ExcludableCheckBox("Genshin Impact", "815"),
        ExcludableCheckBox("Stubborn Protagonist", "683"),
        ExcludableCheckBox("Sickly Characters", "646"),
        ExcludableCheckBox("Servants", "623"),
        ExcludableCheckBox("Disabilities", "200"),
        ExcludableCheckBox("Lord", "823"),
        ExcludableCheckBox("Returning from Another World", "584"),
        ExcludableCheckBox("Cute Story", "176"),
        ExcludableCheckBox("Unlucky Protagonist", "736"),
        ExcludableCheckBox("Life Script", "824"),
        ExcludableCheckBox("Netorare", "480"),
        ExcludableCheckBox("Heaven", "334"),
        ExcludableCheckBox("Spear Wielder", "666"),
        ExcludableCheckBox("Inscriptions", "365"),
        ExcludableCheckBox("Engineer", "239"),
        ExcludableCheckBox("Lord of the Mysteries", "799"),
        ExcludableCheckBox("Masochistic Characters", "427"),
        ExcludableCheckBox("Possession", "542"),
        ExcludableCheckBox("Conditional Power", "149"),
        ExcludableCheckBox("Familiars", "256"),
        ExcludableCheckBox("Healers", "332"),
        ExcludableCheckBox("Slave Harem", "654"),
        ExcludableCheckBox("Herbalist", "338"),
        ExcludableCheckBox("Kind Love Interests", "378"),
        ExcludableCheckBox("Devouring", "797"),
        ExcludableCheckBox("League of Legends", "791"),
        ExcludableCheckBox("Mpreg", "454"),
        ExcludableCheckBox("Famous Parents", "260"),
        ExcludableCheckBox("Love at First Sight", "402"),
        ExcludableCheckBox("Heavenly Defying Comprehension", "803"),
        ExcludableCheckBox("Basketball", "809"),
        ExcludableCheckBox("Hated Protagonist", "331"),
        ExcludableCheckBox("Fallen Angels", "253"),
        ExcludableCheckBox("Dragon Slayers", "215"),
        ExcludableCheckBox("Seme Protagonist", "618"),
        ExcludableCheckBox("Legends", "389"),
        ExcludableCheckBox("Fleet Battles", "282"),
        ExcludableCheckBox("Blood Manipulation", "92"),
        ExcludableCheckBox("Court Official", "159"),
        ExcludableCheckBox("Summoned Hero", "690"),
        ExcludableCheckBox("Androids", "37"),
        ExcludableCheckBox("Lottery", "401"),
        ExcludableCheckBox("Game of Thrones", "813"),
        ExcludableCheckBox("Fat to Fit", "270"),
        ExcludableCheckBox("Priests", "553"),
        ExcludableCheckBox("Seeing Things Other Humans Can't", "615"),
        ExcludableCheckBox("Shoujo-Ai Subplot", "638"),
        ExcludableCheckBox("Twisted Personality", "727"),
        ExcludableCheckBox("Magical Girls", "413"),
        ExcludableCheckBox("Sadistic Characters", "596"),
        ExcludableCheckBox("Enlightenment", "240"),
        ExcludableCheckBox("Prostitutes", "558"),
        ExcludableCheckBox("Weak Protagonist", "749"),
        ExcludableCheckBox("Copy", "807"),
        ExcludableCheckBox("Adapted to Game", "13"),
        ExcludableCheckBox("Puppeteers", "564"),
        ExcludableCheckBox("Sealed Power", "605"),
        ExcludableCheckBox("Cohabitation", "140"),
        ExcludableCheckBox("Mob Protagonist", "444"),
        ExcludableCheckBox("Seven Deadly Sins", "624"),
        ExcludableCheckBox("Single Parent", "649"),
        ExcludableCheckBox("Drugs", "218"),
        ExcludableCheckBox("Territory Management", "802"),
        ExcludableCheckBox("Druids", "219"),
        ExcludableCheckBox("Kidnappings", "377"),
        ExcludableCheckBox("R-15", "567"),
        ExcludableCheckBox("Brainwashing", "101"),
        ExcludableCheckBox("Overprotective Siblings", "507"),
        ExcludableCheckBox("Gambling", "296"),
        ExcludableCheckBox("Arms Dealers", "52"),
        ExcludableCheckBox("Manly Gay Couple", "423"),
        ExcludableCheckBox("Unique Weapons", "734"),
        ExcludableCheckBox("Lawyers", "386"),
        ExcludableCheckBox("Anal", "33"),
        ExcludableCheckBox("Time Loop", "706"),
        ExcludableCheckBox("Grinding", "320"),
        ExcludableCheckBox("Slave Protagonist", "655"),
        ExcludableCheckBox("Hypnotism", "354"),
        ExcludableCheckBox("Demon Slayer", "812"),
        ExcludableCheckBox("Unreliable Narrator", "737"),
        ExcludableCheckBox("Unique Weapon User", "733"),
        ExcludableCheckBox("Poetry", "532"),
        ExcludableCheckBox("Philosophical", "522"),
        ExcludableCheckBox("Feng Shui", "277"),
        ExcludableCheckBox("Chuunibyou", "131"),
        ExcludableCheckBox("Reality-Game Fusion", "830"),
        ExcludableCheckBox("Dragon Riders", "214"),
        ExcludableCheckBox("Omegaverse", "493"),
        ExcludableCheckBox("Suicides", "689"),
        ExcludableCheckBox("Love Rivals", "404"),
        ExcludableCheckBox("Stoic Characters", "674"),
        ExcludableCheckBox("Monster Girls", "449"),
        ExcludableCheckBox("Trickster", "724"),
        ExcludableCheckBox("Handjob", "326"),
        ExcludableCheckBox("Limited Lifespan", "392"),
        ExcludableCheckBox("Restaurant", "582"),
        ExcludableCheckBox("Fallen Nobility", "254"),
        ExcludableCheckBox("Masturbation", "430"),
        ExcludableCheckBox("Dishonest Protagonist", "203"),
        ExcludableCheckBox("Dungeon Master", "220"),
        ExcludableCheckBox("Serial Killers", "622"),
        ExcludableCheckBox("Younger Brothers", "762"),
        ExcludableCheckBox("Pharmacist", "521"),
        ExcludableCheckBox("Secret Relationship", "610"),
        ExcludableCheckBox("Living Alone", "394"),
        ExcludableCheckBox("Mangaka", "421"),
        ExcludableCheckBox("Childish Protagonist", "130"),
        ExcludableCheckBox("Office Romance", "491"),
        ExcludableCheckBox("Models", "445"),
        ExcludableCheckBox("Human Weapon", "350"),
        ExcludableCheckBox("Fanaticism", "262"),
        ExcludableCheckBox("Pilots", "528"),
        ExcludableCheckBox("Lovers Reunited", "406"),
        ExcludableCheckBox("Blind Protagonist", "91"),
        ExcludableCheckBox("Rebellion", "573"),
        ExcludableCheckBox("Programmer", "556"),
        ExcludableCheckBox("Flashbacks", "281"),
        ExcludableCheckBox("Forced into a Relationship", "284"),
        ExcludableCheckBox("More Children More Blessings", "825"),
        ExcludableCheckBox("Sibling's Care", "643"),
        ExcludableCheckBox("Helpful Protagonist", "337"),
        ExcludableCheckBox("Fat Protagonist", "269"),
        ExcludableCheckBox("Awkward Protagonist", "67"),
        ExcludableCheckBox("Spiritual Energy Revival", "828"),
        ExcludableCheckBox("Distrustful Protagonist", "204"),
        ExcludableCheckBox("Folklore", "283"),
        ExcludableCheckBox("Engagement", "238"),
        ExcludableCheckBox("Half-human Protagonist", "325"),
        ExcludableCheckBox("Wishes", "753"),
        ExcludableCheckBox("Tomboyish Female Lead", "712"),
        ExcludableCheckBox("Shapeshifters", "631"),
        ExcludableCheckBox("Love Triangles", "405"),
        ExcludableCheckBox("Shounen-Ai Subplot", "639"),
        ExcludableCheckBox("Shy Characters", "641"),
        ExcludableCheckBox("Reborn as the Villain", "831"),
        ExcludableCheckBox("Body Swap", "94"),
        ExcludableCheckBox("Coming of Age", "147"),
        ExcludableCheckBox("Online Romance", "495"),
        ExcludableCheckBox("DnD", "794"),
        ExcludableCheckBox("Kuudere", "382"),
        ExcludableCheckBox("Monster Society", "450"),
        ExcludableCheckBox("Adapted to Drama CD", "12"),
        ExcludableCheckBox("Spirit Users", "670"),
        ExcludableCheckBox("Trap", "722"),
        ExcludableCheckBox("Orgy", "499"),
        ExcludableCheckBox("Inferiority Complex", "363"),
        ExcludableCheckBox("Unrequited Love", "738"),
        ExcludableCheckBox("Genderless Protagonist", "302"),
        ExcludableCheckBox("Elderly Protagonist", "231"),
        ExcludableCheckBox("Tentacles", "700"),
        ExcludableCheckBox("Clumsy Love Interests", "138"),
        ExcludableCheckBox("Library", "391"),
        ExcludableCheckBox("Parasites", "511"),
        ExcludableCheckBox("Sentimental Protagonist", "621"),
        ExcludableCheckBox("Mysterious Illness", "469"),
        ExcludableCheckBox("Spies", "668"),
        ExcludableCheckBox("Dead Protagonist", "182"),
        ExcludableCheckBox("Former Hero", "288"),
        ExcludableCheckBox("Cousins", "160"),
        ExcludableCheckBox("Seduction", "614"),
        ExcludableCheckBox("Interconnected Storylines", "367"),
        ExcludableCheckBox("Jujutsu Kaisen", "776"),
        ExcludableCheckBox("Curious Protagonist", "172"),
        ExcludableCheckBox("Stockholm Syndrome", "673"),
        ExcludableCheckBox("Genies", "305"),
        ExcludableCheckBox("Time Paradox", "708"),
        ExcludableCheckBox("Mind Break", "438"),
        ExcludableCheckBox("Polite Protagonist", "535"),
        ExcludableCheckBox("Bookworm", "99"),
        ExcludableCheckBox("Transported Modern Structure", "720"),
        ExcludableCheckBox("Bestiality", "82"),
        ExcludableCheckBox("Childhood Promise", "129"),
        ExcludableCheckBox("Parent Complex", "512"),
        ExcludableCheckBox("Sibling Rivalry", "642"),
        ExcludableCheckBox("BDSM", "77"),
        ExcludableCheckBox("Eunuch", "242"),
        ExcludableCheckBox("Introverted Protagonist", "369"),
        ExcludableCheckBox("Affair", "23"),
        ExcludableCheckBox("Autism", "63"),
        ExcludableCheckBox("Matriarchy", "431"),
        ExcludableCheckBox("Selfless Protagonist", "617"),
        ExcludableCheckBox("Automatons", "64"),
        ExcludableCheckBox("Business Wars", "806"),
        ExcludableCheckBox("Quiet Characters", "565"),
        ExcludableCheckBox("Depression", "194"),
        ExcludableCheckBox("Siblings", "644"),
        ExcludableCheckBox("Polyandry", "537"),
        ExcludableCheckBox("Western Names", "788"),
        ExcludableCheckBox("Terrorists", "702"),
        ExcludableCheckBox("Ugly Protagonist", "728"),
        ExcludableCheckBox("Rich to Poor", "589"),
        ExcludableCheckBox("Reincarnated as an Object", "575"),
        ExcludableCheckBox("Antique Shop", "44"),
        ExcludableCheckBox("Amusement Park", "32"),
        ExcludableCheckBox("Nurses", "489"),
        ExcludableCheckBox("Friends Become Enemies", "290"),
        ExcludableCheckBox("Sculptors", "604"),
        ExcludableCheckBox("Forgetful Protagonist", "287"),
        ExcludableCheckBox("Siheyuan", "820"),
        ExcludableCheckBox("Invisibility", "371"),
        ExcludableCheckBox("Schizophrenia", "602"),
        ExcludableCheckBox("Voice Actors", "744"),
        ExcludableCheckBox("Apartment Life", "45"),
        ExcludableCheckBox("Terminal Illness", "701"),
        ExcludableCheckBox("Adapted to Manhwa", "16"),
        ExcludableCheckBox("Nightmares", "483"),
        ExcludableCheckBox("Adapted to Movie", "17"),
        ExcludableCheckBox("Priestesses", "552"),
        ExcludableCheckBox("Co-Workers", "139"),
        ExcludableCheckBox("Undead Protagonist", "810"),
        ExcludableCheckBox("Disfigurement", "202"),
        ExcludableCheckBox("Golems", "317"),
        ExcludableCheckBox("Dystopia", "223"),
        ExcludableCheckBox("Sharing A Body", "632"),
        ExcludableCheckBox("Witcher", "819"),
        ExcludableCheckBox("Based on a Visual Novel", "73"),
        ExcludableCheckBox("Reporters", "581"),
        ExcludableCheckBox("Onmyouji", "496"),
        ExcludableCheckBox("Identity Crisis", "355"),
        ExcludableCheckBox("Language Barrier", "384"),
        ExcludableCheckBox("Part-Time Job", "514"),
        ExcludableCheckBox("Clubs", "137"),
        ExcludableCheckBox("Long-distance Relationship", "399"),
        ExcludableCheckBox("Forced Living Arrangements", "285"),
        ExcludableCheckBox("Paizuri", "509"),
        ExcludableCheckBox("Cunnilingus", "170"),
        ExcludableCheckBox("War Records", "747"),
        ExcludableCheckBox("Rivalry", "591"),
        ExcludableCheckBox("Loneliness", "396"),
        ExcludableCheckBox("Pretend Lovers", "550"),
        ExcludableCheckBox("Photography", "525"),
        ExcludableCheckBox("Timid Protagonist", "711"),
        ExcludableCheckBox("Youkai", "761"),
        ExcludableCheckBox("Astrologers", "62"),
        ExcludableCheckBox("Cosplay", "157"),
        ExcludableCheckBox("Adapted from Manga", "8"),
        ExcludableCheckBox("Confinement", "151"),
        ExcludableCheckBox("Reversible Couple", "588"),
        ExcludableCheckBox("Blind Dates", "90"),
        ExcludableCheckBox("Eavesdropping", "798"),
        ExcludableCheckBox("Neet", "479"),
        ExcludableCheckBox("Star Wars", "817"),
        ExcludableCheckBox("Stalkers", "672"),
        ExcludableCheckBox("Outcasts", "503"),
        ExcludableCheckBox("Secret Crush", "607"),
        ExcludableCheckBox("Female to Male", "276"),
        ExcludableCheckBox("Anti-Magic", "41"),
        ExcludableCheckBox("Valkyries", "739"),
        ExcludableCheckBox("Sex Friends", "626"),
        ExcludableCheckBox("Non-linear Storytelling", "487"),
        ExcludableCheckBox("Straight Uke", "677"),
        ExcludableCheckBox("Galge", "295"),
        ExcludableCheckBox("Mute Character", "467"),
        ExcludableCheckBox("Jobless Class", "375"),
        ExcludableCheckBox("Glasses-wearing Love Interests", "309"),
        ExcludableCheckBox("Shikigami", "635"),
        ExcludableCheckBox("Faith Dependent Deities", "808"),
        ExcludableCheckBox("Delusions", "187"),
        ExcludableCheckBox("Delinquents", "186"),
        ExcludableCheckBox("Dancers", "177"),
        ExcludableCheckBox("Award-winning Work", "66"),
        ExcludableCheckBox("Conflicting Loyalties", "152"),
        ExcludableCheckBox("Coma", "145"),
        ExcludableCheckBox("Futanari", "293"),
        ExcludableCheckBox("Divine Protection", "206"),
        ExcludableCheckBox("Guardian Relationship", "321"),
        ExcludableCheckBox("Grave Keepers", "319"),
        ExcludableCheckBox("Mismatched Couple", "441"),
        ExcludableCheckBox("Outdoor Intercourse", "504"),
        ExcludableCheckBox("Incubus", "360"),
        ExcludableCheckBox("Seven Virtues", "625"),
        ExcludableCheckBox("Sign Language", "647"),
        ExcludableCheckBox("Debts", "185"),
        ExcludableCheckBox("Nudity", "488"),
        ExcludableCheckBox("Roommates", "593"),
        ExcludableCheckBox("Shota", "637"),
        ExcludableCheckBox("Heterochromia", "340"),
        ExcludableCheckBox("Indecisive Protagonist", "361"),
        ExcludableCheckBox("Precognition", "548"),
        ExcludableCheckBox("Frieren", "816"),
        ExcludableCheckBox("Adapted to Visual Novel", "18"),
        ExcludableCheckBox("Collection of Short Stories", "143"),
        ExcludableCheckBox("Cryostasis", "168"),
        ExcludableCheckBox("Bands", "68"),
        ExcludableCheckBox("Netorase", "481"),
        ExcludableCheckBox("Otome Game", "502"),
        ExcludableCheckBox("Bisexual Protagonist", "86"),
        ExcludableCheckBox("Homunculus", "345"),
        ExcludableCheckBox("Voyeurism", "745"),
        ExcludableCheckBox("Gladiators", "308"),
        ExcludableCheckBox("Student Council", "684"),
        ExcludableCheckBox("Samurai", "599"),
        ExcludableCheckBox("Social Outcasts", "661"),
        ExcludableCheckBox("Misandry", "440"),
        ExcludableCheckBox("Fujoshi", "292"),
        ExcludableCheckBox("Glasses-wearing Protagonist", "310"),
        ExcludableCheckBox("Butlers", "110"),
        ExcludableCheckBox("Adapted from Manhua", "9"),
        ExcludableCheckBox("Sleeping", "657"),
        ExcludableCheckBox("Overlord", "826"),
        ExcludableCheckBox("Oneshot", "494"),
        ExcludableCheckBox("Imaginary Friend", "356"),
        ExcludableCheckBox("Jiangshi", "374"),
        ExcludableCheckBox("Array", "822"),
        ExcludableCheckBox("Based on a Song", "70"),
        ExcludableCheckBox("Hong Kong", "821"),
        ExcludableCheckBox("Waiters", "746"),
        ExcludableCheckBox("JSDF", "376"),
        ExcludableCheckBox("Short Story", "636"),
        ExcludableCheckBox("Vocaloid", "743"),
        ExcludableCheckBox("Living Abroad", "393"),
        ExcludableCheckBox("Shield User", "634"),
        ExcludableCheckBox("Editors", "229"),
        ExcludableCheckBox("Reluctant Protagonist", "580"),
        ExcludableCheckBox("Kimetsu no Yaiba", "805"),
        ExcludableCheckBox("Toys", "714"),
        ExcludableCheckBox("Classic", "133"),
        ExcludableCheckBox("Breast Fetish", "102"),
        ExcludableCheckBox("Exhibitionism", "249"),
        ExcludableCheckBox("Pacifist Protagonist", "508"),
        ExcludableCheckBox("Body-double", "96"),
        ExcludableCheckBox("Reborn", "829"),
        ExcludableCheckBox("Straight Seme", "676"),
        ExcludableCheckBox("Phobias", "523"),
        ExcludableCheckBox("Salaryman", "598"),
        ExcludableCheckBox("Hikikomori", "344"),
        ExcludableCheckBox("All-Girls School", "29"),
        ExcludableCheckBox("Senpai-Kouhai Relationship", "619"),
    )

private class FoldersFilter(options: Array<Pair<String, String>> = DEFAULT_FOLDER_OPTIONS) : SelectFilter("Library Folders", options)

private class LibraryExcludeFilter :
    SelectFilter(
        "Library Exclude",
        arrayOf(
            Pair("None", ""),
            Pair("Exclude All", "history"),
            Pair("Exclude Trash", "trash"),
            Pair("Exclude Library & Trash", "in_library"),
        ),
    )

private open class SelectFilter(
    displayName: String,
    val options: Array<Pair<String, String>>,
    defaultIndex: Int = 0,
) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray(), defaultIndex) {
    val selected: String
        get() = options[state].second
}

private class ExcludableCheckBox(name: String, val value: String) : Filter.TriState(name)
