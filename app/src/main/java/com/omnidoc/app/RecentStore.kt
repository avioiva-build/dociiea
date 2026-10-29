package com.omnidoc.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

data class RecentItem(
    val uri: String,
    val name: String,
    val kind: String,
    val lastOpened: Long = System.currentTimeMillis(),
    val lastPage: Int = 0
)

/** Simple Gson-in-SharedPreferences store of the last 30 opened documents. */
class RecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("omnidoc_recents", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun all(): List<RecentItem> {
        val json = prefs.getString("items", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<MutableList<RecentItem>>() {}.type
            gson.fromJson<MutableList<RecentItem>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun touch(uri: String, name: String, kind: String) {
        val items = all().toMutableList()
        items.removeAll { it.uri == uri }
        items.add(0, RecentItem(uri, name, kind))
        while (items.size > 30) items.removeAt(items.size - 1)
        save(items)
    }

    fun remove(uri: String) {
        save(all().toMutableList().apply { removeAll { it.uri == uri } })
    }

    fun saveLastPage(uri: String, page: Int) {
        val items = all().toMutableList()
        val idx = items.indexOfFirst { it.uri == uri }
        if (idx >= 0) {
            items[idx] = items[idx].copy(lastPage = page, lastOpened = System.currentTimeMillis())
            save(items)
        }
    }

    private fun save(items: List<RecentItem>) {
        prefs.edit().putString("items", gson.toJson(items)).apply()
    }
}
