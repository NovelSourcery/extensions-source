package eu.kanade.tachiyomi.novelextension.en.foxaholic

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class Foxaholic : MadaraNovel() {
    // Uses new chapter endpoint (/ajax/chapters/) which returns clean chapter HTML
    // The old admin-ajax.php endpoint returns the full page instead of chapter list
    override val useNewChapterEndpointDefault = true

    // fetchPageText is inherited from MadaraNovel, which already covers this site's content
    // selectors and ad classes (it was written with foxaholic-*-prefixed ad classes in mind) and
    // handles page.url correctly whether it's absolute or relative. A prior override here
    // re-prepended baseUrl onto page.url, which is already absolute - producing a "site+https"
    // URL and a request that never resolved.
}
