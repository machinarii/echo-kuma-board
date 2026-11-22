package dev.kumaboard

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.kumaboard.core.BoardConfig
import dev.kumaboard.core.Provisioning
import dev.kumaboard.core.SortMode
import dev.kumaboard.core.UsageAddress
import dev.kumaboard.core.Validated
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class KumaBoardApp : Application() {

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        CrashRecovery.install(this)
    }

    companion object {
        fun of(context: Context) = context.applicationContext as KumaBoardApp
    }
}

/** SharedPreferences-backed settings (F22), exposed as a flow so every writer updates the board live. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("board", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val config: StateFlow<BoardConfig> = state.asStateFlow()

    var appliedProvisionFileMtime: Long
        get() = prefs.getLong("provisionFileMtime", 0L)
        set(v) = prefs.edit().putLong("provisionFileMtime", v).apply()

    fun save(c: BoardConfig) {
        prefs.edit()
            .putString("baseUrl", c.baseUrl)
            .putString("slug", c.slug)
            .putInt("refreshSeconds", c.refreshSeconds)
            .putString("sort", c.sortMode.key)
            .putInt("columns", c.columns)
            .putBoolean("dimEnabled", c.dimEnabled)
            .putInt("dimStart", c.dimStartMinute)
            .putInt("dimEnd", c.dimEndMinute)
            .putFloat("dimLevel", c.dimLevel)
            .putBoolean("quack", c.soundEnabled)
            .putInt("quietStart", c.quietStartMinute)
            .putInt("quietEnd", c.quietEndMinute)
            .putString("combine", Provisioning.combineToJson(c.combine))
            .putString("layout", Provisioning.layoutToJson(c.layout))
            .putString("usageUrl", c.usageUrl)
            .apply()
        state.value = c
    }

    private fun load(): BoardConfig {
        val d = BoardConfig()
        return BoardConfig(
            baseUrl = prefs.getString("baseUrl", null) ?: d.baseUrl,
            slug = prefs.getString("slug", null) ?: d.slug,
            refreshSeconds = prefs.getInt("refreshSeconds", d.refreshSeconds).coerceIn(BoardConfig.REFRESH_RANGE),
            sortMode = SortMode.fromKey(prefs.getString("sort", null)) ?: d.sortMode,
            columns = prefs.getInt("columns", d.columns).coerceIn(BoardConfig.COLUMN_RANGE),
            dimEnabled = prefs.getBoolean("dimEnabled", d.dimEnabled),
            dimStartMinute = prefs.getInt("dimStart", d.dimStartMinute),
            dimEndMinute = prefs.getInt("dimEnd", d.dimEndMinute),
            dimLevel = prefs.getFloat("dimLevel", d.dimLevel),
            // New key: the old "sound" flag defaulted to off; the quack defaults to on.
            soundEnabled = prefs.getBoolean("quack", d.soundEnabled),
            quietStartMinute = prefs.getInt("quietStart", d.quietStartMinute),
            quietEndMinute = prefs.getInt("quietEnd", d.quietEndMinute),
            combine = Provisioning.combineFromJson(prefs.getString("combine", null)) ?: d.combine,
            layout = Provisioning.layoutFromJson(prefs.getString("layout", null)) ?: d.layout,
            usageUrl = prefs.getString("usageUrl", null)?.let { stored ->
                when (val u = UsageAddress.parse(stored)) { is Validated.Ok -> u.value; is Validated.Err -> null }
            },
        )
    }
}

/**
 * G3/F18: after an uncaught exception, schedule the board to come back and let the process die.
 * Crash loops back off to one restart per minute so a persistent fault cannot spin the CPU.
 */
object CrashRecovery {
    private const val TAG = "KumaBoard"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "Uncaught exception; scheduling restart", error)
            runCatching { scheduleRestart(app, crashLoopDelayMs(app)) }
            if (previous != null) previous.uncaughtException(thread, error) else Process.killProcess(Process.myPid())
        }
    }

    fun scheduleRestart(context: Context, delayMs: Long) {
        val intent = Intent(context, BoardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        val am = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delayMs
        // Exact alarms need no permission on Android 11 (the target ROM); newer Androids may refuse them.
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    /** Restart and kill the process now (used by the watchdog when the UI has stalled). */
    fun restartNow(context: Context, reason: String) {
        Log.e(TAG, "Watchdog restart: $reason")
        scheduleRestart(context, crashLoopDelayMs(context))
        Process.killProcess(Process.myPid())
    }

    private fun crashLoopDelayMs(context: Context): Long {
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = (prefs.getString("times", "") ?: "")
            .split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < 120_000 } + now
        prefs.edit().putString("times", recent.takeLast(10).joinToString(",")).commit()
        return if (recent.size > 3) 60_000L else 2_000L
    }
}
