package eu.kanade.tachiyomi.novelextension.jp.syosetunocturne

import eu.kanade.tachiyomi.multisrc.syosetu.SiteType
import eu.kanade.tachiyomi.multisrc.syosetu.SyosetuBase
import keiyoushi.annotation.Source

@Source
abstract class SyosetuNocturne :
    SyosetuBase(
        siteType = SiteType.NOC,
        isAdult = true,
        supportsRanking = false,
    )
