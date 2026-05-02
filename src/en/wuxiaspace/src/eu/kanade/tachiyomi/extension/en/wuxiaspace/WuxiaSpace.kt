package eu.kanade.tachiyomi.extension.en.wuxiaspace

import eu.kanade.tachiyomi.multisrc.readwn.ReadWN

class WuxiaSpace : ReadWN("Wuxia Space", "https://www.wuxiaspot.com", "en") {
    override fun popularMangaNextPageSelector() = ".paging .pagination a[href]:matchesOwn(^>$)"

    override fun searchMangaNextPageSelector() = ".paging .pagination a[href]:matchesOwn(^>$)"
}
