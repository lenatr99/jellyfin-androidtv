package org.jellyfin.androidtv.ui.search

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.android.ext.android.inject

/**
 * The movies and shows an actor appeared in, from Jellyseerr. Titles in the library open
 * there, the rest open the Jellyseerr details page.
 */
class SeerrPersonFragment : RowsSupportFragment() {
	companion object {
		const val ARG_PERSON_ID = "PersonId"
		const val ARG_NAME = "Name"

		private const val MAX_PER_ROW = 40
	}

	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val navigationRepository by inject<NavigationRepository>()
	private val requester by lazy { SeerrRequester(requireContext(), api, lifecycleScope, navigationRepository) }
	private val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())

	// Going back reuses this fragment with a new view, so the rows stay and only focus has to return
	private var openedRow = 0
	private var openedColumn = 0

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		adapter = rowsAdapter
		onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
			if (item !is SeerrRowItem) return@OnItemViewClickedListener
			val adapter = (row as ListRow).adapter
			openedRow = (0 until rowsAdapter.size()).indexOfFirst { rowsAdapter[it] == row }
			openedColumn = (0 until adapter.size()).indexOfFirst { adapter[it] == item }
			requester.open(item)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		if (rowsAdapter.size() > 0) {
			setSelectedPosition(openedRow, false, ListRowPresenter.SelectItemViewHolderTask(openedColumn).apply { isSmoothScroll = false })
			verticalGridView?.requestFocus()
			return
		}
		val args = requireArguments()

		viewLifecycleOwner.lifecycleScope.launch {
			val credits = SeerrSearch.credits(api, args.getInt(ARG_PERSON_ID))
			if (credits.isEmpty()) {
				Toast.makeText(requireContext(), R.string.lbl_seerr_no_credits, Toast.LENGTH_SHORT).show()
				return@launch
			}
			val name = args.getString(ARG_NAME).orEmpty()
			addRow(name, R.string.lbl_movies, credits.filter { it.mediaType == "movie" })
			addRow(name, R.string.lbl_series, credits.filter { it.mediaType == "tv" })
			verticalGridView?.requestFocus()
		}
	}

	override fun onResume() {
		super.onResume()
		// Cards have no backdrop of their own, so don't leave the last title's behind them
		backgroundService.clearBackgrounds()
	}

	private fun addRow(name: String, @StringRes label: Int, items: List<SeerrRowItem>) {
		if (items.isEmpty()) return
		val row = MutableObjectAdapter<SeerrRowItem>(CardPresenter())
		items.take(MAX_PER_ROW).forEach(row::add)
		rowsAdapter.add(ListRow(HeaderItem(getString(R.string.lbl_seerr_person_row, name, getString(label))), row))
	}
}
