package eu.kanade.tachiyomi.extension.tr.ekitaplar

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class EKitaplar :
    MadaraNovel(
        baseUrl = "https://e-kitaplar.com",
        name = "EKitaplar",
        lang = "tr",
    ) {
    override val useNewChapterEndpointDefault = true
}
