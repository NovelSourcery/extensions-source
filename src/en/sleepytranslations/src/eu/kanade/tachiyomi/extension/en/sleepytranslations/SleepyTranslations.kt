package eu.kanade.tachiyomi.extension.en.sleepytranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class SleepyTranslations :
    MadaraNovel(
        baseUrl = "https://sleepytranslations.com",
        name = "SleepyTranslations",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
}
