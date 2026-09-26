package org.jellyfin.androidtv.ui.shared.toolbar

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.flow.filterNotNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.ui.NowPlayingComposable
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonColors
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.button.IconButtonDefaults
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel

enum class MainToolbarActiveButton {
	User,
	Home,
	Movies,
	TvShows,
	Collections,
	Search,

	None,
}

@Composable
fun MainToolbar(
	activeButton: MainToolbarActiveButton = MainToolbarActiveButton.None,
	modifier: Modifier = Modifier,
	focusRequester: FocusRequester? = null,
) {
	val userRepository = koinInject<UserRepository>()
	val api = koinInject<ApiClient>()

	// Prevent user image to disappear when signing out by skipping null values
	val currentUser by remember { userRepository.currentUser.filterNotNull() }.collectAsState(null)
	val userImage = remember(currentUser) { currentUser?.primaryImage?.getUrl(api) }

	MainToolbar(
		userImage = userImage,
		activeButton = activeButton,
		modifier = modifier,
		focusRequester = focusRequester,
	)
}

@Composable
private fun MainToolbar(
	userImage: String? = null,
	activeButton: MainToolbarActiveButton,
	modifier: Modifier = Modifier,
	focusRequester: FocusRequester? = null,
) {
	val fallbackFocusRequester = remember { FocusRequester() }
	val restoredFocusRequester = focusRequester ?: fallbackFocusRequester
	val navigationRepository = koinInject<NavigationRepository>()
	val mediaManager = koinInject<MediaManager>()
	val sessionRepository = koinInject<SessionRepository>()
	val userViewsRepository = koinInject<UserViewsRepository>()
	val itemLauncher = koinInject<ItemLauncher>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val activity = LocalActivity.current
	val views by userViewsRepository.views.collectAsState(emptyList())
	val moviesView = views.firstOrNull { it.collectionType == CollectionType.MOVIES }
	val tvShowsView = views.firstOrNull { it.collectionType == CollectionType.TVSHOWS }
	val collectionsView = views.firstOrNull { it.collectionType == CollectionType.BOXSETS }
	val activeButtonColors = ButtonDefaults.colors(
		containerColor = JellyfinTheme.colorScheme.buttonActive,
		contentColor = JellyfinTheme.colorScheme.onButtonActive,
	)

	Toolbar(
		modifier = modifier
			.focusRestorer(restoredFocusRequester)
			.focusGroup(),
		start = {
			ToolbarButtons {
				val userImagePainter = rememberAsyncImagePainter(userImage)
				val userImageState by userImagePainter.state.collectAsState()
				val userImageVisible = userImageState is AsyncImagePainter.State.Success

				IconButton(
					onClick = {
						if (activeButton != MainToolbarActiveButton.User) {
							mediaManager.clearAudioQueue()
							sessionRepository.destroyCurrentSession()

							// Open login activity
							activity?.startActivity(ActivityDestinations.startup(activity))
							activity?.finishAfterTransition()
						}
					},
					colors = if (activeButton == MainToolbarActiveButton.User) activeButtonColors else ButtonDefaults.colors(),
					contentPadding = if (userImageVisible) PaddingValues(3.dp) else IconButtonDefaults.ContentPadding,
				) {
					ProfilePicture(
						url = userImage,
						contentDescription = stringResource(R.string.lbl_switch_user),
						modifier = Modifier
							.aspectRatio(1f)
							.clip(IconButtonDefaults.Shape)
					)
				}

				NowPlayingComposable(
					onFocusableChange = {},
				)
			}
		},
		center = {
			ToolbarButtons(
				modifier = Modifier
					.focusRequester(restoredFocusRequester)
			) {
				ProvideTextStyle(JellyfinTheme.typography.default.copy(fontWeight = FontWeight.Bold)) {
					ToolbarNavButton(
						label = stringResource(R.string.lbl_home),
						selected = activeButton == MainToolbarActiveButton.Home,
						activeColors = activeButtonColors,
						onClick = {
							if (activeButton != MainToolbarActiveButton.Home) {
								navigationRepository.navigate(
									Destinations.home,
									replace = true,
								)
							}
						},
					)
					moviesView?.let { view ->
						ToolbarNavButton(
							label = stringResource(R.string.lbl_movies),
							selected = activeButton == MainToolbarActiveButton.Movies,
							activeColors = activeButtonColors,
							onClick = {
								if (activeButton != MainToolbarActiveButton.Movies) {
									itemLauncher.launchUserView(view)
								}
							},
						)
					}
					tvShowsView?.let { view ->
						ToolbarNavButton(
							label = stringResource(R.string.lbl_tv_shows),
							selected = activeButton == MainToolbarActiveButton.TvShows,
							activeColors = activeButtonColors,
							onClick = {
								if (activeButton != MainToolbarActiveButton.TvShows) {
									itemLauncher.launchUserView(view)
								}
							},
						)
					}
					collectionsView?.let { view ->
						ToolbarNavButton(
							label = stringResource(R.string.lbl_collections),
							selected = activeButton == MainToolbarActiveButton.Collections,
							activeColors = activeButtonColors,
							onClick = {
								if (activeButton != MainToolbarActiveButton.Collections) {
									itemLauncher.launchUserView(view)
								}
							},
						)
					}
					ToolbarNavButton(
						label = stringResource(R.string.lbl_search),
						selected = activeButton == MainToolbarActiveButton.Search,
						activeColors = activeButtonColors,
						onClick = {
							if (activeButton != MainToolbarActiveButton.Search) {
								navigationRepository.navigate(Destinations.search())
							}
						},
					)
				}
			}
		},
		end = {
			ToolbarButtons {
				IconButton(
					onClick = { settingsViewModel.show() },
				) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_settings),
						contentDescription = stringResource(R.string.lbl_settings),
					)
				}

				ToolbarClock()
			}
		}
	)
}

@Composable
private fun ToolbarNavButton(
	label: String,
	selected: Boolean,
	activeColors: ButtonColors,
	onClick: () -> Unit,
) {
	Button(
		onClick = onClick,
		colors = if (selected) activeColors else ButtonDefaults.colors(),
		contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
	) {
		Text(
			text = label,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
