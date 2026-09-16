package com.example.infinitetodo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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

    /**
     * Resolves human-readable place name via Coroutine (IO dispatcher)
     */
    suspend fun resolvePlaceName(
        context: Context,
        latitude: Double,
        longitude: Double
    ): String = withContext(Dispatchers.IO) {
        var resolvedName: String? = null

        // 1. Try Android Native Geocoder
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    var addressList: List<Address>? = null
                    val lock = Object()
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        synchronized(lock) {
                            addressList = addresses
                            lock.notifyAll()
                        }
                    }
                    synchronized(lock) {
                        lock.wait(2500)
                    }
                    resolvedName = formatAddress(addressList?.firstOrNull())
                } else {
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocation(latitude, longitude, 1)
                    resolvedName = formatAddress(list?.firstOrNull())
                }
            }
        } catch (_: Exception) { }

        if (!resolvedName.isNullOrBlank()) {
            return@withContext resolvedName
        }

        // 2. OpenStreetMap Nominatim Web Fallback
        try {
            val urlString = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$latitude&lon=$longitude&zoom=18&addressdetails=1"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ToDoTreeApp/1.0 (Android; Location)")
                connectTimeout = 4000
                readTimeout = 4000
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(response)
                val addressObj = json.optJSONObject("address")
                if (addressObj != null) {
                    val road = addressObj.optString("road", "")
                    val suburb = addressObj.optString("suburb", "")
                    val neighbourhood = addressObj.optString("neighbourhood", "")
                    val city = addressObj.optString("city", addressObj.optString("town", addressObj.optString("county", "")))

                    val parts = mutableListOf<String>()
                    if (neighbourhood.isNotBlank()) parts.add(neighbourhood)
                    else if (suburb.isNotBlank()) parts.add(suburb)
                    if (road.isNotBlank() && !parts.contains(road)) parts.add(road)
                    if (city.isNotBlank() && !parts.contains(city)) parts.add(city)

                    if (parts.isNotEmpty()) {
                        resolvedName = parts.joinToString(", ")
                    }
                }
                if (resolvedName.isNullOrBlank()) {
                    resolvedName = json.optString("name", json.optString("display_name", null))
                }
            }
        } catch (_: Exception) { }

        return@withContext resolvedName ?: "Location (${String.format(Locale.US, "%.4f", latitude)}, ${String.format(Locale.US, "%.4f", longitude)})"
    }

    private fun formatAddress(address: Address?): String? {
        if (address == null) return null
        val parts = mutableListOf<String>()
        val feature = address.featureName
        val subLocality = address.subLocality
        val locality = address.locality
        val adminArea = address.adminArea

        if (!feature.isNullOrBlank() && feature != subLocality && feature != locality) {
            parts.add(feature)
        }
        if (!subLocality.isNullOrBlank()) {
            parts.add(subLocality)
        }
        if (!locality.isNullOrBlank() && locality != subLocality) {
            parts.add(locality)
        } else if (!adminArea.isNullOrBlank()) {
            parts.add(adminArea)
        }

        return if (parts.isNotEmpty()) parts.joinToString(", ") else address.getAddressLine(0)
    }

    @SuppressLint("MissingPermission")
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
            onError("Please enable Location (GPS) in Settings")
            return
        }

        try {
            // First check if a reasonably fresh location already exists (< 2 minutes)
            val lastGps = if (hasGps) locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val lastNetwork = if (hasNetwork) locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null

            val bestLast = when {
                lastGps != null && lastNetwork != null -> if (lastGps.time > lastNetwork.time) lastGps else lastNetwork
                lastGps != null -> lastGps
                else -> lastNetwork
            }

            if (bestLast != null && (System.currentTimeMillis() - bestLast.time) < 120_000L) {
                onLocationFound(bestLast)
                return
            }

            // Otherwise, request active single update from available provider
            val provider = if (hasNetwork) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            var delivered = false

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!delivered) {
                        delivered = true
                        onLocationFound(location)
                        locManager.removeUpdates(this)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {}
                override fun onProviderEnabled(p0: String) {}
                override fun onProviderDisabled(p0: String) {}
            }

            locManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())

            // Fallback timeout: if active fix takes too long, deliver the best last-known location
            Handler(Looper.getMainLooper()).postDelayed({
                if (!delivered) {
                    locManager.removeUpdates(listener)
                    if (bestLast != null) {
                        delivered = true
                        onLocationFound(bestLast)
                    } else {
                        onError("Location acquisition timed out. Please try outdoors.")
                    }
                }
            }, 6000)

        } catch (_: SecurityException) {
            onError("Location permission required")
        } catch (e: Exception) {
            onError("GPS error: ${e.message}")
        }
    }
}
