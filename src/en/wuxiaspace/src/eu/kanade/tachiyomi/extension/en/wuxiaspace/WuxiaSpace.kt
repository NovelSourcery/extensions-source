package eu.kanade.tachiyomi.novelextension.en.wuxiaspace

import eu.kanade.tachiyomi.multisrc.readwn.ReadWN
import keiyoushi.annotation.Source

@Source
abstract class WuxiaSpace : ReadWN() {
    override fun popularMangaNextPageSelector() = ".paging .pagination a[href]:matchesOwn(^>$)"

    override fun searchMangaNextPageSelector() = ".paging .pagination a[href]:matchesOwn(^>$)"
}
