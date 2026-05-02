package eu.kanade.tachiyomi.extension.en.zetrotranslation

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
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

    private val excludeLocked: Boolean
        get() = preferences.getBoolean(PREF_EXCLUDE_LOCKED, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = PREF_EXCLUDE_LOCKED
            title = "Exclude locked chapters"
            summary = "Hide chapters that require payment"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val list = super.chapterListParse(response)
        if (!excludeLocked) return list

        return list.filterNot { ch ->
            val name = ch.name ?: ""
            name.contains("🔒") || name.contains("paid", true) || name.contains("vip", true) || name.contains("locked", true)
        }
    }

    companion object {
        private const val PREF_EXCLUDE_LOCKED = "zetrotranslation_exclude_locked"
    }
}
