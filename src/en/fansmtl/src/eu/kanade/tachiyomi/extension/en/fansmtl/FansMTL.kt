package eu.kanade.tachiyomi.extension.en.fansmtl

import eu.kanade.tachiyomi.multisrc.readwn.ReadWN

/**
 * FansMTL - ReadWN-based novel site
 * Uses the ReadWN multisrc template which handles the identical URL patterns and search logic.
 */
class FansMTL :
    ReadWN(
        name = "Fans MTL",
        baseUrl = "https://www.fanmtl.com",
        lang = "en",
    )
