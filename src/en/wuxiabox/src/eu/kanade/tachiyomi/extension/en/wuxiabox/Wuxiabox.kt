package eu.kanade.tachiyomi.extension.en.wuxiabox

import eu.kanade.tachiyomi.multisrc.readwn.ReadWN
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request

class Wuxiabox : ReadWN("Wuxiabox", "https://wuxiabox.com", "en") {
    // Wuxiabox uses the "all-onclick" pagination for popular (sort by Popular)
    override fun popularMangaRequest(page: Int): Request {
        val idx = page - 1
        return GET("$baseUrl/list/all/all-onclick-$idx.html", headers)
    }

    // Latest/Updates use the lastdotime sort
    override fun latestUpdatesRequest(page: Int): Request {
        val idx = page - 1
        return GET("$baseUrl/list/all/all-lastdotime-$idx.html", headers)
    }

    // Pagination uses nav.paging > ul.pagination > li > a
    override fun popularMangaNextPageSelector() = "nav.paging ul.pagination li a[href]"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search/filter results also use the same pagination; enable next-page for searches
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
}
