package eu.kanade.tachiyomi.novelextension.en.divinedaolibrary

import eu.kanade.tachiyomi.multisrc.fictioneer.Fictioneer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.formattedText
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.stripChapterNumberPrefix
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class DivineDaoLibrary : Fictioneer() {
    override val browsePage = "stories"
    override val supportsLatest = true

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val pagePath = if (page == 1) "" else "page/$page/"
        val response = client.newCall(GET("$baseUrl/latest-chapters/$pagePath", headers)).execute()
        val doc = response.asJsoup()
        val novels = doc.select("a.card__link-list-link[href*=/story/]").mapNotNull { link ->
            val href = link.attr("href").trimEnd('/')
            val card = link.parents().firstOrNull { it.selectFirst("img.wp-post-image") != null }
            SManga.create().apply {
                setSlugUrl(href)
                title = link.text().trim()
                thumbnail_url = card?.selectFirst("img.wp-post-image")?.absUrl("src")
            }
        }.distinctBy { it.url }
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return MangasPage(novels, hasNext)
    }

    override fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1.story__identity-title")?.text()?.trim() ?: "Untitled"
        author = doc.selectFirst("div.story__identity-meta a.author")?.text()?.trim()
            ?: doc.selectFirst("div.story__identity-meta")?.text()
                ?.split("|")?.firstOrNull()?.replace("Author:", "")?.replace("by ", "")?.trim()
        thumbnail_url = doc.selectFirst("figure.story__thumbnail > a")?.attr("href")
            ?: doc.selectFirst("img.story__thumbnail-image")?.absUrl("src")
        genre = doc.select("div.tag-group > a, section.tag-group > a")
            .joinToString { it.text().trim() }
        description = doc.selectFirst("section.story__summary")?.formattedText()
        status = when (doc.selectFirst("span.story__status")?.text()?.trim()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "cancelled" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val altTitles = doc.select("*").firstOrNull { it.ownText().trim().equals("Other Names", true) }
            ?.nextElementSibling()?.text()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && !it.equals(title, true) }
            .orEmpty()
        if (altTitles.isNotEmpty()) setAltTitles(altTitles)
    }

    override fun parseChapterList(doc: Document): List<SChapter> {
        val out = mutableListOf<SChapter>()

        fun Element.isLocked() = className().contains("_password") || selectFirst("i.fa-lock") != null

        fun addItem(li: Element, volNum: String?) {
            if (!li.className().contains("_publish") || li.isLocked()) return
            val a = li.selectFirst("a") ?: return
            val title = a.text().trim().stripChapterNumberPrefix()
            out += SChapter.create().apply {
                url = a.attr("href").replace(baseUrl, "").trimEnd('/')
                name = if (volNum != null) "Vol $volNum • $title" else title
            }
        }

        val groups = doc.select("div.chapter-group")
        if (groups.isNotEmpty()) {
            groups.forEach { group ->
                val volNum = Regex("""(?i)vol(?:ume)?\s*(\d+)""")
                    .find(group.selectFirst(".chapter-group__name")?.text().orEmpty())
                    ?.groupValues?.get(1)
                group.select("li.chapter-group__list-item").forEach { addItem(it, volNum) }
            }
        } else {
            doc.select("li.chapter-group__list-item").forEach { addItem(it, null) }
        }

        // No volume model in-app: number sequentially oldest-first, present newest-first.
        out.forEachIndexed { i, ch -> ch.chapter_number = (i + 1).toFloat() }
        return out.reversed()
    }
}
