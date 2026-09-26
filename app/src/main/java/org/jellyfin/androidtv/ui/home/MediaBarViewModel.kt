package org.jellyfin.androidtv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.sdk.compat.copyWithUserData
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class MediaBarItem(
	val item: BaseItemDto,
	val backdropUrl: String?,
	val backdropBlurHash: String?,
	val posterUrl: String?,
	val logoUrl: String?,
	val logoBlurHash: String?,
	val logoAspectRatio: Float?,
)

data class MediaBarTrailer(
	val url: String,
	val startMs: Long = 0L,
)

class MediaBarViewModel(
	private val api: ApiClient,
	private val itemMutationRepository: ItemMutationRepository,
) : ViewModel() {
	private val _items = MutableStateFlow<List<MediaBarItem>>(emptyList())
	val items = _items.asStateFlow()

	private val _index = MutableStateFlow(0)
	val index = _index.asStateFlow()

	private val _loaded = MutableStateFlow(false)
	val loaded = _loaded.asStateFlow()

	private val _trailer = MutableStateFlow<MediaBarTrailer?>(null)
	val trailer = _trailer.asStateFlow()

	private val _playbackAllowed = MutableStateFlow(true)
	val playbackAllowed = _playbackAllowed.asStateFlow()

	private var autoAdvancePaused = false
	private var rotateJob: Job? = null
	private val failedTrailers = mutableSetOf<UUID>()
	private val resolveMutex = Mutex()
	private val trailerResolves = mutableMapOf<UUID, Deferred<MediaBarTrailer?>>()

	val currentItem = combine(_items, _index) { items, index ->
		items.getOrNull(index)
	}.stateIn(viewModelScope, SharingStarted.Eagerly, null)

	init {
		viewModelScope.launch { load() }
		viewModelScope.launch {
			currentItem.collectLatest { item ->
				if (item == null) {
					_trailer.value = null
					return@collectLatest
				}
				val cached = resolveMutex.withLock { trailerResolves[item.item.id] }
				if (cached?.isCompleted == true) {
					_trailer.value = cached.await()
				}
				_trailer.value = trailerFor(item.item)
			}
		}
		startRotateTimer()
	}

	private var lastSkipAt = 0L

	fun next() {
		if (!trySkip()) return
		advance(1)
		startRotateTimer()
	}

	fun previous() {
		if (!trySkip()) return
		advance(-1)
		startRotateTimer()
	}

	private fun trySkip(): Boolean {
		val now = android.os.SystemClock.elapsedRealtime()
		if (now - lastSkipAt < 450L) return false
		lastSkipAt = now
		return true
	}

	fun setAutoAdvancePaused(paused: Boolean) {
		autoAdvancePaused = paused
	}

	fun setPlaybackAllowed(allowed: Boolean) {
		_playbackAllowed.value = allowed
	}

	fun toggleFavorite() {
		val current = currentItem.value ?: return
		viewModelScope.launch {
			val favorite = current.item.userData?.isFavorite != true
			runCatching {
				itemMutationRepository.setFavorite(current.item.id, favorite)
			}.onSuccess { userData ->
				_items.update { items ->
					items.map { slide ->
						if (slide.item.id == current.item.id) {
							slide.copy(item = slide.item.copyWithUserData(userData))
						} else {
							slide
						}
					}
				}
			}.onFailure { err ->
				Timber.w(err, "Failed to toggle media bar favorite for %s", current.item.name)
			}
		}
	}

	fun onTrailerFailed() {
		val id = currentItem.value?.item?.id ?: return
		Timber.w("Media bar video failed for %s", currentItem.value?.item?.name)
		failedTrailers += id
		viewModelScope.launch {
			resolveMutex.withLock { trailerResolves.remove(id) }
		}
		_trailer.value = null
	}

	private fun advance(delta: Int) {
		val items = _items.value
		if (items.isEmpty()) return
		val size = items.size
		_index.value = ((_index.value + delta) % size + size) % size
	}

	private fun startRotateTimer() {
		rotateJob?.cancel()
		rotateJob = viewModelScope.launch {
			while (isActive) {
				delay(ROTATE_INTERVAL)
				if (!autoAdvancePaused && _playbackAllowed.value) advance(1)
			}
		}
	}

	private suspend fun load() {
		val response = try {
			withContext(Dispatchers.IO) {
				api.itemsApi.getItems(
					includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
					recursive = true,
					sortBy = listOf(ItemSortBy.RANDOM),
					filters = setOf(ItemFilter.IS_UNPLAYED),
					imageTypes = listOf(ImageType.BACKDROP, ImageType.PRIMARY, ImageType.LOGO),
					limit = POOL_FETCH_LIMIT,
					fields = ItemRepository.browseFields,
					enableTotalRecordCount = false,
				).content
			}
		} catch (err: ApiClientException) {
			Timber.e(err, "Failed to load media bar items")
			_loaded.value = true
			return
		}

		val mapped = response.items.mapNotNull { it.toMediaBarItem() }
		val movies = mapped.filter { it.item.type == BaseItemKind.MOVIE }
		val series = mapped.filter { it.item.type != BaseItemKind.MOVIE }
		fun byLogo(items: List<MediaBarItem>) =
			items.filter { it.logoUrl != null } + items.filter { it.logoUrl == null }
		_items.value = (byLogo(movies) + byLogo(series)).take(POOL_SIZE)
		_index.value = 0
		_loaded.value = true
		prefetchTrailers()
	}

	private fun prefetchTrailers() {
		viewModelScope.launch {
			val items = _items.value
			if (items.isEmpty()) return@launch
			val start = _index.value
			val upcoming = (1 until items.size).map { items[(start + it) % items.size] }
			// Later slides resolve their own trailer when shown
			upcoming.take(PREFETCH_AHEAD).forEach { trailerFor(it.item) }
		}
	}

	private suspend fun trailerFor(item: BaseItemDto): MediaBarTrailer? {
		if (item.id in failedTrailers) return null
		val deferred = resolveMutex.withLock {
			trailerResolves[item.id] ?: viewModelScope.async(Dispatchers.IO) {
				val resolved = runCatching { resolveTrailer(item) }
					.onFailure { err -> Timber.w(err, "Failed to resolve media bar video for %s", item.name) }
					.getOrNull()
				if (resolved == null) {
					Timber.i("No media bar video for %s", item.name)
				} else {
					Timber.i("Media bar video for %s start=%d", item.name, resolved.startMs)
				}
				resolved
			}.also { trailerResolves[item.id] = it }
		}
		return deferred.await()
	}

	private fun BaseItemDto.toMediaBarItem(): MediaBarItem? {
		if (overview.isNullOrBlank()) return null
		val backdrop = itemBackdropImages.firstOrNull()
		val poster = itemImages[ImageType.PRIMARY]
		if (backdrop == null && poster == null) return null
		val logo = itemImages[ImageType.LOGO]
		return MediaBarItem(
			item = this,
			backdropUrl = backdrop?.getUrl(api, maxWidth = BACKDROP_MAX_WIDTH, maxHeight = BACKDROP_MAX_HEIGHT),
			backdropBlurHash = backdrop?.blurHash ?: poster?.blurHash,
			posterUrl = poster?.getUrl(api, maxWidth = POSTER_MAX_WIDTH, maxHeight = POSTER_MAX_HEIGHT),
			logoUrl = logo?.getUrl(api, maxHeight = LOGO_MAX_HEIGHT),
			logoBlurHash = logo?.blurHash,
			logoAspectRatio = logo?.aspectRatio,
		)
	}

	private suspend fun resolveTrailer(item: BaseItemDto): MediaBarTrailer? {
		val localTrailer = runCatching {
			api.userLibraryApi.getLocalTrailers(itemId = item.id).content.firstOrNull()
		}.onFailure { err ->
			Timber.w(err, "getLocalTrailers failed for %s", item.name)
		}.getOrNull() ?: return null

		val playable = loadPlayable(localTrailer)
		return MediaBarTrailer(previewUrl(playable, transcode = !canDirectPlayPreview(playable)))
	}

	private suspend fun loadPlayable(item: BaseItemDto): BaseItemDto {
		if (!item.mediaSources.isNullOrEmpty()) return item
		return runCatching {
			api.userLibraryApi.getItem(itemId = item.id).content
		}.onFailure { err ->
			Timber.w(err, "Failed to load media sources for trailer %s", item.name)
		}.getOrNull() ?: item
	}

	private fun previewUrl(item: BaseItemDto, transcode: Boolean): String {
		if (!transcode) {
			return api.videosApi.getVideoStreamUrl(
				itemId = item.id,
				container = item.mediaSources?.firstOrNull()?.container,
				mediaSourceId = item.mediaSources?.firstOrNull()?.id,
				static = true,
				tag = item.mediaSources?.firstOrNull()?.eTag,
			)
		}

		return api.videosApi.getVideoStreamUrl(
			itemId = item.id,
			// MP4 transcoding often waits for the output file's metadata.
			// MPEG-TS is delivered as soon as the first encoded frames exist.
			container = "ts",
			mediaSourceId = item.mediaSources?.firstOrNull()?.id,
			static = false,
			videoCodec = "h264",
			audioCodec = "aac",
			maxWidth = PREVIEW_MAX_WIDTH,
			maxHeight = PREVIEW_MAX_HEIGHT,
			videoBitRate = PREVIEW_VIDEO_BITRATE,
			audioBitRate = PREVIEW_AUDIO_BITRATE,
			maxAudioChannels = 2,
			maxVideoBitDepth = 8,
			requireAvc = true,
		)
	}

	private fun canDirectPlayPreview(item: BaseItemDto): Boolean {
		val video = item.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return false
		val codec = video.codec?.lowercase() ?: return false
		if (codec != "h264" && codec != "avc") return false
		if ((video.bitDepth ?: 8) > 8) return false
		return true
	}

	companion object {
		private const val POOL_SIZE = 12
		private const val POOL_FETCH_LIMIT = 24
		private const val PREFETCH_AHEAD = 2
		private val ROTATE_INTERVAL = 12.seconds
		private const val BACKDROP_MAX_WIDTH = 1280
		private const val BACKDROP_MAX_HEIGHT = 720
		private const val POSTER_MAX_WIDTH = 540
		private const val POSTER_MAX_HEIGHT = 810
		private const val LOGO_MAX_HEIGHT = 400
		private const val PREVIEW_MAX_WIDTH = 854
		private const val PREVIEW_MAX_HEIGHT = 480
		private const val PREVIEW_VIDEO_BITRATE = 1_200_000
		private const val PREVIEW_AUDIO_BITRATE = 96_000
		const val SLIDE_FADE_MS = 500
		const val LOGO_REVEAL_MS = 1200
		const val KEN_BURNS_MS = 10_000
		const val TRAILER_FADE_MS = 180
	}
}
