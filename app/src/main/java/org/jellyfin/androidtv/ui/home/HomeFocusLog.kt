package org.jellyfin.androidtv.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import timber.log.Timber

internal object HomeFocusLog {
	const val TAG = "HomeFocus"

	fun i(message: String, vararg args: Any?) {
		Timber.tag(TAG).i(message, *args)
	}

	fun describe(view: View?): String {
		if (view == null) return "null"
		val id = viewId(view)
		val loc = IntArray(2)
		runCatching { view.getLocationOnScreen(loc) }
		return buildString {
			append(view.javaClass.simpleName)
			append('#')
			append(id)
			append(" focusable=")
			append(view.isFocusable)
			append(" focused=")
			append(view.isFocused)
			append(" hasFocus=")
			append(view.hasFocus())
			append(' ')
			append(view.width)
			append('x')
			append(view.height)
			append(" @")
			append(loc[0])
			append(',')
			append(loc[1])
			append(" vis=")
			append(view.visibility)
			append(" desc=")
			append(descendantMode(view))
		}
	}

	fun key(host: View, event: KeyEvent) {
		if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
		when (event.keyCode) {
			KeyEvent.KEYCODE_DPAD_UP,
			KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_LEFT,
			KeyEvent.KEYCODE_DPAD_RIGHT,
			KeyEvent.KEYCODE_DPAD_CENTER,
			KeyEvent.KEYCODE_ENTER,
			-> Unit
			else -> return
		}
		i(
			"KEY %s hostFocus=%s activityFocus=%s",
			KeyEvent.keyCodeToString(event.keyCode),
			describe(host.findFocus()),
			describe(host.activity()?.currentFocus),
		)
	}

	fun dump(label: String, root: View?, toolbar: View?, grid: View?) {
		i("DUMP %s toolbar=%s", label, describe(toolbar))
		if (toolbar is ViewGroup) {
			for (index in 0 until toolbar.childCount) {
				i("DUMP %s toolbarChild[%s]=%s", label, index, describe(toolbar.getChildAt(index)))
			}
		}
		i("DUMP %s grid=%s", label, describe(grid))
		i("DUMP %s rootFocus=%s", label, describe(root?.findFocus()))
		i("DUMP %s activityFocus=%s", label, describe(root?.activity()?.currentFocus))
		i("DUMP %s focusChain=%s", label, focusChain(root?.findFocus() ?: toolbar?.findFocus()))
	}

	fun focusChain(view: View?): String {
		val chain = ArrayList<String>(12)
		var current: View? = view
		while (current != null && chain.size < 12) {
			chain.add(describe(current))
			current = current.parent as? View
		}
		return chain.joinToString(" <- ")
	}

	private fun descendantMode(view: View): String {
		val group = view as? ViewGroup ?: return "-"
		return when (group.descendantFocusability) {
			ViewGroup.FOCUS_BLOCK_DESCENDANTS -> "BLOCK"
			ViewGroup.FOCUS_BEFORE_DESCENDANTS -> "BEFORE"
			ViewGroup.FOCUS_AFTER_DESCENDANTS -> "AFTER"
			else -> group.descendantFocusability.toString()
		}
	}

	private fun viewId(view: View): String {
		if (view.id == View.NO_ID) return "no-id"
		return runCatching { view.resources.getResourceEntryName(view.id) }.getOrElse { "id=${view.id}" }
	}

	private fun View.activity(): Activity? {
		var context: Context? = this.context
		while (context is ContextWrapper) {
			if (context is Activity) return context
			context = context.baseContext
		}
		return context as? Activity
	}
}
