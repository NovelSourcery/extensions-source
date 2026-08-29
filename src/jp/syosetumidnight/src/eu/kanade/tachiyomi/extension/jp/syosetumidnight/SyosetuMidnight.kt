package eu.kanade.tachiyomi.novelextension.jp.syosetumidnight

import eu.kanade.tachiyomi.multisrc.syosetu.SiteType
import eu.kanade.tachiyomi.multisrc.syosetu.SyosetuBase
import keiyoushi.annotation.Source

@Source
abstract class SyosetuMidnight :
    SyosetuBase(
        siteType = SiteType.MID,
        isAdult = true,
        supportsRanking = true,
    )
