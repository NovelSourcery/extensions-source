package keiyoushi.utils

/**
 * Stores [eu.kanade.tachiyomi.source.model.SManga.url] / [eu.kanade.tachiyomi.source.model.SChapter.url]
 * as a bare slug instead of a full path, for sources whose detail/chapter URLs are shaped
 * `<fixed prefix><slug><fixed suffix>`.
 *
 * Backward compatible: a stored value starting with "/" is assumed to be a full path saved by an
 * older version of the source (before it adopted slug storage) and is resolved unchanged, so
 * existing library entries keep working without a migration step. Likewise, a stored value that
 * is already a full "http(s)://" URL - left behind by even older code that tried to strip a
 * hardcoded baseUrl prefix off a scraped absolute href and silently failed whenever the href's
 * host didn't match exactly (a domain rebrand, a scheme mismatch, a mirror) - is also passed
 * through unchanged, otherwise it gets treated as a bare slug and the prefix is glued onto the
 * front of the full URL, e.g. "https://host/novel/https://host/novel/some-slug".
 */
class SlugPath(private val prefix: String, private val suffix: String = "") {

    /** Extracts the bare slug from a full relative path (e.g. from a scraped href). */
    fun slug(path: String): String = path.removePrefix(prefix).removeSuffix(suffix)

    /** Rebuilds the relative path (starting with "/") from a stored value, old or new. */
    fun resolve(stored: String): String = if (stored.startsWith("/") || stored.startsWith("http://") || stored.startsWith("https://")) {
        stored
    } else {
        "$prefix$stored$suffix"
    }
}
