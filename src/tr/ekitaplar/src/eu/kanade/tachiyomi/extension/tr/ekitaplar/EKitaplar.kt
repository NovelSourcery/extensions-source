package eu.kanade.tachiyomi.novelextension.tr.ekitaplar

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class EKitaplar : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
