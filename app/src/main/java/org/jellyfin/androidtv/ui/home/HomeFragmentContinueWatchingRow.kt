package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.koin.core.component.KoinComponent

class HomeFragmentContinueWatchingRow(
	private val resumeQuery: GetResumeItemsRequest,
	private val nextUpQuery: GetNextUpRequest,
) : HomeFragmentRow, KoinComponent {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val bannerPresenter = CardPresenter(true, ImageType.THUMB, CARD_HEIGHT)
		val rowAdapter = ItemRowAdapter(
			context,
			resumeQuery,
			nextUpQuery,
			true,
			true,
			bannerPresenter,
			rowsAdapter,
		)
		rowAdapter.setReRetrieveTriggers(
			arrayOf(ChangeTriggerType.TvPlayback, ChangeTriggerType.MoviePlayback)
		)

		val header = HeaderItem(
			"${context.getString(R.string.lbl_continue_watching)} / ${context.getString(R.string.lbl_next_up)}"
		)
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.Retrieve()
		rowsAdapter.add(row)
	}

	private companion object {
		const val CARD_HEIGHT = 140
	}
}
