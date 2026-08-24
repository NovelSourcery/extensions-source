package eu.kanade.tachiyomi.novelextension.en.novelbuddy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.formattedText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.time.Instant

/**
 * NovelBuddy (novelbuddy.me). Ported from the LNReader plugin: listings come from the
 * api.novelbuddy.me titles/search API, while novel details and chapter content live in the
 * page's __NEXT_DATA__ JSON with a matching titles/{id}/chapters API for the full list.
 */
class NovelBuddy :
    HttpSource(),
    NovelSource {

    override val name = "NovelBuddy"
    override val baseUrl = "https://novelbuddy.me"
    private val apiUrl = "https://api.novelbuddy.me"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.cloudflareClient

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl
        }
        return "$baseUrl/${pathOrUrl.trimStart('/')}"
    }

    // Browse

    override fun popularMangaRequest(page: Int): Request = searchApiRequest(page, sort = "views")

    override fun popularMangaParse(response: Response): MangasPage = parseApiResponse(response)

    override fun latestUpdatesRequest(page: Int): Request = searchApiRequest(page, sort = "latest")

    override fun latestUpdatesParse(response: Response): MangasPage = parseApiResponse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder()
        if (query.isNotBlank()) url.addQueryParameter("q", query)

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> url.addQueryParameter("sort", filter.toUriPart())
                is StatusFilter -> if (filter.toUriPart() != "all") url.addQueryParameter("status", filter.toUriPart())
                is GenreFilter -> {
                    val included = filter.state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }
                    val excluded = filter.state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }
                    if (included.isNotEmpty()) url.addQueryParameter("genres", included.joinToString(","))
                    if (excluded.isNotEmpty()) url.addQueryParameter("exclude", excluded.joinToString(","))
                }
                is MinChaptersFilter -> parseChapterCount(filter.state)?.let { url.addQueryParameter("min_ch", it) }
                is MaxChaptersFilter -> parseChapterCount(filter.state)?.let { url.addQueryParameter("max_ch", it) }
                is DemoFilter -> {
                    val demos = filter.state.filter { it.state }.map { it.value }
                    if (demos.isNotEmpty()) url.addQueryParameter("demographic", demos.joinToString(","))
                }
                else -> {}
            }
        }

        url.addQueryParameter("limit", "24")
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseApiResponse(response)

    private fun searchApiRequest(page: Int, sort: String): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("limit", "24")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    private fun parseChapterCount(value: String): String? {
        val n = value.trim().toIntOrNull() ?: return null
        return if (n in 0..10000) n.toString() else null
    }

    private fun parseApiResponse(response: Response): MangasPage = try {
        val items = json.parseToJsonElement(response.body.string())
            .jsonObject["data"]?.jsonObject?.get("items")?.jsonArray
            ?: return MangasPage(emptyList(), false)

        val mangas = items.mapNotNull { item ->
            val obj = item.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SManga.create().apply {
                title = name
                this.url = url.trimStart('/')
                thumbnail_url = obj["cover"]?.jsonPrimitive?.contentOrNull
                    ?.let { if (it.startsWith("//")) "https:$it" else it }
            }
        }
        MangasPage(mangas, items.size >= 24)
    } catch (e: Exception) {
        MangasPage(emptyList(), false)
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request = GET(buildUrl(manga.url), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val script = Jsoup.parse(response.body.string()).selectFirst("#__NEXT_DATA__")?.html()
            ?: return SManga.create().apply { title = "Untitled" }
        val initialManga = script.initialManga()
            ?: return SManga.create().apply { title = "Untitled" }

        return SManga.create().apply {
            title = initialManga["name"]?.jsonPrimitive?.contentOrNull ?: "Untitled"
            thumbnail_url = initialManga["cover"]?.jsonPrimitive?.contentOrNull
            author = initialManga.names("authors")
            artist = initialManga.names("artists")
            genre = initialManga.names("genres")

            status = when (initialManga["status"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            val summary = initialManga["summary"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { Jsoup.parseBodyFragment(it).body().formattedText() }
                .orEmpty()
            val rating = initialManga["ratingStats"]?.jsonObject?.get("average")?.jsonPrimitive?.contentOrNull
            description = buildString {
                rating?.let { appendLine("Rating: $it") }
                if (isNotEmpty()) appendLine()
                append(summary)
            }.trim()
        }
    }

    private fun String.initialManga(): JsonObject? = runCatching {
        json.parseToJsonElement(this)
            .jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("initialManga")?.jsonObject
    }.getOrNull()

    private fun JsonObject.names(key: String): String = this[key]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        ?.joinToString(", ").orEmpty()

    // Chapters

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val script = Jsoup.parse(response.body.string()).selectFirst("#__NEXT_DATA__")?.html()
            ?: return emptyList()
        val initialManga = script.initialManga() ?: return emptyList()

        val mangaId = initialManga["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val cv = (initialManga["content_version"] ?: initialManga["cv"])?.jsonPrimitive?.contentOrNull

        // Primary: the full chapter list from the API, keyed by content version when present.
        val chaptersApi = "$apiUrl/titles/$mangaId/chapters".toHttpUrl().newBuilder()
            .apply { if (!cv.isNullOrBlank()) addQueryParameter("cv", cv) }
            .build()
        val apiChapters = runCatching {
            json.parseToJsonElement(client.newCall(GET(chaptersApi, headers)).execute().body.string())
                .jsonObject["data"]?.jsonObject?.get("chapters")?.jsonArray
        }.getOrNull()

        if (!apiChapters.isNullOrEmpty()) {
            return apiChapters.mapNotNull { item ->
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val chapterId = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                SChapter.create().apply {
                    this.name = name
                    this.url = url.trimStart('/') + "?id=$mangaId&chapterId=$chapterId"
                    date_upload = parseDate(obj["updated_at"]?.jsonPrimitive?.contentOrNull)
                }
            }
            // The API already returns newest-first, which is the order the reader expects.
        }

        // Fallback: chapters embedded in the initialManga payload.
        return initialManga["chapters"]?.jsonArray?.mapNotNull { item ->
            val obj = item.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SChapter.create().apply {
                this.name = name
                this.url = url.trimStart('/')
                date_upload = parseDate(obj["updatedAt"]?.jsonPrimitive?.contentOrNull)
            }
        }.orEmpty()
    }

    override fun getChapterUrl(chapter: SChapter): String = buildUrl(chapter.url.substringBefore('?'))

    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(date).toEpochMilli() }.getOrDefault(0L)
    }

    // Content

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val mangaId = Regex("[?&]id=([^&]+)").find(page.url)?.groupValues?.get(1)
        val chapterId = Regex("[?&]chapterId=([^&]+)").find(page.url)?.groupValues?.get(1)

        var content = ""
        if (mangaId != null && chapterId != null) {
            content = runCatching {
                val apiResponse = client.newCall(GET("$apiUrl/titles/$mangaId/chapters/$chapterId", headers)).execute()
                json.parseToJsonElement(apiResponse.body.string())
                    .jsonObject["data"]?.jsonObject?.get("chapter")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            }.getOrDefault("")
        }

        if (content.isBlank()) {
            val document = Jsoup.parse(client.newCall(GET(buildUrl(page.url), headers)).execute().body.string())
            val script = document.selectFirst("#__NEXT_DATA__")?.html()
            content = if (script != null) {
                runCatching {
                    json.parseToJsonElement(script)
                        .jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                        ?.get("initialChapter")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                }.getOrDefault("")
            } else {
                document.selectFirst(".chapter__content")?.also {
                    it.select("#listen-chapter, #google_translate_element").remove()
                }?.html().orEmpty()
            }
        }

        return cleanContent(content)
    }

    private fun cleanContent(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(
                Regex(
                    "Find authorized novels in Webnovel.*?faster updates, better experience.*?Please click www\\.webnovel\\.com for visiting\\.",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ),
                "",
            )
            .replace(FWN_REGEX, "")
    }

    // Filters

    override fun getFilterList() = FilterList(
        OrderByFilter(),
        Filter.Separator(),
        StatusFilter(),
        Filter.Separator(),
        GenreFilter(),
        Filter.Separator(),
        MinChaptersFilter(),
        MaxChaptersFilter(),
        Filter.Separator(),
        DemoFilter(),
    )

    private class OrderByFilter : Filter.Select<String>("Order By", arrayOf("Views", "Latest", "Popular", "A-Z", "Rating", "Chapters")) {
        fun toUriPart() = when (state) {
            0 -> "views"
            1 -> "latest"
            2 -> "popular"
            3 -> "alphabetical"
            4 -> "rating"
            5 -> "chapters"
            else -> "views"
        }
    }

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed", "Hiatus", "Cancelled")) {
        fun toUriPart() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            4 -> "cancelled"
            else -> "all"
        }
    }

    private class GenreCheckbox(name: String, val value: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreCheckbox>(
            "Genres (OR, not AND)",
            GENRES.map { GenreCheckbox(it.first, it.second) },
        )

    private class MinChaptersFilter : Filter.Text("Minimum Chapters", "")
    private class MaxChaptersFilter : Filter.Text("Maximum Chapters", "")

    private class DemoCheckbox(name: String, val value: String) : Filter.CheckBox(name)

    private class DemoFilter :
        Filter.Group<DemoCheckbox>(
            "Demographics",
            listOf(
                DemoCheckbox("Shounen", "shounen"),
                DemoCheckbox("Shoujo", "shoujo"),
                DemoCheckbox("Seinen", "seinen"),
                DemoCheckbox("Josei", "josei"),
            ),
        )

    companion object {
        private val FWN_REGEX = Regex("""(?:𝐟|ᵮ|𝑓|𝒇|𝒻|𝓯|𝔣|𝕗|𝖿|𝗳|𝙛|𝚏|ꬵ|ꞙ|ẝ|𝖋|ⓕ|ｆ|ḟ|ʃ|բ|ᶠ|⒡|ſ|ꊰ|ʄ|∱|ᶂ|𝘧|\bf)(?:𝚛|ꭇ|ᣴ|ℾ|𝚪|𝛤|𝜞|𝝘|𝞒|Ⲅ|Г|Ꮁ|ᒥ|ꭈ|ⲅ|ꮁ|ⓡ|ｒ|ŕ|ṙ|ř|ȑ|ȓ|ṛ|ṝ|ŗ|г|Ր|ɾ|ᥬ|ṟ|ɍ|ʳ|⒭|ɼ|ѓ|ᴦ|ᶉ|𝐫|𝑟|𝒓|𝓇|𝓻|𝔯|𝕣|𝖗|𝗋|𝗿|𝘳|𝙧|ᵲ|ґ|ᵣ|r)(?:ə|ә|ⅇ|ꬲ|ꞓ|⋴|𝛆|𝛜|𝜀|𝜖|𝜺|𝝐|𝝴|𝞊|𝞮|𝟄|ⲉ|ꮛ|𐐩|Ꞓ|Ⲉ|⍷|𝑒|𝓮|𝕖|𝖊|𝘦|𝗲|𝚎|𝙚|𝒆|𝔢|𝖾|𝐞|Ҿ|ҿ|ⓔ|ｅ|⒠|è|ᧉ|é|ᶒ|ê|ɘ|ἔ|ề|ế|ễ|૯|ǝ|є|ε|ē|ҽ|ɛ|ể|ẽ|ḕ|ḗ|ĕ|ė|ë|ẻ|ě|ȅ|ȇ|ẹ|ệ|ȩ|ɇ|ₑ|ę|ḝ|ḙ|ḛ|℮|е|ԑ|ѐ|ӗ|ᥱ|ё|ἐ|ἑ|ἒ|ἓ|ἕ|ℯ|e)+(?:𝐰|ꝡ|𝑤|𝒘|𝓌|𝔀|𝔴|𝕨|𝖜|𝗐|𝘄|𝘸|𝙬|𝚠|ա|ẁ|ꮃ|ẃ|ⓦ|⍵|ŵ|ẇ|ẅ|ẘ|ẉ|ⱳ|ὼ|ὠ|ὡ|ὢ|ὣ|ω|ὤ|ὥ|ὦ|ὧ|ῲ|ῳ|ῴ|ῶ|ῷ|Ⱳ|ѡ|ԝ|ᴡ|ώ|ᾠ|ᾡ|ᾡ|ᾢ|ᾣ|ᾤ|ᾥ|ᾦ|ɯ|𝝕|𝟉|𝞏|w)(?:ə|ә|ⅇ|ꬲ|ꞓ|⋴|𝛆|𝛜|𝜀|𝜖|𝜺|𝝐|𝝴|𝞊|𝞮|𝟄|ⲉ|ꮛ|𐐩|Ꞓ|Ⲉ|⍷|𝑒|𝓮|𝕖|𝖊|𝘦|𝗲|𝚎|𝙚|𝒆|𝔢|𝖾|𝐞|Ҿ|ҿ|ⓔ|ｅ|⒠|è|ᧉ|é|ᶒ|ê|ɘ|ἔ|ề|ế|ễ|૯|ǝ|є|ε|ē|ҽ|ɛ|ể|ẽ|ḕ|ḗ|ĕ|ė|ë|ẻ|ě|ȅ|ȇ|ẹ|ệ|ȩ|ɇ|ₑ|ę|ḝ|ḙ|ḛ|℮|е|ԑ|ѐ|ӗ|ᥱ|ё|ἐ|ἑ|ἒ|ἓ|ἕ|ℯ|e)(?:ꮟ|Ꮟ|𝐛|𝘣|𝒷|𝔟|𝓫|𝖇|𝖻|𝑏|𝙗|𝕓|𝒃|𝗯|𝚋|♭|ᑳ|ᒈ|ｂ|ᖚ|ᕹ|ᕺ|ⓑ|ḃ|ḅ|ҍ|ъ|ḇ|ƃ|ɓ|ƅ|ᖯ|Ƅ|Ь|ᑲ|þ|Ƃ|⒝|Ъ|ᶀ|ᑿ|ᒀ|ᒂ|ᒁ|ᑾ|ь|ƀ|Ҍ|Ѣ|ѣ|ᔎ |b)(?:ո|ռ|ח|𝒏|𝓷|𝙣|𝑛|𝖓|𝔫|𝗇|𝚗|𝗻|ᥒ|ⓝ|ή|ｎ|ǹ|ᴒ|ń|ñ|ᾗ|η|ṅ|ň|ṇ|ɲ|ņ|ṋ|ṉ|ղ|ຖ|Ռ|ƞ|ŋ|⒩|ภ|ก|ɳ|п|ŉ|л|ԉ|Ƞ|ἠ|ἡ|ῃ|դ|ᾐ|ᾑ|ᾒ|ᾓ|ᾔ|ᾕ|ᾖ|ῄ|ῆ|ῇ|ῂ|ἢ|ἣ|ἤ|ἥ|ἦ|ἧ|ὴ|ή|በ|ቡ|ቢ|ባ|ቤ|ብ|ቦ|ȵ|𝛈|𝜂|𝜼|𝝶|𝞰|𝕟|延|𝐧|𝔫|ᶇ|ᵰ|ᥥ|∩|n)(?:ం|ం|ം|ං|૦|௦|۵|ℴ|𝑜|𝒐|𝒐|ꬽ|𝝄|𝛔|𝜎|𝝈|𝞂|ჿ|𝚘|০|୦|ዐ|𝛐|𝗈|𝞼|ဝ|ⲟ|𝙤|၀|𐐬|𝔬|𐓪|𝓸|🇴|⍤|○|ϙ|🅾|𝒪|𝖮|𝟢|𝟶|𝙾|o|𝗼|𝕠|𝜊|𝐨|𝝾|𝞸|ᐤ|ｵ|ѳ|᧐|ᥲ|ð|ｏ|ఠ|ᦞ|Փ|ò|ө|ӧ|ó|º|ō|ô|ǒ|ȏ|ŏ|ồ|ȭ|ṏ|ὄ|ṑ|ṓ|ȯ|ȫ|๏|ᴏ|ő|ö|ѻ|о|ዐ|ǭ|ȱ|০|୦|٥|౦|告知|๐|໐|ο|օ|ᴑ|०|੦|ỏ|ơ|ờ|ớ|ỡ|ở|ợ|ọ|ộ|ǫ|ø|ǿ|ɵ|ծ|ὀ|ὁ|ό|ὸ|ό|ὂ|ὃ|ὅ|o)(?:∨|⌄|\|ⅴ|𝐯|𝑣|𝒗|𝓋|𝔳|𝕧|𝖛|ꮩ|ሀ|ⓥ|ｖ|𝜐|𝝊|ṽ|ṿ|౮|ง|ѵ|ע|ᴠ|ν|ט|ᵥ|ѷ|៴|ᘁ|𝙫|𝙫|𝛎|𝜈|𝝂|𝝼|𝞶|𝘷|𝘃|𝓿|v)(?:ə|ә|ⅇ|ꬲ|ꞓ|⋴|𝛆|𝛜|𝜀|𝜖|𝜺|𝝐|𝝴|𝞊|𝞮|𝟄|ⲉ|ꮛ|𐐩|Ꞓ|Ⲉ|⍷|𝑒|𝓮|𝕖|𝖊|𝘦|𝗲|𝚎|𝙚|𝒆|𝔢|𝖾|𝐞|Ҿ|ҿ|ⓔ|ｅ|⒠|è|ᧉ|é|ᶒ|ê|ɘ|ἔ|ề|ế|ễ|૯|ǝ|є|ε|ē|ҽ|ɛ|ể|ẽ|ḕ|ḗ|ĕ|ė|ë|ẻ|ě|ȅ|ȇ|ẹ|ệ|ȩ|ɇ|ę|ḝ|ḙ|ḛ|℮|е|ԑ|ѐ|ӗ|ᥱ|ё|ἐ|ἑ|ἒ|ἓ|ἕ|ℯ|e)(?:ⓛ|ｌ|ŀ|ĺ|ľ|ḷ|ḹ|ḷ|ļ|Ӏ|ℓ|ḽ|ḻ|ł|ﾚ|ɭ|ƚ|ɫ|ⱡ|\||\\|Ɩ|⒧|ʅ|ǀ|ו|ן|Ι|І|｜|ᶩ|ӏ|𝓘|𝕀|𝖨|𝗜|𝘐|𝐥|𝑙|𝒍|𝓁|𝔩|𝕝|𝖑|ލ|𝗅|𝗹|ލ|𝗅|𝗹|𝘭|𝚕|𝜤|𝝞|ı|𝚤|ɩ|ι|𝛊|𝜄|𝜾|𝞲|I|l)(?:.?(?:🝌|ｃ|ⅽ|𝐜|𝑐|𝒄|𝒸|𝓬|𝔠|𝕔|𝖈|𝖈|𝗰|𝘤|𝙘|𝚌|ᴄ|ϲ|ⲥ|с|ꮯ|𐐽|ⲥ|𐐽|ꮯ|ĉ|ｃ|ⓒ|ć|č|ċ|ç|ҁ|ƈ|ḉ|ȼ|ↄ|с|ር|ᴄ|ϲ|ҫ|꒝|ς|ɽ|ϛ|𝙲|ᑦ|᧚|𝐜|𝑐|𝒄|𝒸|𝓬|𝔠|𝕔|𝖈|𝖈|𝗰|𝘤|𝙘|𝚌|₵|🇨|ᥴ|ᒼ|ⅽ|c)(?:ం|ం|ം|ං|૦|௦|۵|ℴ|𝑜|𝒐|𝒐|ꬽ|𝝄|𝛔|𝜎|𝝈|𝞂|ჿ|𝚘|০|୦|ዐ|𝗈|𝞼|ဝ|ⲟ|𝙤|၀|𐐬|𝔬|𐓪|𝓸|🇴|⍤|○|ϙ|🅾|𝒪|𝖮|𝟢|𝟶|𝙾|o|𝗼|𝕠|𝜊|𝐨|𝝾|𝞸|ᐤ|ⓞ|ѳ|᧐|ᥲ|ð|ｏ|ఠ|ᦞ|Փ|ò|ө|ӧ|ó|º|ō|ô|ǒ|ȏ|ŏ|ồ|ȭ|ṏ|ὄ|ṑ|ṓ|ȯ|ȫ|๏|ᴏ|ő|ö|ѻ|о|ዐ|ǭ|ȱ|০|୦|٥|౦|告知|๐|໐|ο|օ|ᴑ|०|੦|ỏ|ơ|ờ|ớ|ỡ|ở|ợ|ọ|ộ|ǫ|ø|ǿ|ɵ|ծ|ὀ|ὁ|ό|ὸ|ό|ὂ|ὃ|ὅ|o)(?:₥|ᵯ|𝖒|𝐦|𝗆|𝔪|𝕞|𝕞|𝕞|ⓜ|ｍ|ന|ᙢ|൩|ḿ|ṁ|ⅿ|ϻ|ṃ|ጠ|ɱ|៳|ᶆ|𝒎|🇲|𝙢|𝓶|𝚖|𝑚|𝗺|᧕|᧗|m))?""")

        private val GENRES = listOf(
            "Action" to "action",
            "Adult" to "adult",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Eastern" to "eastern",
            "Ecchi" to "ecchi",
            "Fan-Fiction" to "fan-fiction",
            "Fantasy" to "fantasy",
            "Game" to "game",
            "Gender Bender" to "gender-bender",
            "Harem" to "harem",
            "Historical" to "historical",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "Lolicon" to "lolicon",
            "Magic" to "magic",
            "Martial Arts" to "martial-arts",
            "Mature" to "mature",
            "Mecha" to "mecha",
            "Military" to "military",
            "Modern Life" to "modern-life",
            "Mystery" to "mystery",
            "Psychological" to "psychological",
            "Reincarnation" to "reincarnation",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shoujo Ai" to "shoujo-ai",
            "Shounen" to "shounen",
            "Shounen Ai" to "shounen-ai",
            "Slice of Life" to "slice-of-life",
            "Smut" to "smut",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
            "System" to "system",
            "Thriller" to "thriller",
            "Tragedy" to "tragedy",
            "Urban" to "urban",
            "Urban Life" to "urban-life",
            "Wuxia" to "wuxia",
            "Xianxia" to "xianxia",
            "Xuanhuan" to "xuanhuan",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
        )
    }
}
