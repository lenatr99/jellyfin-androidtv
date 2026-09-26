package org.jellyfin.androidtv.ui.home

import androidx.leanback.widget.Row
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

class MediaBarRow : Row()

fun MutableObjectAdapter<Row>.addAfterMediaBar(row: Row) {
	val hero = indexOfFirst { it is MediaBarRow }
	add(if (hero >= 0) hero + 1 else 0, row)
}
