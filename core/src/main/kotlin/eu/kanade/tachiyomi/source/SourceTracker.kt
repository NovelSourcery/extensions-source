package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional interface a [Source] can implement to react to user reading/library events.
 *
 * @since extensions-lib 1.6
 */
interface SourceTracker {

    /** Whether this source wants chapter read/unread callbacks. Default true. */
    val supportsChapterTracking: Boolean
        get() = true

    /** Whether this source wants favorite/unfavorite callbacks. Default false. */
    val supportsFavoritesTracking: Boolean
        get() = false

    /**
     * Called after chapters are marked read.
     *
     * @param manga the manga whose chapters were touched.
     * @param changedChapters chapters whose read state flipped to read in this batch.
     *                        May contain arbitrary numbers (e.g. 1, 3, 4) — not just the latest.
     * @param allChapters every chapter the app knows about for this manga, with current
     *                    [SChapter.read] state populated. Use this for full context.
     * @param categories names of categories this manga belongs to. Empty list if the
     *                   manga has no user-named categories.
     */
    suspend fun onChaptersRead(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) = Unit

    /** Called after chapters are marked unread. Same parameters as [onChaptersRead]. */
    suspend fun onChaptersUnread(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) = Unit

    /** Called after the user added this manga to the library. */
    suspend fun onFavorited(
        manga: SManga,
        categories: List<String>,
    ) = Unit

    /** Called after the user removed this manga from the library. */
    suspend fun onUnfavorited(
        manga: SManga,
        categories: List<String>,
    ) = Unit
}
