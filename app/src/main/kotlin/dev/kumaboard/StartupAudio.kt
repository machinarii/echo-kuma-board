package dev.kumaboard

import android.content.Context
import android.media.AudioManager
import android.util.Log
import dev.kumaboard.core.StartupVolume

/**
 * Sets every sound stream (media, ring, notification, alarm, system, call, dial tones) to 40%
 * of its range once per app start. Runs before the first quack can play.
 *
 * Ring and notification changes can be refused while Do Not Disturb is on (that needs
 * notification-policy access); each stream is set separately so one refusal doesn't stop the rest.
 */
object StartupAudio {
    private const val TAG = "KumaBoard"
    @Volatile private var applied = false

    private val STREAMS = listOf(
        AudioManager.STREAM_MUSIC to "media",
        AudioManager.STREAM_RING to "ring",
        AudioManager.STREAM_NOTIFICATION to "notification",
        AudioManager.STREAM_ALARM to "alarm",
        AudioManager.STREAM_SYSTEM to "system",
        AudioManager.STREAM_VOICE_CALL to "call",
        AudioManager.STREAM_DTMF to "dial",
    )

    fun applyOnce(context: Context) {
        if (applied) return
        applied = true
        val am = context.getSystemService(AudioManager::class.java) ?: return
        for ((stream, name) in STREAMS) {
            try {
                val target = StartupVolume.level(am.getStreamMinVolume(stream), am.getStreamMaxVolume(stream))
                am.setStreamVolume(stream, target, 0)
                Log.i(TAG, "Volume $name -> $target/${am.getStreamMaxVolume(stream)} (now ${am.getStreamVolume(stream)})")
            } catch (e: SecurityException) {
                Log.w(TAG, "Volume $name not changed: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Volume $name failed", e)
            }
        }
    }
}
