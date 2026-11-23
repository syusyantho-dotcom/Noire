package com.noir.reader

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** User-chosen or auto-found cover per series, kept across launches. */
object CoverOverrides {
    val map = mutableStateMapOf<String, String>()
    private var prefs: SharedPreferences? = null

    fun key(series: Series) = "${series.sourceId}:${series.id}"

    fun init(preferences: SharedPreferences) {
        if (prefs != null) return
        prefs = preferences
        runCatching {
            val stored = JSONObject(preferences.getString("cover_overrides", "{}") ?: "{}")
            stored.keys().forEach { map[it] = stored.getString(it) }
        }
    }

    fun set(key: String, url: String?) {
        if (url.isNullOrBlank()) map.remove(key) else map[key] = url
        val stored = JSONObject()
        map.forEach { (k, v) -> stored.put(k, v) }
        prefs?.edit()?.putString("cover_overrides", stored.toString())?.apply()
    }
}

fun Series.displayCover(): String? = CoverOverrides.map[CoverOverrides.key(this)] ?: coverUrl

/** Looks for a higher-resolution cover on MangaDex and AniList, matching by exact title. */
object CoverFinder {
    private fun norm(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    suspend fun widthOf(url: String): Int = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext 9999
        runCatching {
            NoirNetwork.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val stream = r.body?.byteStream() ?: return@use 0
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                options.outWidth.coerceAtLeast(0)
            }
        }.getOrDefault(0)
    }

    suspend fun findBest(title: String): String? = withContext(Dispatchers.IO) {
        val wanted = norm(title)
        if (wanted.isEmpty()) return@withContext null
        val dex = runCatching { mangaDex(title, wanted) }.getOrNull()
        val ani = runCatching { aniList(title, wanted) }.getOrNull()
        val dexW = dex?.let { widthOf(it) } ?: 0
        val aniW = ani?.let { widthOf(it) } ?: 0
        if (dexW == 0 && aniW == 0) null else if (dexW >= aniW) dex else ani
    }

    private fun mangaDex(title: String, wanted: String): String? {
        val q = URLEncoder.encode(title, "UTF-8")
        val url = "https://api.mangadex.org/manga?title=$q&limit=8&includes%5B%5D=cover_art" +
            "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive&contentRating%5B%5D=erotica"
        NoirNetwork.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val data = JSONObject(r.body?.string() ?: return null).optJSONArray("data") ?: return null
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val attrs = item.optJSONObject("attributes") ?: continue
                val names = mutableListOf<String>()
                attrs.optJSONObject("title")?.let { t -> t.keys().forEach { names += t.optString(it) } }
                attrs.optJSONArray("altTitles")?.let { alts ->
                    for (j in 0 until alts.length()) alts.getJSONObject(j).let { t -> t.keys().forEach { names += t.optString(it) } }
                }
                if (names.none { norm(it) == wanted }) continue
                val rels = item.optJSONArray("relationships") ?: continue
                for (j in 0 until rels.length()) {
                    val rel = rels.getJSONObject(j)
                    if (rel.optString("type") == "cover_art") {
                        val file = rel.optJSONObject("attributes")?.optString("fileName").orEmpty()
                        if (file.isNotBlank()) return "https://uploads.mangadex.org/covers/${item.getString("id")}/$file"
                    }
                }
            }
        }
        return null
    }

    private fun aniList(title: String, wanted: String): String? {
        val body = JSONObject()
            .put("query", "query(\$s:String){Page(perPage:6){media(search:\$s,type:MANGA){title{romaji english native} synonyms coverImage{extraLarge}}}}")
            .put("variables", JSONObject().put("s", title))
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://graphql.anilist.co").post(body).build()
        NoirNetwork.client.newCall(request).execute().use { r ->
            val media = JSONObject(r.body?.string() ?: return null).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return null
            for (i in 0 until media.length()) {
                val m = media.getJSONObject(i)
                val names = mutableListOf<String>()
                m.optJSONObject("title")?.let { t -> listOf("romaji", "english", "native").forEach { names += t.optString(it) } }
                m.optJSONArray("synonyms")?.let { s -> for (j in 0 until s.length()) names += s.optString(j) }
                if (names.any { norm(it) == wanted }) {
                    val cover = m.optJSONObject("coverImage")?.optString("extraLarge").orEmpty()
                    if (cover.isNotBlank()) return cover
                }
            }
        }
        return null
    }
}
