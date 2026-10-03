package keiyoushi.source

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

private const val ACTION_SEARCH = "eu.kanade.tachiyomi.SEARCH"
private const val ACTION_SEARCH_NOVEL = "eu.kanade.tachiyomi.novel.SEARCH"

class UrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        if (data != null) {
            val intent = Intent().apply {
                setAction(searchAction())
                putExtra("query", data.toString())
                putExtra("filter", packageName)
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.e(packageName, "Unable to launch activity", e)
            }
        }
        finish()
        exitProcess(0)
    }

    private fun searchAction(): String {
        val isNovel = runCatching {
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                .metaData?.getInt("tachiyomi.novelextension.novel", 1) == 1
        }.getOrDefault(true)
        return if (isNovel) ACTION_SEARCH_NOVEL else ACTION_SEARCH
    }
}
