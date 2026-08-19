package eu.kanade.tachiyomi.novelextension.jp.syosetumoonlight

import eu.kanade.tachiyomi.multisrc.syosetu.SiteType
import eu.kanade.tachiyomi.multisrc.syosetu.SyosetuBase
import keiyoushi.annotation.Source

@Source
abstract class SyosetuMoonlight :
    SyosetuBase(
        siteType = SiteType.MNLT,
        isAdult = true,
        supportsRanking = false,
    )
