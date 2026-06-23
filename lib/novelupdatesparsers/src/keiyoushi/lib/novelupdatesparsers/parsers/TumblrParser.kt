package keiyoushi.lib.novelupdatesparsers.parsers

import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class TumblrParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "tumblr"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String = doc.select(".post").html()
}
