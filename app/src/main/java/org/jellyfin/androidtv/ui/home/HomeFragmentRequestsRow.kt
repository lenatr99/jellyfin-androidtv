package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

class HomeFragmentRequestsRow(
	private val items: List<BaseItemDto>,
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		if (items.isEmpty()) return

		val rowAdapter = ItemRowAdapter(context, items, cardPresenter, rowsAdapter, true)
		val header = HeaderItem(context.getString(R.string.lbl_my_requests))
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.Retrieve()
		rowsAdapter.add(row)
	}

	companion object {
		private const val SECTION_TYPE = "MyJellyseerrRequests"

		suspend fun fetch(api: ApiClient, userId: UUID): List<BaseItemDto> = try {
			val ids = sectionItemIds(api, userId)
			if (ids.isEmpty()) {
				Timber.w("My Requests section returned no item ids")
				emptyList()
			} else {
				val loaded = loadLibraryItems(api, ids)
				Timber.i("My Requests loaded ${loaded.size} titles from ${ids.size} ids")
				loaded
			}
		} catch (err: ApiClientException) {
			Timber.e(err, "My Requests home section is unavailable")
			emptyList()
		} catch (err: Exception) {
			Timber.e(err, "My Requests home section failed to load")
			emptyList()
		}

		/**
		 * Read only ids from the plugin JSON. Full BaseItemDto decoding of that
		 * payload can fail (dates/enums), which hid the whole row.
		 */
		private suspend fun sectionItemIds(api: ApiClient, userId: UUID): List<UUID> {
			val payload = api.get<JsonObject>(
				pathTemplate = "/HomeScreen/Section/{sectionType}",
				pathParameters = mapOf("sectionType" to SECTION_TYPE),
				queryParameters = mapOf(
					"UserId" to userId,
					"userId" to userId,
				),
			).content

			val items = payload["Items"]?.jsonArray
				?: payload["items"]?.jsonArray
				?: return emptyList()

			val unresolvedParents = items.mapNotNull { element ->
				val obj = element.jsonObject
				val type = obj.str("Type", "type")
				if (type == "Episode" && obj.uuid("SeriesId", "seriesId") == null) {
					obj.uuid("ParentId", "parentId")
				} else {
					null
				}
			}.distinct()

			val parentItems = loadLibraryItems(api, unresolvedParents).associateBy { it.id }

			val seen = mutableSetOf<UUID>()
			val orderedIds = ArrayList<UUID>(items.size)
			for (element in items) {
				val obj = element.jsonObject
				val id = when (obj.str("Type", "type")) {
					"Episode" -> obj.uuid("SeriesId", "seriesId")
						?: obj.uuid("ParentId", "parentId")?.let { parentItems[it]?.seriesId }
					"Season" -> obj.uuid("SeriesId", "seriesId")
					else -> obj.uuid("Id", "id")
				} ?: continue
				if (seen.add(id)) orderedIds.add(id)
			}
			return orderedIds
		}

		private suspend fun loadLibraryItems(api: ApiClient, ids: List<UUID>): List<BaseItemDto> {
			if (ids.isEmpty()) return emptyList()
			val loaded = api.itemsApi.getItems(
				ids = ids,
				fields = ItemRepository.browseFields,
				limit = ids.size,
				enableTotalRecordCount = false,
			).content.items.orEmpty().associateBy { it.id }
			return ids.mapNotNull { loaded[it] }
		}

		private fun JsonObject.str(vararg keys: String): String? {
			for (key in keys) {
				val value = this[key] as? JsonPrimitive ?: continue
				val content = value.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
				return content
			}
			return null
		}

		private fun JsonObject.uuid(vararg keys: String): UUID? = str(*keys)?.toUUIDOrNull()
	}
}
