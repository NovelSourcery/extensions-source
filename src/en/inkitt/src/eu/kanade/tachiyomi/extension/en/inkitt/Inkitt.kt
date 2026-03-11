package eu.kanade.tachiyomi.extension.en.inkitt

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Inkitt :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Inkitt"
    override val baseUrl = "https://www.inkitt.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    // Tag cache stored as JSON in preferences
    private fun getCachedTags(): Map<String, List<Pair<Int, String>>> {
        val raw = preferences.getString(PREF_TAG_CACHE, null) ?: return emptyMap()
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            obj.entries.associate { (prefix, arr) ->
                prefix to arr.jsonArray.map { tag ->
                    val tagObj = tag.jsonObject
                    tagObj["id"]!!.jsonPrimitive.int to tagObj["name"]!!.jsonPrimitive.content
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCachedTags(cache: Map<String, List<Pair<Int, String>>>) {
        val obj = cache.entries.joinToString(",") { (prefix, tags) ->
            val arr = tags.joinToString(",") { (id, name) ->
                """{"id":$id,"name":"${name.replace("\"", "\\\"")}"}"""
            }
            "\"${prefix.replace("\"", "\\\"")}\":[$arr]"
        }
        preferences.edit().putString(PREF_TAG_CACHE, "{$obj}").apply()
    }

    private fun fetchTagsForPrefix(prefix: String): List<Pair<Int, String>> {
        val cache = getCachedTags()
        cache[prefix]?.let { return it }

        return try {
            val response = client.newCall(
                GET("$baseUrl/api/2/search/tags_autocomplete?q=$prefix&tag_type=tag", headers),
            ).execute()
            val body = response.body.string()
            val parsed = json.parseToJsonElement(body).jsonObject
            val tags = parsed["tags"]?.jsonArray?.map { tag ->
                val tagObj = tag.jsonObject
                tagObj["id"]!!.jsonPrimitive.int to tagObj["name"]!!.jsonPrimitive.content
            } ?: emptyList()

            val updated = cache.toMutableMap()
            updated[prefix] = tags
            saveCachedTags(updated)
            tags
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun learnTagsFromPills(pills: List<String>) {
        if (pills.isEmpty()) return
        val cache = getCachedTags().toMutableMap()
        val discovered = cache.getOrDefault("_discovered", emptyList()).toMutableList()
        var changed = false
        for (pill in pills) {
            if (discovered.none { it.second.equals(pill, ignoreCase = true) }) {
                discovered.add(0 to pill)
                changed = true
            }
        }
        if (changed) {
            cache["_discovered"] = discovered
            saveCachedTags(cache)
        }
    }

    // region Popular

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/2/search/advanced_stories".toHttpUrl().newBuilder()
            .addQueryParameter("story_type", "original")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        val parsed = json.parseToJsonElement(body).jsonObject
        val stories = parsed["stories"]?.jsonArray ?: JsonArray(emptyList())
        val mangas = stories.map { parseStoryToSManga(it.jsonObject) }
        return MangasPage(mangas, mangas.size >= 20)
    }

    // endregion

    // region Latest

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/2/search/advanced_stories".toHttpUrl().newBuilder()
            .addQueryParameter("story_type", "original")
            .addQueryParameter("last_updated_interval", "7")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // endregion

    // region Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = "$baseUrl/api/2/search/advanced_stories".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("search_term", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is TitleFilter -> if (filter.state.isNotBlank()) {
                    urlBuilder.addQueryParameter("title", filter.state)
                }
                is AuthorFilter -> if (filter.state.isNotBlank()) {
                    urlBuilder.addQueryParameter("author_name", filter.state)
                }
                is StoryTypeFilter -> if (filter.state > 0) {
                    urlBuilder.addQueryParameter("story_type", STORY_TYPES[filter.state].second)
                }
                is GenreFilter -> if (filter.state > 0) {
                    urlBuilder.addQueryParameter("genre", GENRES[filter.state].second)
                }
                is StatusFilter -> if (filter.state > 0) {
                    urlBuilder.addQueryParameter("story_status", STATUS_OPTIONS[filter.state].second)
                }
                is LengthFilter -> filter.state.filter { it.state }.forEach {
                    urlBuilder.addQueryParameter("length_class[]", it.name.lowercase().replace(" ", "_"))
                }
                is AgeRatingFilter -> filter.state.filter { it.state }.forEach {
                    urlBuilder.addQueryParameter("age_rating[]", it.name.lowercase())
                }
                is RatingFilter -> if (filter.state.isNotBlank()) {
                    filter.state.toFloatOrNull()?.let {
                        urlBuilder.addQueryParameter("rating_min", it.toString())
                    }
                }
                is SeriesFilter -> if (filter.state) {
                    urlBuilder.addQueryParameter("part_of_series", "true")
                }
                is ViolenceWarningFilter -> if (filter.state) {
                    urlBuilder.addQueryParameter("warning_violence", "true")
                }
                is DeathWarningFilter -> if (filter.state) {
                    urlBuilder.addQueryParameter("warning_death", "true")
                }
                is TagPrefixFilter -> {
                    if (filter.state.isNotBlank()) {
                        fetchTagsForPrefix(filter.state.trim().lowercase())
                    }
                }
                is IncludeTagGroup -> filter.state.filter { it.state }.forEach {
                    urlBuilder.addQueryParameter("include_tags[]", it.tagName)
                }
                is ExcludeTagGroup -> filter.state.filter { it.state }.forEach {
                    urlBuilder.addQueryParameter("exclude_tags[]", it.tagName)
                }
                is FandomFilter -> if (filter.state.isNotBlank()) {
                    filter.state.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        urlBuilder.addQueryParameter("fandoms[]", it)
                    }
                }
                is UpdatedIntervalFilter -> if (filter.state > 0) {
                    urlBuilder.addQueryParameter(
                        "last_updated_interval",
                        UPDATED_INTERVALS[filter.state].second,
                    )
                }
                else -> {}
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // endregion

    // region Details

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        // Extract pills from globalData.storyPills JavaScript variable
        val pills = mutableListOf<String>()
        doc.select("script").forEach { script ->
            val data = script.data()
            val pillsMatch = Regex("""globalData\.storyPills\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(data)
            if (pillsMatch != null) {
                try {
                    val pillsArray = json.parseToJsonElement(pillsMatch.groupValues[1]).jsonArray
                    pillsArray.forEach { pill ->
                        pill.jsonObject["name"]?.jsonPrimitive?.content?.let { pills.add(it) }
                    }
                } catch (_: Exception) {}
            }
        }
        learnTagsFromPills(pills)

        // Parse structured data from React component
        val storyData = parseStoryDetailsJson(doc)

        val genre = storyData?.get("genres")?.jsonArray
            ?.joinToString { it.jsonObject["name"]?.jsonPrimitive?.content ?: it.jsonPrimitive.content } ?: ""
        val author = storyData?.get("author")?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: doc.selectFirst("[id^=StoryDetails-react-component]")?.let { block ->
                block.selectFirst("dt:contains(Author) + dd a")?.text()
            } ?: ""
        val statusText = storyData?.get("storyStatus")?.jsonPrimitive?.content
            ?: storyData?.get("story_status")?.jsonPrimitive?.content
        val ageRating = storyData?.get("ageRating")?.jsonPrimitive?.content ?: ""

        val allTags = if (pills.isNotEmpty()) "$genre, ${pills.joinToString()}" else genre

        return SManga.create().apply {
            title = storyData?.get("title")?.jsonPrimitive?.content
                ?: doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
            this.author = author
            description = buildString {
                val summary = storyData?.get("blurb")?.jsonPrimitive?.content
                    ?: doc.selectFirst("p.story-summary")?.text()
                summary?.let { append(it) }
                if (ageRating.isNotBlank()) append("\n\nAge Rating: $ageRating")
            }
            this.genre = allTags
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            this.status = when {
                statusText?.contains("complete", ignoreCase = true) == true -> SManga.COMPLETED
                statusText?.contains("ongoing", ignoreCase = true) == true -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun parseStoryDetailsJson(doc: Document): JsonObject? {
        val scriptEl = doc.selectFirst("script.js-react-on-rails-component[data-component-name=StoryDetails]")
            ?: return null
        return try {
            json.parseToJsonElement(scriptEl.data()).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    // endregion

    // region Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()

        // Try HTML chapter dropdown first (may exist on some pages)
        val chapterElements = doc.select("a.chapter-link, a[class*=chapter-link]")
        if (chapterElements.isNotEmpty()) {
            return chapterElements.mapIndexed { index, el ->
                SChapter.create().apply {
                    val nr = el.selectFirst(".chapter-nr, [class*=chapter-nr]")?.text()?.trim() ?: "${index + 1}"
                    val chTitle = el.selectFirst(".chapter-title, [class*=chapter-title]")?.text()?.trim() ?: ""
                    name = if (chTitle.isNotBlank()) "$nr. $chTitle" else "Chapter $nr"
                    url = el.attr("href").removePrefix(baseUrl)
                    chapter_number = nr.toFloatOrNull() ?: (index + 1).toFloat()
                }
            }.reversed()
        }

        // Fallback: parse chapter count from React component JSON and generate chapter URLs
        val storyData = parseStoryDetailsJson(doc)
        if (storyData != null) {
            val chaptersCount = storyData["chaptersCount"]?.jsonPrimitive?.int
                ?: storyData["chapters_count"]?.jsonPrimitive?.int ?: 0
            val storyId = storyData["storyId"]?.jsonPrimitive?.content
                ?: storyData["id"]?.jsonPrimitive?.content ?: ""

            if (chaptersCount > 0 && storyId.isNotBlank()) {
                return (1..chaptersCount).map { n ->
                    SChapter.create().apply {
                        name = "Chapter $n"
                        url = "/stories/$storyId/chapters/$n"
                        chapter_number = n.toFloat()
                    }
                }.reversed()
            }
        }

        return emptyList()
    }

    // endregion

    // region Pages

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET(page.url, headers)).execute().asJsoup()
        val content = doc.selectFirst("div#chapterText") ?: return ""
        content.select("script, ins, .adsbygoogle").remove()
        return content.html()
    }

    // endregion

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // region Helpers

    private fun parseStoryToSManga(story: JsonObject): SManga = SManga.create().apply {
        val id = story["id"]?.jsonPrimitive?.content ?: ""
        title = story["title"]?.jsonPrimitive?.content ?: ""
        val cover = story["vertical_cover"]?.jsonObject
        thumbnail_url = cover?.get("url")?.jsonPrimitive?.content
            ?: cover?.get("iphone")?.jsonPrimitive?.content
            ?: story["cover"]?.jsonObject?.get("url")?.jsonPrimitive?.content
        url = "/stories/$id"
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    // endregion

    // region Filters

    override fun getFilterList(): FilterList {
        val allTags = buildTagList()
        val filters = mutableListOf<Filter<*>>(
            TitleFilter(),
            AuthorFilter(),
            StoryTypeFilter(),
            GenreFilter(),
            StatusFilter(),
            UpdatedIntervalFilter(),
            LengthFilter(),
            AgeRatingFilter(),
            RatingFilter(),
            SeriesFilter(),
            ViolenceWarningFilter(),
            DeathWarningFilter(),
            FandomFilter(),
            Filter.Separator(),
            Filter.Header("Tags: type prefix, search, then reopen filters"),
            TagPrefixFilter(),
        )
        if (allTags.isNotEmpty()) {
            filters.add(Filter.Separator())
            filters.add(Filter.Header("Include Tags"))
            filters.add(IncludeTagGroup(allTags.map { TagCheckBox(it.second, it.second) }))
            filters.add(Filter.Header("Exclude Tags"))
            filters.add(ExcludeTagGroup(allTags.map { TagCheckBox(it.second, it.second) }))
        } else {
            filters.add(Filter.Header("No tags loaded. Enter a prefix above and search."))
        }
        return FilterList(filters)
    }

    private fun buildTagList(): List<Pair<Int, String>> {
        val cache = getCachedTags()
        val merged = mutableMapOf<String, Int>()
        for ((_, tags) in cache) {
            for ((id, name) in tags) {
                merged.putIfAbsent(name, id)
            }
        }
        return merged.entries.sortedBy { it.key.lowercase() }.map { it.value to it.key }
    }

    private class TitleFilter : Filter.Text("Title")
    private class AuthorFilter : Filter.Text("Author")
    private class TagPrefixFilter : Filter.Text("Tag prefix (type and search)")
    private class RatingFilter : Filter.Text("Minimum rating (e.g. 2.4)")
    private class FandomFilter : Filter.Text("Fandoms (comma-separated)")
    private class SeriesFilter : Filter.CheckBox("Part of series")
    private class ViolenceWarningFilter : Filter.CheckBox("Warning: Violence")
    private class DeathWarningFilter : Filter.CheckBox("Warning: Death")

    private class TagCheckBox(name: String, val tagName: String) : Filter.CheckBox(name)
    private class IncludeTagGroup(tags: List<TagCheckBox>) : Filter.Group<TagCheckBox>("Include Tags", tags)
    private class ExcludeTagGroup(tags: List<TagCheckBox>) : Filter.Group<TagCheckBox>("Exclude Tags", tags)

    private class StoryTypeFilter : Filter.Select<String>("Story Type", STORY_TYPES.map { it.first }.toTypedArray())

    private class GenreFilter : Filter.Select<String>("Genre", GENRES.map { it.first }.toTypedArray())

    private class StatusFilter : Filter.Select<String>("Status", STATUS_OPTIONS.map { it.first }.toTypedArray())

    private class UpdatedIntervalFilter : Filter.Select<String>("Updated Within", UPDATED_INTERVALS.map { it.first }.toTypedArray())

    private class CheckBoxVal(name: String) : Filter.CheckBox(name)

    private class LengthFilter :
        Filter.Group<CheckBoxVal>(
            "Length",
            listOf(
                CheckBoxVal("Novel"),
                CheckBoxVal("Novella"),
                CheckBoxVal("Novelette"),
                CheckBoxVal("Short Story"),
            ),
        )

    private class AgeRatingFilter :
        Filter.Group<CheckBoxVal>(
            "Age Rating",
            listOf(
                CheckBoxVal("Adults"),
                CheckBoxVal("Teenager"),
                CheckBoxVal("Kids"),
            ),
        )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    companion object {
        private const val PREF_TAG_CACHE = "inkitt_tag_cache"

        private val STORY_TYPES = listOf(
            "All" to "",
            "Original" to "original",
            "Fanfiction" to "fanfiction",
        )

        private val GENRES = listOf(
            "All" to "",
            "Adventure" to "adventure",
            "Action" to "action",
            "Children" to "children",
            "Drama" to "drama",
            "Erotica" to "erotica",
            "Fantasy" to "fantasy",
            "Horror" to "horror",
            "Humor" to "humor",
            "Mystery" to "mystery",
            "Other" to "other",
            "Poetry" to "poetry",
            "Romance" to "romance",
            "Sci-Fi" to "scifi",
            "Thriller" to "thriller",
        )

        private val STATUS_OPTIONS = listOf(
            "All" to "",
            "Complete" to "complete",
            "Ongoing" to "ongoing",
        )

        private val UPDATED_INTERVALS = listOf(
            "Any" to "",
            "Last 7 days" to "7",
            "Last 30 days" to "30",
            "Last 90 days" to "90",
        )
    }

    // endregion
}
