package com.example.raybanvision.session

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.example.raybanvision.data.SavedLink
import org.json.JSONArray
import org.json.JSONObject

object SavedLinksStore {
    private const val PREFS_NAME = "saved_links"
    private const val KEY_LINKS = "links"

    val links = mutableStateListOf<SavedLink>()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    fun save(link: SavedLink) {
        if (link.linkUrl.isNotEmpty() && links.none { it.linkUrl == link.linkUrl }) {
            links.add(0, link)
            persist()
        }
    }

    fun remove(linkUrl: String) {
        if (links.removeAll { it.linkUrl == linkUrl }) {
            persist()
        }
    }

    private fun load() {
        val context = appContext ?: return
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LINKS, null)
            ?: return
        runCatching {
            val array = JSONArray(raw)
            links.clear()
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                links.add(
                    SavedLink(
                        productName = item.optString("productName"),
                        store = item.optString("store"),
                        price = item.optString("price"),
                        linkUrl = item.optString("linkUrl"),
                        savedAt = item.optString("savedAt"),
                    ),
                )
            }
        }
    }

    private fun persist() {
        val context = appContext ?: return
        val array = JSONArray()
        links.forEach { link ->
            array.put(
                JSONObject()
                    .put("productName", link.productName)
                    .put("store", link.store)
                    .put("price", link.price)
                    .put("linkUrl", link.linkUrl)
                    .put("savedAt", link.savedAt),
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LINKS, array.toString())
            .apply()
    }
}
