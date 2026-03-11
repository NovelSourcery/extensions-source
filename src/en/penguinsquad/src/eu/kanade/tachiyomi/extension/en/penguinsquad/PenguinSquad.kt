package eu.kanade.tachiyomi.extension.en.penguinsquad

import eu.kanade.tachiyomi.multisrc.fictioneer.Fictioneer

class PenguinSquad :
    Fictioneer(
        name = "PenguinSquad",
        baseUrl = "https://penguin-squad.com",
        lang = "en",
    ) {
    override val browsePage = "novels"
}
