package eu.kanade.tachiyomi.novelextension.jp.syosetunovel18

import eu.kanade.tachiyomi.multisrc.syosetu.SiteType
import eu.kanade.tachiyomi.multisrc.syosetu.SyosetuBase
import keiyoushi.annotation.Source

@Source
abstract class SyosetuNovel18 :
    SyosetuBase(
        siteType = SiteType.NOVEL18,
        isAdult = true,
        // Irrelevant for NOVEL18 - popular/latest/search always go through the JSON API
        // (novel18.syosetu.com has no HTML ranking or search page at all).
        supportsRanking = false,
    )
