package com.xaepudin.midtrack

import android.graphics.drawable.Drawable

/** One row in the Apps screen. Rank: 0 playing, 1 pinned, 2 player, 3 other. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val playing: Boolean,
    val pinned: Boolean,
    val rank: Int,
    val icon: Drawable?,
)
