package com.example.infinitetodo

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

object CalendarHelper {

    data class CalendarTarget(
        val id: Long,
        val accountName: String,
        val accountType: String
    )

    fun getPrimaryGoogleCalendar(context: Context): CalendarTarget? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        val uri = CalendarContract.Calendars.CONTENT_URI
        var googleTarget: CalendarTarget? = null
        var fallbackTarget: CalendarTarget? = null

        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val accountName = it.getString(1) ?: ""
                    val accountType = it.getString(2) ?: ""
                    val accessLevel = it.getInt(3)
                    val visible = it.getInt(4)

                    if (accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                        if (accountType.equals("com.google", ignoreCase = true)) {
                            return CalendarTarget(id, accountName, accountType)
                        } else if (fallbackTarget == null && visible == 1) {
                            fallbackTarget = CalendarTarget(id, accountName, accountType)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarHelper", "Error resolving calendar target", e)
        }
        return googleTarget ?: fallbackTarget
    }

    fun eventExists(context: Context, eventId: Long): Boolean {
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DELETED)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val deletedCol = it.getColumnIndex(CalendarContract.Events.DELETED)
                    val isDeleted = if (deletedCol != -1) it.getInt(deletedCol) == 1 else false
                    !isDeleted
                } else {
                    false
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun calculateSafeEndTime(startTimeMs: Long, requestedDurationMs: Long = 1800000L): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = startTimeMs
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val endOfDayMs = cal.timeInMillis
        val naturalEnd = startTimeMs + requestedDurationMs
        return if (naturalEnd > endOfDayMs) endOfDayMs else naturalEnd
    }

    fun insertEvent(
        context: Context,
        target: CalendarTarget,
        title: String,
        createdTimestampMs: Long,
        notes: String?
    ): Long? {
        return try {
            val startTime = createdTimestampMs
            val endTime = calculateSafeEndTime(startTime, 1800000L)
            val localTimeZone = TimeZone.getDefault().id

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, target.id)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, notes ?: "")
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, localTimeZone)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull()

            if (eventId != null && target.accountType.equals("com.google", ignoreCase = true)) {
                triggerGoogleCalendarSync(target.accountName)
            }
            eventId
        } catch (e: Exception) {
            Log.e("CalendarHelper", "Insert event error", e)
            null
        }
    }

    fun updateEvent(
        context: Context,
        target: CalendarTarget,
        eventId: Long,
        title: String,
        notes: String?,
        createdTimestampMs: Long
    ): Boolean {
        return try {
            val startTime = createdTimestampMs
            val endTime = calculateSafeEndTime(startTime, 1800000L)
            val localTimeZone = TimeZone.getDefault().id

            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, notes ?: "")
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, localTimeZone)
            }

            val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = context.contentResolver.update(updateUri, values, null, null)

            if (rows > 0 && target.accountType.equals("com.google", ignoreCase = true)) {
                triggerGoogleCalendarSync(target.accountName)
            }
            rows > 0
        } catch (e: Exception) {
            Log.e("CalendarHelper", "Update event error", e)
            false
        }
    }

    fun deleteEvent(context: Context, eventId: Long) {
        try {
            val deleteUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(deleteUri, null, null)
        } catch (e: Exception) {
            Log.e("CalendarHelper", "Delete event error", e)
        }
    }

    private fun triggerGoogleCalendarSync(accountName: String) {
        try {
            val account = Account(accountName, "com.google")
            val bundle = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            ContentResolver.requestSync(account, CalendarContract.AUTHORITY, bundle)
        } catch (e: Exception) {
            Log.e("CalendarHelper", "Failed to request sync", e)
        }
    }
}
