package novelsourcery.lib.novelupdatesparsers.parsers

import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.combined
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class FictionReadParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "fictionread"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        listOf(".content > style", ".highlight-ad-container", ".meaning", ".word")
            .forEach { doc.select(it).remove() }
        val title = doc.select(".title-image span").first()?.text() ?: ""
        doc.select(".content").first()?.children()?.forEach { el ->
            if (el.attr("id").contains("Chaptertitle-info")) {
                el.remove()
                return@forEach
            }
        }
        val content = doc.select(".content").html()
        return combined(title, content)
    }
}
