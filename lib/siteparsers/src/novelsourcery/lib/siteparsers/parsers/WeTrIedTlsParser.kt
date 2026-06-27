package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class WeTrIedTlsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "wetriedtls"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val scriptContent = doc.select("script:containsData(p dir=)").html().ifEmpty {
            doc.select("script:containsData(u003c)").html()
        }
        if (scriptContent.isEmpty()) return ""

        val pushIdx = scriptContent.indexOf(".push(") + ".push(".length
        val lastParen = scriptContent.lastIndexOf(")")
        if (pushIdx !in 1 until lastParen) return ""

        val jsonStr = scriptContent.substring(pushIdx, lastParen)
        val secondElemMatch = Regex("""^\[.*?,([\s\S]*)\]$""").find(jsonStr.trim())
        return secondElemMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") ?: ""
    }
}
