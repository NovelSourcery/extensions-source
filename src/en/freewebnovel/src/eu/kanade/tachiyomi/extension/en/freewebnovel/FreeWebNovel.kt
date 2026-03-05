package eu.kanade.tachiyomi.extension.en.freewebnovel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull

/**
 * FreeWebNovel - ReadNovelFull-based novel site
 * Uses the ReadNovelFull multisrc template which handles all the parsing logic.
 */
class FreeWebNovel :
    ReadNovelFull(
        name = "FreeWebNovel",
        baseUrl = "https://freewebnovel.com",
        lang = "en",
    )
