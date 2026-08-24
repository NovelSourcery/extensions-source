package eu.kanade.tachiyomi.novelextension.jp.syosetu

import eu.kanade.tachiyomi.multisrc.syosetu.SiteType
import eu.kanade.tachiyomi.multisrc.syosetu.SyosetuBase
import keiyoushi.annotation.Source

@Source
abstract class Syosetu :
    SyosetuBase(
        siteType = SiteType.NCODE,
        isAdult = false,
        supportsRanking = true,
    )
