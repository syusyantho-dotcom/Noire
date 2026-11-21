package com.noir.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject

/**
 * Periodically checks followed titles. This is best-effort background work:
 * Android may defer execution according to battery and network constraints.
 */
class ReleaseRadarWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            SourceRegistry.initialize(applicationContext)
            val prefs = applicationContext.getSharedPreferences("noir_preferences", Context.MODE_PRIVATE)
            val saved = JSONArray(prefs.getString("saved_series", "[]") ?: "[]")
            val snapshots = JSONObject(prefs.getString("radar_snapshots", "{}") ?: "{}")
            val updated = JSONObject()
            val discoveries = mutableListOf<Pair<String, List<String>>>()

            for (index in 0 until saved.length()) {
                val item = saved.optJSONObject(index) ?: continue
                val series = Series(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    description = item.optString("description"),
                    coverUrl = item.optString("coverUrl").takeIf { it.isNotBlank() },
                    sourceId = item.optString("sourceId"),
                    sourceName = item.optString("sourceName"),
                    sourceUrl = item.optString("sourceUrl").takeIf { it.isNotBlank() },
                    sourcePublicUrl = item.optString("sourcePublicUrl").takeIf { it.isNotBlank() },
                    tags = item.optJSONArray("tags")?.let { tags ->
                        (0 until tags.length()).map { tags.optString(it) }
                    }.orEmpty(),
                    status = item.optString("status").takeIf { it.isNotBlank() },
                    originalLanguage = item.optString("originalLanguage").takeIf { it.isNotBlank() },
                    year = item.optInt("year", 0).takeIf { it > 0 }
                )
                val key = "${series.sourceId}:${series.id}"
                val source = SourceRegistry.byId(series.sourceId) ?: continue
                val chapters = source.chapters(series)
                val latestIds = chapters.take(60).map { it.id }.toSet()
                val old = snapshots.optJSONArray(key)
                if (old != null) {
                    val previous = buildSet {
                        for (i in 0 until old.length()) add(old.optString(i))
                    }
                    val fresh = chapters.filter { it.id !in previous }.take(5).map { it.name }
                    if (fresh.isNotEmpty()) discoveries += series.title to fresh
                }
                updated.put(key, JSONArray(latestIds.toList()))
            }

            // Preserve snapshots for titles that are temporarily absent from the saved list.
            snapshots.keys().forEach { key -> if (!updated.has(key)) updated.put(key, snapshots.opt(key)) }
            prefs.edit()
                .putString("radar_snapshots", updated.toString())
                .putLong("radar_last_checked", System.currentTimeMillis())
                .apply()

            if (discoveries.isNotEmpty()) postNotification(discoveries)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun postNotification(discoveries: List<Pair<String, List<String>>>) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "noir_release_radar"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "New chapter alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "New chapters from series in your NOIR library"
                }
            )
        }
        val lines = discoveries.take(5).map { (title, chapters) ->
            "$title: ${chapters.joinToString()}"
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (discoveries.size == 1) "New chapter available" else "${discoveries.size} series have new chapters")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(1601, notification) }
    }
}
