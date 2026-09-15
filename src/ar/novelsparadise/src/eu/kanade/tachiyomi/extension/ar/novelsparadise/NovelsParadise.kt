package eu.kanade.tachiyomi.novelextension.ar.novelsparadise

import android.webkit.CookieManager
import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.WebViewSession
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NovelsParadise : LightNovelWPNovel() {
    override val reverseChapters = true

    /** The site's LightNovelWP install prefixes every page with `/np-light`. */
    override val seriesPath: String = "np-light/series"

    private val webViewSession = WebViewSession()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        addInterceptor(CloudflareChallengeInterceptor(webViewSession, baseUrl))
        return this
    }
}

/**
 * Detects Cloudflare managed challenge pages ("Just a moment…") served by
 * novelsparadise.site and auto-solves them via WebView, then retries the request
 * with the `cf_clearance` cookie harvested from the solved WebView session.
 *
 * OkHttp and the WebView have separate cookie stores, so the challenge cookie
 * solved inside the WebView must be read back and injected into the OkHttp
 * request for the retry to pass. The cookie is cached per host so subsequent
 * calls go straight through; if a cached cookie is ever rejected we re-solve
 * once and retry, then fail with a clear message.
 */
private class CloudflareChallengeInterceptor(
    private val session: WebViewSession,
    private val baseUrl: String,
) : Interceptor {

    private val cookieCache = ConcurrentHashMap<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        // Attach a previously solved cookie so normal traffic skips the challenge.
        val cached = cookieCache[host]
        var response = chain.proceed(if (cached != null) request.carrying(cached) else request)

        if (!isChallengePage(response)) return response
        response.close()

        // Challenge seen (with or without a stale cached cookie): solve fresh and retry once.
        val cookie = solveChallenge(chain)
        cookieCache[host] = cookie

        response = chain.proceed(request.carrying(cookie))
        if (isChallengePage(response)) {
            response.close()
            throw Exception(
                "NovelsParadise: the Cloudflare challenge was solved but the request is still " +
                    "blocked. Please open the site in WebView once, then retry.",
            )
        }

        return response
    }

    private fun okhttp3.Request.carrying(cookie: String): okhttp3.Request = newBuilder().header("Cookie", cookie).build()

    private fun isChallengePage(response: Response): Boolean {
        val body = response.peekBody(8192).string()
        return CHALLENGE_MARKERS.any { it in body }
    }

    private fun solveChallenge(chain: Interceptor.Chain): String {
        try {
            runWebViewBlocking<Unit>(call = chain.call(), session = session, timeout = 60.seconds) {
                useOkHttpNetwork = false
                var done = false

                fun checkSolved() {
                    if (done) return
                    // The challenge page swaps out once solved; a real document title means the
                    // interstitial is gone. Read it from the live DOM rather than the callback arg
                    // so it also works for the poll fallback below.
                    evaluateJs("document.title") { titleJson ->
                        if (done) return@evaluateJs
                        val title = runCatching { titleJson.parseAs<String>() }.getOrDefault("")
                        if (title.isNotBlank() && !isChallengeTitle(title)) {
                            done = true
                            resolve(Unit)
                        }
                    }
                }

                onPageFinished { checkSolved() }
                // onPageFinished can miss the swap on some WebView builds, so re-check periodically.
                poll(interval = 1.seconds) { checkSolved() }
                loadUrl(baseUrl)
            }
        } catch (e: WebViewTimeoutException) {
            throw Exception(
                "NovelsParadise: could not solve the Cloudflare challenge automatically. " +
                    "Please open the site in WebView once, then retry.",
                e,
            )
        }

        // The WebView used its own network stack, so cf_clearance was stored as an HttpOnly
        // cookie in the shared Android cookie store — read it back for the OkHttp retry.
        val cookie = CookieManager.getInstance().getCookie(baseUrl)
        if (cookie.isNullOrBlank() || "cf_clearance" !in cookie) {
            throw Exception(
                "NovelsParadise: the Cloudflare challenge was solved but no cf_clearance cookie " +
                    "was stored. Please open the site in WebView once, then retry.",
            )
        }

        return cookie
    }

    private fun isChallengeTitle(title: String): Boolean = title.contains("Just a moment", ignoreCase = true) ||
        title.contains("Attention Required", ignoreCase = true) ||
        title.contains("Security Check", ignoreCase = true)

    companion object {
        private val CHALLENGE_MARKERS = listOf(
            "Just a moment",
            "challenge-platform",
            "cf-challenge-running",
            "Cf-Mitigated: challenge",
        )
    }
}
