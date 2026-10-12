package eu.kanade.tachiyomi.novelextension.en.readhive

import eu.kanade.tachiyomi.multisrc.nzoseries.NzoSeries
import keiyoushi.annotation.Source

@Source
abstract class ReadHive : NzoSeries() {

    override val browsePath = "/browse-series/"

    // The "latest" order returns nothing unless logged in.
    override val supportsLatest = false
}
