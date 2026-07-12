package eu.kanade.tachiyomi.novelextension.ar.novelsparadise

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import okhttp3.OkHttpClient

class NovelsParadise :
    LightNovelWPNovel(
        baseUrl = "https://novelsparadise.site",
        name = "NovelsParadise",
        lang = "ar",
    ) {
    override val reverseChapters = true

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .header("Referer", baseUrl)
                .build()
            chain.proceed(request)
        }
        .build()
}
