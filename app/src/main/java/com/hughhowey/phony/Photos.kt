package com.hughhowey.phony

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Snapshots for a mixtape's J-card: the camera photo taken closest to the moment you heard each
 * song, and a handful from the weeks the tape was made. Camera photos only (DCIM), no screenshots.
 * Read on the phone, shown on the phone; they go nowhere else.
 */
class Photos(private val ctx: Context) {

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private data class Shot(val id: Long, val taken: Long)

    private fun shots(from: Long, to: Long): List<Shot> {
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<Shot>()
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val sel = "${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        try {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, sel, arrayOf(from.toString(), to.toString(), "DCIM/%"),
                "${MediaStore.Images.Media.DATE_TAKEN} ASC")?.use { c ->
                while (c.moveToNext()) out += Shot(c.getLong(0), c.getLong(1))
            }
        } catch (e: Exception) { }
        return out
    }

    /** The photo taken nearest this moment (within six hours either way): {url, taken} or "". */
    fun near(t: Long): String {
        val s = shots(t - 6 * 3600_000, t + 6 * 3600_000).minByOrNull { Math.abs(it.taken - t) } ?: return ""
        return thumb(s)?.toString() ?: ""
    }

    /** Up to n photos spread across a stretch of time: [{url, taken}]. */
    fun across(from: Long, to: Long, n: Int): String {
        val all = shots(from, to)
        val out = JSONArray()
        if (all.isEmpty()) return out.toString()
        val step = all.size.toDouble() / n
        val picks = if (all.size <= n) all else (0 until n).map { all[(it * step + step / 2).toInt().coerceAtMost(all.size - 1)] }
        picks.forEach { s -> thumb(s)?.let { out.put(it) } }
        return out.toString()
    }

    private fun thumb(s: Shot): JSONObject? = try {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, s.id)
        val bmp = ctx.contentResolver.loadThumbnail(uri, Size(520, 520), null)
        val bo = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 82, bo)
        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)).put("taken", s.taken)
    } catch (e: Exception) { null }
}
