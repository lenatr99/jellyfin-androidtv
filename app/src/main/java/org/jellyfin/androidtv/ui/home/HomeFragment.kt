package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.koin.android.ext.android.inject

class HomeFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()

	fun focusRows() {
		HomeFocusLog.i("focusRows from=%s", HomeFocusLog.describe(activity?.currentFocus))
		val rows = homeRows()
		rows?.setGridFocusBlocked(false)
		val grid = rows?.verticalGridView ?: run {
			HomeFocusLog.i("focusRows aborted: verticalGridView is null")
			return
		}
		grid.post {
			val focused = grid.requestFocus()
			HomeFocusLog.i("focusRows requestFocus=%s focused=%s", focused, HomeFocusLog.describe(grid.findFocus()))
			HomeFocusLog.dump("focusRows", view, null, grid)
		}
	}

	private fun homeRows(): HomeRowsFragment? =
		childFragmentManager.fragments.filterIsInstance<HomeRowsFragment>().firstOrNull()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val context = requireContext()

		val frame = object : FrameLayout(context) {
			override fun dispatchKeyEvent(event: KeyEvent): Boolean {
				HomeFocusLog.key(this, event)
				if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
					when (event.keyCode) {
						KeyEvent.KEYCODE_DPAD_UP -> {
							val rows = homeRows()
							val position = rows?.selectedPosition ?: -1
							if (position > 0) {
								HomeFocusLog.i("UP from row %s -> previous row", position)
								rows?.verticalGridView?.setSelectedPositionSmooth(position - 1)
								return true
							}
						}
					}
				}
				return super.dispatchKeyEvent(event)
			}

			override fun requestChildFocus(child: View?, focused: View?) {
				HomeFocusLog.i(
					"requestChildFocus child=%s focused=%s",
					HomeFocusLog.describe(child),
					HomeFocusLog.describe(focused),
				)
				super.requestChildFocus(child, focused)
			}
		}.apply {
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)
			clipChildren = false
			clipToPadding = false
		}

		val rowsContainer = FragmentContainerView(context).apply {
			id = R.id.home_rows_container
			isFocusable = false
			clipChildren = false
			clipToPadding = false
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
			layoutParams = FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)
		}

		frame.addView(rowsContainer)
		return frame
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		if (childFragmentManager.findFragmentById(R.id.home_rows_container) == null) {
			childFragmentManager.commit {
				replace<HomeRowsFragment>(R.id.home_rows_container)
			}
		}

		val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
			HomeFocusLog.i(
				"FOCUS old=%s new=%s",
				HomeFocusLog.describe(oldFocus),
				HomeFocusLog.describe(newFocus),
			)
			HomeFocusLog.dump("focusChange", view, null, homeRows()?.verticalGridView)
		}
		view.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
		view.post {
			HomeFocusLog.dump("attached", view, null, homeRows()?.verticalGridView)
		}
		viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onDestroy(owner: LifecycleOwner) {
				if (view.viewTreeObserver.isAlive) {
					view.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
				}
			}
		})

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)
	}
}
