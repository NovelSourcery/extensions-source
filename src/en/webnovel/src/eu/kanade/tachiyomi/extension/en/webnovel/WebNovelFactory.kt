package eu.kanade.tachiyomi.extension.en.webnovel

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class WebNovelFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        WebNovelNovels(),
    )
}
