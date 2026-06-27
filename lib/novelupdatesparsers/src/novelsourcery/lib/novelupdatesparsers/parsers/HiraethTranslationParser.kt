package novelsourcery.lib.novelupdatesparsers.parsers

import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.combined
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class HiraethTranslationParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "hiraethtranslation"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val title = doc.select("li.active").first()?.text() ?: ""
        val content = doc.select(".text-left").html()
        return combined(title, content)
    }
}
