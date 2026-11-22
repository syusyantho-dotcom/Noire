package com.noir.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Stores complete chapter page sets inside the app's private files directory.
 * Nothing is exposed to shared storage and no storage permission is required.
 */
object OfflineChapterStore {
    private const val PREFS = "noir_offline"
    private const val INDEX = "downloaded_chapters"
    private val lock = Any()
    private val client get() = NoirNetwork.client

    fun key(series: Series, chapter: Chapter): String =
        "${series.sourceId}:${series.id}:${chapter.id}"

    fun downloadedKeys(context: Context): Set<String> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(INDEX, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(stored)
            buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
        }.getOrDefault(emptySet())
    }

    fun isDownloaded(context: Context, series: Series, chapter: Chapter): Boolean =
        key(series, chapter) in downloadedKeys(context)

    fun localPages(context: Context, series: Series, chapter: Chapter): List<String>? {
        if (!isDownloaded(context, series, chapter)) return null
        val directory = chapterDirectory(context, series, chapter)
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".img") }
            ?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) return null
        return files.map { it.toURI().toString() }
    }

    suspend fun download(
        context: Context,
        series: Series,
        chapter: Chapter,
        onProgress: (Int, Int) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val source = SourceRegistry.byId(series.sourceId)
            ?: throw IOException("The original source is no longer available.")
        val pageUrls = source.pages(chapter).pages
        if (pageUrls.isEmpty()) throw IOException("This chapter has no downloadable pages.")

        val directory = chapterDirectory(context, series, chapter)
        if (directory.exists()) directory.deleteRecursively()
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create the offline chapter folder.")
        }

        try {
            pageUrls.forEachIndexed { index, url ->
                val request = Request.Builder()
                    .url(url)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Page ${index + 1} failed (HTTP ${response.code}).")
                    }
                    val bytes = response.body?.bytes()
                        ?: throw IOException("Page ${index + 1} was empty.")
                    if (bytes.isEmpty()) throw IOException("Page ${index + 1} was empty.")
                    File(directory, "page_${(index + 1).toString().padStart(4, '0')}.img").writeBytes(bytes)
                }
                onProgress(index + 1, pageUrls.size)
            }

            synchronized(lock) {
                val keys = downloadedKeys(context).toMutableSet()
                keys.add(key(series, chapter))
                val array = JSONArray()
                keys.forEach(array::put)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(INDEX, array.toString()).commit()
            }
            directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                ?.map { it.toURI().toString() } ?: emptyList()
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    /** Removes one downloaded chapter from the device and from the index. */
    suspend fun delete(context: Context, series: Series, chapter: Chapter) = withContext(Dispatchers.IO) {
        chapterDirectory(context, series, chapter).deleteRecursively()
        synchronized(lock) {
            val keys = downloadedKeys(context).toMutableSet()
            keys.remove(key(series, chapter))
            val array = JSONArray()
            keys.forEach(array::put)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(INDEX, array.toString()).commit()
        }
    }

    /** Keeps the chapter list of a series with downloads so those chapters still open without a connection. */
    fun saveChapterList(context: Context, series: Series, chapters: List<Chapter>) {
        runCatching {
            val array = JSONArray()
            chapters.forEach { c ->
                array.put(org.json.JSONObject()
                    .put("id", c.id).put("name", c.name).put("number", c.number).put("language", c.language)
                    .put("sourceUrl", c.sourceUrl ?: org.json.JSONObject.NULL)
                    .put("sourceTitle", c.sourceTitle ?: org.json.JSONObject.NULL)
                    .put("sourceBranch", c.sourceBranch ?: org.json.JSONObject.NULL)
                    .put("sourceUploadDate", c.sourceUploadDate)
                    .put("sourceVolume", c.sourceVolume)
                    .put("sourceScanlator", c.sourceScanlator ?: org.json.JSONObject.NULL))
            }
            val file = chapterListFile(context, series)
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }
    }

    fun cachedChapterList(context: Context, series: Series): List<Chapter> = runCatching {
        val file = chapterListFile(context, series)
        if (!file.isFile) return@runCatching emptyList<Chapter>()
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                fun text(name: String) = if (o.isNull(name)) null else o.optString(name)
                add(Chapter(
                    id = o.optString("id"), name = o.optString("name"), number = o.optString("number"),
                    language = o.optString("language"), sourceUrl = text("sourceUrl"),
                    sourceTitle = text("sourceTitle"), sourceBranch = text("sourceBranch"),
                    sourceUploadDate = o.optLong("sourceUploadDate", 0L),
                    sourceVolume = o.optInt("sourceVolume", 0), sourceScanlator = text("sourceScanlator")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun seriesDirectory(context: Context, series: Series): File =
        File(File(context.filesDir, "offline_chapters"), safe(series.sourceId) + "_" + safe(series.id))

    private fun chapterListFile(context: Context, series: Series): File =
        File(seriesDirectory(context, series), "_chapters.json")

    private fun chapterDirectory(context: Context, series: Series, chapter: Chapter): File =
        File(seriesDirectory(context, series), safe(chapter.id))
}
