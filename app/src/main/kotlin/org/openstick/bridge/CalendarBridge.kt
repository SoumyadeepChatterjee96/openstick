package org.openstick.bridge

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Reads Android Calendar events over the next 14 days and writes a clean JSON file
 * into Linux guest (/run/openstick/calendar).
 */
class CalendarBridge(
    private val context: Context,
    private val rootfsDir: File
) {
    companion object {
        private const val TAG = "CalendarBridge"
        private const val DAYS_AHEAD = 14
        private const val MAX_EVENTS = 150
    }

    private val targetFile = File(rootfsDir, "run/openstick/calendar")

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun sync() {
        if (!hasPermission()) return

        try {
            val now = System.currentTimeMillis()
            val end = now + (DAYS_AHEAD.toLong() * 24 * 60 * 60 * 1000)

            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, now)
            ContentUris.appendId(uriBuilder, end)

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION
            )

            val cursor: Cursor? = context.contentResolver.query(
                uriBuilder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            val eventsJson = StringBuilder("[\n")
            var count = 0

            cursor?.use { c ->
                val titleCol = c.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginCol = c.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endCol = c.getColumnIndex(CalendarContract.Instances.END)
                val allDayCol = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val locCol = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

                while (c.moveToNext() && count < MAX_EVENTS) {
                    val title = if (titleCol >= 0) c.getString(titleCol) ?: "Untitled" else "Untitled"
                    val begin = if (beginCol >= 0) c.getLong(beginCol) else 0L
                    val endMs = if (endCol >= 0) c.getLong(endCol) else 0L
                    val allDay = if (allDayCol >= 0) c.getInt(allDayCol) == 1 else false
                    val loc = if (locCol >= 0) c.getString(locCol) ?: "" else ""

                    if (count > 0) eventsJson.append(",\n")
                    eventsJson.append("  {")
                        .append("\"title\": \"").append(escape(title)).append("\", ")
                        .append("\"start\": ").append(begin).append(", ")
                        .append("\"end\": ").append(endMs).append(", ")
                        .append("\"all_day\": ").append(allDay).append(", ")
                        .append("\"location\": \"").append(escape(loc)).append("\"")
                        .append("}")
                    count++
                }
            }
            eventsJson.append("\n]\n")

            targetFile.parentFile?.mkdirs()
            targetFile.writeText(eventsJson.toString())

            // Mirror compatibility
            val bslFile = File(rootfsDir, "run/bsl/calendar")
            bslFile.parentFile?.mkdirs()
            bslFile.writeText(eventsJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync calendar", e)
        }
    }

    private fun escape(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", " ")
    }
}
