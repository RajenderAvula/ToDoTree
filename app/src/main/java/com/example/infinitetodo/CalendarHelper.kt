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
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS
        )
        val uri = CalendarContract.Calendars.CONTENT_URI

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

        return fallbackTarget
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
            val endTime = createdTimestampMs + 3600000L
            val timeZone = TimeZone.getDefault().id

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, target.id)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, notes ?: "")
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
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
    ) {
        try {
            val startTime = createdTimestampMs
            val endTime = createdTimestampMs + 3600000L
            val timeZone = TimeZone.getDefault().id

            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                if (notes != null) {
                    put(CalendarContract.Events.DESCRIPTION, notes)
                }
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            }

            val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.update(updateUri, values, null, null)

            if (target.accountType.equals("com.google", ignoreCase = true)) {
                triggerGoogleCalendarSync(target.accountName)
            }
        } catch (e: Exception) {
            Log.e("CalendarHelper", "Update event error", e)
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
