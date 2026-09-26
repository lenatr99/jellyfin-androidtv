package org.jellyfin.androidtv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonColors
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.ticks
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.androidtv.util.PlaybackHelper
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import coil3.ImageLoader
import coil3.request.ImageRequest
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@Composable
fun MediaBar(
	focusRequester: FocusRequester,
	modifier: Modifier = Modifier,
	viewModel: MediaBarViewModel = koinViewModel(),
	onBarFocusChanged: (Boolean) -> Unit = {},
) {
	val item by viewModel.currentItem.collectAsState()
	val trailer by viewModel.trailer.collectAsState()
	// False while focus is in the rows below; pause video and animation there to keep scrolling smooth
	val playbackAllowed by viewModel.playbackAllowed.collectAsState()
	val index by viewModel.index.collectAsState()
	val items by viewModel.items.collectAsState()
	val playFocusRequester = remember { FocusRequester() }
	val toolbarFocusRequester = remember { FocusRequester() }
	var toolbarHasFocus by remember { mutableStateOf(false) }
	var barHadFocus by remember { mutableStateOf(false) }
	var didInitialPlayFocus by remember { mutableStateOf(false) }
	val playbackHelper = koinInject<PlaybackHelper>()
	val navigationRepository = koinInject<NavigationRepository>()
	val context = LocalContext.current
	val view = LocalView.current
	val lifecycleOwner = LocalLifecycleOwner.current
	var homeResumed by remember(lifecycleOwner) {
		mutableStateOf(
			lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
		)
	}
	val webNavy = colorResource(R.color.media_bar_navy)
	val webNavyTop = colorResource(R.color.media_bar_navy_top)
	val imageLoader = koinInject<ImageLoader>()
	val slideFade = tween<Float>(MediaBarViewModel.SLIDE_FADE_MS)

	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, _ ->
			homeResumed =
				lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
		}

		lifecycleOwner.lifecycle.addObserver(observer)

		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}

	LaunchedEffect(items, index) {
		if (items.isEmpty()) return@LaunchedEffect
		val next = items[(index + 1) % items.size]
		listOfNotNull(next.backdropUrl, next.logoUrl).forEach { url ->
			imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
		}
	}

	Box(
		modifier = modifier
			.clipToBounds()
			.background(webNavy)

			// Whenever focus enters the Media Bar from another Home row,
			// always enter through Play, never through the toolbar.
			.focusProperties {
				onEnter = {
					HomeFocusLog.i("MediaBar ENTER -> Play")
					playFocusRequester.requestFocus()
				}
			}
			.focusGroup()

			.onPreviewKeyEvent { event ->
				when {
					event.isInitialDown(Key.DirectionDown) && toolbarHasFocus -> {
						HomeFocusLog.i("Toolbar Down -> Play")

						runCatching {
							playFocusRequester.requestFocus()
						}.getOrDefault(false)
					}

					event.isInitialDown(Key.DirectionDown) && !toolbarHasFocus -> {
						HomeFocusLog.i("MediaBar Down -> next row")
						moveHomeGrid(view, 1)
					}

					else -> false
				}
			},
	) {
		val current = item
		if (current == null) {
			HomeFocusLog.i("MediaBar item is null, waiting for pool")
		} else {
		val kenBurns = remember(current.item.id) { Animatable(1f) }
		LaunchedEffect(current.item.id, playbackAllowed) {
			if (!playbackAllowed) return@LaunchedEffect
			// Resume where it paused, with the remaining share of the duration
			val remaining = ((1.08f - kenBurns.value) / 0.08f * MediaBarViewModel.KEN_BURNS_MS).toInt()
			if (remaining > 0) kenBurns.animateTo(
				1.08f,
				animationSpec = tween(remaining, easing = LinearEasing),
			)
		}
		val logoReveal = remember(current.item.id) { Animatable(0.7f) }
		LaunchedEffect(current.item.id) {
			logoReveal.snapTo(0.7f)
			logoReveal.animateTo(1f, tween(MediaBarViewModel.LOGO_REVEAL_MS))
		}

		Box(modifier = Modifier.fillMaxSize()) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					// Read animated values inside graphicsLayer so each frame only redraws, not recomposes
					.graphicsLayer {
						scaleX = kenBurns.value
						scaleY = kenBurns.value
						transformOrigin = TransformOrigin(0.5f, 0.2f)
					},
			) {
				AnimatedContent(
					targetState = current.backdropUrl ?: current.posterUrl,
					transitionSpec = { fadeIn(slideFade) togetherWith fadeOut(slideFade) },
					label = "mediaBarBackdrop",
				) { url ->
					AsyncImage(
						modifier = Modifier.fillMaxSize(),
						url = url,
						blurHash = current.backdropBlurHash,
						aspectRatio = 16f / 9f,
						scaleType = ImageView.ScaleType.CENTER_CROP,
					)
				}
			}

			MediaBarTrailerPlayer(
				trailer = trailer,
				playing = homeResumed && playbackAllowed && trailer != null,
				onError = viewModel::onTrailerFailed,
				modifier = Modifier.fillMaxSize(),
			)

			MediaBarScrims(
				navy = webNavy,
				navyTop = webNavyTop,
			)
		}

		Column(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.fillMaxWidth(0.62f)
				.padding(start = 56.dp, end = 24.dp, bottom = 36.dp)
				.focusGroup()
				.focusRequester(focusRequester)
				.focusRestorer(playFocusRequester)
				.onFocusChanged { state ->
					HomeFocusLog.i(
						"MediaBar group hasFocus=%s isFocused=%s",
						state.hasFocus,
						state.isFocused,
					)
					viewModel.setAutoAdvancePaused(state.hasFocus)
					onBarFocusChanged(state.hasFocus)
					val gained = state.hasFocus && !barHadFocus
					barHadFocus = state.hasFocus
					if (gained) {
						HomeFocusLog.i("MediaBar gained focus, requesting Play")
						runCatching { playFocusRequester.requestFocus() }
					}
				},
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			AnimatedContent(
				targetState = current,
				transitionSpec = { fadeIn(slideFade) togetherWith fadeOut(slideFade) },
				contentKey = { it.item.id },
				label = "mediaBarInfo",
			) { slide ->
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					if (slide.logoUrl != null) {
						AsyncImage(
							modifier = Modifier
								.height(80.dp)
								.widthIn(max = 400.dp)
								.wrapContentWidth(Alignment.Start)
								.padding(bottom = 4.dp)
								.graphicsLayer { alpha = logoReveal.value },
							url = slide.logoUrl,
							blurHash = null,
							aspectRatio = 0f,
							scaleType = ImageView.ScaleType.FIT_START,
						)
					} else {
						Text(
							text = slide.item.name.orEmpty(),
							color = Color.White,
							fontSize = 32.sp,
							fontWeight = FontWeight.Bold,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.graphicsLayer { alpha = logoReveal.value },
						)
					}

					MediaBarMetaRow(slide.item)

					val genres = slide.item.genres.orEmpty().take(3).joinToString(" • ")
					if (genres.isNotBlank()) {
						Text(
							text = genres,
							color = Color.White.copy(alpha = 0.92f),
							fontSize = 14.sp,
							fontWeight = FontWeight.Medium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}

					Text(
						text = slide.item.overview.orEmpty(),
						color = Color.White.copy(alpha = 0.90f),
						fontSize = 14.sp,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}

			MediaBarActions(
				favorited = current.item.userData?.isFavorite == true,
				playFocusRequester = playFocusRequester,
				onPlayUp = {
					HomeFocusLog.i("Play Up -> toolbar")

					runCatching {
						toolbarFocusRequester.requestFocus()
					}.getOrDefault(false)
				},
				onPlay = { playbackHelper.retrieveAndPlay(current.item.id, false, context) },
				onDetails = { navigationRepository.navigate(Destinations.itemDetails(current.item.id)) },
				onFavorite = viewModel::toggleFavorite,
				onPrevious = viewModel::previous,
				onNext = viewModel::next,
			)
		}

			if (items.size > 1) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(end = 56.dp, bottom = 36.dp),
				) {
					repeat(items.size) { i ->
						val selected = i == index
						val width by animateDpAsState(
							targetValue = if (selected) 32.dp else 6.dp,
							animationSpec = tween(MediaBarViewModel.SLIDE_FADE_MS),
							label = "mediaBarDot",
						)
						Box(
							modifier = Modifier
								.height(6.dp)
								.width(width)
								.background(
									color = Color.White.copy(alpha = if (selected) 1f else 0.38f),
									shape = CircleShape,
								)
						)
					}
				}
			}
		}

		MainToolbar(
			activeButton = MainToolbarActiveButton.Home,
			focusRequester = toolbarFocusRequester,
			modifier = Modifier
				.align(Alignment.TopCenter)
				.fillMaxWidth()
				.zIndex(100f)
				.onFocusChanged { state ->
					toolbarHasFocus = state.hasFocus

					HomeFocusLog.i(
						"MediaBar toolbar hasFocus=%s",
						state.hasFocus,
					)
				},
		)

		// Never request toolbar focus here. Play owns initial Home focus.
		// Wait until a slide exists so Play is actually composed.
		LaunchedEffect(item) {
			if (item == null || didInitialPlayFocus) return@LaunchedEffect
			didInitialPlayFocus = true
			runCatching { playFocusRequester.requestFocus() }
				.onSuccess { HomeFocusLog.i("requested initial Play focus") }
				.onFailure { HomeFocusLog.i("initial Play FocusRequester failed: %s", it.message) }
		}
	}
}

@Composable
private fun MediaBarScrims(
	navy: Color,
	navyTop: Color,
) {
	Box(modifier = Modifier.fillMaxSize()) {
		Box(
			modifier = Modifier
				.align(Alignment.TopCenter)
				.fillMaxWidth()
				.height(168.dp)
				.background(
					Brush.verticalGradient(
						0.00f to navyTop.copy(alpha = 0.78f),
						0.45f to navyTop.copy(alpha = 0.32f),
						1.00f to Color.Transparent,
					)
				)
		)
		Box(
			modifier = Modifier
				.align(Alignment.CenterStart)
				.fillMaxHeight()
				.fillMaxWidth(0.42f)
				.background(
					Brush.horizontalGradient(
						0.00f to Color.Black.copy(alpha = 0.38f),
						1.00f to Color.Transparent,
					)
				)
		)
		Box(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth()
				.fillMaxHeight(0.42f)
				.background(
					Brush.verticalGradient(
						0.00f to Color.Transparent,
						0.22f to navy.copy(alpha = 0.18f),
						0.48f to navy.copy(alpha = 0.48f),
						0.74f to navy.copy(alpha = 0.82f),
						1.00f to navy,
					)
				)
		)
	}
}

private val META_SEPARATOR = "  ·  "
private val ENDS_AT_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val OUTLINE_BORDER = Color.White.copy(alpha = 0.88f)

@Composable
private fun MediaBarMetaRow(item: BaseItemDto) {
	val seasonCount = item.childCount
	val seasonLabel = if (item.type == BaseItemKind.SERIES && seasonCount != null && seasonCount > 0) {
		val noun = if (seasonCount == 1) {
			stringResource(R.string.lbl_season)
		} else {
			stringResource(R.string.lbl_seasons)
		}
		"$seasonCount $noun"
	} else {
		null
	}
	val endsAtLabel = if (seasonLabel == null) {
		item.runTimeTicks?.ticks?.takeIf { it.inWholeSeconds > 0 }?.let { duration ->
			val ends = LocalTime.now().plusSeconds(duration.inWholeSeconds)
			stringResource(R.string.media_bar_ends_at, ends.format(ENDS_AT_FORMAT))
		}
	} else {
		null
	}
	val parts = buildList {
		item.communityRating?.let { add(MetaPart.Rating(it)) }
		yearOf(item)?.let { add(MetaPart.Text(it)) }
		item.officialRating?.takeIf { it.isNotBlank() }?.let { add(MetaPart.Badge(it)) }
		(seasonLabel ?: endsAtLabel)?.let { add(MetaPart.Text(it)) }
	}
	if (parts.isEmpty()) return

	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		parts.forEachIndexed { index, part ->
			when (part) {
				is MetaPart.Rating -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_star),
						contentDescription = stringResource(R.string.lbl_community_rating),
						modifier = Modifier.size(15.dp),
						tint = Color(0xFFEECE55),
					)
					Text(
						text = String.format("%.1f", part.value),
						color = Color.White,
						fontSize = 14.sp,
						fontWeight = FontWeight.SemiBold,
					)
				}
				is MetaPart.Text -> Text(
					text = part.value,
					color = Color.White.copy(alpha = 0.92f),
					fontSize = 14.sp,
					fontWeight = FontWeight.Medium,
				)
				is MetaPart.Badge -> Text(
					text = part.value,
					color = Color.White,
					fontSize = 11.sp,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier
						.border(1.dp, Color(0xFF47505C), RoundedCornerShape(4.dp))
						.padding(horizontal = 6.dp, vertical = 1.dp),
				)
			}
			if (index != parts.lastIndex) {
				Text(
					text = META_SEPARATOR.trim(),
					color = Color.White.copy(alpha = 0.55f),
					fontSize = 14.sp,
				)
			}
		}
	}
}

private sealed interface MetaPart {
	data class Rating(val value: Float) : MetaPart
	data class Text(val value: String) : MetaPart
	data class Badge(val value: String) : MetaPart
}

private fun yearOf(item: BaseItemDto): String? =
	item.productionYear?.toString()
		?: item.premiereDate?.year?.toString()

@Composable
private fun MediaBarActions(
	favorited: Boolean,
	playFocusRequester: FocusRequester,
	onPlayUp: () -> Boolean,
	onPlay: () -> Unit,
	onDetails: () -> Unit,
	onFavorite: () -> Unit,
	onPrevious: () -> Unit,
	onNext: () -> Unit,
) {
	val outlineColors = ButtonDefaults.colors(
		containerColor = Color.Transparent,
		contentColor = Color.White,
		focusedContainerColor = Color.White,
		focusedContentColor = Color.Black,
	)
	val favoriteColors = ButtonDefaults.colors(
		containerColor = Color.Transparent,
		contentColor = if (favorited) Color(0xFFCC3333) else Color.White,
		focusedContainerColor = Color.White,
		focusedContentColor = if (favorited) Color(0xFFCC3333) else Color.Black,
	)
	val playColors = ButtonDefaults.colors(
		containerColor = Color.White.copy(alpha = 0.18f),
		contentColor = Color.White,
		focusedContainerColor = Color.White,
		focusedContentColor = Color.Black,
	)

	Row(
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.focusGroup(),
	) {
		MediaBarIconButton(
			onClick = onDetails,
			colors = outlineColors,
			contentDescription = stringResource(R.string.lbl_details),
			modifier = Modifier.onPreviewKeyEvent { event ->
				if (event.isInitialDown(Key.DirectionLeft)) {
					onPrevious()
					true
				} else {
					false
				}
			},
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_info_glyph),
				contentDescription = null,
				modifier = Modifier.size(18.dp),
			)
		}

		Button(
			onClick = onPlay,
			shape = CircleShape,
			colors = playColors,
			contentPadding = PaddingValues(horizontal = 26.dp, vertical = 11.dp),
			modifier = Modifier
				.focusRequester(playFocusRequester)
				.border(1.5.dp, OUTLINE_BORDER, CircleShape)
				.onFocusChanged { state ->
					HomeFocusLog.i(
						"MediaBar Play isFocused=%s hasFocus=%s",
						state.isFocused,
						state.hasFocus,
					)
				}
				.onPreviewKeyEvent { event ->
					if (event.isInitialDown(Key.DirectionUp)) {
						onPlayUp()
					} else {
						false
					}
				},
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_play),
				contentDescription = null,
				modifier = Modifier.size(16.dp),
			)
			Spacer(Modifier.width(8.dp))
			Text(
				text = stringResource(R.string.lbl_play),
				fontSize = 16.sp,
				fontWeight = FontWeight.SemiBold,
			)
		}

		MediaBarIconButton(
			onClick = onFavorite,
			colors = favoriteColors,
			contentDescription = stringResource(
				if (favorited) R.string.lbl_remove_favorite else R.string.lbl_add_favorite
			),
			modifier = Modifier.onPreviewKeyEvent { event ->
				if (event.isInitialDown(Key.DirectionRight)) {
					onNext()
					true
				} else {
					false
				}
			},
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_heart),
				contentDescription = null,
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

@Composable
private fun MediaBarIconButton(
	onClick: () -> Unit,
	colors: ButtonColors,
	contentDescription: String,
	modifier: Modifier = Modifier,
	content: @Composable BoxScope.() -> Unit,
) {
	IconButton(
		onClick = onClick,
		shape = CircleShape,
		colors = colors,
		contentPadding = PaddingValues(0.dp),
		modifier = modifier
			.size(42.dp)
			.border(1.5.dp, OUTLINE_BORDER, CircleShape)
			.semantics { this.contentDescription = contentDescription },
	) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center,
			content = content,
		)
	}
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaBarTrailerPlayer(
	trailer: MediaBarTrailer?,
	playing: Boolean,
	onError: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val dataSourceFactory = koinInject<HttpDataSource.Factory>()
	var hasFrame by remember { mutableStateOf(false) }
	var playbackStarted by remember { mutableStateOf(false) }
	val player = remember {
		ExoPlayer.Builder(context)
			.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
			.setLoadControl(
				DefaultLoadControl.Builder()
					.setBufferDurationsMs(
						1_000,
						5_000,
						0,
						250,
					)
					.build()
			)
			.build()
			.apply {
				volume = 0f
				repeatMode = Player.REPEAT_MODE_ONE
				playWhenReady = false
			}
	}

	DisposableEffect(player) {
		val listener = object : Player.Listener {
			private var preparedAt = 0L

			override fun onPlaybackStateChanged(playbackState: Int) {
				if (playbackState == Player.STATE_BUFFERING) {
					preparedAt = android.os.SystemClock.elapsedRealtime()
				}
			}

			override fun onRenderedFirstFrame() {
				hasFrame = true
				val wait = if (preparedAt == 0L) -1 else android.os.SystemClock.elapsedRealtime() - preparedAt
				Timber.i("Media bar first frame after %dms", wait)
			}

			override fun onPlayerError(error: PlaybackException) {
				Timber.e(error, "Media bar ExoPlayer error")
				hasFrame = false
				onError()
			}
		}
		player.addListener(listener)
		onDispose {
			player.removeListener(listener)
			player.release()
		}
	}

	LaunchedEffect(trailer, playing) {
		playbackStarted = false

		if (trailer == null) {
			hasFrame = false
			player.playWhenReady = false
			player.stop()
			player.clearMediaItems()
			return@LaunchedEffect
		}

		val currentUri =
			player.currentMediaItem?.localConfiguration?.uri?.toString()

		if (currentUri != trailer.url) {
			hasFrame = false
			player.setMediaItem(MediaItem.fromUri(trailer.url))
			player.playWhenReady = false
			player.prepare()

			if (trailer.startMs > 0) {
				player.seekTo(trailer.startMs)
			}
		} else {
			player.playWhenReady = false
			player.pause()
		}

		if (playing) {
			delay(5.seconds)

			val stillCurrent =
				player.currentMediaItem
					?.localConfiguration
					?.uri
					?.toString() == trailer.url

			if (stillCurrent) {
				playbackStarted = true
				player.playWhenReady = true
				player.play()
			}
		}
	}

	AndroidView(
		factory = { viewContext ->
			val playerView = (
				LayoutInflater.from(viewContext)
					.inflate(R.layout.media_bar_player, null) as PlayerView
			).apply {
				this.player = player
				resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

				setBackgroundColor(android.graphics.Color.TRANSPARENT)
				alpha = 0f

				clipToPadding = true
				clipChildren = true
				blockAllFocus()
			}
			FrameLayout(viewContext).apply {
				layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				)
				clipToPadding = true
				clipChildren = true
				addView(
					playerView,
					FrameLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.MATCH_PARENT,
					),
				)
				blockAllFocus()
			}
		},
		update = { wrapper ->
			wrapper.blockAllFocus()
			val playerView = wrapper.getChildAt(0) as? PlayerView
			playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
			playerView?.blockAllFocus()
			// Keep the complete video hierarchy attached and visible at all times.
			// TextureView needs a visible ancestor hierarchy in order to create and
			// retain its SurfaceTexture. Hide unfinished video using alpha only.
			wrapper.visibility = View.VISIBLE
			playerView?.visibility = View.VISIBLE
			playerView?.alpha =
				if (playbackStarted && hasFrame) 1f else 0f
		},
		onRelease = {
			(it.getChildAt(0) as? PlayerView)?.player = null
		},
		modifier = modifier.focusProperties { canFocus = false },
	)
}

private fun View.blockAllFocus() {
	isFocusable = false
	isFocusableInTouchMode = false
	isClickable = false
	if (this is ViewGroup) {
		descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
		for (i in 0 until childCount) {
			getChildAt(i).blockAllFocus()
		}
	}
}

private fun KeyEvent.isInitialDown(key: Key): Boolean =
	type == KeyEventType.KeyDown && this.key == key

private fun moveHomeGrid(view: View, delta: Int): Boolean {
	var parent = view.parent
	while (parent != null) {
		if (parent is VerticalGridView) {
			val next = parent.selectedPosition + delta
			val count = parent.adapter?.itemCount ?: 0
			if (next in 0 until count) {
				parent.setSelectedPositionSmooth(next)
				return true
			}
			return false
		}
		parent = parent.parent
	}
	return false
}
