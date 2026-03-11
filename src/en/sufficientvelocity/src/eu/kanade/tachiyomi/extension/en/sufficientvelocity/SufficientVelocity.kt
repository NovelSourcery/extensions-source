package eu.kanade.tachiyomi.extension.en.sufficientvelocity

import eu.kanade.tachiyomi.multisrc.xenforo.XenForo

class SufficientVelocity :
    XenForo(
        name = "Sufficient Velocity",
        baseUrl = "https://forums.sufficientvelocity.com",
    ) {
    override val reverseChapters = true

    override val forums = listOf(
        "User Fiction" to 2,
        "Original Fiction" to 157,
        "Worm" to 94,
        "Archive" to 31,
        "Alternate History" to 91,
        "Weird History" to 95,
        "Quests" to 29,
        "Quests Archive" to 17,
    )

    override val novelUrlBlacklist = Regex(".*\\.20442$")
}
