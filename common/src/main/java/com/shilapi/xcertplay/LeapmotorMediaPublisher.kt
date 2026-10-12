package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.json.JSONObject

/**
 * Publishes CarPlay song metadata and playback state to Leapmotor vehicle head units.
 *
 * Leapmotor's native infotainment system (Leapmotor OS on C11, C01, C10, C16) observes
 * media playback via two primary mechanisms:
 * 1. The proprietary broadcast [ACTION_LEAPMOTOR_MEDIA_INFO] (`com.leapmotor.transfer.MEDIA.INFO`),
 *    which Leapmotor's central audio manager (`com.leapmotor.audiocenter` / media widget) listens to.
 *    Marking the source as "HiCar" routes it into the car's built-in HiCar audio card.
 * 2. The instrument cluster (ICU) broadcast [ACTION_METER_MUSIC] (`car.meter.music.BROADCAST`),
 *    which carries a length-prefixed JSON payload formatted as expected by the cluster renderer.
 */
internal object LeapmotorMediaPublisher {
    private const val TAG = "DiPlay-LeapMedia"

    /** Broadcast action observed by Leapmotor AudioCenter and desktop media widgets. */
    const val ACTION_LEAPMOTOR_MEDIA_INFO = "com.leapmotor.transfer.MEDIA.INFO"

    /** Broadcast action observed by the C11 instrument cluster (ICU). */
    const val ACTION_METER_MUSIC = "car.meter.music.BROADCAST"

    @Volatile
    var onDiagnostic: ((String) -> Unit)? = null

    /**
     * Checks whether the current runtime environment is a Leapmotor vehicle head unit
     * or running under a Leapmotor disguised package identity.
     */
    fun isLeapmotor(context: Context?): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        val pkg = context?.packageName ?: ""
        return fingerprint.contains("msmnile_gvmq") || // C11 SA8155P IVI platform
            fingerprint.contains("leapmotor") ||
            brand.contains("leapmotor") ||
            manufacturer.contains("leapmotor") ||
            model.contains("leapmotor") ||
            pkg == "com.android.camera2" ||
            pkg == "com.android.gallery3d"
    }

    /**
     * Publishes [info] to Leapmotor's audio center and instrument cluster.
     */
    fun publish(context: Context?, info: CarPlayNowPlaying) {
        val ctx = context ?: return
        val title = info.title?.trim() ?: ""
        val artist = info.artist?.trim() ?: ""
        val album = info.album?.trim() ?: ""
        val playing = info.playing
        val duration = info.durationMillis ?: 0L
        val position = info.elapsedMillis ?: 0L

        // 1. Send the primary Leapmotor transfer broadcast
        val transferIntent = Intent(ACTION_LEAPMOTOR_MEDIA_INFO).apply {
            putExtra("title", title)
            putExtra("artist", artist)
            putExtra("album", album)
            putExtra("duration", duration)
            putExtra("position", position)
            putExtra("playing", playing)
            putExtra("status", if (playing) 1 else 0)
            putExtra("playStatus", if (playing) 1 else 0)
            putExtra("playState", if (playing) 1 else 0)
            putExtra("source", "HiCar")
            putExtra("appName", "HiCar")
            putExtra("mediaType", "music")
            putExtra("package", ctx.packageName)
        }
        runCatching { ctx.sendBroadcast(transferIntent) }

        // 2. Send framed ICU cluster broadcast for C11/C01 dashboard cards
        val jsonPayload = JSONObject().apply {
            put("src", "hicar")
            put("type", "music")
            put("action", if (playing) "play" else "pause")
            put("data", JSONObject().apply {
                put("title", title)
                put("artist", artist)
                put("album", album)
                put("duration", duration)
                put("position", position)
                put("playing", playing)
            })
        }
        val meterIntent = Intent(ACTION_METER_MUSIC).apply {
            putExtra("receiver", frameReceiver(jsonPayload.toString()))
            putExtra("title", title)
            putExtra("artist", artist)
        }
        runCatching { ctx.sendBroadcast(meterIntent) }

        val msg = "published HiCar song info: title='$title' artist='$artist' playing=$playing"
        Log.i(TAG, msg)
        runCatching { onDiagnostic?.invoke(msg) }
    }

    /**
     * Clears now-playing information when the CarPlay session disconnects.
     */
    fun clear(context: Context?) {
        val ctx = context ?: return
        val emptyIntent = Intent(ACTION_LEAPMOTOR_MEDIA_INFO).apply {
            putExtra("title", "")
            putExtra("artist", "")
            putExtra("album", "")
            putExtra("duration", 0L)
            putExtra("position", 0L)
            putExtra("playing", false)
            putExtra("status", 0)
            putExtra("playStatus", 0)
            putExtra("playState", 0)
            putExtra("source", "HiCar")
            putExtra("appName", "HiCar")
            putExtra("mediaType", "music")
            putExtra("package", ctx.packageName)
        }
        runCatching { ctx.sendBroadcast(emptyIntent) }

        val jsonPayload = JSONObject().apply {
            put("src", "hicar")
            put("type", "music")
            put("action", "stop")
            put("data", JSONObject().apply {
                put("title", "")
                put("artist", "")
                put("playing", false)
            })
        }
        val meterIntent = Intent(ACTION_METER_MUSIC).apply {
            putExtra("receiver", frameReceiver(jsonPayload.toString()))
            putExtra("title", "")
            putExtra("artist", "")
        }
        runCatching { ctx.sendBroadcast(meterIntent) }

        Log.i(TAG, "cleared Leapmotor song info")
    }

    /**
     * Frames a UTF-8 JSON payload with a two-byte length prefix:
     * `b0 = len / 100`, `b1 = len % 100`, which is the format decoded by Leapmotor's ICU.
     */
    private fun frameReceiver(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        val b0 = ((len / 100) and 0xff).toByte()
        val b1 = ((len % 100) and 0xff).toByte()
        return byteArrayOf(b0, b1) + bytes
    }
}
