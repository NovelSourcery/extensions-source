package eu.kanade.tachiyomi.novelextension.tr.turkcelightnovels

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class TurkceLightNovels : MadaraNovel() {
    override val mangaPathTemplate = SlugPath("/light-novel/")
}
