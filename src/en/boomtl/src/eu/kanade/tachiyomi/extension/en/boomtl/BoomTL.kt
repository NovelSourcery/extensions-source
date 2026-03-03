package eu.kanade.tachiyomi.extension.en.boomtl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * BoomTL extension for reading novels from boomtl.com
 *
 * This site uses an unusual JSON format with q-data.json files.
 * The format uses base-36 encoded indices to reference data in a flat _objs array.
 */
class BoomTL :
    HttpSource(),
    NovelSource {

    override val name = "BoomTL"
    override val baseUrl = "https://boomtl.com"
    override val lang = "en"
    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    /**
     * Decode a base-36 reference string to an integer index.
     * Examples: "2" -> 2, "a" -> 10, "z" -> 35, "10" -> 36, "1a" -> 46, "1b" -> 47
     */
    private fun decodeRef(ref: String): Int {
        var result = 0
        for (char in ref) {
            val value = when {
                char.isDigit() -> char - '0'
                char.isLetter() -> char.lowercaseChar() - 'a' + 10
                else -> 0
            }
            result = result * 36 + value
        }
        return result
    }

    /**
     * Resolve a reference to get the actual value from _objs array.
     * The reference can be a string index into the _objs array.
     */
    private fun resolveRef(ref: String, objs: JsonArray): JsonElement? {
        val index = decodeRef(ref)
        return objs.getOrNull(index)
    }
    private fun getString(refObj: JsonObject, key: String, objs: JsonArray): String? {
        val value = refObj[key] ?: return null

        return when (value) {
            is JsonPrimitive -> {
                val content = value.contentOrNull
                if (content != null && content.startsWith('\u0006')) {
                    content.substring(1)
                } else if (content != null && content.matches(Regex("[0-9a-zA-Z]+"))) {
                    val resolved = resolveRef(content, objs)
                    when (resolved) {
                        is JsonPrimitive -> {
                            val resolvedContent = resolved.contentOrNull
                            if (resolvedContent != null && resolvedContent.startsWith('\u0006')) {
                                resolvedContent.substring(1)
                            } else {
                                resolvedContent
                            }
                        }

                        else -> null
                    }
                } else {
                    content
                }
            }

            else -> null
        }
    }
    private fun getInt(refObj: JsonObject, key: String, objs: JsonArray): Int? {
        val ref = refObj[key]?.jsonPrimitive?.contentOrNull ?: return null
        val resolved = resolveRef(ref, objs) ?: return null
        return when (resolved) {
            is JsonPrimitive -> resolved.intOrNull
            else -> null
        }
    }
    private fun getDouble(refObj: JsonObject, key: String, objs: JsonArray): Double? {
        val ref = refObj[key]?.jsonPrimitive?.contentOrNull ?: return null
        val resolved = resolveRef(ref, objs) ?: return null
        return when (resolved) {
            is JsonPrimitive -> resolved.doubleOrNull
            else -> null
        }
    }
    private fun getBoolean(refObj: JsonObject, key: String, objs: JsonArray): Boolean? {
        val ref = refObj[key]?.jsonPrimitive?.contentOrNull ?: return null
        val resolved = resolveRef(ref, objs) ?: return null
        return when (resolved) {
            is JsonPrimitive -> resolved.booleanOrNull
            else -> null
        }
    }
    private fun getArray(refObj: JsonObject, key: String, objs: JsonArray): JsonArray? {
        val ref = refObj[key]?.jsonPrimitive?.contentOrNull ?: return null
        val resolved = resolveRef(ref, objs) ?: return null
        return when (resolved) {
            is JsonArray -> resolved
            else -> null
        }
    }
    private fun getObject(refObj: JsonObject, key: String, objs: JsonArray): JsonObject? {
        val ref = refObj[key]?.jsonPrimitive?.contentOrNull ?: return null
        val resolved = resolveRef(ref, objs) ?: return null
        return when (resolved) {
            is JsonObject -> resolved
            else -> null
        }
    }

    /**
     * Parse the special q-data.json format.
     * Returns the entry object and the _objs array.
     */
    private fun parseQData(responseBody: String): Pair<JsonObject?, JsonArray> {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val objs = root["_objs"]?.jsonArray ?: JsonArray(emptyList())
        val entryRef = root["_entry"]?.jsonPrimitive?.contentOrNull

        val entryObj = if (entryRef != null) {
            val index = decodeRef(entryRef)
            objs.getOrNull(index)?.jsonObject
        } else {
            null
        }

        return Pair(entryObj, objs)
    }

    override fun popularMangaRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/popular/q-data.json", headers)
    } else {
        // POST request for pagination with proper headers
        val body = """{"_entry":"2","_objs":["\u0002_#s_BFnMU0fpdeM",$page,["0","1"]]}"""
        val postHeaders = headers.newBuilder()
            .add("Content-Type", "application/qwik-json")
            .add("Accept", "*/*")
            .add("X-QRL", "BFnMU0fpdeM")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/popular/")
            .build()
        POST(
            "$baseUrl/popular/?qfunc=BFnMU0fpdeM",
            postHeaders,
            body.toRequestBody("application/qwik-json".toMediaType()),
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val responseBody = response.body.string()

        if (responseBody.trimStart().startsWith("<!") || responseBody.trimStart().startsWith("<html")) {
            // Server returned HTML, likely an error page
            return MangasPage(emptyList(), false)
        }

        val (entryObj, objs) = parseQData(responseBody)

        val novels = mutableListOf<SManga>()

        if (entryObj != null) {
            findNovelsInEntry(entryObj, objs).forEach { novel ->
                novels.add(novel)
            }
        }

        for (i in 0 until objs.size) {
            val element = objs.getOrNull(i) ?: continue
            if (element is JsonObject) {
                val hasTitle = element["title"] != null
                val hasSlug = element["slug"] != null
                val hasPoster = element["poster"] != null

                var isNovelObject = false
                if (hasTitle || hasSlug || hasPoster) {
                    for ((key, value) in element) {
                        if ((key == "title" || key == "slug" || key == "poster") &&
                            value is JsonPrimitive
                        ) {
                            val content = value.contentOrNull
                            if (content != null && !content.matches(Regex("[0-9a-zA-Z]+"))) {
                                isNovelObject = true
                                break
                            } else if (content != null && content.matches(Regex("[0-9a-zA-Z]+"))) {
                                val resolved = resolveRef(content, objs)
                                if (resolved is JsonPrimitive &&
                                    resolved.contentOrNull?.startsWith('\u0006') == true
                                ) {
                                    isNovelObject = true
                                    break
                                }
                            }
                        }
                    }
                }

                if (isNovelObject) {
                    val novel = parseNovelFromRefObj(element, objs)
                    if (novel != null && novels.none { it.url == novel.url }) {
                        novels.add(novel)
                    }
                }
            }
        }

        return MangasPage(novels, novels.isNotEmpty())
    }

    private fun findNovelsInEntry(entryObj: JsonObject, objs: JsonArray): List<SManga> {
        val novels = mutableListOf<SManga>()

        val dataRef = entryObj["data"]?.jsonPrimitive?.contentOrNull
        if (dataRef != null) {
            val dataObj = resolveRef(dataRef, objs)?.jsonObject
            if (dataObj != null) {
                val novelsRef = dataObj["novels"]?.jsonPrimitive?.contentOrNull
                if (novelsRef != null) {
                    val novelsArray = resolveRef(novelsRef, objs)?.jsonArray
                    novelsArray?.forEach { item ->
                        when (item) {
                            is JsonPrimitive -> {
                                val novelObj = resolveRef(item.content, objs)?.jsonObject
                                if (novelObj != null) {
                                    val novel = parseNovelFromRefObj(novelObj, objs)
                                    if (novel != null) novels.add(novel)
                                }
                            }

                            is JsonObject -> {
                                val novel = parseNovelFromRefObj(item, objs)
                                if (novel != null) novels.add(novel)
                            }

                            else -> {
                            }
                        }
                    }
                }
            }
        }

        return novels
    }

    private fun parseNovelFromRefObj(refObj: JsonObject, objs: JsonArray): SManga? {
        var title = getString(refObj, "title", objs)

        if (title == null) {
            for ((key, value) in refObj) {
                if (key == "title" && value is JsonPrimitive) {
                    val content = value.contentOrNull
                    if (content != null && content.startsWith('\u0006')) {
                        title = content.substring(1)
                    } else {
                        title = content
                    }
                    break
                }
            }
        }

        if (title == null) {
            title = getString(refObj, "name", objs)
        }

        if (title == null) return null

        var slug = getString(refObj, "slug", objs)
        if (slug == null) {
            for ((key, value) in refObj) {
                if (key == "slug" && value is JsonPrimitive) {
                    val content = value.contentOrNull
                    if (content != null && content.startsWith('\u0006')) {
                        slug = content.substring(1)
                    } else {
                        slug = content
                    }
                    break
                }
            }
        }

        if (slug == null) {
            slug = title.trim()
                .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
                .replace(Regex("\\s+"), "-")
                .lowercase()
        }

        val poster = getString(refObj, "poster", objs)
            ?: getString(refObj, "cover", objs)
            ?: getString(refObj, "thumbnail", objs)
            ?: getString(refObj, "image", objs)

        val synopsis = getString(refObj, "synopsis", objs)
            ?: getString(refObj, "description", objs)
            ?: getString(refObj, "summary", objs)

        val alter = getString(refObj, "alter", objs)
            ?: getString(refObj, "alternative", objs)
            ?: getString(refObj, "alternative_title", objs)

        val status = getString(refObj, "status", objs)
        val language = getString(refObj, "language", objs)
        val type = getString(refObj, "type", objs)
        val author = getString(refObj, "author", objs)

        val tags = mutableListOf<String>()

        val genresArray = getArray(refObj, "genres", objs)
        if (genresArray != null) {
            for (genreRef in genresArray) {
                if (genreRef is JsonPrimitive) {
                    val genreObj = resolveRef(genreRef.content, objs)?.jsonObject
                    if (genreObj != null) {
                        val genreName = getString(genreObj, "name", objs)
                        if (!genreName.isNullOrBlank()) {
                            tags.add(genreName)
                        }
                    }
                }
            }
        }

        val taxonomiesRef = refObj["taxonomies"]?.jsonPrimitive?.contentOrNull
        if (taxonomiesRef != null) {
            val taxonomiesObj = resolveRef(taxonomiesRef, objs)?.jsonObject
            if (taxonomiesObj != null) {
                for ((key, value) in taxonomiesObj) {
                    if (value is JsonPrimitive) {
                        val taxonomyArray = resolveRef(value.content, objs)?.jsonArray
                        taxonomyArray?.forEach { taxRef ->
                            if (taxRef is JsonPrimitive) {
                                val taxObj = resolveRef(taxRef.content, objs)?.jsonObject
                                if (taxObj != null) {
                                    val taxName = getString(taxObj, "name", objs)
                                    if (!taxName.isNullOrBlank()) {
                                        tags.add(taxName)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return SManga.create().apply {
            url = "/novel/$slug/"
            this.title = title
            thumbnail_url = poster

            description = buildString {
                if (!alter.isNullOrBlank() && alter != title) {
                    appendLine("Alternative Title: $alter")
                    appendLine()
                }
                if (!author.isNullOrBlank()) {
                    appendLine("Author: $author")
                    appendLine()
                }
                if (!synopsis.isNullOrBlank()) {
                    append(synopsis)
                }
            }

            this.status = when (status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            genre = buildList {
                if (!type.isNullOrBlank()) add(type)
                if (!language.isNullOrBlank()) add(language)
                addAll(tags)
            }.joinToString(", ")
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/novel-list/q-data.json", headers)
    } else {
        // POST request for pagination with proper headers
        val body = """{"_entry":"2","_objs":["\u0002_#s_07iTlk4gnHs",$page,["0","1"]]}"""
        val postHeaders = headers.newBuilder()
            .add("Content-Type", "application/qwik-json")
            .add("Accept", "*/*")
            .add("X-QRL", "07iTlk4gnHs")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/novel-list/")
            .build()
        POST(
            "$baseUrl/novel-list/?qfunc=07iTlk4gnHs",
            postHeaders,
            body.toRequestBody("application/qwik-json".toMediaType()),
        )
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = query.trim().replace(" ", "-").lowercase()
        return if (page == 1) {
            GET("$baseUrl/search/$encodedQuery/q-data.json", headers)
        } else {
            // POST request for search pagination with proper headers
            val body = """{"_entry":"2","_objs":["\u0002_#s_HbSA6nELNQs",$page,["0","1"]]}"""
            val postHeaders = headers.newBuilder()
                .add("Content-Type", "application/qwik-json")
                .add("Accept", "*/*")
                .add("X-QRL", "HbSA6nELNQs")
                .add("Origin", baseUrl)
                .add("Referer", "$baseUrl/search/$encodedQuery/")
                .build()
            POST(
                "$baseUrl/search/$encodedQuery/?qfunc=HbSA6nELNQs",
                postHeaders,
                body.toRequestBody("application/qwik-json".toMediaType()),
            )
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = extractSlug(manga.url)
        return GET("$baseUrl/novel/$slug/q-data.json", headers)
    }

    /**
     * Extract the novel slug from a URL, handling various URL formats.
     * Handles: /novel/slug/, /novel/slug, https://boomtl.com/novel/slug/, etc.
     */
    private fun extractSlug(url: String): String {
        var path = url.removePrefix(baseUrl).removePrefix("/")

        path = path.removeSuffix("/q-data.json").removeSuffix("q-data.json")

        val withoutNovel = if (path.startsWith("novel/")) {
            path.removePrefix("novel/")
        } else {
            path
        }

        return withoutNovel.trimEnd('/').substringBefore("/")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val (entryObj, objs) = parseQData(response.body.string())

        if (entryObj == null) {
            return SManga.create()
        }

        val novelRefObj = findNovelRefObj(entryObj, objs)

        val manga = if (novelRefObj != null) {
            parseNovelFromRefObj(novelRefObj, objs) ?: createMangaFromEntry(entryObj, objs)
        } else {
            createMangaFromEntry(entryObj, objs)
        }

        manga.url = manga.url.removeSuffix("/q-data.json").removeSuffix("q-data.json")
        return manga
    }

    private fun findNovelRefObj(entryObj: JsonObject, objs: JsonArray): JsonObject? {
        // Structure: loaders -> data -> novel object with id, title, slug, etc.

        val loadersRef = entryObj["loaders"]?.jsonPrimitive?.contentOrNull
        if (loadersRef != null) {
            val loaders = resolveRef(loadersRef, objs)?.jsonObject
            if (loaders != null) {
                for ((_, value) in loaders) {
                    if (value is JsonPrimitive) {
                        val resolved = resolveRef(value.content, objs)
                        if (resolved is JsonObject && resolved.containsKey("id")) {
                            return resolved
                        }
                    }
                }
            }
        }

        for ((_, value) in entryObj) {
            if (value is JsonPrimitive) {
                val ref = value.contentOrNull ?: continue
                val resolved = resolveRef(ref, objs)
                if (resolved is JsonObject && resolved.containsKey("id")) {
                    return resolved
                }
            }
        }

        return null
    }

    private fun createMangaFromEntry(entryObj: JsonObject, objs: JsonArray): SManga {
        var title = getString(entryObj, "title", objs)
        if (title == null) {
            for ((key, value) in entryObj) {
                if (key == "title" && value is JsonPrimitive) {
                    val content = value.contentOrNull
                    if (content != null && content.startsWith('\u0006')) {
                        title = content.substring(1)
                    } else {
                        title = content
                    }
                    break
                }
            }
        }

        title = title ?: "Unknown"

        var slug = getString(entryObj, "slug", objs)
        if (slug == null) {
            slug = title.trim().replace(" ", "-").lowercase()
        }

        return SManga.create().apply {
            url = "/novel/$slug/"
            this.title = title
            description = getString(entryObj, "synopsis", objs)
            thumbnail_url = getString(entryObj, "poster", objs)
            author = getString(entryObj, "author", objs)
            status = when (getString(entryObj, "status", objs)?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = extractSlug(manga.url)
        return GET("$baseUrl/novel/$slug/q-data.json", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val (entryObj, objs) = parseQData(response.body.string())

        if (entryObj == null) {
            return emptyList()
        }

        val requestUrl = response.request.url.toString()
        val novelSlug = requestUrl.substringAfter("/novel/").substringBefore("/q-data.json")

        val chapters = mutableListOf<SChapter>()

        val novelRefObj = findNovelRefObj(entryObj, objs)

        if (novelRefObj != null) {
            val chaptersArray = getArray(novelRefObj, "chapters", objs)
            if (chaptersArray != null) {
                for (chapterRef in chaptersArray) {
                    if (chapterRef !is JsonPrimitive) continue
                    val chapterObj = resolveRef(chapterRef.content, objs)?.jsonObject ?: continue

                    val chapter = parseChapter(chapterObj, objs, novelSlug)
                    if (chapter != null) {
                        chapters.add(chapter)
                    }
                }
            }
        }

        if (chapters.isEmpty()) {
            for (i in 0 until objs.size) {
                val obj = objs.getOrNull(i) ?: continue
                if (obj !is JsonObject) continue

                if (obj.containsKey("id") && obj.containsKey("chapter") && !obj.containsKey("title")) {
                    val chapter = parseChapter(obj, objs, novelSlug)
                    if (chapter != null) {
                        chapters.add(chapter)
                    }
                }
            }
        }

        return chapters.sortedBy { it.chapter_number }
    }

    private fun parseChapter(chapterObj: JsonObject, objs: JsonArray, novelSlug: String): SChapter? {
        val chapterNum = getInt(chapterObj, "chapter", objs) ?: return null
        val createdAt = getString(chapterObj, "created_at", objs)
        val title = getString(chapterObj, "title", objs)

        return SChapter.create().apply {
            url = "/novel/$novelSlug/chapter-$chapterNum/"
            name = if (!title.isNullOrBlank()) {
                "Chapter $chapterNum: $title"
            } else {
                "Chapter $chapterNum"
            }
            chapter_number = chapterNum.toFloat()
            date_upload = parseDate(createdAt)
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val cleanUrl = chapter.url.removeSuffix("/q-data.json").removeSuffix("q-data.json")
        val url = if (cleanUrl.endsWith("/")) cleanUrl else "$cleanUrl/"
        return GET("$baseUrl${url}q-data.json", headers)
    }

    private fun parseDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return try {
            dateFormat.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        // which should be $baseUrl + chapter.url from pageListRequest
        val requestUrl = response.request.url.toString()
        val chapterUrl = requestUrl.removeSuffix("/q-data.json").removeSuffix("q-data.json")
        return listOf(Page(0, chapterUrl))
    }

    override suspend fun fetchPageText(page: Page): String {
        val chapterUrl = page.url
        val baseChapterUrl = if (chapterUrl.endsWith("/")) chapterUrl else "$chapterUrl/"

        val fullUrl = if (baseChapterUrl.startsWith("http")) {
            baseChapterUrl
        } else {
            "$baseUrl$baseChapterUrl"
        }

        val response = client.newCall(GET("${fullUrl}q-data.json", headers)).execute()
        val (entryObj, objs) = parseQData(response.body.string())

        if (entryObj == null) {
            return ""
        }

        for (i in 0 until objs.size) {
            val element = objs.getOrNull(i) ?: continue
            if (element is JsonPrimitive) {
                val content = element.contentOrNull ?: continue
                if (content.startsWith("<") && (content.contains("<p>") || content.contains("<h2>"))) {
                    return content
                }
            }
        }

        val dataRef = entryObj["data"]?.jsonPrimitive?.contentOrNull
        if (dataRef != null) {
            val data = resolveRef(dataRef, objs)?.jsonObject
            if (data != null) {
                val contentHtml = getString(data, "content", objs)
                if (!contentHtml.isNullOrBlank()) {
                    return contentHtml
                }
            }
        }

        return ""
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList = FilterList()
}
