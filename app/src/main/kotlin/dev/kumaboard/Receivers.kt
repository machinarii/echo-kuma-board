package dev.kumaboard

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.kumaboard.core.Provisioning
import dev.kumaboard.core.Validated

/**
 * F17/G3: bring the board back after boot or an app update. When Kuma Board is the Home app the
 * system launches it anyway; otherwise this needs the SYSTEM_ALERT_WINDOW app-op (see README).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startActivity(
                Intent(context, BoardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w("KumaBoard", "Could not start board after ${intent.action}", it) }
    }
}

/**
 * F23: non-touch provisioning over adb. Protected by android.permission.DUMP, which the adb shell
 * holds and ordinary apps cannot obtain.
 *
 * ```
 * adb shell am broadcast -n dev.kumaboard/.ProvisionReceiver -a dev.kumaboard.PROVISION \
 *     --es url https://monitor.example.net/status/echo --ei refreshSeconds 30 --es sort problems
 * ```
 * Or pass a whole JSON document: `--es json '{"url":"…"}'`. The result text is printed by `am`.
 */
class ProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = KumaBoardApp.of(context).settings
        val extras = intent.extras ?: Bundle.EMPTY
        val current = store.config.value

        // Combine rules are structured; set them through the "json" extra or the provisioning file.
        val result = extras.getString("json")?.let { Provisioning.applyJson(current, it) } ?: Provisioning.apply(
            current,
            url = extras.getString("url") ?: extras.getString("baseUrl"),
            slug = extras.getString("slug"),
            refreshSeconds = extras.intOrNull("refreshSeconds"),
            sort = extras.getString("sort"),
            columns = extras.intOrNull("columns"),
            dimEnabled = extras.boolOrNull("dimEnabled"),
            dimStart = extras.getString("dimStart"),
            dimEnd = extras.getString("dimEnd"),
            dimLevel = extras.doubleOrNull("dimLevel"),
            sound = extras.boolOrNull("sound"),
            quietStart = extras.getString("quietStart"),
            quietEnd = extras.getString("quietEnd"),
            usageUrl = extras.getString("usageUrl"),
        )
        when (result) {
            is Validated.Ok -> {
                store.save(result.value)
                val c = result.value
                resultCode = Activity.RESULT_OK
                resultData = "ok: ${c.baseUrl} slug=${c.slug} refresh=${c.refreshSeconds}s sort=${c.sortMode.key} columns=${c.columns} usage=${if (c.usageUrl != null) "on" else "off"}"
            }
            is Validated.Err -> {
                resultCode = Activity.RESULT_CANCELED
                resultData = "error: ${result.message}"
            }
        }
        Log.i("KumaBoard", "Provisioning $resultData")
    }

    // `am broadcast` sends --es strings, --ei ints, --ez booleans, --ef floats; accept any of them.
    @Suppress("DEPRECATION")
    private fun Bundle.raw(k: String): Any? = get(k)
    private fun Bundle.intOrNull(k: String): Int? = when (val v = raw(k)) {
        is Int -> v; is Long -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null
    }
    private fun Bundle.boolOrNull(k: String): Boolean? = when (val v = raw(k)) {
        is Boolean -> v; is String -> v.trim().lowercase().toBooleanStrictOrNull(); else -> null
    }
    private fun Bundle.doubleOrNull(k: String): Double? = when (val v = raw(k)) {
        is Float -> v.toDouble(); is Double -> v; is Int -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null
    }
}
