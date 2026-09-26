package org.jellyfin.androidtv.ui.search

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

/**
 * A title from Jellyseerr that is not in the library yet, found through the
 * Jellyfin Enhanced plugin (the same search the web client shows).
 */
class SeerrRowItem(
	val tmdbId: Int,
	val mediaType: String,
	val title: String,
	val year: String?,
	val posterUrl: String?,
	var requested: Boolean,
	/** Set when the title is already in the Jellyfin library. */
	var libraryId: UUID? = null,
) : BaseRowItem(baseRowType = BaseRowType.GridButton) {
	override fun getCardName(context: Context) = title
	override fun getFullName(context: Context) = title
	override fun getName(context: Context) = title
	override fun getSubText(context: Context) = when {
		libraryId != null -> context.getString(R.string.lbl_seerr_in_library)
		requested || SeerrSearch.wasRequested(this) -> context.getString(R.string.lbl_seerr_requested)
		else -> year
	}

	override fun equals(other: Any?) = other is SeerrRowItem && other.tmdbId == tmdbId && other.mediaType == mediaType
	override fun hashCode() = tmdbId
}

/**
 * TMDB ids of the movies and shows in the library. Jellyseerr's availability sync misses
 * some titles (it can't match every show), so check the library itself.
 */
object LibraryTmdbIndex {
	private const val MAX_AGE_MS = 30 * 60 * 1000L
	private val mutex = Mutex()
	private var loadedAt = 0L
	private var index: Map<String, UUID> = emptyMap()

	/** Keys are "movie:<tmdb id>" and "tv:<tmdb id>", like Jellyseerr's media types. */
	suspend fun get(api: ApiClient): Map<String, UUID> = mutex.withLock {
		if (System.currentTimeMillis() - loadedAt < MAX_AGE_MS) return@withLock index
		try {
			val payload = api.get<JsonObject>(
				pathTemplate = "/Items",
				queryParameters = mapOf(
					"recursive" to true,
					"includeItemTypes" to "Movie,Series",
					"fields" to "ProviderIds",
					// Movies inside collections would otherwise be hidden behind the collection (as in media-stack similar.js)
					"collapseBoxSetItems" to false,
					"enableImages" to false,
					"enableUserData" to false,
					"enableTotalRecordCount" to false,
				),
			).content
			index = payload["Items"]?.jsonArray.orEmpty().mapNotNull { element ->
				val obj = element.jsonObject
				val tmdb = obj["ProviderIds"]?.jsonObject?.str("Tmdb") ?: return@mapNotNull null
				val type = if (obj.str("Type") == "Series") "tv" else "movie"
				val id = obj.str("Id")?.toUUIDOrNull() ?: return@mapNotNull null
				"$type:$tmdb" to id
			}.toMap()
			loadedAt = System.currentTimeMillis()
		} catch (err: Exception) {
			Timber.w(err, "Library TMDB index unavailable")
		}
		index
	}
}

data class SeerrSeason(val number: Int, val name: String, val requestable: Boolean)

object SeerrSearch {
	// Jellyseerr media status: 1 = unknown (not requested), 5 = available (already in the library)
	private const val STATUS_UNKNOWN = 1
	private const val STATUS_AVAILABLE = 5

	// Jellyseerr request status: 3 = declined
	private const val REQUEST_DECLINED = 3

	// Jellyseerr's image proxy: TMDB's own CDN certificate isn't trusted by older TVs
	private const val TMDB_IMAGE_PROXY = "https://jellyseerr.lenalab.si/imageproxy/tmdb"
	private const val POSTER_BASE = "$TMDB_IMAGE_PROXY/t/p/w300"

	fun tmdbImage(path: String, size: String) = "$TMDB_IMAGE_PROXY/t/p/$size$path"

	/** Rewrites a TMDB image URL to go through the Jellyseerr proxy. */
	fun proxiedPoster(url: String) = url.replace("https://image.tmdb.org", TMDB_IMAGE_PROXY)

	suspend fun search(api: ApiClient, query: String): List<SeerrRowItem> = try {
		val library = LibraryTmdbIndex.get(api)
		val payload = api.get<JsonObject>(
			pathTemplate = "/JellyfinEnhanced/jellyseerr/search",
			queryParameters = mapOf("query" to query),
		).content
		payload["results"]?.jsonArray.orEmpty().mapNotNull { element ->
			val obj = element.jsonObject
			val mediaType = obj.str("mediaType")?.takeIf { it == "movie" || it == "tv" } ?: return@mapNotNull null
			val status = obj["mediaInfo"]?.jsonObject?.get("status")?.jsonPrimitive?.intOrNull
			if (status == STATUS_AVAILABLE) return@mapNotNull null
			val tmdbId = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
			// Already shown in the library results above
			if ("$mediaType:$tmdbId" in library) return@mapNotNull null
			SeerrRowItem(
				tmdbId = tmdbId,
				mediaType = mediaType,
				title = obj.str("title") ?: obj.str("name") ?: return@mapNotNull null,
				year = (obj.str("releaseDate") ?: obj.str("firstAirDate") ?: obj.str("first_air_date"))?.take(4),
				posterUrl = (obj.str("posterPath") ?: obj.str("poster_path"))?.let { "$POSTER_BASE$it" },
				requested = status != null,
			)
		}
	} catch (err: Exception) {
		Timber.w(err, "Jellyseerr search is unavailable")
		emptyList()
	}

	/** Jellyseerr details for a movie or show, including its library and request status. */
	suspend fun details(api: ApiClient, item: SeerrRowItem): JsonObject? = try {
		api.get<JsonObject>(
			pathTemplate = "/JellyfinEnhanced/jellyseerr/{type}/{tmdbId}",
			pathParameters = mapOf("type" to item.mediaType, "tmdbId" to item.tmdbId),
		).content
	} catch (err: Exception) {
		Timber.e(err, "Jellyseerr details unavailable for ${item.title}")
		null
	}

	/** The library item when this title is fully available, so it can be opened instead of requested. */
	fun libraryItemId(details: JsonObject): UUID? {
		val mediaInfo = details["mediaInfo"]?.jsonObject ?: return null
		if (mediaInfo["status"]?.jsonPrimitive?.intOrNull != STATUS_AVAILABLE) return null
		return mediaInfo.str("jellyfinMediaId")?.toUUIDOrNull()
	}

	/** Seasons of a show, skipping specials; already available or requested seasons are not requestable. */
	fun seasons(payload: JsonObject): List<SeerrSeason> {
		val mediaInfo = payload["mediaInfo"]?.jsonObject
		val available = mediaInfo?.get("seasons")?.jsonArray.orEmpty()
			.map { it.jsonObject }
			.filter { (it["status"]?.jsonPrimitive?.intOrNull ?: STATUS_UNKNOWN) > STATUS_UNKNOWN }
		val requested = mediaInfo?.get("requests")?.jsonArray.orEmpty()
			.map { it.jsonObject }
			.filter { it["status"]?.jsonPrimitive?.intOrNull != REQUEST_DECLINED }
			.flatMap { it["seasons"]?.jsonArray.orEmpty().map { season -> season.jsonObject } }
		val taken = (available + requested).mapNotNull { it["seasonNumber"]?.jsonPrimitive?.intOrNull }.toSet()
		return payload["seasons"]?.jsonArray.orEmpty().mapNotNull { element ->
			val obj = element.jsonObject
			val number = obj["seasonNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
			SeerrSeason(number, obj.str("name") ?: "Season $number", number !in taken)
		}
	}

	// Requests made in this session, so cards show them after returning from the details page
	private val requestedKeys = mutableSetOf<String>()

	fun markRequested(item: SeerrRowItem) {
		requestedKeys += "${item.mediaType}:${item.tmdbId}"
	}

	fun wasRequested(item: SeerrRowItem) = "${item.mediaType}:${item.tmdbId}" in requestedKeys

	suspend fun request(api: ApiClient, item: SeerrRowItem, seasons: List<Int>? = null): Boolean = try {
		api.post<JsonObject>(
			pathTemplate = "/JellyfinEnhanced/jellyseerr/request",
			requestBody = buildJsonObject {
				put("mediaType", item.mediaType)
				put("mediaId", item.tmdbId)
				if (item.mediaType == "tv") {
					if (seasons == null) put("seasons", "all")
					else put("seasons", JsonArray(seasons.map(::JsonPrimitive)))
				}
			},
		)
		true
	} catch (err: Exception) {
		Timber.e(err, "Jellyseerr request failed for ${item.title}")
		false
	}
}

/**
 * Opens a Jellyseerr card: owned movies go to the library, everything else to the
 * Jellyseerr details page, which looks like the Jellyfin one.
 */
class SeerrRequester(
	private val context: Context,
	private val api: ApiClient,
	private val scope: CoroutineScope,
	private val navigationRepository: NavigationRepository,
) {
	fun open(item: SeerrRowItem) {
		val owned = item.libraryId
		if (owned != null && item.mediaType != "tv") navigationRepository.navigate(Destinations.itemDetails(owned))
		else navigationRepository.navigate(Destinations.seerrDetails(item))
	}

	/** Movies are requested directly; shows ask which seasons first. */
	fun request(item: SeerrRowItem, seasons: List<SeerrSeason>, onRequested: () -> Unit) {
		if (item.mediaType != "tv") {
			send(item, null, onRequested)
			return
		}
		val checked = BooleanArray(seasons.size)
		AlertDialog.Builder(context)
			.setTitle(item.title)
			.setMultiChoiceItems(seasons.map { it.name }.toTypedArray(), checked) { _, index, isChecked -> checked[index] = isChecked }
			.setPositiveButton(R.string.lbl_seerr_request) { _, _ ->
				val selected = seasons.filterIndexed { index, _ -> checked[index] }.map { it.number }
				if (selected.isNotEmpty()) send(item, selected, onRequested)
			}
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}

	private fun send(item: SeerrRowItem, seasons: List<Int>?, onRequested: () -> Unit) = scope.launch {
		val ok = SeerrSearch.request(api, item, seasons)
		if (ok) {
			item.requested = true
			SeerrSearch.markRequested(item)
			onRequested()
		}
		Toast.makeText(context, if (ok) R.string.lbl_seerr_requested else R.string.lbl_seerr_request_failed, Toast.LENGTH_SHORT).show()
	}
}

internal fun JsonObject.str(key: String) =
	(this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
