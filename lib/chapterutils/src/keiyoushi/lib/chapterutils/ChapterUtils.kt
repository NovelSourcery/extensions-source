package keiyoushi.lib.chapterutils

import eu.kanade.tachiyomi.source.model.SChapter
import org.jsoup.nodes.Document

// ── High-level entry point ────────────────────────────────────────────────────

/**
 * Fetches a paginated chapter list, using [existingChapters] (as passed into
 * getMangaUpdate) to avoid redundant requests.
 *
 * The caller supplies [fetchPage], a lambda that takes a 1-based page number and returns
 * the chapters on that page plus whether a next page exists.  URL construction is entirely
 * the caller's responsibility, so any URL scheme (query param, path segment, etc.) works.
 *
 * Behaviour:
 * - Returns [existingChapters] immediately when [siteTotal] confirms nothing changed.
 * - Falls back to a full fetch when [existingChapters] is empty.
 * - Otherwise probes the estimated start page, detects the real page size, then fetches only
 *   the pages that could contain new chapters and prepends the known-good existing chapters.
 *
 * @param existingChapters  Chapters that already exist locally for this manga.
 * @param siteTotal  Chapter count reported by the site (0 or negative = unknown, skips early exit).
 * @param assumedPageSize  Initial guess for chapters-per-page used before the probe.
 * @param fetchPage  Lambda `(pageNumber) -> Pair<chapters, hasNextPage>`.
 * @param sortChapters  Post-processing sort applied to the final list; defaults to chapter-number order.
 */
suspend fun paginatedChapterList(
    existingChapters: List<SChapter>,
    siteTotal: Int,
    assumedPageSize: Int = 100,
    fetchPage: suspend (page: Int) -> Pair<List<SChapter>, Boolean>,
    sortChapters: (List<SChapter>) -> List<SChapter> = ::sortByChapterNumber,
): List<SChapter> {
    val existing = existingChapters
    val existingCount = existing.size

    if (shouldReturnExisting(existingCount, siteTotal)) {
        return existing
    }

    if (existingCount == 0) {
        return sortChapters(fetchAll(fetchPage))
    }

    val estimatedStart = incrementalStartPage(existingCount, assumedPageSize)
    val (probeChapters, probeHasNext) = fetchPage(estimatedStart)

    val pageSize = detectPageSize(probeChapters, probeHasNext, estimatedStart, assumedPageSize)
    val startPage = incrementalStartPage(existingCount, pageSize)
    val keepCount = (startPage - 1) * pageSize

    val freshChapters: List<SChapter>
    if (startPage != estimatedStart) {
        freshChapters = sortChapters(fetchAll(fetchPage, startPage))
    } else {
        val collected = probeChapters.toMutableList()
        if (probeHasNext) {
            var page = estimatedStart + 1
            while (true) {
                val (pageChapters, hasNext) = fetchPage(page)
                collected += pageChapters
                if (!hasNext) break
                page++
            }
        }
        freshChapters = sortChapters(collected)
    }

    return mergeChapters(existing, freshChapters, keepCount)
}

// ── Low-level building blocks ─────────────────────────────────────────────────

/**
 * Returns true when the existing chapter count exactly matches the site total,
 * meaning no fetch is needed.  Both values must be positive for this to fire.
 */
fun shouldReturnExisting(existingCount: Int, siteTotal: Int): Boolean = existingCount > 0 && siteTotal > 0 && existingCount == siteTotal

/**
 * Calculates the 1-based page number where an incremental fetch should begin,
 * given that we already have [existingCount] chapters stored at [pageSize] per page.
 */
fun incrementalStartPage(existingCount: Int, pageSize: Int): Int = ((existingCount - 1) / pageSize) + 1

/**
 * Infers the real page size from a probe page.
 *
 * The inference is only reliable when the probe page is a full middle page — i.e. it has a
 * next page AND is not page 1.  The last page is always partial, and page 1 could be the
 * only page.  Returns [assumed] in all other cases.
 */
fun detectPageSize(
    probeChapters: List<SChapter>,
    probeHasNext: Boolean,
    probePage: Int,
    assumed: Int,
): Int = if (probeHasNext && probePage > 1 && probeChapters.isNotEmpty()) {
    probeChapters.size
} else {
    assumed
}

/**
 * Merges existing (newest-first as stored) chapters with freshly fetched chapters.
 *
 * [keepCount] is the number of existing chapters to carry forward.  The oldest [keepCount]
 * entries are sliced from [existing] (which is newest-first, so takeLast), reversed to
 * ascending order, then prepended to [fresh].
 *
 * Duplicate URLs between the two lists are harmless — the caller's deduplication layer
 * (SyncChaptersWithSource) discards them by URL.
 */
fun mergeChapters(
    existing: List<SChapter>,
    fresh: List<SChapter>,
    keepCount: Int,
): List<SChapter> {
    if (keepCount <= 0) return fresh
    val keptOldestFirst = existing.takeLast(keepCount).reversed()
    return keptOldestFirst + fresh
}

/**
 * Sorts chapters by the numeric value embedded in their name.
 *
 * Matches "chapter N", "ch N", "ch. N" (case-insensitive).  Chapters without a recognisable
 * number sort to the end (Double.MAX_VALUE).
 */
fun sortByChapterNumber(chapters: List<SChapter>): List<SChapter> = chapters.sortedWith(
    compareBy { chapter ->
        CHAPTER_NUMBER_REGEX.find(chapter.name)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Double.MAX_VALUE
    },
)

/**
 * Returns true if [doc] contains the common pagination selectors for a "next page" link.
 * Covers Bootstrap-style, rel="next", and aria-label variants seen across extensions.
 */
fun standardHasNextPage(doc: Document): Boolean = doc.selectFirst("""a[rel="next"]""") != null ||
    doc.selectFirst(".pagination li.active + li:not(.disabled) a") != null ||
    doc.selectFirst("""a.page-link[aria-label*="Next"]""") != null ||
    doc.selectFirst("""nav[aria-label*="Pagination"] a[rel="next"]""") != null

/**
 * Throws an [Exception] prompting the user to open a WebView if the document looks like a
 * Cloudflare challenge page.
 */
fun checkCloudflare(doc: Document) {
    if (doc.title().contains("Cloudflare", ignoreCase = true)) {
        throw Exception("Cloudflare challenge detected. Please open in WebView.")
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

private val CHAPTER_NUMBER_REGEX =
    Regex("""(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

private suspend fun fetchAll(
    fetchPage: suspend (page: Int) -> Pair<List<SChapter>, Boolean>,
    startPage: Int = 1,
): List<SChapter> {
    val all = mutableListOf<SChapter>()
    var page = startPage
    while (true) {
        val (chapters, hasNext) = fetchPage(page)
        all += chapters
        if (!hasNext) break
        page++
    }
    return all
}
