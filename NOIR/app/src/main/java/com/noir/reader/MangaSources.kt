package com.noir.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Series(
    val id: String,
    val title: String,
    val description: String,
    val coverUrl: String?,
    val sourceId: String,
    val sourceName: String
)

data class Chapter(
    val id: String,
    val name: String,
    val number: String,
    val language: String
)

data class ChapterPages(
    val chapterId: String,
    val pages: List<String>
)

interface MangaSource {
    val id: String
    val name: String
    val description: String
    suspend fun popular(page: Int = 0): List<Series>
    suspend fun search(query: String, page: Int = 0): List<Series>
    suspend fun chapters(series: Series): List<Chapter>
    suspend fun pages(chapter: Chapter): ChapterPages
}

class MangaDexSource : MangaSource {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val description = "Community catalogue with safe-rated titles"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.mangadex.org$path")
            .header("User-Agent", "NOIR-Reader/0.5 (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Source returned HTTP ${response.code}")
            JSONObject(response.body?.string() ?: throw IOException("Empty response"))
        }
    }

    private fun seriesFrom(item: JSONObject): Series {
        val attributes = item.optJSONObject("attributes") ?: JSONObject()
        val titleObject = attributes.optJSONObject("title") ?: JSONObject()
        val title = titleObject.optString("en").takeIf { it.isNotBlank() }
            ?: titleObject.keys().asSequence().map { titleObject.optString(it) }.firstOrNull { it.isNotBlank() }
            ?: "Untitled"
        val descriptionObject = attributes.optJSONObject("description") ?: JSONObject()
        val description = descriptionObject.optString("en", "No description available.")
        val relationships = item.optJSONArray("relationships")
        var cover: String? = null
        if (relationships != null) {
            for (i in 0 until relationships.length()) {
                val relation = relationships.optJSONObject(i) ?: continue
                if (relation.optString("type") == "cover_art") {
                    val filename = relation.optJSONObject("attributes")?.optString("fileName")
                    if (!filename.isNullOrBlank()) cover = "https://uploads.mangadex.org/covers/${item.optString("id")}/$filename.256.jpg"
                    break
                }
            }
        }
        return Series(item.optString("id"), title, description, cover, id, name)
    }

    private fun mangaUrl(query: String?, page: Int): String {
        val url = "https://api.mangadex.org/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "24")
            .addQueryParameter("offset", (page * 24).toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("order[followedCount]", "desc")
        if (!query.isNullOrBlank()) url.addQueryParameter("title", query)
        return url.build().toString()
    }

    private suspend fun loadSeries(query: String?, page: Int): List<Series> {
        val json = get(mangaUrl(query, page).substringAfter("https://api.mangadex.org"))
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.let(::seriesFrom)
        }
    }

    override suspend fun popular(page: Int) = loadSeries(null, page)
    override suspend fun search(query: String, page: Int) = loadSeries(query, page)

    override suspend fun chapters(series: Series): List<Chapter> {
        val url = "https://api.mangadex.org/manga/${series.id}/feed".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "100")
            .addQueryParameter("translatedLanguage[]", "en")
            .addQueryParameter("order[chapter]", "desc")
            .addQueryParameter("order[volume]", "desc")
            .addQueryParameter("includes[]", "scanlation_group")
            .build()
        val json = get(url.toString().substringAfter("https://api.mangadex.org"))
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val a = item.optJSONObject("attributes") ?: JSONObject()
            val number = a.optString("chapter", "?")
            val title = a.optString("title")
            Chapter(item.optString("id"), if (title.isBlank()) "Chapter $number" else "Chapter $number · $title", number, a.optString("translatedLanguage", "en"))
        }
    }

    override suspend fun pages(chapter: Chapter): ChapterPages {
        val json = get("/at-home/server/${chapter.id}")
        val base = json.optString("baseUrl")
        val chapterObject = json.optJSONObject("chapter") ?: JSONObject()
        val hash = chapterObject.optString("hash")
        val files = chapterObject.optJSONArray("data") ?: return ChapterPages(chapter.id, emptyList())
        val pages = (0 until files.length()).map { i ->
            "$base/data/$hash/${files.optString(i)}"
        }
        return ChapterPages(chapter.id, pages)
    }
}

object SourceRegistry {
    // New sources implement MangaSource and register here; UI and reader remain source-agnostic.
    val sources: List<MangaSource> = listOf(MangaDexSource())
    fun byId(id: String): MangaSource? = sources.firstOrNull { it.id == id }
}
