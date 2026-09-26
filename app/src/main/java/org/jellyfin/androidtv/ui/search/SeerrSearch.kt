package org.jellyfin.androidtv.ui.search

import android.content.Context
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
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post
import timber.log.Timber

/**
 * A title from Jellyseerr that is not in the library yet, found through the
 * Jellyfin Enhanced plugin (the same search the web client shows).
 */
class SeerrRowItem(
	val tmdbId: Int,
	val mediaType: String,
	val title: String,
	private val year: String?,
	val posterUrl: String?,
	var requested: Boolean,
) : BaseRowItem(baseRowType = BaseRowType.GridButton) {
	override fun getCardName(context: Context) = title
	override fun getFullName(context: Context) = title
	override fun getName(context: Context) = title
	override fun getSubText(context: Context) =
		if (requested) context.getString(R.string.lbl_seerr_requested) else year

	override fun equals(other: Any?) = other is SeerrRowItem && other.tmdbId == tmdbId && other.mediaType == mediaType
	override fun hashCode() = tmdbId
}

object SeerrSearch {
	// Jellyseerr media status: 5 = available (already in the library)
	private const val STATUS_AVAILABLE = 5

	suspend fun search(api: ApiClient, query: String): List<SeerrRowItem> = try {
		val payload = api.get<JsonObject>(
			pathTemplate = "/JellyfinEnhanced/jellyseerr/search",
			queryParameters = mapOf("query" to query),
		).content
		payload["results"]?.jsonArray.orEmpty().mapNotNull { element ->
			val obj = element.jsonObject
			val mediaType = obj.str("mediaType")?.takeIf { it == "movie" || it == "tv" } ?: return@mapNotNull null
			val status = obj["mediaInfo"]?.jsonObject?.get("status")?.jsonPrimitive?.intOrNull
			if (status == STATUS_AVAILABLE) return@mapNotNull null
			SeerrRowItem(
				tmdbId = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
				mediaType = mediaType,
				title = obj.str("title") ?: obj.str("name") ?: return@mapNotNull null,
				year = (obj.str("releaseDate") ?: obj.str("firstAirDate"))?.take(4),
				posterUrl = obj.str("posterPath")?.let { "https://image.tmdb.org/t/p/w300$it" },
				requested = status != null,
			)
		}
	} catch (err: Exception) {
		Timber.w(err, "Jellyseerr search is unavailable")
		emptyList()
	}

	suspend fun request(api: ApiClient, item: SeerrRowItem): Boolean = try {
		api.post<JsonObject>(
			pathTemplate = "/JellyfinEnhanced/jellyseerr/request",
			requestBody = buildJsonObject {
				put("mediaType", item.mediaType)
				put("mediaId", item.tmdbId)
				if (item.mediaType == "tv") put("seasons", "all")
			},
		)
		true
	} catch (err: Exception) {
		Timber.e(err, "Jellyseerr request failed for ${item.title}")
		false
	}

	private fun JsonObject.str(key: String) =
		(this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
