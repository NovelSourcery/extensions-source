package eu.kanade.tachiyomi.novelextension.en.katreadingcafe

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.setAltTitles
import okhttp3.Response

class KatReadingCafe :
    LightNovelWPNovel(
        baseUrl = "https://katreadingcafe.com",
        name = "Kat Reading Cafe",
        lang = "en",
    ),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            thumbnail_url = parseImageUrl(doc.selectFirst(".thumb img, .thumbook img, img.ts-post-image"))

            title = doc.selectFirst(".entry-title")?.text()?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
                ?: name

            author = doc.select(".spe span:contains(Author), .serl:contains(Author)").firstOrNull()
                ?.let { it.nextElementSibling()?.text() ?: it.parent()?.text()?.substringAfter("Author")?.replace(":", "")?.trim() }

            genre = doc.select(".genxed a, .sertogenre a, .sztag a, a[rel=tag]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .joinToString()

            val statusText = doc.select(".sertostat, .spe, .serl").text().lowercase()
            status = when {
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                statusText.contains("dropped") -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            val altNames = doc.select(".spe span:contains(Alternative), .infox .alternative, .seriestualt")
                .firstOrNull()
                ?.let { it.nextElementSibling()?.text() ?: it.parent()?.text()?.substringAfter("Alternative")?.replace(":", "")?.trim() }
                ?.split(",", ";")?.map { it.trim() }?.filter { it.isNotEmpty() && it != title }
                .orEmpty()
            if (altNames.isNotEmpty()) setAltTitles(altNames)

            description = buildString {
                doc.selectFirst(".rating .num, [itemprop=ratingValue]")?.text()?.takeIf { it.isNotEmpty() }
                    ?.let { append("Rating: $it\n") }
                val synopsis = doc.selectFirst("[itemprop=description], .entry-content")?.text()?.trim()
                if (!synopsis.isNullOrEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(synopsis)
                }
            }.trim()
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = super.chapterListParse(response)
        if (preferences.getBoolean(PREF_SHOW_LOCKED, false)) return chapters
        return chapters.filterNot { it.name.startsWith("🔒") }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Show chapters that require payment/unlock. Hidden by default."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked"
    }
}
