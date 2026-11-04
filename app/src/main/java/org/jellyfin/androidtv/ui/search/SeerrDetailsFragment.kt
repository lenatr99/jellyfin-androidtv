package org.jellyfin.androidtv.ui.search

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.databinding.FragmentSeerrDetailsBinding
import org.jellyfin.androidtv.databinding.ItemSeerrCastBinding
import org.jellyfin.androidtv.databinding.ItemSeerrFactBinding
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
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

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
		private const val MAX_CAST = 12
		private const val MAX_CREW = 3
		private const val FOCUS_SCALE = 1.08f
		private const val FOCUS_ANIMATION_MS = 120L
		private const val FRESH_SCORE = 60
		private const val RATING_TAG = "seerr-rating"
	}

	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val navigationRepository by inject<NavigationRepository>()
	private val requester by lazy { SeerrRequester(requireContext(), api, lifecycleScope, navigationRepository) }

	private lateinit var item: SeerrRowItem
	private var details: JsonObject? = null
	private var ratings: JsonObject? = null
	private var page: FragmentSeerrDetailsBinding? = null

	// Cast sits below the first screen: show that whole section, and go back to the top for the buttons
	private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
		val page = page ?: return@OnGlobalFocusChangeListener
		if (focused == null) return@OnGlobalFocusChangeListener
		page.seerrScroll.smoothScrollTo(0, if (focused.parent == page.seerrCastRow) page.seerrMore.top else 0)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val binding = FragmentSeerrDetailsBinding.inflate(inflater, container, false)
		page = binding
		return binding.root
	}

	override fun onDestroyView() {
		view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusListener)
		page = null
		super.onDestroyView()
	}

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

		view.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
		val hero = page?.seerrHero ?: return
		val binding = hero.binding
		binding.fdTitle.text = item.title
		binding.mainImage.load(item.posterUrl, null, null, 2.0 / 3.0, 0)

		viewLifecycleOwner.lifecycleScope.launch {
			details = SeerrSearch.details(api, item)
			if (details == null && item.libraryId == null) {
				Toast.makeText(requireContext(), R.string.lbl_seerr_request_failed, Toast.LENGTH_SHORT).show()
			}
			render(hero)
			renderMore()
			showRatings()
		}
		// Separate from the details, so a slow ratings lookup never holds up the page
		viewLifecycleOwner.lifecycleScope.launch {
			ratings = SeerrSearch.ratings(api, item)
			showRatings()
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
		val creators = if (isTv) details?.names("createdBy") else crew("Director")
		val secondCredit = if (isTv) details?.names("networks") else crew("Writer", "Screenplay", "Story")
		binding.infoTitle2.text = getString(if (isTv) R.string.lbl_seerr_created_by else R.string.lbl_seerr_director)
		binding.infoValue2.text = creators
		binding.infoTitle2.isVisible = creators != null
		binding.infoValue2.isVisible = creators != null
		binding.infoTitle3.text = getString(if (isTv) R.string.lbl_seerr_network else R.string.lbl_seerr_writers)
		binding.infoValue3.text = secondCredit
		binding.infoTitle3.isVisible = secondCredit != null
		binding.infoValue3.isVisible = secondCredit != null
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

	/** The cast row and facts panel below the first screen. */
	private fun renderMore() {
		val page = page ?: return
		val details = details ?: return
		val isTv = item.mediaType == "tv"
		val placeholder = ContextCompat.getDrawable(requireContext(), R.drawable.tile_port_person)

		val cast = (details["credits"] as? JsonObject)?.objects("cast").orEmpty().take(MAX_CAST)
		page.seerrCastRow.removeAllViews()
		cast.forEach { person ->
			val card = ItemSeerrCastBinding.inflate(layoutInflater, page.seerrCastRow, true)
			card.castName.text = person.str("name")
			card.castRole.text = person.str("character")
			card.castImage.clipToOutline = true
			card.castImage.load(person.str("profilePath")?.let { SeerrSearch.tmdbImage(it, "w185") }, null, placeholder)
			val personId = (person["id"] as? JsonPrimitive)?.intOrNull
			val name = person.str("name")
			if (personId != null && name != null) card.root.setOnClickListener {
				navigationRepository.navigate(Destinations.seerrPerson(personId, name))
			}
			card.root.setOnFocusChangeListener { view, hasFocus ->
				val scale = if (hasFocus) FOCUS_SCALE else 1f
				view.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIMATION_MS)
				card.castName.alpha = if (hasFocus) 1f else .8f
			}
		}
		page.seerrCastSection.isVisible = cast.isNotEmpty()

		val facts = buildList {
			add(R.string.lbl_seerr_release_status to details.str("status"))
			if (isTv) {
				add(R.string.lbl_seerr_first_aired to formatDate(details.str("firstAirDate")))
				add(R.string.lbl_seerr_last_aired to formatDate(details.str("lastAirDate")))
				add(R.string.lbl_seasons to details.number("numberOfSeasons")?.roundToInt()?.toString())
				add(R.string.lbl_episodes to details.number("numberOfEpisodes")?.roundToInt()?.toString())
			} else {
				add(R.string.lbl_seerr_released to formatDate(details.str("releaseDate")))
				add(R.string.lbl_seerr_revenue to formatMoney(details.number("revenue")))
				add(R.string.lbl_seerr_budget to formatMoney(details.number("budget")))
			}
			add(R.string.lbl_seerr_original_language to details.str("originalLanguage")?.let {
				Locale.forLanguageTag(it).displayLanguage.ifBlank { null }
			})
			add(R.string.lbl_seerr_production_country to details.names("productionCountries"))
			if (!isTv) add(R.string.lbl_seerr_studio to details.names("productionCompanies"))
		}.filter { (_, value) -> value != null }
		page.seerrFacts.removeAllViews()
		facts.forEach { (label, value) ->
			val row = ItemSeerrFactBinding.inflate(layoutInflater, page.seerrFacts, true)
			row.factLabel.setText(label)
			row.factValue.text = value
		}
		page.seerrFacts.isVisible = facts.isNotEmpty()
		page.seerrMore.isVisible = cast.isNotEmpty() || facts.isNotEmpty()
	}

	/** Adds the scores to the info row; safe to repeat, as the details and ratings arrive separately. */
	private fun showRatings() {
		val row = page?.seerrHero?.binding?.fdMainInfoRow ?: return
		for (index in row.childCount - 1 downTo 0) {
			if (row.getChildAt(index).tag == RATING_TAG) row.removeViewAt(index)
		}

		// Movies nest Rotten Tomatoes under "rt", shows return it directly
		val rottenTomatoes = ratings?.let { (it["rt"] as? JsonObject) ?: it }
		rottenTomatoes?.number("criticsScore")?.roundToInt()?.let { score ->
			val fresh = rottenTomatoes.str("criticsRating")?.contains("fresh", ignoreCase = true) ?: (score >= FRESH_SCORE)
			addRating(row, "$score%", if (fresh) R.drawable.ic_rt_fresh else R.drawable.ic_rt_rotten)
		}
		rottenTomatoes?.number("audienceScore")?.roundToInt()?.let { score ->
			addRating(row, getString(R.string.lbl_seerr_audience_score, score))
		}
		(ratings?.get("imdb") as? JsonObject)?.number("criticsScore")?.takeIf { it > 0 }?.let { score ->
			addRating(row, "IMDb ${String.format(Locale.getDefault(), "%.1f", score)}")
		}
		details?.number("voteAverage")?.takeIf { it > 0 }?.let { score ->
			addRating(row, "TMDB ${(score * 10).roundToInt()}%")
		}
	}

	private fun addRating(row: LinearLayout, text: String, icon: Int? = null) {
		val context = row.context
		row.addView(TextView(context).apply {
			tag = RATING_TAG
			this.text = text
			gravity = Gravity.CENTER_VERTICAL
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			icon?.let { ContextCompat.getDrawable(context, it) }?.let { drawable ->
				val size = Utils.convertDpToPixel(context, 16)
				drawable.setBounds(0, 0, size, size)
				setCompoundDrawablesRelative(drawable, null, null, null)
				compoundDrawablePadding = Utils.convertDpToPixel(context, 4)
			}
		}, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
			marginStart = Utils.convertDpToPixel(context, 16)
		})
	}

	private fun crew(vararg jobs: String) = (details?.get("credits") as? JsonObject)?.objects("crew").orEmpty()
		.filter { it.str("job") in jobs }
		.mapNotNull { it.str("name") }
		.distinct()
		.take(MAX_CREW)
		.joinToString(", ")
		.ifEmpty { null }

	private fun formatDate(date: String?) = date
		?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
		?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))

	private fun formatMoney(amount: Double?) = amount?.takeIf { it > 0 }?.let {
		NumberFormat.getCurrencyInstance(Locale.US).apply { maximumFractionDigits = 0 }.format(it)
	}

	private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

	private fun JsonObject.names(key: String) = objects(key).mapNotNull { it.str("name") }.joinToString(", ").ifEmpty { null }

	private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

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
