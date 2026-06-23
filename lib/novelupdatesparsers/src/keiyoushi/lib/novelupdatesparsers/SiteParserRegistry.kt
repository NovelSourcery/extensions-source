package keiyoushi.lib.novelupdatesparsers

import keiyoushi.lib.novelupdatesparsers.parsers.AkuTranslationsParser
import keiyoushi.lib.novelupdatesparsers.parsers.AsuraTlsParser
import keiyoushi.lib.novelupdatesparsers.parsers.BlogspotParser
import keiyoushi.lib.novelupdatesparsers.parsers.BrightNovelsParser
import keiyoushi.lib.novelupdatesparsers.parsers.CanonStoryParser
import keiyoushi.lib.novelupdatesparsers.parsers.DaoistParser
import keiyoushi.lib.novelupdatesparsers.parsers.DreamyTranslationsParser
import keiyoushi.lib.novelupdatesparsers.parsers.FictionReadParser
import keiyoushi.lib.novelupdatesparsers.parsers.GenericFallbackParser
import keiyoushi.lib.novelupdatesparsers.parsers.GenesisStudioParser
import keiyoushi.lib.novelupdatesparsers.parsers.GreenzParser
import keiyoushi.lib.novelupdatesparsers.parsers.HiraethTranslationParser
import keiyoushi.lib.novelupdatesparsers.parsers.HostedNovelParser
import keiyoushi.lib.novelupdatesparsers.parsers.INovelTranslationParser
import keiyoushi.lib.novelupdatesparsers.parsers.InfiniteNovelTranslationsParser
import keiyoushi.lib.novelupdatesparsers.parsers.IsoTlsParser
import keiyoushi.lib.novelupdatesparsers.parsers.KoFiParser
import keiyoushi.lib.novelupdatesparsers.parsers.KonkonParser
import keiyoushi.lib.novelupdatesparsers.parsers.LeafStudioParser
import keiyoushi.lib.novelupdatesparsers.parsers.MachineSlicedBreadParser
import keiyoushi.lib.novelupdatesparsers.parsers.MiriluParser
import keiyoushi.lib.novelupdatesparsers.parsers.MythoriaTalesParser
import keiyoushi.lib.novelupdatesparsers.parsers.NovelPlexParser
import keiyoushi.lib.novelupdatesparsers.parsers.NovelWorldTranslationsParser
import keiyoushi.lib.novelupdatesparsers.parsers.NovelsHubParser
import keiyoushi.lib.novelupdatesparsers.parsers.PatreonParser
import keiyoushi.lib.novelupdatesparsers.parsers.RPDParser
import keiyoushi.lib.novelupdatesparsers.parsers.RainOfSnowParser
import keiyoushi.lib.novelupdatesparsers.parsers.ReadingPiaParser
import keiyoushi.lib.novelupdatesparsers.parsers.RedOxTranslationParser
import keiyoushi.lib.novelupdatesparsers.parsers.SacredTextTranslationsParser
import keiyoushi.lib.novelupdatesparsers.parsers.ScribbleHubParser
import keiyoushi.lib.novelupdatesparsers.parsers.SkyDemonOrderParser
import keiyoushi.lib.novelupdatesparsers.parsers.StabbingWithASyringeParser
import keiyoushi.lib.novelupdatesparsers.parsers.TinyTranslationParser
import keiyoushi.lib.novelupdatesparsers.parsers.TumblrParser
import keiyoushi.lib.novelupdatesparsers.parsers.VampiraMtlParser
import keiyoushi.lib.novelupdatesparsers.parsers.WattpadParser
import keiyoushi.lib.novelupdatesparsers.parsers.WeTrIedTlsParser
import keiyoushi.lib.novelupdatesparsers.parsers.WebNovelParser
import keiyoushi.lib.novelupdatesparsers.parsers.WordPressParser
import keiyoushi.lib.novelupdatesparsers.parsers.WuxiaworldParser
import keiyoushi.lib.novelupdatesparsers.parsers.YoruParser
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
