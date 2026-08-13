package eu.kanade.tachiyomi.novelextension.es.panchotranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class PanchoTranslations : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
