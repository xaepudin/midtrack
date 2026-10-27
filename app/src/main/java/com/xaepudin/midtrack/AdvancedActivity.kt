package com.xaepudin.midtrack

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout

/**
 * Everything that is configured once and then left alone: the guardrails,
 * notification behavior, natural-end behavior and the battery-optimization
 * shortcut. Keeping it off the main screen leaves the main screen for the
 * controls a user actually touches while listening.
 */
class AdvancedActivity : Activity() {

    private lateinit var inputMinDuration: EditText
    private lateinit var inputMaxTarget: EditText
    private lateinit var minDurationWrap: TextInputLayout
    private lateinit var maxTargetWrap: TextInputLayout
    private lateinit var switchGuardrails: MaterialSwitch
    private lateinit var guardrailsOffHint: TextView
    private lateinit var switchRepin: MaterialSwitch
    private lateinit var chkAutoNext: MaterialSwitch
    private lateinit var batteryStatus: TextView
    private lateinit var btnBattery: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_advanced)

        val root = findViewById<View>(R.id.advancedRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.advanced_title)
        toolbar.navigationContentDescription = getString(R.string.back)
        toolbar.setNavigationOnClickListener { finish() }

        inputMinDuration = findViewById(R.id.inputMinDuration)
        inputMaxTarget = findViewById(R.id.inputMaxTarget)
        minDurationWrap = findViewById(R.id.minDurationWrap)
        maxTargetWrap = findViewById(R.id.maxTargetWrap)
        switchGuardrails = findViewById(R.id.switchGuardrails)
        guardrailsOffHint = findViewById(R.id.guardrailsOffHint)
        switchRepin = findViewById(R.id.switchRepin)
        chkAutoNext = findViewById(R.id.chkAutoNext)
        batteryStatus = findViewById(R.id.batteryStatus)
        btnBattery = findViewById(R.id.btnBattery)
        findViewById<View>(R.id.btnSetupChecklist).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        wireInput(inputMinDuration, 0, 7200) { Prefs.setMinDurationSec(this, it) }
        wireInput(inputMaxTarget, 0, 100) { Prefs.setMaxTargetPercent(this, it) }

        switchGuardrails.setOnCheckedChangeListener { _, checked ->
            Prefs.setGuardrailsEnabled(this, checked)
            renderGuardrailFields()
            sendPrefsChanged()
        }
        switchRepin.setOnCheckedChangeListener { _, checked ->
            Prefs.setRepinEnabled(this, checked)
            sendPrefsChanged()
        }
        chkAutoNext.setOnCheckedChangeListener { _, checked ->
            Prefs.setSeekOnAutoNext(this, checked)
            sendPrefsChanged()
        }
        btnBattery.setOnClickListener { DeviceSettings.openBatterySettings(this) }

        renderValues()
        renderBattery()
    }

    override fun onResume() {
        super.onResume()
        renderValues()
        renderBattery()
    }

    private fun renderValues() {
        inputMinDuration.setText(getString(R.string.number_value, Prefs.minDurationSec(this)))
        inputMaxTarget.setText(getString(R.string.number_value, Prefs.maxTargetPercent(this)))
        switchGuardrails.isChecked = Prefs.guardrailsEnabled(this)
        switchRepin.isChecked = Prefs.repinEnabled(this)
        chkAutoNext.isChecked = Prefs.seekOnAutoNext(this)
        renderGuardrailFields()
    }

    /**
     * Both limit fields are inert until the master switch is on. The stored
     * values are kept while off, so turning guardrails back on restores the
     * user's limits instead of silently resetting them.
     */
    private fun renderGuardrailFields() {
        val on = Prefs.guardrailsEnabled(this)
        minDurationWrap.isEnabled = on
        maxTargetWrap.isEnabled = on
        guardrailsOffHint.visibility = if (on) View.GONE else View.VISIBLE
    }

    /**
     * Saves on every keystroke (phones rarely fire focus-loss — the user just
     * closes the keyboard or leaves the app) and again on focus loss.
     */
    private fun wireInput(input: EditText, lo: Int, hi: Int, save: (Int) -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                save(v.coerceIn(lo, hi))
                sendPrefsChanged()
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = input.text.toString().toIntOrNull()
                if (v != null) save(v.coerceIn(lo, hi))
                renderValues()
                sendPrefsChanged()
            }
        }
    }

    private fun sendPrefsChanged() {
        sendBroadcast(Intent(MidtrackActionReceiver.ACTION_PREFS_CHANGED).setPackage(packageName))
    }

    private fun renderBattery() {
        val exempt = DeviceSettings.isIgnoringBatteryOptimizations(this)
        batteryStatus.text = if (exempt) {
            getString(R.string.battery_unrestricted)
        } else {
            getString(R.string.battery_optimized_message)
        }
        btnBattery.visibility = if (exempt) View.GONE else View.VISIBLE
    }


}
