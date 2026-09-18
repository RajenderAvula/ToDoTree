package com.example.infinitetodo

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
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

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

    suspend fun resolvePlaceName(context: Context, latitude: Double, longitude: Double): String = withContext(Dispatchers.IO) {
        val geocoder = Geocoder(context, Locale.getDefault())
        val defaultCoord = "Location (${String.format(Locale.US, "%.4f", latitude)}, ${String.format(Locale.US, "%.4f", longitude)})"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val geocoded = suspendCancellableCoroutine<String?> { cont ->
                geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        cont.resume(formatAddress(addresses.firstOrNull()))
                    }
                    override fun onError(errorMessage: String?) {
                        cont.resume(null)
                    }
                })
            }
            geocoded ?: fetchFromWebFallbackSync(latitude, longitude) ?: defaultCoord
        } else {
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                formatAddress(addresses?.firstOrNull()) ?: fetchFromWebFallbackSync(latitude, longitude) ?: defaultCoord
            } catch (_: Exception) {
                fetchFromWebFallbackSync(latitude, longitude) ?: defaultCoord
            }
        }
    }

    fun fetchPlaceName(context: Context, latitude: Double, longitude: Double, onResolved: (String) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val name = resolvePlaceName(context, latitude, longitude)
            onResolved(name)
        }
    }

    private fun fetchFromWebFallbackSync(latitude: Double, longitude: Double): String? {
        return try {
            val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$latitude&lon=$longitude&zoom=18&addressdetails=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "ToDoTreeApp/1.0 (Android)")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
                reader.close()

                val json = JSONObject(sb.toString())
                val addressObj = json.optJSONObject("address")
                if (addressObj != null) {
                    val road = addressObj.optString("road", "")
                    val suburb = addressObj.optString("suburb", "")
                    val neighbourhood = addressObj.optString("neighbourhood", "")
                    val city = addressObj.optString("city", addressObj.optString("town", ""))

                    val parts = mutableListOf<String>()
                    if (neighbourhood.isNotBlank()) parts.add(neighbourhood) else if (suburb.isNotBlank()) parts.add(suburb)
                    if (road.isNotBlank() && !parts.contains(road)) parts.add(road)
                    if (city.isNotBlank() && !parts.contains(city)) parts.add(city)
                    if (parts.isNotEmpty()) parts.joinToString(", ") else json.optString("display_name", null)
                } else {
                    json.optString("display_name", null)
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun formatAddress(address: Address?): String? {
        if (address == null) return null
        val parts = mutableListOf<String>()
        val feature = address.featureName
        val subLocality = address.subLocality
        val locality = address.locality
        val adminArea = address.adminArea

        if (!feature.isNullOrBlank() && feature != subLocality && feature != locality) parts.add(feature)
        if (!subLocality.isNullOrBlank()) parts.add(subLocality)
        if (!locality.isNullOrBlank() && locality != subLocality) parts.add(locality) else if (!adminArea.isNullOrBlank()) parts.add(adminArea)

        return if (parts.isNotEmpty()) parts.joinToString(", ") else address.getAddressLine(0)
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

            if (cached != null && (System.currentTimeMillis() - cached.time) < 60000L) {
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
        } catch (_: SecurityException) {
            onError("Location permission required")
        } catch (_: Exception) {
            onError("Unable to acquire GPS fix")
        }
    }
}
