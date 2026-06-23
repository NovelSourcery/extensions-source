package novelsourcery.lib.novelupdatesparsers.parsers

import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class ReadingPiaParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "readingpia"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        listOf(".ezoic-ad", ".ezoic-adpicker-ad", ".ez-video-wrap").forEach { doc.select(it).remove() }
        return doc.select(".chapter-body").html()
    }
}
