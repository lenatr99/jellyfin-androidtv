package org.jellyfin.androidtv.ui.search

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.DetailRowView
import org.jellyfin.androidtv.ui.TextUnderButton
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.InfoLayoutHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

/**
 * Details for a Jellyseerr title, using the Jellyfin details page layout, with a clear
 * status line saying whether it still needs to be requested.
 */
class SeerrDetailsFragment : Fragment() {
	companion object {
		const val ARG_TMDB_ID = "TmdbId"
		const val ARG_MEDIA_TYPE = "MediaType"
		const val ARG_TITLE = "Title"
		const val ARG_YEAR = "Year"
		const val ARG_POSTER = "Poster"
		const val ARG_REQUESTED = "Requested"
		const val ARG_LIBRARY_ID = "LibraryId"

		private const val TICKS_PER_MINUTE = 600_000_000L
	}


	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val navigationRepository by inject<NavigationRepository>()
	private val requester by lazy { SeerrRequester(requireContext(), api, lifecycleScope, navigationRepository) }

	private lateinit var item: SeerrRowItem
	private var details: JsonObject? = null

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
		DetailRowView(requireContext())

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val args = requireArguments()
		item = SeerrRowItem(
			tmdbId = args.getInt(ARG_TMDB_ID),
			mediaType = args.getString(ARG_MEDIA_TYPE).orEmpty(),
			title = args.getString(ARG_TITLE).orEmpty(),
			year = args.getString(ARG_YEAR),
			posterUrl = args.getString(ARG_POSTER),
			requested = args.getBoolean(ARG_REQUESTED),
		).apply { libraryId = args.getString(ARG_LIBRARY_ID)?.toUUIDOrNull() }

		val binding = (view as DetailRowView).binding
		binding.fdTitle.text = item.title
		binding.mainImage.load(item.posterUrl, null, null, 2.0 / 3.0, 0)

		lifecycleScope.launch {
			details = SeerrSearch.details(api, item)
			if (details == null && item.libraryId == null) {
				Toast.makeText(requireContext(), R.string.lbl_seerr_request_failed, Toast.LENGTH_SHORT).show()
			}
			render(view)
		}
	}

	private fun render(view: DetailRowView) {
		val binding = view.binding
		val details = details
		val libraryItem = item.libraryId ?: details?.let(SeerrSearch::libraryItemId)
		val isTv = item.mediaType == "tv"

		details?.str("backdropPath")?.let { backgroundService.setBackgroundUrl(SeerrSearch.tmdbImage(it, "w1280")) }
		if (item.posterUrl == null) details?.str("posterPath")?.let {
			binding.mainImage.load(SeerrSearch.tmdbImage(it, "w500"), null, null, 2.0 / 3.0, 0)
		}

		// Same info row as the Jellyfin page (year, rating, runtime), drawn from a stand-in item
		val stub = BaseItemDto(
			id = UUID(0, item.tmdbId.toLong()),
			type = if (isTv) BaseItemKind.SERIES else BaseItemKind.MOVIE,
			name = item.title,
			productionYear = item.year?.toIntOrNull(),
			communityRating = details?.get("voteAverage")?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.takeIf { it > 0 },
			runTimeTicks = details?.get("runtime")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { it * TICKS_PER_MINUTE },
		)
		InfoLayoutHelper.addInfoRow(requireContext(), stub, binding.fdMainInfoRow, true)
		binding.fdGenreRow.text = details?.get("genres")?.jsonArray.orEmpty()
			.mapNotNull { it.jsonObject.str("name") }.joinToString(" / ")

		// The status line makes it clear whether this still has to be requested
		val requested = item.requested || SeerrSearch.wasRequested(item) ||
			(details?.get("mediaInfo")?.jsonObject?.get("status")?.jsonPrimitive?.intOrNull ?: 1) > 1
		binding.infoTitle1.text = getString(R.string.lbl_seerr_status)
		binding.infoValue1.text = getString(
			when {
				libraryItem != null -> R.string.lbl_seerr_in_library
				requested -> R.string.lbl_seerr_requested
				else -> R.string.lbl_seerr_not_in_library
			}
		)
		binding.infoTitle2.text = getString(if (isTv) R.string.lbl_seerr_first_aired else R.string.lbl_seerr_released)
		binding.infoValue2.text = details?.str(if (isTv) "firstAirDate" else "releaseDate")
		binding.infoTitle3.text = getString(if (isTv) R.string.lbl_seerr_network else R.string.lbl_seerr_studio)
		binding.infoValue3.text = details?.get(if (isTv) "networks" else "productionCompanies")?.jsonArray
			?.firstOrNull()?.jsonObject?.str("name")
		binding.fdSummaryText.text = listOfNotNull(details?.str("tagline"), details?.str("overview")).joinToString("\n\n")

		// Buttons, as on the Jellyfin page
		val buttonSize = Utils.convertDpToPixel(requireContext(), 40)
		val seasons = if (isTv && details != null) SeerrSearch.seasons(details).filter { it.requestable } else emptyList()
		val canRequest = if (isTv) seasons.isNotEmpty() else !requested && libraryItem == null
		binding.fdButtonRow.removeAllViews()
		if (libraryItem != null) binding.fdButtonRow.addView(
			TextUnderButton.create(requireContext(), R.drawable.ic_play, buttonSize, 2, getString(R.string.lbl_seerr_open)) {
				navigationRepository.navigate(Destinations.itemDetails(libraryItem))
			}
		)
		if (canRequest) binding.fdButtonRow.addView(
			TextUnderButton.create(
				requireContext(), R.drawable.ic_add, buttonSize, 2,
				getString(if (libraryItem != null) R.string.lbl_seerr_request_more else R.string.lbl_seerr_request),
			) {
				requester.request(item, seasons) { render(view) }
			}
		)
		trailerKey()?.let { key ->
			binding.fdButtonRow.addView(
				TextUnderButton.create(requireContext(), R.drawable.ic_trailer, buttonSize, 0, getString(R.string.lbl_play_trailers)) {
					playTrailer(key)
				}
			)
		}
		binding.fdButtonRow.getChildAt(0)?.requestFocus()
		binding.fdButtonRow.isVisible = binding.fdButtonRow.childCount > 0
	}

	/** A YouTube trailer, preferring a real trailer over teasers. */
	private fun trailerKey(): String? = details?.get("relatedVideos")?.jsonArray.orEmpty()
		.map { it.jsonObject }
		.filter { it.str("site") == "YouTube" && it.str("key") != null }
		.sortedBy { if (it.str("type") == "Trailer") 0 else if (it.str("type") == "Teaser") 1 else 2 }
		.firstOrNull()
		?.str("key")

	/**
	 * Plays the trailer in the app's own player. The server fetches it from YouTube as
	 * H.264 MP4 (media-stack scripts/jellyfin/trailers): this TV's YouTube app and
	 * embedded player can't play it. Back closes it.
	 */
	private fun playTrailer(key: String) {
		val context = requireContext()
		val player = ExoPlayer.Builder(context).build().apply {
			setMediaItem(MediaItem.fromUri("${api.baseUrl}/trailers/$key.mp4?api_key=${api.accessToken}"))
			playWhenReady = true
			prepare()
		}
		val playerView = PlayerView(context).apply {
			this.player = player
			setBackgroundColor(Color.BLACK)
			setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
			useController = true
		}
		player.addListener(object : Player.Listener {
			override fun onPlayerError(error: PlaybackException) {
				Timber.w(error, "Trailer playback failed")
				Toast.makeText(context, R.string.lbl_seerr_trailer_failed, Toast.LENGTH_SHORT).show()
			}
		})

		Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
			setContentView(playerView)
			setOnDismissListener { player.release() }
			show()
		}
		playerView.requestFocus()
	}
}
