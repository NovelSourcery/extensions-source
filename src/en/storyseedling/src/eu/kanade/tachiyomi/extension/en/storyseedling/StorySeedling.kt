package eu.kanade.tachiyomi.novelextension.en.storyseedling

import eu.kanade.tachiyomi.multisrc.nzoseries.NzoSeries
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class StorySeedling : NzoSeries() {

    // Library entries were saved as "<id>/" slugs, or full "/series/..." paths by older versions.
    private val mangaPath = SlugPath("/series/")

    override val showLockedPrefKey = "pref_show_locked_chapters"

    override fun mangaUrlFromPermalink(permalink: String): String = mangaPath.slug(permalink.replace(baseUrl, ""))

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    // Chapters were saved as site-relative paths; keep that so read progress survives.
    override fun chapterUrlFromLink(link: String): String = link.replace(baseUrl, "")

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
}
