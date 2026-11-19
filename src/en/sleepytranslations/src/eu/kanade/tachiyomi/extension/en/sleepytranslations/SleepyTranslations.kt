package eu.kanade.tachiyomi.novelextension.en.sleepytranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath
import okhttp3.OkHttpClient
import okhttp3.Protocol

@Source
abstract class SleepyTranslations : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
    override val mangaPathTemplate = SlugPath("/series/")

    // The WordPress.com edge answers OkHttp's HTTP/2 requests with a 403 bot check.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = protocols(listOf(Protocol.HTTP_1_1))
}
