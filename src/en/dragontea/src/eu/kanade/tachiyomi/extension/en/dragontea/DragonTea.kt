package eu.kanade.tachiyomi.novelextension.en.dragontea

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import okhttp3.Request

@Source
abstract class DragonTea : MadaraNovel() {
    override val useNewChapterEndpointDefault = true

    // The default `?post_type=wp-manga` archive mixes in the site's comics - novels live under
    // their own "novel-genre" taxonomy term instead (comics get their own "/novel-genre/comics/"
    // term). Search already scopes to novels correctly, so it's left alone.
    override fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/novel-genre/novels/page/$page/", headers)

    override fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/novel-genre/novels/page/$page/?m_orderby=latest", headers)
}
