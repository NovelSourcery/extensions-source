package eu.kanade.tachiyomi.novelextension.ko.fortuneeternal

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class FortuneEternal : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
