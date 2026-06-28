package novelsourcery.lib.siteparsers

import novelsourcery.lib.siteparsers.parsers.AkuTranslationsParser
import novelsourcery.lib.siteparsers.parsers.AsuraTlsParser
import novelsourcery.lib.siteparsers.parsers.BlogspotParser
import novelsourcery.lib.siteparsers.parsers.BrightNovelsParser
import novelsourcery.lib.siteparsers.parsers.CanonStoryParser
import novelsourcery.lib.siteparsers.parsers.DaoistParser
import novelsourcery.lib.siteparsers.parsers.DreamyTranslationsParser
import novelsourcery.lib.siteparsers.parsers.FictionReadParser
import novelsourcery.lib.siteparsers.parsers.GenericFallbackParser
import novelsourcery.lib.siteparsers.parsers.GenesisStudioParser
import novelsourcery.lib.siteparsers.parsers.GreenzParser
import novelsourcery.lib.siteparsers.parsers.HiraethTranslationParser
import novelsourcery.lib.siteparsers.parsers.HostedNovelParser
import novelsourcery.lib.siteparsers.parsers.INovelTranslationParser
import novelsourcery.lib.siteparsers.parsers.InfiniteNovelTranslationsParser
import novelsourcery.lib.siteparsers.parsers.IsoTlsParser
import novelsourcery.lib.siteparsers.parsers.KoFiParser
import novelsourcery.lib.siteparsers.parsers.KonkonParser
import novelsourcery.lib.siteparsers.parsers.LeafStudioParser
import novelsourcery.lib.siteparsers.parsers.MachineSlicedBreadParser
import novelsourcery.lib.siteparsers.parsers.MiriluParser
import novelsourcery.lib.siteparsers.parsers.MythoriaTalesParser
import novelsourcery.lib.siteparsers.parsers.NoBadNovelParser
import novelsourcery.lib.siteparsers.parsers.NovelPlexParser
import novelsourcery.lib.siteparsers.parsers.NovelWorldTranslationsParser
import novelsourcery.lib.siteparsers.parsers.NovelsHubParser
import novelsourcery.lib.siteparsers.parsers.PatreonParser
import novelsourcery.lib.siteparsers.parsers.RPDParser
import novelsourcery.lib.siteparsers.parsers.RainOfSnowParser
import novelsourcery.lib.siteparsers.parsers.ReadingPiaParser
import novelsourcery.lib.siteparsers.parsers.RedOxTranslationParser
import novelsourcery.lib.siteparsers.parsers.SacredTextTranslationsParser
import novelsourcery.lib.siteparsers.parsers.ScribbleHubParser
import novelsourcery.lib.siteparsers.parsers.SkyDemonOrderParser
import novelsourcery.lib.siteparsers.parsers.StabbingWithASyringeParser
import novelsourcery.lib.siteparsers.parsers.TinyTranslationParser
import novelsourcery.lib.siteparsers.parsers.TumblrParser
import novelsourcery.lib.siteparsers.parsers.VampiraMtlParser
import novelsourcery.lib.siteparsers.parsers.WattpadParser
import novelsourcery.lib.siteparsers.parsers.WeTrIedTlsParser
import novelsourcery.lib.siteparsers.parsers.WebNovelParser
import novelsourcery.lib.siteparsers.parsers.WordPressParser
import novelsourcery.lib.siteparsers.parsers.WuxiaworldParser
import novelsourcery.lib.siteparsers.parsers.YoruParser
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object SiteParserRegistry {
    // Site-specific parsers are listed before platform parsers so they take precedence
    // over WordPress/Blogspot auto-detection for domains that need custom handling.
    private val parsers: List<SiteParser> = listOf(
        AkuTranslationsParser(),
        AsuraTlsParser(),
        BrightNovelsParser(),
        CanonStoryParser(),
        DaoistParser(),
        DreamyTranslationsParser(),
        FictionReadParser(),
        GenesisStudioParser(),
        GreenzParser(),
        HiraethTranslationParser(),
        HostedNovelParser(),
        InfiniteNovelTranslationsParser(),
        INovelTranslationParser(),
        IsoTlsParser(),
        KoFiParser(),
        KonkonParser(),
        LeafStudioParser(),
        MachineSlicedBreadParser(),
        MiriluParser(),
        MythoriaTalesParser(),
        NoBadNovelParser(),
        NovelPlexParser(),
        NovelsHubParser(),
        NovelWorldTranslationsParser(),
        PatreonParser(),
        RPDParser(),
        RainOfSnowParser(),
        ReadingPiaParser(),
        RedOxTranslationParser(),
        SacredTextTranslationsParser(),
        ScribbleHubParser(),
        SkyDemonOrderParser(),
        StabbingWithASyringeParser(),
        TinyTranslationParser(),
        TumblrParser(),
        VampiraMtlParser(),
        WattpadParser(),
        WebNovelParser(),
        WeTrIedTlsParser(),
        WuxiaworldParser(),
        YoruParser(),
        WordPressParser(),
        BlogspotParser(),
        GenericFallbackParser(),
    )

    fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val raw = parsers.first { it.canHandle(doc, url) }.parse(doc, url, client, headers)
        return postProcess(raw, url)
    }

    private fun postProcess(html: String, url: HttpUrl): String {
        val port = url.port
        val baseOrigin = "${url.scheme}://${url.host}${if (port != HttpUrl.defaultPort(url.scheme)) ":$port" else ""}"
        val processed = html.replace("href=\"/", "href=\"$baseOrigin/")

        val doc = Jsoup.parse(processed)
        doc.select("noscript").remove()
        doc.select("img").forEach { el ->
            val lazySrc = el.attr("data-lazy-src")
            if (lazySrc.isNotEmpty()) el.attr("src", lazySrc)
            val lazySrcset = el.attr("data-lazy-srcset")
            if (lazySrcset.isNotEmpty()) el.attr("srcset", lazySrcset)
            if (el.hasClass("lazyloaded")) el.removeClass("lazyloaded")
        }
        return doc.html()
    }
}
