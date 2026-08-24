package eu.kanade.tachiyomi.novelextension.en.novelbinnet

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

/**
 * Novel-Bin (net). Ported from two divergent feature-branch attempts (`ext/novelbinnet`,
 * `feat/novelbinnet`) written against the pre-KeiSource-migration theme API; ended up needing
 * almost none of either's custom logic - live-verified the site's markup
 * (`div.row` > `h3.novel-title a` cards, `ul.list-chapter li a` chapters) already matches the
 * modern [ReadNovelFull] base class's default selectors exactly.
 */
@Source
abstract class NovelBinNet : ReadNovelFull() {
    override val popularPage = "monthvisit"
    override val latestPage = "dayvisit"

    /** [SManga.url] is stored as the bare slug under "/novel-bin/" (the site migrated off an
     * older "/b/<slug>" path at some point - no released version of this extension ever shipped
     * under that scheme, so there's no legacy stored data to keep resolving). */
    override val mangaPathTemplate = SlugPath("/novel-bin/")

    // Verified live: novel pages have no div#rating[data-novel-id], and the url has no digits for
    // the base class's id-from-path fallback either, so the ajax/chapter-archive attempt would
    // always fail over to the same direct-page parse anyway - skip straight to it.
    override val noAjax = true
}
