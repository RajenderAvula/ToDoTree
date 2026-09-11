package com.example.infinitetodo

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

object CalendarHelper {

    /**
     * Resolves the primary Google account calendar ID.
     */
    fun getPrimaryGoogleCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY
        )

        val uri = CalendarContract.Calendars.CONTENT_URI
        val selection = "(${CalendarContract.Calendars.ACCOUNT_TYPE} = ?)"
        val selectionArgs = arrayOf("com.google")

        return try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val primaryCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)

                var fallbackId: Long? = null
                while (cursor.moveToNext()) {
                    val calId = cursor.getLong(idCol)
                    val isPrimary = cursor.getInt(primaryCol) == 1
                    if (isPrimary) return calId
                    if (fallbackId == null) fallbackId = calId
                }
                fallbackId
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Inserts an event silently into Google Calendar with a 10-minute popup reminder.
     */
    fun insertEvent(
        context: Context,
        calendarId: Long,
        title: String,
        startTimeMs: Long,
        notes: String
    ): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, notes)
            put(CalendarContract.Events.DTSTART, startTimeMs)
            put(CalendarContract.Events.DTEND, startTimeMs + (60 * 60 * 1000)) // 1 hour duration
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }

        return try {
            val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = eventUri?.lastPathSegment?.toLongOrNull()

            if (eventId != null) {
                // Add a notification reminder 10 minutes prior
                val reminderValues = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    put(CalendarContract.Reminders.MINUTES, 10)
                }
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
            }
            eventId
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Deletes an event silently by its Google Calendar event ID.
     */
    fun deleteEvent(context: Context, eventId: Long) {
        try {
            val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(deleteUri, null, null)
        } catch (_: SecurityException) {
            // Handled when calendar access permission is revoked
        }
    }
}

