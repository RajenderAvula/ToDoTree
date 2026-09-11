package com.example.infinitetodo

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
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

    fun insertEvent(
        context: Context,
        calendarId: Long,
        title: String,
        startTimeMs: Long,
        notes: String?
    ): Long? {
        return try {
            val endTimeMs = startTimeMs + (60 * 60 * 1000) // Default 1 hour duration
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startTimeMs)
                put(CalendarContract.Events.DTEND, endTimeMs)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, notes ?: "")
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
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
        notes: String?
    ) {
        try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                if (notes != null) {
                    put(CalendarContract.Events.DESCRIPTION, notes)
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
