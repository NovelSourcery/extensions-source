package eu.kanade.tachiyomi.novelextension.th.novellucky

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class NovelLucky : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
