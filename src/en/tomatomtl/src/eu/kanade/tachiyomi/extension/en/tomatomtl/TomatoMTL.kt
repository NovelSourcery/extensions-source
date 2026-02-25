package eu.kanade.tachiyomi.extension.en.tomatomtl

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SourceItem(val id: String, val name: String)

class TomatoMTL :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "TomatoMTL"
    override val baseUrl = "https://tomatomtl.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // API bases
    private val apiBase = "https://tomato-api.tomatomtl.com"
    private val gardenApiBase = "https://tomato-garden-api.tomatomtl.com/api"

    // Decryption key from the website's JavaScript
    private val unlockCode = "65237366656177646a7671646b65313736383537393230302356523111774562"

    // Custom cookie jar to persist translation preferences
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .cookieJar(
            object : CookieJar {
                private val cookies = mutableMapOf<String, MutableList<Cookie>>()

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    this.cookies.getOrPut(url.host) { mutableListOf() }.apply {
                        cookies.forEach { cookie ->
                            removeAll { it.name == cookie.name }
                            add(cookie)
                        }
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val hostCookies = cookies[url.host] ?: mutableListOf()
                    val translationMode = getTranslationMode()
                    val additionalCookies = listOf(
                        Cookie.Builder()
                            .domain(url.host)
                            .name("machine_translation")
                            .value(translationMode)
                            .build(),
                        Cookie.Builder()
                            .domain(url.host)
                            .name("translator_button")
                            .value("en")
                            .build(),
                    )
                    return hostCookies + additionalCookies
                }
            },
        )
        .build()

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        // Garden popular novels API - using garden-stats.php
        return GET(
            "$baseUrl/api/garden-stats.php?action=popular&period=all&page=$page&page_size=20",
            headers,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonResult = json.parseToJsonElement(response.body.string()).jsonObject

        val success = jsonResult["success"]?.jsonPrimitive?.contentOrNull
        if (success != "true" && success?.toBoolean() != true) {
            return MangasPage(emptyList(), false)
        }

        val items = jsonResult["items"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val hasMore = jsonResult["has_more"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

        val titlesToTranslate = mutableListOf<String>()

        val mangaData = items.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val bookId = item["book_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null

                val rawTitle = (
                    item["name_raw"]?.jsonPrimitive?.contentOrNull
                        ?: item["book_name"]?.jsonPrimitive?.contentOrNull
                        ?: "Unknown"
                    ).let { Parser.unescapeEntities(it, false) }

                titlesToTranslate.add(rawTitle)

                val url = if (bookId.startsWith("garden:")) {
                    val parts = bookId.removePrefix("garden:").split(":", limit = 2)
                    val source = parts[0]
                    val hexId = parts[1]
                    "/garden/$source/$hexId"
                } else {
                    "/book/$bookId"
                }

                Triple(
                    url,
                    item["cover_raw"]?.jsonPrimitive?.contentOrNull
                        ?: item["thumb_url"]?.jsonPrimitive?.contentOrNull,
                    item["author_raw"]?.jsonPrimitive?.contentOrNull
                        ?: item["author"]?.jsonPrimitive?.contentOrNull,
                )
            } catch (e: Exception) {
                Log.e("TomatoMTL", "Error parsing manga: ${e.message}")
                null
            }
        }

        // Batch translate titles
        val translatedTitles = translateTitles(titlesToTranslate)

        val mangas = mangaData.mapIndexed { index, (url, cover, author) ->
            SManga.create().apply {
                this.url = url
                title = translatedTitles.getOrNull(index) ?: titlesToTranslate.getOrNull(index) ?: "Unknown"
                thumbnail_url = cover
                this.author = author
            }
        }

        return MangasPage(mangas, hasMore)
    }

    /**
     * Batch translate titles using Google Translate API
     */
    private fun translateTitles(titles: List<String>): List<String> {
        if (titles.isEmpty()) return emptyList()

        val chineseRegex = Regex("[\\u4e00-\\u9fff]")
        val needsTranslation = titles.any { chineseRegex.containsMatchIn(it) }
        if (!needsTranslation) return titles

        return try {
            val bodyArray = buildJsonArray {
                add(
                    buildJsonArray {
                        add(
                            buildJsonArray {
                                titles.forEach { add(it) }
                            },
                        )
                        add("zh-CN")
                        add("en")
                    },
                )
                add("te")
            }

            val requestBody = json.encodeToString(JsonArray.serializer(), bodyArray)
                .toRequestBody("application/json+protobuf".toMediaType())

            val request = Request.Builder()
                .url("https://translate-pa.googleapis.com/v1/translateHtml")
                .post(requestBody)
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body.string()

            val responseJson = json.parseToJsonElement(responseBody).jsonArray
            if (responseJson.isNotEmpty() && responseJson[0] is JsonArray) {
                responseJson[0].jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            } else {
                titles
            }
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Failed to translate titles: ${e.message}")
            titles
        }
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val bodyJson = buildJsonObject {
            put("query", "")
            put("offset", (page - 1) * 20)
            put("sort_order", "new_update")
        }
        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/api/search-proxy.php", headers, requestBody)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = (page - 1) * 20

        val sourceFilter = filters.find { it is SourceFilter } as? SourceFilter
        val selectedSource = sourceFilter?.toUriPart()

        if (!selectedSource.isNullOrEmpty()) {
            // Garden source search or browse
            if (query.isNotEmpty()) {
                val searchUrl = "$gardenApiBase/source/$selectedSource/search?keyword=${URLEncoder.encode(query, "UTF-8")}&page=$page"
                return GET(searchUrl, headers)
            } else {
                // Browse garden source home (first tab by default)
                val homeUrl = "$gardenApiBase/source/$selectedSource/home/0?page=$page"
                return GET(homeUrl, headers)
            }
        }

        val categoryFilter = filters.find { it is CategoryFilter } as? CategoryFilter

        if (categoryFilter != null && categoryFilter.state > 0 && query.isEmpty()) {
            val genderFilter = filters.find { it is GenderFilter } as? GenderFilter
            val creationStatusFilter =
                filters.find { it is CreationStatusFilter } as? CreationStatusFilter
            val wordCountFilterCat =
                filters.find { it is WordCountFilterCategory } as? WordCountFilterCategory
            val sortFilterCat = filters.find { it is SortFilterCategory } as? SortFilterCategory

            val categoryId = categoryFilter.toUriPart()
            val gender = genderFilter?.toUriPart() ?: "1"
            val creationStatus = creationStatusFilter?.toUriPart() ?: "0"
            val wordCount = wordCountFilterCat?.toUriPart() ?: "0"
            val sort = sortFilterCat?.toUriPart() ?: "0"
            val page = (offset / 20) + 1

            val url = "$baseUrl/categories_v2?category_id=$categoryId&sub_category_id=" +
                "&word_count=$wordCount&creation_status=$creationStatus" +
                "&gender=$gender&sort=$sort&page=$page"

            return GET(url, headers)
        }

        // Regular search using search-proxy.php
        val bodyJson = buildJsonObject {
            put("query", query)
            put("offset", offset)

            filters.forEach { filter ->
                when (filter) {
                    is SearchStatusFilter -> {
                        if (filter.state > 0) {
                            put("update_status", filter.toUriPart())
                        }
                    }

                    is SearchWordCountFilter -> {
                        if (filter.state > 0) {
                            put("word_count", filter.toUriPart())
                        }
                    }

                    is SearchSortFilter -> {
                        if (filter.state > 0) {
                            put("sort_order", filter.toUriPart())
                        }
                    }

                    else -> {}
                }
            }
        }

        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/api/search-proxy.php", headers, requestBody)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val responseBody = response.body.string()
        val requestUrl = response.request.url.toString()

        val isGardenApi = requestUrl.contains("tomato-garden-api")
        val isCategoriesV2 = requestUrl.contains("categories_v2")

        if (isCategoriesV2) {
            return parseCategoriesV2Html(responseBody)
        }

        return try {
            val jsonResult = json.parseToJsonElement(responseBody).jsonObject

            val iv = jsonResult["iv"]?.jsonPrimitive?.contentOrNull
            val enc = jsonResult["enc"]?.jsonPrimitive?.contentOrNull

            if (iv != null && enc != null) {
                if (isGardenApi) {
                    parseGardenApiResponse(iv, enc, requestUrl)
                } else {
                    // Decrypt and parse categories response
                    parseCategoriesResponse(iv, enc)
                }
            } else {
                // Regular search response
                parseSearchResponse(jsonResult)
            }
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Error parsing search response: ${e.message}")
            MangasPage(emptyList(), false)
        }
    }

    /**
     * Parse garden API search/browse response.
     * Garden API returns books with: url, title, author, cover
     */
    private fun parseGardenApiResponse(iv: String, enc: String, requestUrl: String): MangasPage {
        val decrypted = decryptContent(iv, enc)
        Log.d("TomatoMTL", "Garden API decrypted: ${decrypted.take(500)}...")

        val page = try {
            requestUrl.substringAfter("page=").substringBefore("&").toIntOrNull() ?: 1
        } catch (e: Exception) {
            1
        }

        return try {
            val jsonResult = json.parseToJsonElement(decrypted).jsonObject
            val success = jsonResult["success"]?.jsonPrimitive?.booleanOrNull ?: false

            if (!success) {
                Log.e("TomatoMTL", "Garden API returned success=false")
                return MangasPage(emptyList(), false)
            }

            val books = jsonResult["data"]?.jsonArray ?: return MangasPage(emptyList(), false)

            // Pagination logic - garden API uses "has_more" or check array size
            val hasMore = when {
                jsonResult["has_more"]?.jsonPrimitive?.booleanOrNull != null -> jsonResult["has_more"]?.jsonPrimitive?.booleanOrNull ?: false
                jsonResult["hasMore"]?.jsonPrimitive?.booleanOrNull != null -> jsonResult["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false
                books.size >= 20 -> true
                else -> false
            }

            val titlesToTranslate = mutableListOf<String>()
            val mangaData = books.mapNotNull { element ->
                try {
                    val book = element.jsonObject

                    // CORRECTED FIELD MAPPINGS
                    val bookUrl = book["link"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null

                    val rawTitle = book["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                    titlesToTranslate.add(rawTitle)

                    val hexUrl = stringToHex(bookUrl)
                    val source = book["host"]?.jsonPrimitive?.contentOrNull
                        ?.replace(Regex("https?://"), "")
                        ?.replace(Regex("\\..*"), "")
                        ?: "unknown"

                    val gardenUrl = "/garden/$source/$hexUrl"

                    Triple(
                        gardenUrl,
                        book["cover"]?.jsonPrimitive?.contentOrNull?.let { linkCover(it) },
                        book["description"]?.jsonPrimitive?.contentOrNull, // This is actually author
                    )
                } catch (e: Exception) {
                    Log.e("TomatoMTL", "Error parsing garden book: ${e.message}")
                    null
                }
            }

            // Translate titles if needed
            val translatedTitles = translateTitles(titlesToTranslate)

            val mangas = mangaData.mapIndexed { index, (url, cover, author) ->
                SManga.create().apply {
                    this.url = url
                    title = translatedTitles.getOrNull(index) ?: titlesToTranslate.getOrNull(index) ?: "Unknown"
                    thumbnail_url = cover
                    this.author = author
                }
            }

            Log.d("TomatoMTL", "Garden API parsed ${mangas.size} novels, hasMore=$hasMore, page=$page")
            MangasPage(mangas, hasMore)
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Error parsing garden API response: ${e.message}", e)
            MangasPage(emptyList(), false)
        }
    }

    private fun parseSearchResponse(jsonResult: JsonObject): MangasPage {
        val searchTabs = jsonResult["search_tabs"]?.jsonArray
        if (searchTabs.isNullOrEmpty()) {
            return MangasPage(emptyList(), false)
        }

        val firstTab = searchTabs[0].jsonObject
        val data = firstTab["data"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val hasMore = firstTab["has_more"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

        val titlesToTranslate = mutableListOf<String>()
        val mangaData = data.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val bookDataArray = item["book_data"]?.jsonArray
                val bookData = bookDataArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null

                val bookId = bookData["book_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null

                val url = if (bookId.startsWith("garden:")) {
                    val parts = bookId.removePrefix("garden:").split(":", limit = 2)
                    val source = parts[0]
                    val hexId = parts[1]
                    "/garden/$source/$hexId"
                } else {
                    "/book/$bookId"
                }

                val rawTitle = (bookData["book_name"]?.jsonPrimitive?.contentOrNull ?: "Unknown").let { Parser.unescapeEntities(it, false) }
                titlesToTranslate.add(rawTitle)

                Triple(
                    url,
                    bookData["thumb_url"]?.jsonPrimitive?.contentOrNull?.let { linkCover(it) },
                    bookData["author"]?.jsonPrimitive?.contentOrNull,
                )
            } catch (e: Exception) {
                Log.e("TomatoMTL", "Error parsing search result: ${e.message}")
                null
            }
        }

        // Batch translate titles
        val translatedTitles = translateTitles(titlesToTranslate)

        val mangas = mangaData.mapIndexed { index, (url, cover, author) ->
            SManga.create().apply {
                this.url = url
                title = translatedTitles.getOrNull(index) ?: titlesToTranslate.getOrNull(index) ?: "Unknown"
                thumbnail_url = cover
                this.author = author
            }
        }

        return MangasPage(mangas, hasMore)
    }

    private fun parseCategoriesResponse(iv: String, enc: String): MangasPage {
        val decrypted = decryptContent(iv, enc)

        return try {
            val jsonResult = json.parseToJsonElement(decrypted).jsonObject
            val success = jsonResult["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

            if (!success) return MangasPage(emptyList(), false)

            val books = jsonResult["books"]?.jsonArray ?: return MangasPage(emptyList(), false)
            val hasMore = jsonResult["has_more"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

            val titlesToTranslate = mutableListOf<String>()
            val mangaData = books.mapNotNull { element ->
                try {
                    val book = element.jsonObject
                    val bookId = book["book_id"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val rawTitle = (book["book_name"]?.jsonPrimitive?.contentOrNull ?: "Unknown").let { Parser.unescapeEntities(it, false) }
                    titlesToTranslate.add(rawTitle)

                    Triple(
                        "/book/$bookId",
                        book["thumb_url"]?.jsonPrimitive?.contentOrNull?.let { linkCover(it) },
                        book["author"]?.jsonPrimitive?.contentOrNull,
                    )
                } catch (e: Exception) {
                    null
                }
            }

            // Batch translate titles
            val translatedTitles = translateTitles(titlesToTranslate)

            val mangas = mangaData.mapIndexed { index, (url, cover, author) ->
                SManga.create().apply {
                    this.url = url
                    title = translatedTitles.getOrNull(index) ?: titlesToTranslate.getOrNull(index) ?: "Unknown"
                    thumbnail_url = cover
                    this.author = author
                }
            }

            MangasPage(mangas, hasMore)
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Error parsing categories response: ${e.message}")
            MangasPage(emptyList(), false)
        }
    }

    /**
     * Parse categories_v2 HTML response.
     * The page contains embedded JSON in a <script> tag: const books = [{book_id, book_name, thumb_url, author, score}, ...]
     */
    private fun parseCategoriesV2Html(html: String): MangasPage {
        return try {
            val doc = Jsoup.parse(html)
            val scripts = doc.select("script")

            var booksJsonStr: String? = null
            for (script in scripts) {
                val scriptContent = script.data()
                val booksMatch = Regex("""const\s+books\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(scriptContent)
                if (booksMatch != null) {
                    booksJsonStr = booksMatch.groupValues[1]
                    break
                }
            }

            if (booksJsonStr == null) {
                return MangasPage(emptyList(), false)
            }

            val booksArray = json.parseToJsonElement(booksJsonStr).jsonArray

            val titlesToTranslate = mutableListOf<String>()
            val mangaData = booksArray.mapNotNull { element ->
                try {
                    val book = element.jsonObject
                    val bookId = book["book_id"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val rawTitle = (book["book_name"]?.jsonPrimitive?.contentOrNull ?: "Unknown")
                        .let { Parser.unescapeEntities(it, false) }
                    titlesToTranslate.add(rawTitle)

                    Triple(
                        "/book/$bookId",
                        book["thumb_url"]?.jsonPrimitive?.contentOrNull?.let { linkCover(it) },
                        book["author"]?.jsonPrimitive?.contentOrNull,
                    )
                } catch (e: Exception) {
                    null
                }
            }

            val translatedTitles = translateTitles(titlesToTranslate)

            val mangas = mangaData.mapIndexed { index, (url, cover, author) ->
                SManga.create().apply {
                    this.url = url
                    title = translatedTitles.getOrNull(index) ?: titlesToTranslate.getOrNull(index) ?: "Unknown"
                    thumbnail_url = cover
                    this.author = author
                }
            }

            // Determine hasMore from the actual pagination element.
            // <ul class="pagination"> contains <li class="page-item active"> (current) and
            //   <li class="page-item"><a class="page-link" href="...&page=N">N</a></li>.
            // A page link with a number greater than the active page means there IS a next page.
            // If pagination is missing entirely, assume there IS a next page (safer default).
            val hasMore = run {
                val pagination = doc.selectFirst("ul.pagination")
                if (pagination == null) {
                    // No pagination element – assume more pages exist unless we got 0 books
                    mangas.isNotEmpty()
                } else {
                    val activePage = pagination.selectFirst("li.page-item.active")
                        ?.text()?.trim()?.toIntOrNull() ?: 1
                    val pageLinks = pagination.select("li.page-item a.page-link")
                    pageLinks.any { link ->
                        val pageNum = link.text().trim().toIntOrNull()
                        pageNum != null && pageNum > activePage
                    }
                }
            }
            MangasPage(mangas, hasMore)
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Error parsing categories_v2 HTML: ${e.message}")
            MangasPage(emptyList(), false)
        }
    }

    /**
     * Optimize cover image URL using wsrv.nl proxy (matching JS link_cover function)
     * Handles different URL formats:
     * 1. Already optimized wsrv.nl URLs - return as-is
     * 2. Fanqie URLs (fqnovelpic.com/novel-pic) - transform via wsrv.nl
     * 3. Garden cover URLs (tomato-proxy.cachefly.net) - already proxied, return as-is
     * 4. Other URLs - try to transform
     */
    private fun linkCover(url: String): String {
        if (url.isBlank() || url == "/assets/images/noimg_1.jpg") {
            return "$baseUrl/assets/images/noimg_1.jpg"
        }

        if (url.contains("wsrv.nl") || url.contains("cover-img.raudo.eu.org")) {
            // Fix malformed URLs like "https://wsrv.nl//novel-pic/..."
            return url.replace("wsrv.nl//", "wsrv.nl/?url=https://p6-novel.byteimg.com/origin/")
        }

        if (url.contains("tomato-proxy.cachefly.net")) {
            return url
        }

        if (url.contains("fqnovelpic.com") || url.contains("byteimg.com")) {
            val novelPicIndex = url.indexOf("/novel-pic/")
            if (novelPicIndex != -1) {
                val path = url.substring(novelPicIndex)
                    .split("~")[0] // Remove tilde parts
                    .split("?")[0] // Remove query params
                return "https://wsrv.nl/?url=https://p6-novel.byteimg.com/origin$path&w=225&h=300&fit=cover&output=webp"
            }
        }

        // Default: return the URL as-is (might be external or already valid)
        return url
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    /**
     * Override fetchMangaDetails to handle garden novels differently.
     * Garden novel pages require login, so we fetch details from garden-stats API instead.
     */
    override fun fetchMangaDetails(manga: SManga): rx.Observable<SManga> = if (manga.url.contains("/garden/")) {
        // For garden novels, fetch details from garden-stats API
        fetchGardenMangaDetails(manga)
    } else {
        // For regular novels, use default HTML parsing
        super.fetchMangaDetails(manga)
    }

    private fun fetchGardenMangaDetails(manga: SManga): rx.Observable<SManga> {
        return rx.Observable.fromCallable {
            try {
                val pathParts = manga.url.removePrefix("/garden/").split("/")
                if (pathParts.size < 2) return@fromCallable manga

                val source = pathParts[0]
                val hexId = pathParts[1]
                val novelUrl = hexToString(hexId)

                Log.d("TomatoMTL", "Fetching garden details for: source=$source, url=$novelUrl")

                val detailUrl = "$gardenApiBase/source/$source/detail?url=${URLEncoder.encode(novelUrl, "UTF-8")}"

                val response = client.newCall(GET(detailUrl, headers)).execute()
                val responseBody = response.body.string()

                try {
                    val jsonResult = json.parseToJsonElement(responseBody).jsonObject
                    val iv = jsonResult["iv"]?.jsonPrimitive?.contentOrNull
                    val enc = jsonResult["enc"]?.jsonPrimitive?.contentOrNull

                    if (iv != null && enc != null) {
                        val decrypted = decryptContent(iv, enc)
                        Log.d("TomatoMTL", "Decrypted garden detail: ${decrypted.take(500)}...")

                        val decryptedJson = json.parseToJsonElement(decrypted).jsonObject

                        if (decryptedJson["success"]?.jsonPrimitive?.booleanOrNull == true) {
                            val data = decryptedJson["data"]?.jsonObject
                            if (data != null) {
                                val rawName = data["name"]?.jsonPrimitive?.contentOrNull
                                val rawAuthor = data["author"]?.jsonPrimitive?.contentOrNull
                                val rawCover = data["cover"]?.jsonPrimitive?.contentOrNull
                                val rawDescription = data["description"]?.jsonPrimitive?.contentOrNull
                                    ?: data["intro"]?.jsonPrimitive?.contentOrNull
                                val rawStatus = data["status"]?.jsonPrimitive?.contentOrNull

                                manga.apply {
                                    // Translate title
                                    title = rawName?.let { translateSingleTitle(it) } ?: manga.title

                                    // Author
                                    author = rawAuthor ?: manga.author

                                    // Cover - optimize if needed
                                    rawCover?.let { cover ->
                                        thumbnail_url = if (cover.contains("wsrv.nl") || cover.contains("cover-img.raudo.eu.org")) {
                                            cover
                                        } else {
                                            val hexCover = stringToHex(cover)
                                            "https://wsrv.nl/?url=https://tomato-proxy.cachefly.net/$hexCover&w=225&h=300&fit=cover&output=webp"
                                        }
                                    }

                                    // Description - translate if needed
                                    description = buildString {
                                        rawDescription?.let { desc ->
                                            val translatedDesc = if (needsTranslation(desc)) {
                                                try {
                                                    translateSingleTitle(desc)
                                                } catch (e: Exception) {
                                                    desc
                                                }
                                            } else {
                                                desc
                                            }
                                            append(translatedDesc)
                                            append("\n\n")
                                        }
                                        append("━━━━━━━━━━━━━━━━━━\n")
                                        append("📚 Source: $source\n")
                                        append("🔗 Original URL: $novelUrl\n")
                                        append("━━━━━━━━━━━━━━━━━━\n\n")
                                        append("⚠️ Garden novels are aggregated from third-party sources by TomatoMTL.")
                                    }

                                    // Status
                                    status = when (rawStatus?.lowercase()) {
                                        "completed", "finished", "完结" -> SManga.COMPLETED
                                        "ongoing", "连载", "连载中" -> SManga.ONGOING
                                        else -> SManga.UNKNOWN
                                    }
                                }

                                return@fromCallable manga
                            }
                        } else {
                            val error = decryptedJson["error"]?.jsonPrimitive?.contentOrNull
                            Log.e("TomatoMTL", "Garden detail API error: $error")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TomatoMTL", "Error parsing garden details: ${e.message}", e)
                }

                manga.apply {
                    if (description.isNullOrBlank()) {
                        description = buildString {
                            append("━━━━━━━━━━━━━━━━━━\n")
                            append("📚 Source: $source\n")
                            append("🔗 Original URL: $novelUrl\n")
                            append("━━━━━━━━━━━━━━━━━━\n\n")
                            append("⚠️ Garden novels are aggregated from third-party sources.\n")
                            append("Failed to load full details from the garden API.")
                        }
                    }
                }
                manga
            } catch (e: Exception) {
                Log.e("TomatoMTL", "Error fetching garden manga details: ${e.message}", e)
                manga
            }
        }
    }

    /**
     * Convert a string to hex representation
     */
    private fun stringToHex(str: String): String = str.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    /**
     * Clean HTML tags and entities from text using Jsoup
     */
    private fun cleanHtml(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return Jsoup.parse(text).text().trim()
    }

    /**
     * Translate a single title using Google Translate API
     */
    private fun translateSingleTitle(title: String): String {
        if (title.isBlank()) return title

        val chineseRegex = Regex("[\\u4e00-\\u9fff]")
        if (!chineseRegex.containsMatchIn(title)) return title

        return try {
            val translated = translateTitles(listOf(title))
            translated.firstOrNull() ?: title
        } catch (e: Exception) {
            title
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body.string()
        val document = Jsoup.parse(html)

        return SManga.create().apply {
            val bookName = extractJsVariable(html, "book_name")
            val bookCover = extractJsVariable(html, "book_cover")
            val descriptionJs = extractJsVariable(html, "description")
            val authorsZh = extractJsVariable(html, "authors_zh")

            // Title - prefer JS variable, decode unicode escapes
            title = if (!bookName.isNullOrBlank()) {
                val decoded = decodeUnicodeEscapes(bookName)
                translateSingleTitle(decoded)
            } else {
                document.selectFirst("h1.book-title, #book_name")?.text() ?: "Unknown"
            }

            // Cover
            thumbnail_url = bookCover?.let { decodeUnicodeEscapes(it) }
                ?: document.selectFirst("#book_cover")?.attr("src")
                ?: document.selectFirst("#book_cover_link")?.attr("href")
                ?: document.selectFirst(".book-cover img")?.attr("src")

            // Author from JS or metadata
            author = if (!authorsZh.isNullOrBlank()) {
                decodeUnicodeEscapes(authorsZh)
            } else {
                val metadataItems = document.select(".book-meta-item, .book-metadata .book-meta-item")
                metadataItems.find { it.text().contains("Author") }
                    ?.selectFirst("span.ms-2, a")?.text()
            }

            // Status
            val metadataItems = document.select(".book-meta-item, .book-metadata .book-meta-item")
            val statusBadge = metadataItems.find { it.text().contains("Status") }
                ?.selectFirst(".badge")?.text()?.lowercase()
            status = when {
                statusBadge?.contains("completed") == true -> SManga.COMPLETED
                statusBadge?.contains("ongoing") == true -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            // Description - prefer JS variable, decode and translate
            description = if (!descriptionJs.isNullOrBlank()) {
                val decoded = decodeUnicodeEscapes(descriptionJs)
                val translated = if (needsTranslation(decoded)) {
                    translateWithGoogle(decoded)
                } else {
                    decoded
                }
                translated
            } else {
                document.selectFirst("#description, .book-description")?.text()
            }

            // Alternative titles (Original name)
            val originalName = metadataItems.find { it.text().contains("Original name") }
                ?.selectFirst("span")?.text()
            if (!originalName.isNullOrBlank() && originalName != title) {
                description = "Original Title: $originalName\n\n$description"
            }

            // Genre/Categories
            val categories = document.select(".book-meta-item a[href*=categories]").map { it.text() }
            genre = categories.joinToString(", ")
        }
    }

    /**
     * Extract JavaScript variable value from HTML script tags
     */
    private fun extractJsVariable(html: String, varName: String): String? {
        // Match patterns like: const varName = "value"; or let varName = "value";
        val patterns = listOf(
            Regex("""(?:const|let|var)\s+$varName\s*=\s*"([^"]*)"(?:\s*;)?"""),
            Regex("""(?:const|let|var)\s+$varName\s*=\s*'([^']*)'(?:\s*;)?"""),
            Regex("""["']$varName["']\s*:\s*["']([^"']*)["']"""),
        )

        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    /**
     * Decode Unicode escape sequences like \u4e00 to actual characters
     */
    private fun decodeUnicodeEscapes(input: String): String = try {
        val pattern = Regex("""\\u([0-9a-fA-F]{4})""")
        pattern.replace(input) { matchResult ->
            val codePoint = matchResult.groupValues[1].toInt(16)
            codePoint.toChar().toString()
        }
    } catch (e: Exception) {
        input
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    private fun fetchGardenChapterList(manga: SManga): rx.Observable<List<SChapter>> {
        return rx.Observable.fromCallable {
            try {
                val pathParts = manga.url.removePrefix("/garden/").split("/")
                if (pathParts.size < 2) return@fromCallable emptyList<SChapter>()

                val source = pathParts[0]
                val hexId = pathParts[1]
                val novelUrl = hexToString(hexId)

                val chaptersUrl = "$gardenApiBase/source/$source/chapters?url=${URLEncoder.encode(novelUrl, "UTF-8")}"
                Log.d("TomatoMTL", "Fetching garden chapters from: $chaptersUrl")

                val response = client.newCall(GET(chaptersUrl, headers)).execute()
                val responseBody = response.body.string()

                val chapters = mutableListOf<SChapter>()
                val chapterNames = mutableListOf<String>()

                try {
                    val jsonResult = json.parseToJsonElement(responseBody).jsonObject
                    val iv = jsonResult["iv"]?.jsonPrimitive?.contentOrNull
                    val enc = jsonResult["enc"]?.jsonPrimitive?.contentOrNull

                    if (iv != null && enc != null) {
                        val decrypted = decryptContent(iv, enc)
                        Log.d("TomatoMTL", "Decrypted chapters: ${decrypted.take(500)}...")

                        val decryptedJson = json.parseToJsonElement(decrypted).jsonObject

                        if (decryptedJson["success"]?.jsonPrimitive?.booleanOrNull != true) {
                            val error = decryptedJson["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                            Log.e("TomatoMTL", "Garden API returned error: $error")
                            return@fromCallable listOf(
                                SChapter.create().apply {
                                    url = manga.url + "/error"
                                    name = "⚠️ $error"
                                    chapter_number = 0f
                                },
                            )
                        }

                        val chapterList = decryptedJson["data"]?.jsonArray
                            ?: decryptedJson["chapters"]?.jsonArray

                        chapterList?.forEachIndexed { index, element ->
                            try {
                                val chapterObj = element.jsonObject
                                val chapterName = chapterObj["name"]?.jsonPrimitive?.contentOrNull
                                    ?: chapterObj["title"]?.jsonPrimitive?.contentOrNull
                                    ?: "Chapter ${index + 1}"
                                val chapterUrl = chapterObj["url"]?.jsonPrimitive?.contentOrNull

                                if (chapterUrl.isNullOrBlank()) {
                                    Log.w("TomatoMTL", "Chapter ${index + 1} has no URL")
                                    return@forEachIndexed
                                }

                                chapters.add(
                                    SChapter.create().apply {
                                        url = "/garden/$source/$hexId/${URLEncoder.encode(chapterUrl, "UTF-8")}-$index"
                                        name = chapterName
                                        chapter_number = (index + 1).toFloat()
                                    },
                                )
                                chapterNames.add(chapterName)
                            } catch (e: Exception) {
                                Log.e("TomatoMTL", "Error parsing garden chapter $index: ${e.message}")
                            }
                        }

                        // Translate chapter names in batch if needed
                        if (chapters.isNotEmpty() && chapterNames.any { needsTranslation(it) }) {
                            try {
                                val translatedNames = translateTitles(chapterNames)
                                chapters.forEachIndexed { index, chapter ->
                                    if (index < translatedNames.size) {
                                        chapter.name = translatedNames[index]
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("TomatoMTL", "Error translating chapter names: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TomatoMTL", "Garden API error: ${e.message}", e)

                    chapters.add(
                        SChapter.create().apply {
                            url = manga.url + "/0"
                            name = "⚠️ Login Required"
                            chapter_number = 0f
                        },
                    )
                }

                chapters.ifEmpty {
                    listOf(
                        SChapter.create().apply {
                            url = manga.url + "/info"
                            name = "⚠️ Garden novels require login on TomatoMTL website"
                            chapter_number = 0f
                        },
                    )
                }
            } catch (e: Exception) {
                Log.e("TomatoMTL", "Error fetching garden chapter list: ${e.message}")
                listOf(
                    SChapter.create().apply {
                        url = manga.url + "/error"
                        name = "Error loading chapters: ${e.message}"
                        chapter_number = 0f
                    },
                )
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()

        val chapterLinks =
            document.select(".chapter-link, a[href*='/book/'][href*='/'], a[href*='/garden/']")

        chapterLinks.forEach { link ->
            try {
                val href = link.attr("href")
                if (href.isBlank() || href == "#" || !isChapterUrl(href)) return@forEach

                val chapter = SChapter.create().apply {
                    url = if (href.startsWith("http")) {
                        href.removePrefix(baseUrl)
                    } else {
                        href
                    }
                    name = link.text().trim().ifBlank {
                        link.attr("title").ifBlank { "Chapter" }
                    }

                    val chapterNumMatch = Regex("""#?(\d+)""").find(
                        link.selectFirst(".chapter-number")?.text() ?: name,
                    )
                    chapter_number = chapterNumMatch?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                }

                // Avoid duplicates
                if (chapters.none { it.url == chapter.url }) {
                    chapters.add(chapter)
                }
            } catch (e: Exception) {
                Log.e("TomatoMTL", "Error parsing chapter: ${e.message}")
            }
        }

        return chapters.reversed()
    }

    /**
     * Override fetchChapterList to use the catalog API for non-garden novels
     */
    override fun fetchChapterList(manga: SManga): rx.Observable<List<SChapter>> = if (manga.url.contains("/garden/")) {
        fetchGardenChapterList(manga)
    } else {
        fetchRegularChapterList(manga)
    }

    /**
     * Fetch chapter list for non-garden novels using the catalog API
     */
    private fun fetchRegularChapterList(manga: SManga): rx.Observable<List<SChapter>> {
        return rx.Observable.fromCallable {
            try {
                val bookId = manga.url.removePrefix("/book/").split("/").firstOrNull()
                if (bookId.isNullOrBlank()) {
                    return@fromCallable this.chapterListParse(
                        client.newCall(GET("$baseUrl${manga.url}", headers)).execute(),
                    )
                }

                val catalogUrl = "$baseUrl/catalog/$bookId"
                Log.d("TomatoMTL", "Fetching catalog: $catalogUrl")

                val response = client.newCall(GET(catalogUrl, headers)).execute()
                val responseBody = response.body.string()

                val chapters = mutableListOf<SChapter>()
                val chapterNames = mutableListOf<String>()

                try {
                    val catalogJson = json.parseToJsonElement(responseBody).jsonObject
                    val iv = catalogJson["iv"]?.jsonPrimitive?.contentOrNull
                    val enc = catalogJson["enc"]?.jsonPrimitive?.contentOrNull

                    if (iv != null && enc != null) {
                        val decrypted = decryptContent(iv, enc)
                        Log.d("TomatoMTL", "Decrypted catalog: ${decrypted.take(500)}...")

                        val chapterArray = json.parseToJsonElement(decrypted).jsonArray

                        chapterArray.forEachIndexed { index, element ->
                            try {
                                val chapterObj = element.jsonObject
                                val rawTitle = chapterObj["title"]?.jsonPrimitive?.contentOrNull
                                    ?: chapterObj["name"]?.jsonPrimitive?.contentOrNull
                                    ?: "Chapter ${index + 1}"
                                val chapterId = chapterObj["id"]?.jsonPrimitive?.contentOrNull
                                    ?: return@forEachIndexed

                                // Decode unicode escapes in title
                                val chapterTitle = decodeUnicodeEscapes(rawTitle)

                                chapters.add(
                                    SChapter.create().apply {
                                        url = "/book/$bookId/$chapterId"
                                        name = chapterTitle
                                        chapter_number = (index + 1).toFloat()
                                    },
                                )
                                chapterNames.add(chapterTitle)
                            } catch (e: Exception) {
                                Log.e("TomatoMTL", "Error parsing catalog chapter $index: ${e.message}")
                            }
                        }

                        // Translate chapter names in batch if needed
                        if (chapters.isNotEmpty() && chapterNames.any { needsTranslation(it) }) {
                            try {
                                val translatedNames = translateTitles(chapterNames)
                                chapters.forEachIndexed { index, chapter ->
                                    if (index < translatedNames.size) {
                                        chapter.name = translatedNames[index]
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("TomatoMTL", "Error translating chapter names: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TomatoMTL", "Error parsing catalog: ${e.message}")
                }

                if (chapters.isEmpty()) {
                    return@fromCallable this.chapterListParse(
                        client.newCall(GET("$baseUrl${manga.url}", headers)).execute(),
                    )
                }

                chapters
            } catch (e: Exception) {
                Log.e("TomatoMTL", "Error fetching regular chapter list: ${e.message}")
                emptyList<SChapter>()
            }
        }
    }

    private fun isChapterUrl(href: String): Boolean {
        // Garden chapter: /garden/{source}/{hex}/{chapter_id}
        // Regular chapter: /book/{book_id}/{chapter_id}
        val gardenPattern = Regex("""/garden/[^/]+/[^/]+/[^/]+""")
        val bookPattern = Regex("""/book/\d+/\d+""")
        return gardenPattern.containsMatchIn(href) || bookPattern.containsMatchIn(href)
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun fetchPageList(chapter: SChapter): rx.Observable<List<Page>> {
        val url = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        return rx.Observable.just(listOf(Page(0, url)))
    }

    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val chapterUrl = page.url
        Log.d("TomatoMTL", "Fetching chapter content: $chapterUrl")

        val isGarden = chapterUrl.contains("/garden/")

        return if (isGarden) {
            fetchGardenChapter(chapterUrl)
        } else {
            fetchRegularChapter(chapterUrl)
        }
    }

    private fun fetchGardenChapter(chapterUrl: String): String {
        val pathString = if (chapterUrl.startsWith("http")) {
            chapterUrl.substringAfter("/garden/", "")
        } else {
            chapterUrl.removePrefix("/garden/")
        }

        val pathParts = pathString.split("/")
        if (pathParts.size < 3 || pathString.isEmpty()) return "Invalid chapter URL format"

        val source = pathParts[0]
        // pathParts[1] is the hex novel URL (not used directly for chapter fetch)
        // pathParts[2] contains {encoded_chapter_url}-{index}
        val chapterPart = pathParts[2]

        val lastDashIdx = chapterPart.lastIndexOf("-")
        val encodedChapterUrl = if (lastDashIdx > 0) {
            chapterPart.substring(0, lastDashIdx)
        } else {
            chapterPart
        }

        // Decode the chapter URL
        val chapterApiUrl = try {
            URLDecoder.decode(encodedChapterUrl, "UTF-8")
        } catch (e: Exception) {
            encodedChapterUrl
        }

        Log.d("TomatoMTL", "Garden chapter fetch - source: $source, chapterUrl: $chapterApiUrl")

        if (source.isEmpty()) return "Invalid source in garden URL"

        // API endpoint: GET /api/source/{source}/chapter?url={encoded_chapter_url}
        val apiUrl = "$gardenApiBase/source/$source/chapter?url=${URLEncoder.encode(chapterApiUrl, "UTF-8")}"

        val apiResponse = client.newCall(GET(apiUrl, headers)).execute()
        val responseBody = apiResponse.body.string()

        return try {
            if (apiResponse.code == 404) {
                return "Chapter not found (404)"
            }

            val jsonResult = json.parseToJsonElement(responseBody).jsonObject
            val iv = jsonResult["iv"]?.jsonPrimitive?.contentOrNull
            val enc = jsonResult["enc"]?.jsonPrimitive?.contentOrNull

            if (iv != null && enc != null) {
                val decrypted = decryptContent(iv, enc)
                Log.d("TomatoMTL", "Decrypted garden chapter: ${decrypted.take(200)}...")

                val decryptedJson = json.parseToJsonElement(decrypted).jsonObject

                if (decryptedJson["success"]?.jsonPrimitive?.booleanOrNull != true) {
                    val error = decryptedJson["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                    return "Failed to load chapter: $error"
                }

                val data = decryptedJson["data"]?.jsonObject
                val content = data?.get("content")?.jsonPrimitive?.contentOrNull
                    ?: data?.get("text")?.jsonPrimitive?.contentOrNull

                if (content.isNullOrBlank()) {
                    return "Chapter content is empty. This source may require authentication on the TomatoMTL website."
                }

                // Sanitize and process content
                val sanitizedContent = sanitizeChapterContent(content)
                processAndTranslate(sanitizedContent)
            } else {
                val error = jsonResult["error"]?.jsonPrimitive?.contentOrNull
                if (error != null) return error

                "Failed to load chapter content - missing encryption data"
            }
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Error fetching garden chapter: ${e.message}", e)
            if (responseBody.contains("404")) return "Chapter not found"
            "Error loading chapter: ${e.message}"
        }
    }
    private fun sanitizeChapterContent(raw: String): String {
        if (raw.isBlank()) return ""

        var content = raw
            .replace(Regex("</img\\s*>", RegexOption.IGNORE_CASE), "")
            .replace("\r\n", "\n")
            .replace("\u00a0", " ")
            .replace(Regex("""\{!-- PGC_VOICE:[\s\S]*? --\}\n本节目由番茄畅听出品[，。]*.*?\n""", RegexOption.IGNORE_CASE), "")

        if (Regex("<[a-z][\\s\\S]*>", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
            val doc = Jsoup.parse(content)
            content = doc.body().text()
        }

        // Always decode HTML entities (&#39; → ', &amp; → &, etc.)
        content = Parser.unescapeEntities(content, false)

        return content.split("\n")
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun fetchRegularChapter(chapterUrl: String): String {
        val fullUrl = if (chapterUrl.startsWith("http")) chapterUrl else "$baseUrl$chapterUrl"

        val pathParts = fullUrl.removePrefix("$baseUrl/book/").split("/")
        if (pathParts.size < 2) return "Invalid chapter URL"

        val bookId = pathParts[0]

        val catalogUrl = "$baseUrl/catalog/$bookId"
        return try {
            val catalogResponse = client.newCall(GET(catalogUrl, headers)).execute()
            val catalogBody = catalogResponse.body.string()

            // Catalog is encrypted
            val catalogJson = json.parseToJsonElement(catalogBody).jsonObject
            val iv = catalogJson["iv"]?.jsonPrimitive?.contentOrNull
            val enc = catalogJson["enc"]?.jsonPrimitive?.contentOrNull

            if (iv != null && enc != null) {
                // For now, fetch from HTML page as fallback
                fetchChapterFromHtml(fullUrl)
            } else {
                fetchChapterFromHtml(fullUrl)
            }
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Error fetching regular chapter: ${e.message}")
            fetchChapterFromHtml(fullUrl)
        }
    }

    private fun fetchChapterFromHtml(chapterUrl: String): String {
        val fullUrl = if (chapterUrl.startsWith("http")) chapterUrl else "$baseUrl$chapterUrl"
        val pageResponse = client.newCall(GET(fullUrl, headers)).execute()
        val pageHtml = pageResponse.body.string()
        val document = Jsoup.parse(pageHtml)

        val encryptedDataMatch = Regex("""let\s+encryptedData\s*=\s*\{[^}]*"iv"\s*:\s*"([^"]+)"[^}]*"enc"\s*:\s*"([^"]+)"[^}]*\}""").find(pageHtml)
            ?: Regex("""encryptedData\s*=\s*\{[^}]*"iv"\s*:\s*"([^"]+)"[^}]*"enc"\s*:\s*"([^"]+)"[^}]*\}""").find(pageHtml)

        if (encryptedDataMatch != null) {
            val iv = encryptedDataMatch.groupValues[1]
            val enc = encryptedDataMatch.groupValues[2]
            Log.d("TomatoMTL", "Found encryptedData in HTML: iv=${iv.take(20)}...")

            val decrypted = decryptContent(iv, enc)
            if (decrypted.isNotBlank()) {
                val sanitizedContent = sanitizeChapterContent(decrypted)
                return processAndTranslate(sanitizedContent)
            }
        }

        val contentElement =
            document.selectFirst("#chapter-content, .chapter-content, #content, .content")
        if (contentElement != null) {
            return processAndTranslate(contentElement.html())
        }

        val scripts = document.select("script")
        for (script in scripts) {
            val data = script.data()
            if (data.contains("\"iv\"") && data.contains("\"enc\"")) {
                val ivMatch = Regex(""""iv"\s*:\s*"([^"]+)"""").find(data)
                val encMatch = Regex(""""enc"\s*:\s*"([^"]+)"""").find(data)

                val iv = ivMatch?.groupValues?.get(1)
                val enc = encMatch?.groupValues?.get(1)

                if (iv != null && enc != null) {
                    val decrypted = decryptContent(iv, enc)
                    val sanitizedContent = sanitizeChapterContent(decrypted)
                    return processAndTranslate(sanitizedContent)
                }
            }
        }

        return "Could not find chapter content"
    }

    private fun processAndTranslate(content: String): String {
        val translationMode = getTranslationMode()

        if (translationMode != "none" && needsTranslation(content)) {
            return translateContent(content, translationMode)
        }

        return formatContent(content)
    }

    /**
     * Decrypt content using AES-CBC with the unlock code
     * The unlock code is base64 encoded, we decode it and take first 16 bytes for AES-128
     */
    private fun decryptContent(ivBase64: String, encryptedBase64: String): String = try {
        // Decode the key from base64 and take first 16 bytes for AES-128
        val keyBytes = Base64.decode(unlockCode, Base64.DEFAULT).copyOf(16)

        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val encrypted = Base64.decode(encryptedBase64, Base64.DEFAULT)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(encrypted)

        String(decrypted, Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e("TomatoMTL", "Decryption error: ${e.message}")
        ""
    }

    private fun hexToString(hex: String): String = try {
        hex.chunked(2)
            .map { it.toInt(16).toChar() }
            .joinToString("")
    } catch (e: Exception) {
        ""
    }

    private fun needsTranslation(content: String): Boolean {
        val chineseRegex = Regex("[\\u4e00-\\u9fff]")
        val matches = chineseRegex.findAll(content).count()
        return matches > content.length * 0.1
    }

    private fun formatContent(content: String): String = try {
        val jsonArray = json.parseToJsonElement(content).jsonArray
        jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            .joinToString("\n\n") { "<p>$it</p>" }
    } catch (e: Exception) {
        content.split("\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>$it</p>" }
    }

    private fun translateContent(content: String, mode: String): String = when (mode) {
        "google" -> translateWithGoogle(content)
        "google2" -> translateWithGoogle2(content)
        "gemini" -> translateWithGemini(content)
        "bing" -> translateWithBing(content)
        "yandex" -> translateWithYandex(content)
        else -> formatContent(content)
    }

    private fun translateWithGoogle(content: String): String {
        try {
            val paragraphs = try {
                json.parseToJsonElement(content).jsonArray
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
            } catch (e: Exception) {
                content.split("\n").filter { it.isNotBlank() }
            }

            if (paragraphs.isEmpty()) return formatContent(content)

            val bodyArray = buildJsonArray {
                add(
                    buildJsonArray {
                        add(
                            buildJsonArray {
                                paragraphs.forEach { add(it) }
                            },
                        )
                        add("zh-CN")
                        add("en")
                    },
                )
                add("te")
            }

            val requestBody = json.encodeToString(JsonArray.serializer(), bodyArray)
                .toRequestBody("application/json+protobuf".toMediaType())

            val request = Request.Builder()
                .url("https://translate-pa.googleapis.com/v1/translateHtml")
                .post(requestBody)
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body.string()

            val responseJson = json.parseToJsonElement(responseBody).jsonArray
            if (responseJson.isNotEmpty() && responseJson[0] is JsonArray) {
                val translations = responseJson[0].jsonArray
                return translations.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .joinToString("\n") { "<p>$it</p>" }
            }

            return formatContent(content)
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Google translate error: ${e.message}")
            return formatContent(content)
        }
    }

    private fun translateWithGoogle2(content: String): String {
        try {
            val text = try {
                json.parseToJsonElement(content).jsonArray
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .joinToString("\n")
            } catch (e: Exception) {
                content
            }

            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate-pa.googleapis.com/v1/translate?" +
                "params.client=gtx&query.source_language=zh-CN&query.target_language=en" +
                "&query.display_language=en-US&data_types=TRANSLATION&data_types=1" +
                "&key=AIzaSyDLEeFI5OtFBwYBIoK_jj5m32rZK5CkCXA&query.text=$encodedText"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body.string()

            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val translation = responseJson["translation"]?.jsonPrimitive?.contentOrNull

            return translation?.split("\n")
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n") { "<p>$it</p>" }
                ?: formatContent(content)
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Google2 translate error: ${e.message}")
            return formatContent(content)
        }
    }

    private fun translateWithGemini(content: String): String {
        // Gemini AI translation (currently under maintenance per website)
        return translateWithGoogle(content)
    }

    private fun translateWithBing(content: String): String {
        try {
            val text = try {
                json.parseToJsonElement(content).jsonArray
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .joinToString("\n")
            } catch (e: Exception) {
                content
            }

            if (text.isBlank()) return formatContent(content)

            val requestBody = buildJsonArray {
                add(
                    buildJsonObject {
                        put("Text", text)
                    },
                )
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(
                    "https://api.cognitive.microsofttranslator.com/translate?" +
                        "from=&to=en&api-version=3.0&textType=html&includeSentenceLength=true",
                )
                .post(requestBody)
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body.string()

            if (!response.isSuccessful) {
                Log.e("TomatoMTL", "Bing translate failed (${response.code}): $responseBody")
                return translateWithGoogle(content)
            }

            val jsonElement = json.parseToJsonElement(responseBody)

            if (jsonElement !is JsonArray) {
                Log.e("TomatoMTL", "Bing translate API returned non-array response: $responseBody")
                return translateWithGoogle(content)
            }

            val translation = jsonElement[0].jsonObject["translations"]?.jsonArray
                ?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

            return translation?.split("\n")
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n") { "<p>$it</p>" }
                ?: formatContent(content)
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Bing translate error: ${e.message}")
            return translateWithGoogle(content) // Fallback
        }
    }

    private fun translateWithYandex(content: String): String {
        try {
            val text = try {
                json.parseToJsonElement(content).jsonArray
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .joinToString("\n")
            } catch (e: Exception) {
                content
            }

            if (text.isBlank()) return formatContent(content)

            val encodedText = URLEncoder.encode(text, "UTF-8")
            val request = Request.Builder()
                .url(
                    "https://translate.yandex.net/api/v1/tr.json/translate?" +
                        "lang=zh-en&text=$encodedText&format=html",
                )
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body.string()

            if (!response.isSuccessful) {
                Log.e("TomatoMTL", "Yandex translate failed (${response.code}): $responseBody")
                return translateWithGoogle(content)
            }

            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val textArray = responseJson["text"]?.jsonArray

            return textArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.flatMap { it.split("\n") }
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n") { "<p>$it</p>" }
                ?: formatContent(content)
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Yandex translate error: ${e.message}")
            return translateWithGoogle(content) // Fallback to Google
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    // ======================== Preferences ========================

    private fun getTranslationMode(): String = preferences.getString(TRANSLATION_MODE_KEY, "bing") ?: "bing"

    private fun shouldCacheSources(): Boolean = preferences.getBoolean(CACHE_SOURCES_KEY, true)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TRANSLATION_MODE_KEY
            title = "Translation Service"
            entries = arrayOf(
                "Bing Translate (fast)",
                "Google Translate (fast)",
                "Google Translate 2 (fast)",
                "Gemini AI (under maintenance)",
                "Yandex Translate (fast)",
                "None (Raw Chinese)",
            )
            entryValues = arrayOf("bing", "google", "google2", "gemini", "yandex", "none")
            setDefaultValue("bing")
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = CACHE_SOURCES_KEY
            title = "Cache Sources List"
            summary = "When enabled, caches the garden sources list. " +
                "Disable to always fetch fresh data."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>()

        filters.add(Filter.Header("Search Filters (for keyword search)"))
        filters.add(SearchStatusFilter())
        filters.add(SearchWordCountFilter())
        filters.add(SearchSortFilter())

        filters.add(Filter.Separator())
        filters.add(Filter.Header("Category Browse (leave search empty)"))
        filters.add(CategoryFilter(getCategories()))
        filters.add(GenderFilter())
        filters.add(CreationStatusFilter())
        filters.add(WordCountFilterCategory())
        filters.add(SortFilterCategory())

        filters.add(Filter.Separator())
        filters.add(Filter.Header("Garden Source Browse"))

        val sources = getSources()
        if (sources.isNotEmpty()) {
            filters.add(SourceFilter(sources))
        } else {
            filters.add(Filter.Header("(Sources loading... refresh filters)"))
            // Trigger background refresh
            if (shouldCacheSources()) {
                Thread { refreshSources() }.start()
            }
        }

        return FilterList(filters)
    }

    private fun getSources(): List<Pair<String, String>> {
        val cached = preferences.getString(SOURCES_CACHE_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SourceItem>>(cached).map { Pair(it.id, it.name) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun refreshSources() {
        try {
            val response = client.newCall(GET("$gardenApiBase/sources", headers)).execute()
            val responseBody = response.body.string()
            val jsonResult = json.parseToJsonElement(responseBody).jsonObject

            val iv = jsonResult["iv"]?.jsonPrimitive?.contentOrNull
            val enc = jsonResult["enc"]?.jsonPrimitive?.contentOrNull

            if (iv != null && enc != null) {
                val decrypted = decryptContent(iv, enc)
                val result = json.parseToJsonElement(decrypted).jsonObject

                if (result["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true) {
                    val sourcesArray = result["data"]?.jsonArray
                    if (sourcesArray != null) {
                        val sources = sourcesArray.mapNotNull { element ->
                            val obj = element.jsonObject
                            val srcId = obj["id"]?.jsonPrimitive?.contentOrNull
                                ?: return@mapNotNull null
                            val srcName = obj["name"]?.jsonPrimitive?.contentOrNull ?: srcId
                            SourceItem(srcId, srcName)
                        }
                        preferences.edit()
                            .putString(SOURCES_CACHE_KEY, json.encodeToString(sources))
                            .apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TomatoMTL", "Error refreshing sources: ${e.message}")
        }
    }

    private fun getCategories(): List<Pair<String, String>> = listOf(
        Pair("", "Any"),
        Pair("1", "Urban"),
        Pair("7", "Fantasy"),
        Pair("23", "Farming"),
        Pair("12", "History"),
        Pair("538", "Doujin"),
        Pair("8", "Post-apocalyptic Sci-Fi"),
        Pair("259", "Fantasy Xianxia"),
        Pair("746", "Gaming And Sports"),
        Pair("100", "Supernatural"),
        Pair("27", "Warlord As A Live-In Son-in-Law"),
        Pair("26", "Divine Doctor"),
        Pair("10", "Suspense"),
        Pair("751", "Suspense And Supernatural"),
        Pair("37", "Transmigration"),
        Pair("36", "Rebirth"),
        Pair("262", "Urban Brainstorming"),
        Pair("124", "Urban Cultivation"),
        Pair("19", "System"),
        Pair("25", "Live-in Son-in-law"),
        Pair("20", "Divine Tycoon"),
        Pair("261", "Urban Daily Life"),
        Pair("522", "Face-Slapping Moments"),
        Pair("2", "Urban Life"),
        Pair("273", "Ancient History"),
        Pair("373", "Journey To The West Derivative"),
        Pair("90", "Genius"),
        Pair("71", "Myriad Worlds And Realms"),
        Pair("17", "Antique Appraisal"),
        Pair("263", "Urban Farming"),
        Pair("61", "Mystery And Deduction"),
        Pair("452", "Alternate History"),
        Pair("511", "Eastern Fantasy"),
        Pair("16", "Martial Arts"),
        Pair("375", "Special Forces"),
        Pair("81", "Tomb Raiding"),
        Pair("80", "Way Of The Sword"),
        Pair("44", "Space/Dimension"),
        Pair("384", "Invincible"),
        Pair("67", "Three Kingdoms"),
        Pair("75", "Food Delivery"),
        Pair("42", "Stay-at-Home Dad"),
        Pair("11", "Countryside"),
        Pair("68", "Apocalypse"),
        Pair("516", "Urban Superpowers"),
        Pair("40", "Islands"),
        Pair("39", "Anime/2D Culture"),
        Pair("66", "Prehistoric/Mythical Era"),
        Pair("379", "Survival"),
        Pair("15", "Sports"),
        Pair("91", "Multiple Heroines"),
        Pair("92", "Cunning And Manipulative"),
        Pair("389", "Single Female Heroine"),
        Pair("369", "Villain"),
        Pair("381", "Chat Groups"),
        Pair("382", "Transmigration Into A Book"),
        Pair("374", "Marvel"),
        Pair("368", "Naruto"),
        Pair("376", "Dragon Ball"),
        Pair("371", "Pokémon"),
        Pair("370", "Pirates"),
        Pair("367", "Ultraman Doujin"),
        Pair("465", "Comprehensive Anime"),
        Pair("718", "Anime Derivatives"),
    )

    companion object {
        private const val TRANSLATION_MODE_KEY = "translationMode"
        private const val CACHE_SOURCES_KEY = "cacheSources"
        private const val SOURCES_CACHE_KEY = "sourcesCache"
    }
}

private class SearchStatusFilter :
    SelectFilter(
        "Update Status",
        arrayOf(
            Pair("Any", ""),
            Pair("Completed", "completed"),
            Pair("Completed within 6 months", "completed_6m"),
            Pair("Ongoing", "ongoing"),
            Pair("Updated within 3 days", "3day"),
            Pair("Updated within 7 days", "7day"),
            Pair("Updated within 1 month", "1month"),
        ),
    )

private class SearchWordCountFilter :
    SelectFilter(
        "Word Count",
        arrayOf(
            Pair("Any", ""),
            Pair("≤ 100K words", "lte10"),
            Pair("≤ 300K words", "lte30"),
            Pair("≤ 500K words", "lte50"),
            Pair("≥ 300K words", "gte30"),
            Pair("≥ 500K words", "gte50"),
            Pair("≥ 1M words", "gte100"),
            Pair("≥ 2M words", "gte200"),
            Pair("≥ 3M words", "gte300"),
            Pair("≥ 5M words", "gte500"),
        ),
    )

private class SearchSortFilter :
    SelectFilter(
        "Sort Order",
        arrayOf(
            Pair("Default", ""),
            Pair("Newly Created", "new_book"),
            Pair("High Rating", "score"),
            Pair("Word Count", "word_number"),
            Pair("New Update", "new_update"),
        ),
    )

// Category Browse Filters
private class CategoryFilter(categories: List<Pair<String, String>>) :
    SelectFilter(
        "Category",
        categories.map { Pair(it.second, it.first) }.toTypedArray(),
    )

private class GenderFilter :
    SelectFilter(
        "Gender",
        arrayOf(
            Pair("Male", "1"),
            Pair("Female", "0"),
        ),
    )

private class CreationStatusFilter :
    SelectFilter(
        "Creation Status",
        arrayOf(
            Pair("All", "0"),
            Pair("Completed", "2"),
            Pair("Ongoing", "1"),
        ),
    )

private class WordCountFilterCategory :
    SelectFilter(
        "Word Count",
        arrayOf(
            Pair("All", "0"),
        ),
    )

private class SortFilterCategory :
    SelectFilter(
        "Sort By",
        arrayOf(
            Pair("Default", "0"),
            Pair("New Books", "1"),
            Pair("High Rating", "2"),
            Pair("Word Count", "3"),
        ),
    )

private class SourceFilter(sources: List<Pair<String, String>>) :
    SelectFilter(
        "Garden Source",
        (listOf(Pair("All Sources", "")) + sources.map { Pair(it.second, it.first) }).toTypedArray(),
    )

private open class SelectFilter(
    displayName: String,
    val options: Array<Pair<String, String>>,
    defaultIndex: Int = 0,
) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray(), defaultIndex) {
    fun toUriPart(): String = options[state].second
}
