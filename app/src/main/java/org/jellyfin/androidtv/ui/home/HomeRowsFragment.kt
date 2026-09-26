package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()
	private val mediaBarViewModel by viewModel<MediaBarViewModel>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository, userPreferences) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val presenterSelector = ClassPresenterSelector().apply {
			addClassPresenter(MediaBarRow::class.java, MediaBarRowPresenter())
			addClassPresenter(ListRow::class.java, PositionableListRowPresenter())
		}
		val rowsAdapter = MutableObjectAdapter<Row>(presenterSelector)
		rowsAdapter.add(MediaBarRow())
		adapter = rowsAdapter

		lifecycleScope.launch {
			mediaBarViewModel.loaded.filter { it }.first()
			if (mediaBarViewModel.items.value.isEmpty()) {
				val hero = rowsAdapter.firstOrNull { it is MediaBarRow } ?: return@launch
				rowsAdapter.remove(hero)
			}
		}

		lifecycleScope.launch(Dispatchers.IO) {
			val currentUser = withTimeout(30.seconds) {
				userRepository.currentUser.filterNotNull().first()
			}

			// Start out with default sections
			val homesections = userSettingPreferences.activeHomesections
			// Web home sections from the server plugin; these replace the built-in latest rows
			val pluginSections = HomeFragmentPluginSectionRow.sections(api, currentUser.id)

			// Make sure the rows are empty
			val rows = mutableListOf<HomeFragmentRow>()
			var insertRequestsAt: Int? = null
			var addedContinueWatching = false

			// Check for coroutine cancellation
			if (!isActive) return@launch

			// Actually add the sections
			for (section in homesections) {
				val before = rows.size
				when (section) {
					HomeSectionType.LATEST_MEDIA -> if (pluginSections.isEmpty()) {
						rows.add(helper.loadRecentlyAdded(userViewsRepository.views.first()))
					}
					// Hide My Media; libraries stay reachable from the sidebar.
					HomeSectionType.LIBRARY_TILES_SMALL,
					HomeSectionType.LIBRARY_BUTTONS -> Unit
					HomeSectionType.RESUME,
					HomeSectionType.NEXT_UP -> if (!addedContinueWatching) {
						rows.add(helper.loadContinueWatchingNextUp())
						addedContinueWatching = true
					}
					HomeSectionType.RESUME_AUDIO -> rows.add(helper.loadResumeAudio())
					HomeSectionType.RESUME_BOOK -> Unit // Books are not (yet) supported
					HomeSectionType.ACTIVE_RECORDINGS -> rows.add(helper.loadLatestLiveTvRecordings())
					HomeSectionType.LIVE_TV -> if (currentUser.policy?.enableLiveTvAccess == true) {
						rows.add(HomeFragmentLiveTVRow(requireActivity(), userRepository))
						rows.add(helper.loadOnNow())
					}

					HomeSectionType.NONE -> Unit
				}
				// Match the web plugin order: after Continue Watching / Next Up.
				if (section == HomeSectionType.RESUME || section == HomeSectionType.NEXT_UP) {
					if (rows.size > before) insertRequestsAt = rows.size
				}
			}

			// Few requests at a time keeps older TVs responsive; order follows the web home screen
			val pluginRows = coroutineScope {
				pluginSections.map { section ->
					async(pluginSectionDispatcher) {
						HomeFragmentPluginSectionRow(section.title, HomeFragmentPluginSectionRow.fetch(api, currentUser.id, section))
					}
				}.awaitAll()
			}
			rows.addAll(insertRequestsAt ?: rows.size, pluginRows)

			// Add sections to layout
			withContext(Dispatchers.Main) {
				val cardPresenter = CardPresenter()

				// Add rows in order
				notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				for (row in rows) row.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)

				// Wire up Live TV sibling rows so the On Now row removes the buttons row when empty
				@Suppress("UNCHECKED_CAST")
				val rowsAdapter = adapter as MutableObjectAdapter<Row>
				for (i in 0 until rowsAdapter.size()) {
					val listRow = rowsAdapter.get(i) as? ListRow ?: continue
					val itemAdapter = listRow.adapter as? ItemRowAdapter ?: continue
					if (itemAdapter.queryType == QueryType.LiveTvProgram && i > 0) {
						val previousRow = rowsAdapter.get(i - 1) as? ListRow ?: continue
						itemAdapter.setSiblingRow(previousRow)
					}
				}
			}
		}

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)
			}
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		verticalGridView?.apply {
			windowAlignment = VerticalGridView.WINDOW_ALIGN_LOW_EDGE
			windowAlignmentOffsetPercent = -1f
			itemAlignmentOffset = 0
			itemAlignmentOffsetPercent = 0f
			clipToPadding = false
			clipChildren = false
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
			setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.media_bar_navy))
			setItemSpacing(0)
			applyWindowAlignment(mediaBarSelected = true)
			post {
				val focused = requestFocus()
				HomeFocusLog.i(
					"initial grid requestFocus=%s selected=%s focused=%s",
					focused,
					selectedPosition,
					HomeFocusLog.describe(findFocus()),
				)
			}
		}
	}

	fun setGridFocusBlocked(blocked: Boolean) {
		HomeFocusLog.i(
			"setGridFocusBlocked blocked=%s selected=%s focused=%s",
			blocked,
			selectedPosition,
			HomeFocusLog.describe(verticalGridView?.findFocus()),
		)
		verticalGridView?.descendantFocusability = if (blocked) {
			ViewGroup.FOCUS_BLOCK_DESCENDANTS
		} else {
			ViewGroup.FOCUS_AFTER_DESCENDANTS
		}
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		val returningToHome = !justLoaded

		// Keep the home backdrop stable like the web Media Bar: row focus must not swap the
		// full-screen background behind Movies / TV Shows / other pills.
		backgroundService.clearBackgrounds()

		if (currentRow != null &&
			currentItem != null &&
			currentItem?.baseItem != null &&
			currentItem!!.baseItem!!.id == dataRefreshService.lastDeletedItemId
		) {
			(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		if (returningToHome) {
			refreshCurrentItem()
			refreshRows()
		} else {
			justLoaded = false
		}

		val selected =
			(adapter as? MutableObjectAdapter<Row>)?.get(selectedPosition)

		mediaBarViewModel.setPlaybackAllowed(selected is MediaBarRow)

		Timber.i("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)

		verticalGridView?.post {
			if (!isAdded) return@post

			val grid = verticalGridView ?: return@post

			// Home always lands at the Media Bar.
			grid.setSelectedPosition(0)
			applyWindowAlignment(mediaBarSelected = true)

			grid.post {
				if (!isAdded) return@post

				(parentFragment as? HomeFragment)?.focusRows()
			}
		}
	}

	override fun onPause() {
		mediaBarViewModel.setPlaybackAllowed(false)
		super.onPause()
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	private fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
		lifecycleScope.launch(Dispatchers.IO) {
			if (delayed) delay(1.5.seconds)

			repeat(adapter.size()) { i ->
				val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
				if (force) rowAdapter?.Retrieve()
				else rowAdapter?.ReRetrieveIfNeeded()
			}
		}
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	private fun applyWindowAlignment(mediaBarSelected: Boolean) {
		val grid = verticalGridView ?: return
		val offset = if (mediaBarSelected) {
			0
		} else {
			TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP,
				CONTENT_ROW_TOP_DP,
				resources.displayMetrics,
			).toInt()
		}
		if (grid.windowAlignmentOffset != offset) {
			grid.windowAlignmentOffset = offset
		}
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item is GridButton) {
				when (item.id) {
					LiveTvOption.LIVE_TV_GUIDE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvGuide)
					LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSchedule)
					LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvRecordings)
					LiveTvOption.LIVE_TV_SERIES_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
				}
			}

			if (item !is BaseRowItem) return
			if (row !is ListRow) return
			@Suppress("UNCHECKED_CAST")
			itemLauncher.launch(item, row.adapter as MutableObjectAdapter<Any>, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (row is MediaBarRow) {
				HomeFocusLog.i(
					"selected MediaBarRow holder=%s focused=%s",
					rowViewHolder?.view?.javaClass?.simpleName,
					HomeFocusLog.describe(verticalGridView?.findFocus()),
				)

				currentItem = null
				currentRow = null

				mediaBarViewModel.setPlaybackAllowed(
					lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
				)

				applyWindowAlignment(mediaBarSelected = true)
				return
			}

			mediaBarViewModel.setPlaybackAllowed(false)
			applyWindowAlignment(mediaBarSelected = false)

			if (item !is BaseRowItem) {
				currentItem = null
			} else {
				currentItem = item
				currentRow = row as ListRow

				val itemRowAdapter = row.adapter as? ItemRowAdapter
				itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))
			}
		}
	}

	companion object {
		private const val CONTENT_ROW_TOP_DP = 108f
		private val pluginSectionDispatcher = Dispatchers.IO.limitedParallelism(3)
	}
}
