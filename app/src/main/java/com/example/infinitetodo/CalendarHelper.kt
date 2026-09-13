package com.example.infinitetodo

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

object CalendarHelper {

    fun getPrimaryGoogleCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        val uri = CalendarContract.Calendars.CONTENT_URI
        val cursor = context.contentResolver.query(uri, projection, null, null, null)

        cursor?.use {
            var fallbackId: Long? = null
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val isPrimary = it.getInt(1)
                val accountType = it.getString(2)

                if (isPrimary == 1) return id
                if (accountType == "com.google") fallbackId = id
            }
            if (fallbackId != null) return fallbackId
        }
        return 1L
    }

    private fun normalizeToMidnightUtc(epochMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun insertEvent(
        context: Context,
        calendarId: Long,
        title: String,
        startTimeMs: Long,
        notes: String?,
        isAllDay: Boolean = false
    ): Long? {
        return try {
            val values = ContentValues().apply {
                if (isAllDay) {
                    val startUtc = normalizeToMidnightUtc(startTimeMs)
                    val endUtc = startUtc + 86400000L
                    put(CalendarContract.Events.DTSTART, startUtc)
                    put(CalendarContract.Events.DTEND, endUtc)
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    put(CalendarContract.Events.DTSTART, startTimeMs)
                    put(CalendarContract.Events.DTEND, startTimeMs + 3600000L)
                    put(CalendarContract.Events.ALL_DAY, 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, notes ?: "")
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.lastPathSegment?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun updateEvent(
        context: Context,
        eventId: Long,
        title: String,
        notes: String?,
        startTimeMs: Long,
        isAllDay: Boolean = false
    ) {
        try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                if (notes != null) {
                    put(CalendarContract.Events.DESCRIPTION, notes)
                }
                if (isAllDay) {
                    val startUtc = normalizeToMidnightUtc(startTimeMs)
                    val endUtc = startUtc + 86400000L
                    put(CalendarContract.Events.DTSTART, startUtc)
                    put(CalendarContract.Events.DTEND, endUtc)
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    put(CalendarContract.Events.DTSTART, startTimeMs)
                    put(CalendarContract.Events.DTEND, startTimeMs + 3600000L)
                    put(CalendarContract.Events.ALL_DAY, 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
            }
            val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.update(updateUri, values, null, null)
        } catch (_: Exception) {}
    }

    fun deleteEvent(context: Context, eventId: Long) {
        try {
            val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(deleteUri, null, null)
        } catch (_: Exception) {}
    }
}
