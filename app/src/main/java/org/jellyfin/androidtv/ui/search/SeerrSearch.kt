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
	const val MEDIA_TYPE_PERSON = "person"

	// Jellyseerr media status: 1 = unknown (not requested), 5 = available (already in the library)
	private const val STATUS_UNKNOWN = 1
	private const val STATUS_AVAILABLE = 5

	// Jellyseerr request status: 3 = declined
	private const val REQUEST_DECLINED = 3

	// A show role counts in full from this many episodes, so a guest spot ranks below a lead role
	private const val FULL_ROLE_EPISODES = 20

	// TMDB genres: news, reality and talk
	private val UNSCRIPTED_GENRES = setOf(10763, 10764, 10767)

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
			if (obj.str("mediaType") == MEDIA_TYPE_PERSON) return@mapNotNull personItem(obj)
			if (obj["mediaInfo"]?.jsonObject?.get("status")?.jsonPrimitive?.intOrNull == STATUS_AVAILABLE) return@mapNotNull null
			// Already shown in the library results above
			rowItem(obj, library)?.takeIf { it.libraryId == null }
		}
	} catch (err: Exception) {
		Timber.w(err, "Jellyseerr search is unavailable")
		emptyList()
	}

	/** Movies and shows a person acted in, best known first, without chat show and news appearances. */
	suspend fun credits(api: ApiClient, personId: Int): List<SeerrRowItem> = try {
		val library = LibraryTmdbIndex.get(api)
		val payload = api.get<JsonObject>(
			pathTemplate = "/JellyfinEnhanced/jellyseerr/person/{personId}/combined_credits",
			pathParameters = mapOf("personId" to personId),
		).content
		payload["cast"]?.jsonArray.orEmpty()
			.mapNotNull { it as? JsonObject }
			.filterNot { credit ->
				val genres = (credit["genreIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
				genres.any { it in UNSCRIPTED_GENRES } || credit.str("character")?.contains("self", ignoreCase = true) == true
			}
			.sortedByDescending(::creditScore)
			.mapNotNull { rowItem(it, library) }
			.filter { it.posterUrl != null }
			.distinct()
	} catch (err: Exception) {
		Timber.w(err, "Jellyseerr credits unavailable for person $personId")
		emptyList()
	}

	/** Popularity weighed by episodes, the same score as media-stack person-credits.js. */
	private fun creditScore(credit: JsonObject): Double {
		val votes = (credit["voteCount"] as? JsonPrimitive)?.intOrNull ?: 0
		val episodes = (credit["episodeCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return votes.toDouble()
		return votes.toDouble() * minOf(episodes, FULL_ROLE_EPISODES) / FULL_ROLE_EPISODES
	}

	/** An actor card, which opens their filmography. People without a photo are rarely who was searched for. */
	private fun personItem(obj: JsonObject): SeerrRowItem? = SeerrRowItem(
		tmdbId = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return null,
		mediaType = MEDIA_TYPE_PERSON,
		title = obj.str("name") ?: return null,
		year = null,
		posterUrl = tmdbImage(obj.str("profilePath") ?: return null, "w185"),
		requested = false,
	)

	private fun rowItem(obj: JsonObject, library: Map<String, UUID>): SeerrRowItem? {
		val mediaType = obj.str("mediaType")?.takeIf { it == "movie" || it == "tv" } ?: return null
		val tmdbId = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return null
		return SeerrRowItem(
			tmdbId = tmdbId,
			mediaType = mediaType,
			title = obj.str("title") ?: obj.str("name") ?: return null,
			year = (obj.str("releaseDate") ?: obj.str("firstAirDate") ?: obj.str("first_air_date"))?.take(4),
			posterUrl = (obj.str("posterPath") ?: obj.str("poster_path"))?.let { "$POSTER_BASE$it" },
			requested = ((obj["mediaInfo"] as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull != null,
			libraryId = library["$mediaType:$tmdbId"],
		)
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

	/** Rotten Tomatoes and IMDb scores; many titles have none, so a failed lookup is expected. */
	suspend fun ratings(api: ApiClient, item: SeerrRowItem): JsonObject? = try {
		api.get<JsonObject>(
			pathTemplate = "/JellyfinEnhanced/jellyseerr/{type}/{tmdbId}/${if (item.mediaType == "tv") "ratings" else "ratingscombined"}",
			pathParameters = mapOf("type" to item.mediaType, "tmdbId" to item.tmdbId),
		).content
	} catch (err: Exception) {
		Timber.i(err, "Jellyseerr ratings unavailable for ${item.title}")
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
		if (item.mediaType == SeerrSearch.MEDIA_TYPE_PERSON) {
			navigationRepository.navigate(Destinations.seerrPerson(item.tmdbId, item.title))
			return
		}
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
