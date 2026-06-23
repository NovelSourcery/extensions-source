package keiyoushi.lib.novelupdatesparsers.parsers

import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.combined
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class DreamyTranslationsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "dreamy-translations"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val title = doc.select("h1 > span").first()?.text() ?: ""
        val content = doc.select(".chapter-content > div").first()
        content?.select("em")?.forEach { em -> em.wrap("<p></p>") }
        return combined(title, content?.html() ?: "")
    }
}
