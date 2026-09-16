package com.example.infinitetodo

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

object LocationAndContactHelper {

    fun launchDialer(context: Context, rawPhone: String) {
        try {
            val cleanPhone = rawPhone.replace(" ", "").replace("-", "")
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone"))
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open dialer", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchSms(context: Context, rawPhone: String) {
        try {
            val cleanPhone = rawPhone.replace(" ", "").replace("-", "")
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone"))
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open SMS app", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchWhatsApp(context: Context, rawPhone: String) {
        try {
            val digitsOnly = rawPhone.filter { it.isDigit() }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$digitsOnly")
                setPackage("com.whatsapp")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val digitsOnly = rawPhone.filter { it.isDigit() }
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digitsOnly"))
                context.startActivity(webIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchTelegram(context: Context, rawPhone: String) {
        try {
            val digitsOnly = rawPhone.filter { it.isDigit() }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+$digitsOnly")).apply {
                setPackage("org.telegram.messenger")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val digitsOnly = rawPhone.filter { it.isDigit() }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+$digitsOnly")))
            } catch (_: Exception) {
                Toast.makeText(context, "Telegram not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchEmail(context: Context, email: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "No email client found", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Universal App Chooser: Opens system sharesheet allowing any messaging or communication
     * app (Viber, Signal, Skype, Teams, Slack, Messages, etc.) to handle the contact info.
     */
    fun openAllAppsContactMenu(context: Context, name: String, rawPhone: String) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$name: $rawPhone")
            }
            val chooser = Intent.createChooser(sendIntent, "Contact $name via...")
            context.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to open apps menu", Toast.LENGTH_SHORT).show()
        }
    }

    fun openInMap(context: Context, latitude: Double, longitude: Double, label: String?) {
        val query = if (!label.isNullOrBlank()) Uri.encode(label) else "$latitude,$longitude"
        val gmmIntentUri = Uri.parse("geo:0,0?q=$latitude,$longitude($query)")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            context.startActivity(mapIntent)
        } catch (_: Exception) {
            try {
                val fallbackUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
                context.startActivity(Intent(Intent.ACTION_VIEW, fallbackUri))
            } catch (_: Exception) {
                Toast.makeText(context, "No map application available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestFreshLocation(
        context: Context,
        onLocationFound: (Location) -> Unit,
        onError: (String) -> Unit
    ) {
        val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locManager == null) {
            onError("Location service unavailable")
            return
        }

        val hasGps = locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val hasNetwork = locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!hasGps && !hasNetwork) {
            onError("Please turn on Device Location (GPS)")
            return
        }

        try {
            val cached = (if (hasGps) locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null)
                ?: (if (hasNetwork) locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null)

            if (cached != null && (System.currentTimeMillis() - cached.time) < 120000L) {
                onLocationFound(cached)
                return
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onLocationFound(location)
                    locManager.removeUpdates(this)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            val provider = if (hasGps) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            locManager.requestSingleUpdate(provider, listener, null)

            if (cached != null) {
                onLocationFound(cached)
            }
        } catch (e: SecurityException) {
            onError("Location permission required")
        } catch (e: Exception) {
            onError("Unable to acquire GPS fix")
        }
    }
}
