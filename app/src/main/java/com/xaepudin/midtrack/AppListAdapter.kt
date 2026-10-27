package com.xaepudin.midtrack

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * RecyclerView adapter for the Apps screen. It owns the expanded per-app rule
 * panel so scrolling and rebinding keep the list cheap.
 */
class AppListAdapter(
    private val ctx: Context,
    private val stateText: (AppEntry) -> String,
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    interface Callbacks {
        fun onToggle(entry: AppEntry, selected: Boolean)
        fun onRule(entry: AppEntry)
        fun onPin(entry: AppEntry)
        fun onRuleChanged(pkg: String)
        fun onRuleReset(pkg: String)
    }

    private var entries: List<AppEntry> = emptyList()
    private val stateViews = HashMap<String, TextView>()

    /** Whether the whitelist switches are active ("Only these"). */
    var scopeSelected: Boolean = false
    var selectedPackages: Set<String> = emptySet()

    /** Package whose rule editor is open, if any. */
    var expandedPkg: String? = null

    fun submit(list: List<AppEntry>) {
        entries = list
        stateViews.clear()
        notifyDataSetChanged()
    }

    /** Refresh just the state line after a rule edit, without rebuilding the editor. */
    fun refreshState(pkg: String) {
        val entry = entries.firstOrNull { it.pkg == pkg } ?: return
        stateViews[pkg]?.text = stateText(entry)
    }

    fun findPosition(pkg: String): Int = entries.indexOfFirst { it.pkg == pkg }

    /** Open this app's rule editor, or close it if it was already open. */
    fun toggleExpanded(pkg: String) {
        val previous = expandedPkg
        expandedPkg = if (previous == pkg) null else pkg
        if (previous != null) {
            val oldPosition = findPosition(previous)
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
        }
        if (expandedPkg != null) {
            val newPosition = findPosition(pkg)
            if (newPosition >= 0) notifyItemChanged(newPosition)
        }
    }

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val row = LayoutInflater.from(parent.context).inflate(R.layout.item_app, container, false)
        container.addView(row)
        return Holder(container, row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        // Drop a previously bound editor panel; the row itself stays at index 0.
        while (holder.container.childCount > 1) {
            holder.container.removeViewAt(1)
        }
        val entry = entries[position]
        holder.pkg = entry.pkg

        val icon = holder.row.findViewById<ImageView>(R.id.appIcon)
        if (entry.icon != null) icon.setImageDrawable(entry.icon) else icon.setImageResource(R.drawable.ic_brand)
        holder.row.findViewById<TextView>(R.id.appName).text = entry.label
        val state = holder.row.findViewById<TextView>(R.id.appPkg)
        state.text = stateText(entry)
        stateViews[entry.pkg] = state

        val pin = holder.row.findViewById<MaterialButton>(R.id.appPinBtn)
        pin.setIconResource(if (entry.pinned) R.drawable.ic_star else R.drawable.ic_star_border)
        pin.iconTint = ColorStateList.valueOf(
            ctx.getColor(if (entry.pinned) R.color.ss_primary else R.color.ss_on_surface_variant)
        )
        pin.contentDescription = ctx.getString(
            if (entry.pinned) R.string.apps_unpin else R.string.apps_pin,
            entry.label,
        )
        pin.setOnClickListener { callbacks.onPin(entry) }

        val rule = holder.row.findViewById<MaterialButton>(R.id.appRuleBtn)
        rule.contentDescription = ctx.getString(R.string.rule_for_app, entry.label)
        rule.setOnClickListener { callbacks.onRule(entry) }

        val toggle = holder.row.findViewById<MaterialSwitch>(R.id.appSwitch)
        if (scopeSelected) {
            toggle.visibility = View.VISIBLE
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = entry.pkg in selectedPackages
            toggle.setOnCheckedChangeListener { _, checked -> callbacks.onToggle(entry, checked) }
            holder.row.isClickable = true
            holder.row.isFocusable = true
            holder.row.setOnClickListener { toggle.toggle() }
        } else {
            toggle.setOnCheckedChangeListener(null)
            toggle.visibility = View.GONE
            holder.row.isClickable = false
            holder.row.isFocusable = false
            holder.row.setOnClickListener(null)
        }

        if (entry.pkg == expandedPkg) {
            holder.container.addView(
                AppRuleEditor(
                    ctx = ctx,
                    pkg = entry.pkg,
                    appName = entry.label,
                    onChanged = {
                        stateViews[entry.pkg]?.text = stateText(entry)
                        callbacks.onRuleChanged(entry.pkg)
                    },
                    onReset = {
                        expandedPkg = null
                        callbacks.onRuleReset(entry.pkg)
                        val current = holder.bindingAdapterPosition
                        if (current != RecyclerView.NO_POSITION) notifyItemChanged(current)
                    },
                ).build(holder.container)
            )
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.pkg?.let { stateViews.remove(it) }
        super.onViewRecycled(holder)
    }

    class Holder(val container: LinearLayout, val row: View) : RecyclerView.ViewHolder(container) {
        var pkg: String? = null
    }
}
