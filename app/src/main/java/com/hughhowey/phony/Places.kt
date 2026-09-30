package com.hughhowey.phony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Where the phone is, roughly, for the J-card's ports-of-call page.
 *
 * Approximate location only (a kilometre or two is plenty to tell one harbour from the next),
 * asked for once from the page itself. Place names come from Android's geocoder, which needs a
 * signal; out at sea PHONY keeps the position and names it the next time it's online.
 * Nothing leaves the phone except that lookup.
 */
class Places(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var fresh: Location? = null
    @Volatile private var asking = false

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** {lat, lon, at, place} for where the phone is (place may be "" until it's named), or "". */
    fun here(): String {
        if (!hasPermission()) return ""
        val loc = best() ?: run { refresh(); return "" }
        // older than twenty minutes: ask for a newer one for next time
        if (System.currentTimeMillis() - loc.time > 20 * 60_000) refresh()
        return JSONObject().put("lat", loc.latitude).put("lon", loc.longitude).put("at", loc.time).put("place", name(loc.latitude, loc.longitude)).toString()
    }

    /** The name for a spot, from the saved names; looked up in the background if it isn't known yet. */
    fun name(lat: Double, lon: Double): String {
        val key = key(lat, lon)
        prefs.getString(key, null)?.let { return it }
        pool.execute { lookup(lat, lon, key) }
        return ""
    }

    private fun lookup(lat: Double, lon: Double, key: String) {
        if (prefs.contains(key) || !Geocoder.isPresent()) return
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return
            val town = listOfNotNull(a.subLocality, a.locality, a.subAdminArea, a.featureName?.takeIf { it.any(Char::isLetter) }).firstOrNull { it.isNotBlank() }
            val country = a.countryName ?: a.adminArea
            val n = when {
                town != null && country != null && !town.equals(country, true) -> "$town, $country"
                town != null -> town
                country != null -> country
                else -> return
            }
            prefs.edit().putString(key, n).apply()
        } catch (e: Exception) { /* no signal: try again another time */ }
    }

    private fun best(): Location? {
        val cands = mutableListOf<Location>()
        fresh?.let { cands += it }
        val providers = mutableListOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) providers += LocationManager.FUSED_PROVIDER
        for (p in providers) try { lm?.getLastKnownLocation(p)?.let { cands += it } } catch (e: Exception) { }
        return cands.maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time < 12 * 3600_000 }
    }

    private fun refresh() {
        if (asking || lm == null || Build.VERSION.SDK_INT < 30) return
        asking = true
        val p = if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        try {
            lm.getCurrentLocation(p, null, ContextCompat.getMainExecutor(ctx)) { loc -> asking = false; if (loc != null) { fresh = loc; name(loc.latitude, loc.longitude) } }
        } catch (e: Exception) { asking = false }
    }

    /** Spots within about two kilometres share a name. */
    private fun key(lat: Double, lon: Double) = "%.2f,%.2f".format(Locale.US, Math.round(lat * 50) / 50.0, Math.round(lon * 50) / 50.0)
}
