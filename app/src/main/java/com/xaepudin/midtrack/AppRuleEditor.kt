package com.xaepudin.midtrack

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout

/**
 * The inline "rule for this app" panel, shown under an expanded app row.
 *
 * It edits a full copy of the jump settings for one package and writes it
 * through to [Prefs] on every change, so the running service picks the rule up
 * on the very next track — no save button, no restart. The first edit creates
 * the rule (seeded from the current global default); "Use default settings"
 * deletes it again.
 *
 * Nothing here rebuilds the app list: that would re-inflate the field you are
 * typing in on every keystroke.
 */
class AppRuleEditor(
    private val ctx: Context,
    private val pkg: String,
    private val appName: String,
    /** Called after every stored change with the rule now in force. */
    private val onChanged: (SeekLogic.Config) -> Unit,
    /** "Use default settings" was pressed — collapse the panel and rebuild. */
    private val onReset: () -> Unit,
) {

    /** What the panel edits: the app's own rule, or the default it starts from. */
    private var draft: SeekLogic.Config = Prefs.config(ctx, pkg)

    fun build(parent: ViewGroup): View {
        val root = LayoutInflater.from(ctx).inflate(R.layout.item_app_rule, parent, false)
        val state = root.findViewById<TextView>(R.id.ruleState)
        val modeGroup = root.findViewById<MaterialButtonToggleGroup>(R.id.ruleModeGroup)
        val rowAbsolute = root.findViewById<View>(R.id.ruleRowAbsolute)
        val rowPercent = root.findViewById<View>(R.id.ruleRowPercent)
        val rowRandom = root.findViewById<View>(R.id.ruleRowRandom)
        val inputAbsolute = root.findViewById<EditText>(R.id.ruleAbsolute)
        val inputPercent = root.findViewById<EditText>(R.id.rulePercent)
        val inputRandomMin = root.findViewById<EditText>(R.id.ruleRandomMin)
        val inputRandomMax = root.findViewById<EditText>(R.id.ruleRandomMax)
        val inputMinDuration = root.findViewById<EditText>(R.id.ruleMinDuration)
        val inputMaxTarget = root.findViewById<EditText>(R.id.ruleMaxTarget)
        val minDurationWrap = root.findViewById<TextInputLayout>(R.id.ruleMinDurationWrap)
        val maxTargetWrap = root.findViewById<TextInputLayout>(R.id.ruleMaxTargetWrap)
        val guardrailsOff = root.findViewById<TextView>(R.id.ruleGuardrailsOff)
        val autoNext = root.findViewById<MaterialSwitch>(R.id.ruleAutoNext)
        val reset = root.findViewById<MaterialButton>(R.id.btnRuleReset)

        // The guardrails master switch lives in Advanced settings. When it is
        // off, these per-app limits stay visible but cannot be edited or applied.
        val guardrailsOn = Prefs.guardrailsEnabled(ctx)
        minDurationWrap.isEnabled = guardrailsOn
        maxTargetWrap.isEnabled = guardrailsOn
        guardrailsOff.visibility = if (guardrailsOn) View.GONE else View.VISIBLE

        val customRuleText = ctx.getString(R.string.app_rule_custom, appName)
        state.text =
            if (Prefs.hasAppRule(ctx, pkg)) customRuleText
            else ctx.getString(R.string.using_default_settings)
        modeGroup.check(
            when (draft.mode) {
                "absolute" -> R.id.rbRuleAbsolute
                "random" -> R.id.rbRuleRandom
                else -> R.id.rbRulePercent
            }
        )

        fun showModeRows() {
            rowAbsolute.visibility = if (draft.mode == "absolute") View.VISIBLE else View.GONE
            rowPercent.visibility = if (draft.mode == "relative") View.VISIBLE else View.GONE
            rowRandom.visibility = if (draft.mode == "random") View.VISIBLE else View.GONE
        }

        /** One place that stores the rule and updates everything that shows it. */
        fun persist(cfg: SeekLogic.Config) {
            draft = cfg
            Prefs.setAppRule(ctx, pkg, cfg)
            reset.visibility = View.VISIBLE
            state.text = customRuleText
            onChanged(cfg)
        }

        bindInt(inputAbsolute, draft.absoluteSeconds, 0, 7200, { c, v -> c.copy(absoluteSeconds = v) }, { persist(it) })
        bindInt(inputPercent, draft.relativePercent, 1, 99, { c, v -> c.copy(relativePercent = v) }, { persist(it) })
        bindInt(inputRandomMin, draft.randomMin, 1, 99, { c, v -> c.copy(randomMin = v) }, { persist(it) })
        bindInt(inputRandomMax, draft.randomMax, 1, 99, { c, v -> c.copy(randomMax = v) }, { persist(it) })
        bindInt(inputMinDuration, draft.minDurationSec, 0, 7200, { c, v -> c.copy(minDurationSec = v) }, { persist(it) })
        bindInt(inputMaxTarget, draft.maxTargetPercent, 0, 100, { c, v -> c.copy(maxTargetPercent = v) }, { persist(it) })

        autoNext.isChecked = draft.seekOnAutoNext
        autoNext.setOnCheckedChangeListener { _, checked ->
            persist(draft.copy(seekOnAutoNext = checked))
        }

        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            persist(
                draft.copy(
                    mode = when (checkedId) {
                        R.id.rbRuleAbsolute -> "absolute"
                        R.id.rbRuleRandom -> "random"
                        else -> "relative"
                    }
                )
            )
            // Just swap which value row is visible — the group itself has
            // already re-laid out the buttons.
            showModeRows()
        }

        reset.visibility = if (Prefs.hasAppRule(ctx, pkg)) View.VISIBLE else View.GONE
        reset.setOnClickListener {
            Prefs.clearAppRule(ctx, pkg)
            onReset()
        }

        showModeRows()
        return root
    }

    /**
     * Saves an int field as it is typed (phones rarely fire focus loss — the
     * user just closes the keyboard or leaves the app) and snaps the text back
     * into range when the field loses focus.
     */
    private fun bindInt(
        input: EditText,
        initial: Int,
        lo: Int,
        hi: Int,
        apply: (SeekLogic.Config, Int) -> SeekLogic.Config,
        onEdit: (SeekLogic.Config) -> Unit,
    ) {
        input.setText(ctx.getString(R.string.number_value, initial))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                onEdit(apply(draft, v.coerceIn(lo, hi)))
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            // "9999" typed into a 0–99 field is stored clamped; make the field
            // show what was actually stored.
            if (!hasFocus) input.setText(ctx.getString(R.string.number_value, valueOf(input.id)))
        }
    }

    /** The stored value behind a field, for the focus-loss snap. */
    private fun valueOf(id: Int): Int = when (id) {
        R.id.ruleAbsolute -> draft.absoluteSeconds
        R.id.rulePercent -> draft.relativePercent
        R.id.ruleRandomMin -> draft.randomMin
        R.id.ruleRandomMax -> draft.randomMax
        R.id.ruleMinDuration -> draft.minDurationSec
        R.id.ruleMaxTarget -> draft.maxTargetPercent
        else -> 0
    }
}
