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
    val sourceName: String,
    val sourceUrl: String? = null,
    val sourcePublicUrl: String? = null,
    val tags: List<String> = emptyList(),
    val status: String? = null,
    val originalLanguage: String? = null,
    val year: Int? = null
)

data class Chapter(
    val id: String,
    val name: String,
    val number: String,
    val language: String,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val sourceBranch: String? = null,
    val sourceUploadDate: Long = 0L,
    val sourceVolume: Int = 0,
    val sourceScanlator: String? = null
)

data class ChapterPages(
    val chapterId: String,
    val pages: List<String>
)

data class SourceTagOption(val title: String, val key: String)
data class SourceFilterOptions(
    val sortOrders: List<String> = listOf("POPULARITY"),
    val states: List<String> = emptyList(),
    val tags: List<SourceTagOption> = emptyList()
)

interface MangaSource {
    val id: String
    val name: String
    val description: String
    val language: String get() = ""
    val category: String get() = "Manga"
    val isBroken: Boolean get() = false
    suspend fun iconUrl(): String? = null
    suspend fun popular(page: Int = 0): List<Series>
    suspend fun search(query: String, page: Int = 0): List<Series>
    suspend fun chapters(series: Series): List<Chapter>
    suspend fun pages(chapter: Chapter): ChapterPages
    suspend fun filterOptions(): SourceFilterOptions = SourceFilterOptions()
    suspend fun browse(
        query: String, page: Int, sort: String, state: String?, tagKey: String?
    ): List<Series> = if (query.isBlank()) popular(page) else search(query, page)
}

class MangaDexSource : MangaSource {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val description = "Community catalogue with safe-rated titles"
    override val language = "EN / ID"
    override val category = "Manga"
    override suspend fun iconUrl(): String = "https://mangadex.org/favicon.ico"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.mangadex.org$path")
            .header("User-Agent", "NOIR-Reader/1.0 (Android)")
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
        val tagsArray = attributes.optJSONArray("tags")
        val tags = if (tagsArray == null) emptyList() else (0 until tagsArray.length()).mapNotNull { index ->
            val tag = tagsArray.optJSONObject(index) ?: return@mapNotNull null
            val tagAttributes = tag.optJSONObject("attributes") ?: return@mapNotNull null
            val names = tagAttributes.optJSONObject("name") ?: return@mapNotNull null
            names.optString("en").takeIf { it.isNotBlank() }
        }
        val year = attributes.optInt("year", 0).takeIf { it > 0 }
        return Series(
            item.optString("id"), title, description, cover, id, name,
            tags = tags,
            status = attributes.optString("status").takeIf { it.isNotBlank() },
            originalLanguage = attributes.optString("originalLanguage").takeIf { it.isNotBlank() },
            year = year
        )
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
        // MangaDex caps feed responses; page through the complete feed instead of
        // silently hiding everything after the first 100 chapters.
        val result = mutableListOf<Chapter>()
        var offset = 0
        val pageSize = 500
        while (true) {
            val builder = "https://api.mangadex.org/manga/${series.id}/feed".toHttpUrl().newBuilder()
                .addQueryParameter("limit", pageSize.toString())
                .addQueryParameter("offset", offset.toString())
                // English and Indonesian translations are both useful defaults for NOIR readers.
                .addQueryParameter("translatedLanguage[]", "en")
                .addQueryParameter("translatedLanguage[]", "id")
                .addQueryParameter("order[chapter]", "desc")
                .addQueryParameter("order[volume]", "desc")
                .addQueryParameter("includes[]", "scanlation_group")
            val json = get(builder.build().toString().substringAfter("https://api.mangadex.org"))
            val data = json.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val a = item.optJSONObject("attributes") ?: JSONObject()
                val number = a.optString("chapter", "?")
                val title = a.optString("title")
                result += Chapter(
                    item.optString("id"),
                    if (title.isBlank()) "Chapter $number" else "Chapter $number · $title",
                    number,
                    a.optString("translatedLanguage", "en")
                )
            }
            offset += data.length()
            if (data.length() < pageSize) break
        }
        return result.distinctBy { it.id }
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
    @Volatile private var registered: List<MangaSource> = listOf(MangaDexSource())
    @Volatile private var initialized = false

    val sources: List<MangaSource> get() = registered

    @Synchronized
    fun initialize(context: android.content.Context) {
        if (initialized) return
        val parserSources = runCatching {
            KotatsuParserSources.create(context.applicationContext)
        }.onFailure {
            android.util.Log.e("NOIR", "Kotatsu parser registry could not be initialized", it)
        }.getOrDefault(emptyList())
        // Keep NOIR's MangaDex adapter (with its own language and chapter handling) and avoid
        // registering Kotatsu's duplicate MangaDex entry.
        registered = (listOf(MangaDexSource()) + parserSources)
            .distinctBy { it.id }
        initialized = true
    }

    fun byId(id: String): MangaSource? = registered.firstOrNull { it.id == id }
}
