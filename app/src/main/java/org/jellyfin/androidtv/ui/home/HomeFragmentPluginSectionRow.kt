package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.core.text.HtmlCompat
import androidx.leanback.widget.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.search.LibraryTmdbIndex
import org.jellyfin.androidtv.ui.search.SeerrRowItem
import org.jellyfin.androidtv.ui.search.SeerrSearch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.net.URL
import java.util.UUID

/**
 * A row from the server's Home Screen Sections plugin (the sections shown on web).
 */
class HomeFragmentPluginSectionRow(
	private val title: String,
	private val items: List<BaseItemDto>,
	private val seerrItems: List<SeerrRowItem> = emptyList(),
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		// Chart rows include titles not in the library, shown as Jellyseerr cards
		if (seerrItems.isNotEmpty()) {
			val adapter = MutableObjectAdapter<SeerrRowItem>(cardPresenter)
			seerrItems.forEach(adapter::add)
			rowsAdapter.add(ListRow(HeaderItem(title), adapter))
			return
		}
		if (items.isEmpty()) return

		val rowAdapter = ItemRowAdapter(context, items, cardPresenter, rowsAdapter, true)
		val row = ListRow(HeaderItem(title), rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.Retrieve()
		rowsAdapter.add(row)
	}

	data class Section(val type: String, val title: String, val additionalData: String?)

	companion object {
		/**
		 * Shown natively (Continue Watching) or not library items (Discover, Upcoming),
		 * which the card presenter can't display.
		 */
		private val SKIPPED = setOf(
			"ContinueWatching", "ContinueWatchingNextUp", "NextUp",
			"Discover", "DiscoverMovies", "DiscoverTV",
			"UpcomingMovies", "UpcomingShows", "UpcomingMusic", "UpcomingBooks",
			"MyMedia", "LiveTV",
		)

		/** The user's enabled sections in web order, or empty when the plugin is unavailable. */
		suspend fun sections(api: ApiClient, userId: UUID): List<Section> = try {
			val payload = api.get<JsonObject>(
				pathTemplate = "/HomeScreen/Sections",
				queryParameters = mapOf("userId" to userId),
			).content
			payload["Items"]?.jsonArray.orEmpty()
				.mapNotNull { element ->
					val obj = element.jsonObject
					val type = obj.str("Section") ?: return@mapNotNull null
					Section(type, obj.str("DisplayText") ?: type, obj.str("AdditionalData"))
				}
				.filter { it.type !in SKIPPED }
				.distinctBy { it.type to it.additionalData }
		} catch (err: Exception) {
			Timber.w(err, "Home Screen Sections plugin is unavailable")
			emptyList()
		}

		private const val RECENT_SHOWS = "RecentlyAddedShows"
		private const val RECENT_SHOWS_LIMIT = 16
		private const val RECENT_SHOWS_PAGE = 50
		private const val STAGING_PATH = "/staging-ready/"

		suspend fun load(api: ApiClient, userId: UUID, section: Section, charts: Map<String, List<SeerrRowItem>>) =
			charts[section.type]?.let { HomeFragmentPluginSectionRow(section.title, emptyList(), it) }
				?: HomeFragmentPluginSectionRow(section.title, fetch(api, userId, section))

		/**
		 * The US popularity charts, including titles missing from the library. The server
		 * publishes them only inside a web script (media-stack scripts/jellyfin/us_charts.py),
		 * so read its embedded JSON.
		 */
		suspend fun charts(api: ApiClient): Map<String, List<SeerrRowItem>> = try {
			val library = LibraryTmdbIndex.get(api)
			val script = withContext(Dispatchers.IO) { URL("${api.baseUrl}/JavaScriptInjector/public.js").readText() }
			val start = script.indexOf(CHARTS_MARKER).takeIf { it >= 0 }?.plus(CHARTS_MARKER.length)
			val end = start?.let { script.indexOf(";\n", it) }
			if (start == null || end == null || end < 0) emptyMap()
			else Json.parseToJsonElement(script.substring(start, end)).jsonObject.mapValues { (_, row) ->
				row.jsonObject["Items"]?.jsonArray.orEmpty().mapNotNull { element ->
					val obj = element.jsonObject
					val ids = obj["ProviderIds"]?.jsonObject
					SeerrRowItem(
						tmdbId = ids?.str("Jellyseerr")?.toIntOrNull() ?: return@mapNotNull null,
						mediaType = obj.str("SourceType") ?: return@mapNotNull null,
						title = HtmlCompat.fromHtml(obj.str("Name") ?: return@mapNotNull null, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
						year = obj.str("PremiereDate")?.take(4),
						posterUrl = ids.str("JellyseerrPoster")?.let(SeerrSearch::proxiedPoster),
						requested = false,
					).apply { libraryId = library["$mediaType:$tmdbId"] }
				}
			}
		} catch (err: Exception) {
			Timber.w(err, "US popularity charts are unavailable")
			emptyMap()
		}

		private const val CHARTS_MARKER = "const charts = "

		suspend fun fetch(api: ApiClient, userId: UUID, section: Section): List<BaseItemDto> = try {
			if (section.type == RECENT_SHOWS) recentShows(api, userId)
			else loadLibraryItems(api, sectionItemIds(api, userId, section))
		} catch (err: ApiClientException) {
			Timber.e(err, "Home section ${section.type} is unavailable")
			emptyList()
		} catch (err: Exception) {
			Timber.e(err, "Home section ${section.type} failed to load")
			emptyList()
		}

		/**
		 * Read only ids from the plugin JSON. Full BaseItemDto decoding of that
		 * payload can fail (dates/enums), which hid the whole row.
		 */
		private suspend fun sectionItemIds(api: ApiClient, userId: UUID, section: Section): List<UUID> {
			val payload = api.get<JsonObject>(
				pathTemplate = "/HomeScreen/Section/{sectionType}",
				pathParameters = mapOf("sectionType" to section.type),
				queryParameters = buildMap {
					put("UserId", userId)
					put("userId", userId)
					section.additionalData?.let { put("AdditionalData", it) }
				},
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

		/**
		 * The plugin lists every physical series folder, so shows still in the staging
		 * preview appear once per episode. Group them by provider id, like the web script
		 * (media-stack scripts/jellyfin/web/recent.js), preferring the permanent entry.
		 */
		private suspend fun recentShows(api: ApiClient, userId: UUID): List<BaseItemDto> {
			val groups = LinkedHashMap<String, BaseItemDto>()
			var startIndex = 0
			// Page until there are enough distinct shows; a large staged season can fill whole pages
			while (true) {
				val page = api.itemsApi.getItems(
					userId = userId,
					includeItemTypes = setOf(BaseItemKind.SERIES),
					recursive = true,
					isMissing = false,
					sortBy = setOf(ItemSortBy.DATE_LAST_CONTENT_ADDED),
					sortOrder = setOf(SortOrder.DESCENDING),
					fields = ItemRepository.browseFields + ItemFields.PROVIDER_IDS + ItemFields.PATH,
					startIndex = startIndex,
					limit = RECENT_SHOWS_PAGE,
					enableTotalRecordCount = false,
				).content.items.orEmpty()

				for (show in page) {
					val ids = show.providerIds.orEmpty()
					val key = listOf("Tvdb", "Tmdb", "Imdb").firstNotNullOfOrNull { provider ->
						ids[provider]?.takeIf { it.isNotBlank() }?.let { "$provider:${it.lowercase()}" }
					} ?: "item:${show.id}"
					val existing = groups[key]
					if (existing == null) {
						// Stop at the limit, but keep scanning this page for permanent entries of kept shows
						if (groups.size < RECENT_SHOWS_LIMIT) groups[key] = show
					} else if (existing.path.orEmpty().startsWith(STAGING_PATH) && show.path?.startsWith(STAGING_PATH) == false) {
						groups[key] = show
					}
				}

				if (groups.size >= RECENT_SHOWS_LIMIT || page.size < RECENT_SHOWS_PAGE) break
				startIndex += page.size
			}
			return groups.values.toList()
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
