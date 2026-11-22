package dev.kumaboard

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.kumaboard.core.BoardConfig
import dev.kumaboard.core.MonitorStatus
import dev.kumaboard.core.NightWindow
import dev.kumaboard.core.Palette
import dev.kumaboard.core.Provisioning
import dev.kumaboard.core.ServerAddress
import dev.kumaboard.core.SortMode
import dev.kumaboard.core.Validated
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * On-device settings (F22, F23). Plain framework widgets keep the APK small. Two-column on the
 * 960×480 panel: connection on the left, display on the right, one primary Save action.
 */
class SettingsActivity : ComponentActivity() {
    private val p = Palette.MACCHIATO
    private val store by lazy { KumaBoardApp.of(this).settings }

    private lateinit var address: EditText
    private lateinit var slug: EditText
    private lateinit var refresh: EditText
    private lateinit var columns: EditText
    private lateinit var sort: RadioGroup
    private lateinit var dim: Switch
    private lateinit var dimStart: EditText
    private lateinit var dimEnd: EditText
    private lateinit var dimLevel: EditText
    private lateinit var sound: Switch
    private lateinit var quietStart: EditText
    private lateinit var quietEnd: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = store.config.value

        val left = column().apply {
            addView(heading("Connection"))
            address = field("Kuma address or status page URL", c.baseUrl, InputType.TYPE_TEXT_VARIATION_URI)
            addView(label("Kuma address or status page URL")); addView(address)
            slug = field("Status page slug", c.slug, InputType.TYPE_CLASS_TEXT)
            addView(label("Status page slug")); addView(slug)
            refresh = field("30", c.refreshSeconds.toString(), InputType.TYPE_CLASS_NUMBER)
            addView(label("Refresh every (seconds, 10–300)")); addView(refresh)
            addView(row(
                button("Test connection", primary = false) { testConnection() },
            ))
            status = TextView(this@SettingsActivity).apply { pf(15f); setTextColor(p.subtext0); setPadding(0, dp(6), 0, 0) }
            addView(status)
            addView(heading("Kiosk"))
            addView(row(
                button("Set as Home app", primary = false) { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
                button("Android settings", primary = false) { startActivity(Intent(Settings.ACTION_SETTINGS)) },
            ))
            addView(note(
                "Provision without typing:\n" +
                    "adb push kuma-board.json /sdcard/Android/data/dev.kumaboard/files/\n" +
                    "or: adb shell am broadcast -n dev.kumaboard/.ProvisionReceiver -a dev.kumaboard.PROVISION --es url <status page URL>",
            ))
        }

        val right = column().apply {
            addView(heading("Display"))
            addView(label("Order"))
            sort = RadioGroup(this@SettingsActivity).apply {
                orientation = RadioGroup.HORIZONTAL
                addView(radio(1, "Kuma order", c.sortMode == SortMode.KUMA_ORDER))
                addView(radio(2, "Problems first", c.sortMode == SortMode.PROBLEMS_FIRST))
            }
            addView(sort)
            columns = field("0", c.columns.toString(), InputType.TYPE_CLASS_NUMBER)
            addView(label("Columns (0 = automatic)")); addView(columns)
            dim = switch("Dim at night", c.dimEnabled)
            addView(dim)
            dimStart = field("23:00", NightWindow.formatHhMm(c.dimStartMinute), InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
            dimEnd = field("07:00", NightWindow.formatHhMm(c.dimEndMinute), InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
            dimLevel = field("8", (c.dimLevel * 100).roundToInt().toString(), InputType.TYPE_CLASS_NUMBER)
            addView(row(labeled("From", dimStart), labeled("To", dimEnd), labeled("Brightness %", dimLevel)))
            addView(note("Full brightness returns whenever a monitor is down."))
            sound = switch("Quack when a server goes down", c.soundEnabled)
            addView(sound)
            quietStart = field("22:00", NightWindow.formatHhMm(c.quietStartMinute), InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
            quietEnd = field("09:00", NightWindow.formatHhMm(c.quietEndMinute), InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
            addView(row(labeled("Silent from (Pacific)", quietStart), labeled("Until", quietEnd)))
        }

        val columnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(24) })
            addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(p.crust)
            addView(TextView(this@SettingsActivity).apply {
                text = "Kuma Board ${BuildConfig.VERSION_NAME}"
                setTextColor(p.subtext0); pf(14f)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Cancel", primary = false) { finish() })
            addView(space(dp(12)))
            addView(button("Save", primary = true) { save() })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
            addView(ScrollView(this@SettingsActivity).apply {
                isFillViewport = true
                addView(columnsRow.apply { setPadding(dp(20), dp(12), dp(20), dp(12)) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)
        Immersive.install(this) // no system bars here either
        // Bars are hidden, so the content can use the whole screen; keep it clear of the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(0, 0, 0, ime.bottom)
            insets
        }
    }

    /** Validates everything through the same code path as adb/JSON provisioning. */
    private fun readForm(): Validated<BoardConfig> {
        val addr = address.text.toString()
        val slugText = slug.text.toString()
        // If the address is a full status page URL, its slug wins unless the slug field was edited.
        val urlSlug = (ServerAddress.parse(addr) as? Validated.Ok)?.value?.slugFromUrl
        val effectiveSlug = if (urlSlug != null && slugText == store.config.value.slug) null else slugText
        return Provisioning.apply(
            store.config.value,
            url = addr,
            slug = effectiveSlug,
            refreshSeconds = refresh.text.toString().trim().toIntOrNull() ?: -1,
            sort = if (sort.checkedRadioButtonId == 2) "problems" else "kuma",
            columns = columns.text.toString().trim().ifEmpty { "0" }.toIntOrNull() ?: -1,
            dimEnabled = dim.isChecked,
            dimStart = dimStart.text.toString(),
            dimEnd = dimEnd.text.toString(),
            dimLevel = dimLevel.text.toString().trim().toDoubleOrNull()?.let { if (it <= 1.0) it / 100.0 else it } ?: -1.0,
            sound = sound.isChecked,
            quietStart = quietStart.text.toString(),
            quietEnd = quietEnd.text.toString(),
        )
    }

    private fun save() {
        when (val r = readForm()) {
            is Validated.Ok -> {
                store.save(r.value)
                setResult(Activity.RESULT_OK)
                finish()
            }
            is Validated.Err -> showStatus(r.message, MonitorStatus.DOWN)
        }
    }

    private fun testConnection() {
        val cfg = when (val r = readForm()) {
            is Validated.Ok -> r.value
            is Validated.Err -> return showStatus(r.message, MonitorStatus.DOWN)
        }
        showStatus("Testing ${cfg.host()}…", MonitorStatus.PENDING)
        lifecycleScope.launch {
            try {
                val board = KumaClient().fetch(cfg)
                val down = board.downCount
                showStatus("OK — ${board.monitors.size} monitors" + (if (down > 0) ", $down down" else ", none down"), MonitorStatus.UP)
            } catch (e: Exception) {
                showStatus(e.message ?: "Failed", MonitorStatus.DOWN)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Immersive.hide(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.hide(this)
    }

    private fun showStatus(msg: String, s: MonitorStatus) {
        status.text = msg
        status.setTextColor(if (s == MonitorStatus.PENDING) p.subtext0 else p.statusColor(s))
    }

    // ------------------------------------------------------------------ tiny view kit

    /** Greybeard at a pixel-perfect size near [size] dp (see [PixelFont]). */
    private fun android.widget.TextView.pf(size: Float, bold: Boolean = false) {
        val pick = PixelFont.pick(size * resources.displayMetrics.density)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, pick.px.toFloat())
        typeface = PixelFont.typeface(context, pick.file, bold)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun heading(t: String) = TextView(this).apply {
        text = t; pf(18f, bold = true); setTextColor(p.text)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; pf(14f); setTextColor(p.subtext0)
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; pf(13f); setTextColor(p.subtext0); setPadding(0, dp(8), 0, 0)
        setTextIsSelectable(true)
    }

    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        setText(value); this.hint = hint; inputType = type or (if (type and InputType.TYPE_MASK_CLASS == 0) InputType.TYPE_CLASS_TEXT else 0)
        isSingleLine = true; pf(17f); setTextColor(p.text); setHintTextColor(p.overlay0)
        minHeight = dp(48)
        background = rounded(p.base, p.surface0)
        setPadding(dp(12), 0, dp(12), 0)
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    private fun labeled(t: String, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(t)); addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, 0)
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10) })
        }
    }

    private fun radio(id: Int, t: String, checked: Boolean) = RadioButton(this).apply {
        this.id = id; text = t; isChecked = checked; pf(16f); setTextColor(p.text)
        buttonTintList = android.content.res.ColorStateList.valueOf(p.blue)
        minHeight = dp(48)
    }

    private fun switch(t: String, checked: Boolean) = Switch(this).apply {
        text = t; isChecked = checked; pf(16f); setTextColor(p.text); minHeight = dp(48)
        thumbTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.blue, p.subtext0),
        )
        trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.surface1, p.surface0),
        )
    }

    private fun button(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; pf(16f, bold = primary); minHeight = dp(48); minWidth = dp(96)
        setTextColor(if (primary) p.crust else p.text)
        background = if (primary) rounded(p.blue, p.blue) else rounded(p.base, p.surface1)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }

    private fun rounded(fill: Int, border: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat(); setColor(fill); setStroke(dp(1), border)
    }

}
