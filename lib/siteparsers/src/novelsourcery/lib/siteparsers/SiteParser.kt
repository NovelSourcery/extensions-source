package novelsourcery.lib.siteparsers

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

interface SiteParser {
    fun canHandle(doc: Document, url: HttpUrl): Boolean
    fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String
}

internal fun HttpUrl.domainKey(): String {
    val unwanted = setOf("app", "blogspot", "casper", "wordpress", "www")
    return host.split(".").find { it !in unwanted } ?: host.split(".").first()
}

internal fun combined(title: String, content: String) = if (title.isNotEmpty()) "<h2>$title</h2><hr><br>$content" else content
