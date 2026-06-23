package novelsourcery.lib.novelupdatesparsers.parsers

import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.combined
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class TinyTranslationParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "tinytranslation"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        listOf(".content noscript", ".google_translate_element", ".navigate", ".post-views", "br")
            .forEach { doc.select(it).remove() }
        val titleEl = doc.select(".title-content").first()
        val title = titleEl?.text() ?: ""
        titleEl?.remove()
        val content = doc.select(".content").html()
        return combined(title, content)
    }
}
