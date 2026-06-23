package novelsourcery.lib.novelupdatesparsers

import novelsourcery.lib.novelupdatesparsers.parsers.AkuTranslationsParser
import novelsourcery.lib.novelupdatesparsers.parsers.AsuraTlsParser
import novelsourcery.lib.novelupdatesparsers.parsers.BlogspotParser
import novelsourcery.lib.novelupdatesparsers.parsers.BrightNovelsParser
import novelsourcery.lib.novelupdatesparsers.parsers.CanonStoryParser
import novelsourcery.lib.novelupdatesparsers.parsers.DaoistParser
import novelsourcery.lib.novelupdatesparsers.parsers.DreamyTranslationsParser
import novelsourcery.lib.novelupdatesparsers.parsers.FictionReadParser
import novelsourcery.lib.novelupdatesparsers.parsers.GenericFallbackParser
import novelsourcery.lib.novelupdatesparsers.parsers.GenesisStudioParser
import novelsourcery.lib.novelupdatesparsers.parsers.GreenzParser
import novelsourcery.lib.novelupdatesparsers.parsers.HiraethTranslationParser
import novelsourcery.lib.novelupdatesparsers.parsers.HostedNovelParser
import novelsourcery.lib.novelupdatesparsers.parsers.INovelTranslationParser
import novelsourcery.lib.novelupdatesparsers.parsers.InfiniteNovelTranslationsParser
import novelsourcery.lib.novelupdatesparsers.parsers.IsoTlsParser
import novelsourcery.lib.novelupdatesparsers.parsers.KoFiParser
import novelsourcery.lib.novelupdatesparsers.parsers.KonkonParser
import novelsourcery.lib.novelupdatesparsers.parsers.LeafStudioParser
import novelsourcery.lib.novelupdatesparsers.parsers.MachineSlicedBreadParser
import novelsourcery.lib.novelupdatesparsers.parsers.MiriluParser
import novelsourcery.lib.novelupdatesparsers.parsers.MythoriaTalesParser
import novelsourcery.lib.novelupdatesparsers.parsers.NovelPlexParser
import novelsourcery.lib.novelupdatesparsers.parsers.NovelWorldTranslationsParser
import novelsourcery.lib.novelupdatesparsers.parsers.NovelsHubParser
import novelsourcery.lib.novelupdatesparsers.parsers.PatreonParser
import novelsourcery.lib.novelupdatesparsers.parsers.RPDParser
import novelsourcery.lib.novelupdatesparsers.parsers.RainOfSnowParser
import novelsourcery.lib.novelupdatesparsers.parsers.ReadingPiaParser
import novelsourcery.lib.novelupdatesparsers.parsers.RedOxTranslationParser
import novelsourcery.lib.novelupdatesparsers.parsers.SacredTextTranslationsParser
import novelsourcery.lib.novelupdatesparsers.parsers.ScribbleHubParser
import novelsourcery.lib.novelupdatesparsers.parsers.SkyDemonOrderParser
import novelsourcery.lib.novelupdatesparsers.parsers.StabbingWithASyringeParser
import novelsourcery.lib.novelupdatesparsers.parsers.TinyTranslationParser
import novelsourcery.lib.novelupdatesparsers.parsers.TumblrParser
import novelsourcery.lib.novelupdatesparsers.parsers.VampiraMtlParser
import novelsourcery.lib.novelupdatesparsers.parsers.WattpadParser
import novelsourcery.lib.novelupdatesparsers.parsers.WeTrIedTlsParser
import novelsourcery.lib.novelupdatesparsers.parsers.WebNovelParser
import novelsourcery.lib.novelupdatesparsers.parsers.WordPressParser
import novelsourcery.lib.novelupdatesparsers.parsers.WuxiaworldParser
import novelsourcery.lib.novelupdatesparsers.parsers.YoruParser
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
