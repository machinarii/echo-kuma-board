package dev.kumaboard

import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.kumaboard.core.Backoff
import dev.kumaboard.core.Board
import dev.kumaboard.core.Combine
import dev.kumaboard.core.DemoData
import dev.kumaboard.core.BoardConfig
import dev.kumaboard.core.Freshness
import dev.kumaboard.core.Provisioning
import dev.kumaboard.core.Sorting
import dev.kumaboard.core.SoundPolicy
import dev.kumaboard.core.UsageDisplay
import dev.kumaboard.core.UsageReport
import dev.kumaboard.core.Validated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The board: one full-screen activity that owns the screen (F15, F16), refreshes on a
 * lifecycle-aware loop (§10), dims at night (F20), pages (F9), drifts (F21) and watches itself (§10).
 */
class BoardActivity : ComponentActivity() {

    private lateinit var view: BoardView
    private val settings by lazy { KumaBoardApp.of(this).settings }
    private val client = KumaClient()

    // Fetch state. Survives only as long as the process; after process death the board refetches.
    private var board: Board? = null
    private var lastSuccessMs: Long? = null
    private var failures = 0
    private var lastError: String? = null
    private var page = 0
    private var driftStep = 0

    /** Wakes the refresh loop early: network came back, settings changed. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Weekly Claude/Codex usage for the bottom strip; kept through failed fetches (numbers age visibly). */
    private var usage: UsageReport? = null
    private var usageFromUrl: String? = null
    private val usageWake = Channel<Unit>(Channel.CONFLATED)

    // Watchdog: the main thread and the refresh loop each stamp a heartbeat.
    @Volatile private var mainTick = SystemClock.elapsedRealtime()
    @Volatile private var loopTick = SystemClock.elapsedRealtime()
    @Volatile private var started = false
    private var watchdog: ScheduledExecutorService? = null

    /** F25: the quack, preloaded so it plays instantly. */
    private var soundPool: SoundPool? = null
    private var quackId = 0

    /**
     * Debug builds only: `adb shell am start -n dev.kumaboard/.BoardActivity --ez demo true
     * [--ei demoCount 16] [--es demoScenario down|ok|nodata]` shows sample data instead of fetching.
     */
    private var demo: Pair<Int, String>? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wake.trySend(Unit) // F18: Wi-Fi reconnect -> refresh now instead of waiting out the backoff
            usageWake.trySend(Unit)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDemo(intent)
        StartupAudio.applyOnce(this) // all sound streams to 40% on app start
        loadQuack()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // F16
        // G3: after a reboot the board must be visible without a swipe. Show over the lock screen
        // and wake the display; the rest of the device stays behind the lock screen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        view = BoardView(this)
        view.onSettingsGesture = { startActivity(Intent(this, SettingsActivity::class.java)) }
        setContentView(view)
        Immersive.install(this) // F15

        // Kiosk: Back does nothing on the board.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        lifecycleScope.launch {
            // Stops when the screen is off / activity stopped, resumes on wake (§10).
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { refreshLoop() }
                launch { tickLoop() }
                launch { pagingLoop() }
                launch { usageLoop() }
                launch {
                    settings.config.drop(1).collect {
                        failures = 0
                        render()
                        wake.trySend(Unit)
                        usageWake.trySend(Unit)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (readDemo(intent)) wake.trySend(Unit)
    }

    private fun readDemo(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG || intent?.hasExtra("demo") != true) return false
        val previous = demo
        demo = if (intent.getBooleanExtra("demo", false)) {
            intent.getIntExtra("demoCount", DemoData.NAMES.size) to (intent.getStringExtra("demoScenario") ?: "down")
        } else null
        // Same sample size, new scenario: keep the old board so status changes (and the quack) fire.
        if (demo == null || previous == null || previous.first != demo?.first) board = null
        page = 0
        return true
    }

    override fun onStart() {
        super.onStart()
        started = true
        mainTick = SystemClock.elapsedRealtime()
        loopTick = SystemClock.elapsedRealtime()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        startWatchdog()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applyProvisioningFile()
    }

    override fun onStop() {
        started = false
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        watchdog?.shutdownNow()
        watchdog = null
        super.onStop()
    }

    override fun onDestroy() {
        soundPool?.release()
        soundPool = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** F15: immersive, bars come back only transiently on a swipe. */
    private fun hideSystemBars() = Immersive.hide(this)

    // ------------------------------------------------------------------ loops

    private suspend fun refreshLoop() {
        while (true) {
            val config = settings.config.value
            loopTick = SystemClock.elapsedRealtime()
            try {
                val d = demo
                if (d != null && d.second == "stale" && board != null) throw java.io.IOException("Demo: simulated outage")
                val fetched = d?.let { DemoData.board(System.currentTimeMillis(), it.first, it.second) } ?: client.fetch(config)
                // Combine rules first (e.g. DNS 1 + 2 -> one "DNS" tile), before counts, sorting and sound.
                // Then the display layout: renames, hidden monitors, tile order (Kuma is not changed).
                val next = config.layout.apply(Combine.apply(fetched, config.combine))
                board = next
                lastSuccessMs = System.currentTimeMillis()
                failures = 0
                lastError = null
                checkQuack(next, config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Fetch failed ($failures): $lastError")
            }
            render()
            val wait = Backoff.delayMs(failures, config.refreshIntervalMs)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /**
     * Usage strip: a small file that changes slowly, so every 5 minutes (1 minute after a failure).
     * Separate from the Kuma loop so a missing usage file never delays or stales the board.
     */
    private suspend fun usageLoop() {
        while (true) {
            val url = settings.config.value.usageUrl
            var ok = true
            if (url == null) {
                usage = null
            } else {
                if (url != usageFromUrl) usage = null
                try {
                    usage = client.fetchUsage(url)
                    usageFromUrl = url
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ok = false
                    Log.w(TAG, "Usage fetch failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            render()
            withTimeoutOrNull(if (ok) USAGE_MS else USAGE_RETRY_MS) { usageWake.receive() }
        }
    }

    /** Once every few seconds: clock-driven state (stale, night window, drift) and the watchdog stamp. */
    private suspend fun tickLoop() {
        var ticks = 0
        while (true) {
            mainTick = SystemClock.elapsedRealtime()
            if (ticks % DRIFT_EVERY_TICKS == 0 && ticks > 0) driftStep = (driftStep + 1) % DRIFT.size
            if (ticks % PROVISION_CHECK_TICKS == 0 && ticks > 0) applyProvisioningFile()
            render()
            ticks++
            delay(TICK_MS)
        }
    }

    private suspend fun pagingLoop() {
        while (true) {
            delay(PAGE_MS)
            val pages = view.pageCount
            page = if (pages > 1) (page + 1) % pages else 0
            render()
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val config = settings.config.value
        val now = System.currentTimeMillis()
        val b = board
        val stale = Freshness.isStale(now, lastSuccessMs, failures, config.refreshIntervalMs)
        val night = config.dimEnabled && config.nightWindow.contains(minuteOfDay()) && b?.anyAlert != true
        applyBrightness(if (night) config.dimLevel else null)

        val (message, detail) = when {
            b != null -> null to null
            lastError != null -> "Can't load the board yet" to "$lastError. Retrying…"
            else -> "Connecting to ${config.host()}…" to "Status page “${config.slug}”"
        }
        val (dx, dy) = DRIFT[driftStep]
        view.setModel(
            BoardView.Model(
                board = b?.let { it.copy(monitors = Sorting.apply(it.monitors, config.sortMode)) },
                columns = config.columns,
                page = page,
                stale = stale && b != null,
                offline = failures > 0,
                updatedLabel = lastSuccessMs?.let { timeFormat.format(Date(it)) },
                clockLabel = clockFormat.format(Date(now)),
                message = message,
                messageDetail = detail,
                night = night,
                nowMs = now,
                driftX = dx,
                driftY = dy,
                usage = config.usageUrl?.let { UsageDisplay.items(usage, now) },
            ),
        )
    }

    /** Status-bar clock: no seconds, with AM/PM. */
    private val clockFormat by lazy {
        // Owner decision 2026-11-10: 12-hour clock with AM/PM, e.g. "7:58 PM".
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
    }

    /** "Updated hh:mm" uses the same format as the clock so the two read alike. */
    private val timeFormat get() = clockFormat

    private var appliedBrightness: Float? = -2f
    /** F20: window-level brightness needs no system permission. null = follow the system setting. */
    private fun applyBrightness(level: Float?) {
        if (level == appliedBrightness) return
        appliedBrightness = level
        window.attributes = window.attributes.apply {
            screenBrightness = level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    // ------------------------------------------------------------------ F25 audible cue

    /**
     * Quack once per outage when a monitor has been down for more than 5 minutes.
     * Silent 22:00–09:00 Pacific; see SoundPolicy.
     * Plays on the alarm stream so Do Not Disturb or a muted notification volume doesn't swallow it.
     */
    private val alertPrefs by lazy { getSharedPreferences("alerts", MODE_PRIVATE) }
    private var firstSeenDown: Map<Int, Long> = emptyMap()

    /** Announced outages are persisted so a restart doesn't quack again for the same outage. */
    private fun checkQuack(board: Board, config: BoardConfig) {
        val announced = alertPrefs.getStringSet("announced", emptySet()).orEmpty()
        val r = SoundPolicy.evaluate(board, System.currentTimeMillis(), config, announced, firstSeenDown)
        firstSeenDown = r.firstSeenDown
        if (r.announced != announced) alertPrefs.edit().putStringSet("announced", r.announced).apply()
        if (r.announce.isNotEmpty()) {
            Log.i(TAG, "Quack: down > 5 min: ${r.announce.joinToString { it.name }}")
            quack()
        }
    }

    private fun quack() {
        val pool = soundPool ?: return
        if (quackId != 0) pool.play(quackId, 1f, 1f, 1, 0, 1f)
    }

    private fun loadQuack() {
        if (soundPool != null) return
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
            .also { quackId = it.load(this, R.raw.quack, 1) }
    }

    // ------------------------------------------------------------------ F23 provisioning file

    /**
     * `adb push kuma-board.json /sdcard/Android/data/dev.kumaboard/files/` — applied when its
     * modification time changes, so the same file is not re-applied on every resume.
     */
    private fun applyProvisioningFile() {
        val dir = getExternalFilesDir(null) ?: return
        val file = File(dir, PROVISION_FILE)
        if (!file.isFile) return
        val mtime = file.lastModified()
        if (mtime == settings.appliedProvisionFileMtime) return
        settings.appliedProvisionFileMtime = mtime
        val body = runCatching { file.readText() }.getOrElse { return }
        when (val r = Provisioning.applyJson(settings.config.value, body)) {
            is Validated.Ok -> {
                settings.save(r.value)
                Toast.makeText(this, "Settings loaded from $PROVISION_FILE", Toast.LENGTH_SHORT).show()
            }
            is Validated.Err -> Toast.makeText(this, "$PROVISION_FILE: ${r.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ watchdog

    /**
     * Restarts the app if the main thread stops ticking (frozen UI) or the refresh loop stops
     * iterating while the board is in the foreground.
     */
    private fun startWatchdog() {
        watchdog?.shutdownNow()
        watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kuma-watchdog").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                if (!started) return@scheduleWithFixedDelay
                val now = SystemClock.elapsedRealtime()
                val interval = settings.config.value.refreshIntervalMs
                val loopLimit = interval + 60_000L // fetch is bounded by a 12 s call timeout
                when {
                    now - mainTick > MAIN_STALL_MS -> CrashRecovery.restartNow(this, "main thread stalled ${now - mainTick} ms")
                    now - loopTick > loopLimit -> CrashRecovery.restartNow(this, "refresh loop stalled ${now - loopTick} ms")
                }
            }, 30, 15, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val TAG = "KumaBoard"
        const val PROVISION_FILE = "kuma-board.json"
        private const val TICK_MS = 5_000L
        private const val PAGE_MS = 15_000L                 // F9
        private const val DRIFT_EVERY_TICKS = 36            // 3 minutes (F21)
        private const val PROVISION_CHECK_TICKS = 12        // 1 minute
        private const val MAIN_STALL_MS = 45_000L
        private const val USAGE_MS = 5 * 60_000L
        private const val USAGE_RETRY_MS = 60_000L
        /** Small, slow pixel shift; stays inside the 16 px margin. */
        private val DRIFT = listOf(0f to 0f, 2f to 1f, 3f to -1f, 1f to -2f, -2f to -1f, -3f to 1f, -1f to 2f)
    }
}
