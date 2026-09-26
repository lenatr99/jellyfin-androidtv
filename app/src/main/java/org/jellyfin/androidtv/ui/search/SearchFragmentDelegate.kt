package org.jellyfin.androidtv.ui.search

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.api.client.ApiClient

class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
	private val api: ApiClient,
	private val scope: CoroutineScope,
) {
	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())

	fun showResults(searchResultGroups: Collection<SearchResultGroup>) {
		rowsAdapter.clear()
		val adapters = mutableListOf<ItemRowAdapter>()
		val seerrGroup = searchResultGroups.firstOrNull { it.seerrItems.isNotEmpty() }
		for ((labelRes, baseItems) in searchResultGroups) {
			if (baseItems.isEmpty()) continue
			val adapter = ItemRowAdapter(
				context,
				baseItems.toList(),
				CardPresenter(),
				rowsAdapter,
				QueryType.Search
			).apply {
				setRow(ListRow(HeaderItem(context.getString(labelRes)), this))
			}
			adapters.add(adapter)
		}
		for (adapter in adapters) adapter.Retrieve()

		// Jellyseerr titles last, like on web
		if (seerrGroup != null) {
			val seerrAdapter = MutableObjectAdapter<SeerrRowItem>(CardPresenter())
			seerrGroup.seerrItems.forEach(seerrAdapter::add)
			rowsAdapter.add(ListRow(HeaderItem(context.getString(seerrGroup.labelRes)), seerrAdapter))
		}
	}

	private fun confirmRequest(item: SeerrRowItem, adapter: MutableObjectAdapter<SeerrRowItem>) {
		if (item.requested) {
			Toast.makeText(context, R.string.lbl_seerr_requested, Toast.LENGTH_SHORT).show()
			return
		}
		AlertDialog.Builder(context)
			.setTitle(item.title)
			.setMessage(R.string.lbl_seerr_request_confirm)
			.setPositiveButton(R.string.lbl_seerr_request) { _, _ ->
				scope.launch {
					val ok = SeerrSearch.request(api, item)
					if (ok) {
						item.requested = true
						adapter.indexOf(item).takeIf { it >= 0 }?.let { adapter.set(it, item) }
					}
					Toast.makeText(context, if (ok) R.string.lbl_seerr_requested else R.string.lbl_seerr_request_failed, Toast.LENGTH_SHORT).show()
				}
			}
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (item is SeerrRowItem) {
			@Suppress("UNCHECKED_CAST")
			confirmRequest(item, (row as ListRow).adapter as MutableObjectAdapter<SeerrRowItem>)
			return@OnItemViewClickedListener
		}
		if (item !is BaseRowItem) return@OnItemViewClickedListener
		row as ListRow
		val adapter = row.adapter as ItemRowAdapter
		itemLauncher.launch(item as BaseRowItem?, adapter, context)
	}

	val onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
		val baseItem = item?.let { (item as BaseRowItem).baseItem }
		if (baseItem != null) {
			backgroundService.setBackground(baseItem)
		} else {
			backgroundService.clearBackgrounds()
		}
	}
}
