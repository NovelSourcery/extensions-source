package eu.kanade.tachiyomi.novelextension.en.fansmtl

import eu.kanade.tachiyomi.multisrc.readwn.ReadWN
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source

/**
 * FansMTL - ReadWN-based novel site
 * Uses the ReadWN multisrc template which handles the identical URL patterns and search logic.
 */
@Source
abstract class FansMTL : ReadWN() {
    // Use the same pagination/list patterns as Wuxiabox for consistency
    override fun buildPopularMangaRequest(page: Int) = GET("$baseUrl/list/all/all-onclick-${page - 1}.html", headers)

    override fun buildLatestUpdatesRequest(page: Int) = GET("$baseUrl/list/all/all-lastdotime-${page - 1}.html", headers)

    override fun popularMangaNextPageSelector() = "nav.paging ul.pagination li a[href]"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
}
