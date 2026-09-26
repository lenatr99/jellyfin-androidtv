package org.jellyfin.androidtv.ui.home

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.core.view.isVisible
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.jellyfin.androidtv.ui.base.JellyfinTheme

class MediaBarRowPresenter : RowPresenter() {
	init {
		headerPresenter = ZeroHeightHeaderPresenter()
		selectEffectEnabled = false
	}

	override fun isUsingDefaultSelectEffect() = false

	override fun createRowViewHolder(parent: ViewGroup): ViewHolder {
		val height = heroHeight(parent)
		val composeView = ComposeView(parent.context).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			parent.findViewTreeViewModelStoreOwner()?.let { setViewTreeViewModelStoreOwner(it) }
			isFocusable = false
			isFocusableInTouchMode = false
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
		}

		composeView.setContent {
			JellyfinTheme {
				MediaBar(
					focusRequester = remember { FocusRequester() },
					modifier = Modifier.fillMaxSize(),
				)
			}
		}

		return ViewHolder(MediaBarRowView(parent, height, composeView))
	}

	override fun initializeRowViewHolder(vh: ViewHolder) {
		super.initializeRowViewHolder(vh)
		vh.headerViewHolder?.view?.isVisible = false
	}

	override fun onBindRowViewHolder(vh: ViewHolder, item: Any) {
		super.onBindRowViewHolder(vh, item)
		vh.headerViewHolder?.view?.isVisible = false
	}

	companion object {
		const val HEIGHT_FRACTION = 0.83f

		fun heroHeight(parent: ViewGroup): Int {
			val h = parent.height.takeIf { it > 0 } ?: parent.resources.displayMetrics.heightPixels
			return (h * HEIGHT_FRACTION).toInt()
		}
	}
}

private class ZeroHeightHeaderPresenter : RowHeaderPresenter() {
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val view = View(parent.context).apply {
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
			visibility = View.GONE
		}
		return ViewHolder(view)
	}
}

private class MediaBarRowView(
	parent: ViewGroup,
	height: Int,
	composeView: ComposeView,
) : FrameLayout(parent.context) {
	init {
		descendantFocusability = FOCUS_AFTER_DESCENDANTS
		isFocusable = true
		isFocusableInTouchMode = true
		layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
		addView(composeView)
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
			HomeFocusLog.i(
				"MediaBarRow KEY %s rowFocused=%s focusedChild=%s",
				KeyEvent.keyCodeToString(event.keyCode),
				isFocused,
				HomeFocusLog.describe(findFocus()),
			)
		}

		return super.dispatchKeyEvent(event)
	}
}
