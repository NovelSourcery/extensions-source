package eu.kanade.tachiyomi.novelextension.en.noicetranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath
import okhttp3.OkHttpClient
import okhttp3.Protocol

@Source
abstract class NoiceTranslations : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
    override val mangaPathTemplate = SlugPath("/manga/")

    // site blocks http2
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = protocols(listOf(Protocol.HTTP_1_1))
}
