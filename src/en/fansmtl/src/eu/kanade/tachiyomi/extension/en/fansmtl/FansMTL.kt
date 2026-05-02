package eu.kanade.tachiyomi.extension.en.fansmtl

import eu.kanade.tachiyomi.multisrc.readwn.ReadWN
import eu.kanade.tachiyomi.network.GET

/**
 * FansMTL - ReadWN-based novel site
 * Uses the ReadWN multisrc template which handles the identical URL patterns and search logic.
 */
class FansMTL :
    ReadWN(
        name = "Fans MTL",
        baseUrl = "https://www.fanmtl.com",
        lang = "en",
    ) {
    // Use the same pagination/list patterns as Wuxiabox for consistency
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/list/all/all-onclick-${page - 1}.html", headers)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/list/all/all-lastdotime-${page - 1}.html", headers)

    override fun popularMangaNextPageSelector() = "nav.paging ul.pagination li a[href]"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
}
