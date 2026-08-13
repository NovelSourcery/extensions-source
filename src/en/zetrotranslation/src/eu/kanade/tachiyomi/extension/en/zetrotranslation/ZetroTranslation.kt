package eu.kanade.tachiyomi.novelextension.en.zetrotranslation

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ZetroTranslation :
    MadaraNovel(
        baseUrl = "https://zetrotranslation.com",
        name = "Zetro Translation",
        lang = "en",
    ),
    ConfigurableSource {
    override val reverseChapterListDefault = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val includeLocked: Boolean
        get() = preferences.getBoolean(PREF_INCLUDE_LOCKED, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        CheckBoxPreference(screen.context).apply {
            key = PREF_INCLUDE_LOCKED
            title = "Include locked chapters"
            summary = "Show chapters that require payment"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // getMangaUpdate is MadaraNovel's real entry point — chapterListParse below is only reachable
    // via the legacy fetchChapterList path, so the locked-chapter filter has to live here too or it
    // silently never runs.
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val update = super.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
        if (!fetchChapters || includeLocked) return update

        return SMangaUpdate(update.manga, update.chapters.filterNot { ch -> ch.isLocked() })
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val list = super.chapterListParse(response)
        if (includeLocked) return list

        return list.filterNot { ch -> ch.isLocked() }
    }

    private fun SChapter.isLocked(): Boolean {
        val chapterName = name ?: ""
        return chapterName.contains("🔒") || chapterName.contains("paid", true) ||
            chapterName.contains("vip", true) || chapterName.contains("locked", true)
    }

    companion object {
        private const val PREF_INCLUDE_LOCKED = "zetrotranslation_include_locked"
    }
}
