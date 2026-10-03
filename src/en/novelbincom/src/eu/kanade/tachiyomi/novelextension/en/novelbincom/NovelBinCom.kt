package eu.kanade.tachiyomi.novelextension.en.novelbincom

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class NovelBinCom : ReadNovelFull() {
    override val popularPage = "monthvisit"
    override val latestPage = "dayvisit"
    override val noAjax = true
    override val mangaPathTemplate = SlugPath("/novel-bin/")
}
