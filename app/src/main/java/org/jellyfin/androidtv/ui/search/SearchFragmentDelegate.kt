package org.jellyfin.androidtv.ui.search

import android.content.Context
import androidx.annotation.StringRes
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import kotlinx.coroutines.CoroutineScope
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
import org.jellyfin.androidtv.ui.navigation.NavigationRepository

class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
	private val api: ApiClient,
	private val scope: CoroutineScope,
	private val navigationRepository: NavigationRepository,
) {
	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())
	private val seerrRequester = SeerrRequester(context, api, scope, navigationRepository)

	fun showResults(searchResultGroups: Collection<SearchResultGroup>) {
		rowsAdapter.clear()
		val adapters = mutableListOf<ItemRowAdapter>()
		val seerrGroup = searchResultGroups.firstOrNull { it.seerrItems.isNotEmpty() }
		val (actors, titles) = seerrGroup?.seerrItems.orEmpty().partition { it.mediaType == SeerrSearch.MEDIA_TYPE_PERSON }
		// Jellyseerr ranks by relevance, so only lead with actors when one is the best match
		val actorsFirst = seerrGroup?.seerrItems?.firstOrNull()?.mediaType == SeerrSearch.MEDIA_TYPE_PERSON
		if (actorsFirst) addSeerrRow(R.string.lbl_people, actors)
		for ((labelRes, baseItems) in searchResultGroups) {
			if (baseItems.isEmpty()) continue
			// Jellyseerr actors open a full filmography, so they replace the library's people
			if (labelRes == R.string.lbl_people && actors.isNotEmpty()) continue
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
		if (seerrGroup != null) addSeerrRow(seerrGroup.labelRes, titles)
		if (!actorsFirst) addSeerrRow(R.string.lbl_people, actors)
	}

	private fun addSeerrRow(@StringRes labelRes: Int, items: List<SeerrRowItem>) {
		if (items.isEmpty()) return
		val adapter = MutableObjectAdapter<SeerrRowItem>(CardPresenter())
		items.forEach(adapter::add)
		rowsAdapter.add(ListRow(HeaderItem(context.getString(labelRes)), adapter))
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (item is SeerrRowItem) {
			seerrRequester.open(item)
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
