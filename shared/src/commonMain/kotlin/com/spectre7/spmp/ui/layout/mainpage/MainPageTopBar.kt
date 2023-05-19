package com.spectre7.spmp.ui.layout.mainpage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spectre7.spmp.PlayerServiceHost
import com.spectre7.spmp.api.LocalisedYoutubeString
import com.spectre7.spmp.model.*
import com.spectre7.spmp.platform.ProjectPreferences
import com.spectre7.spmp.ui.component.LyricsLineDisplay
import com.spectre7.spmp.ui.layout.RadioBuilderIcon
import com.spectre7.spmp.ui.layout.YoutubeMusicLoginConfirmation
import com.spectre7.spmp.ui.theme.Theme
import com.spectre7.utils.composable.NoRipple

@Composable
fun MainPageTopBar(
    auth_info: YoutubeMusicAuthInfo,
    playerProvider: () -> PlayerViewContext,
    getFilterChips: () -> List<Pair<Int, String>>?,
    getSelectedFilterChip: () -> Int?,
    onFilterChipSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    var song: Song? by remember { mutableStateOf(null) }
    val song_reg_lyrics_listener: (Pair<Int, Song.Lyrics.Source>?) -> Unit = remember { { data ->
        val lyrics_holder = song!!.lyrics
        if (data?.first != lyrics_holder.lyrics?.id || data?.second != lyrics_holder.lyrics?.source) {
            lyrics_holder.loadAndGet()
        }
    } }

    LaunchedEffect(PlayerServiceHost.status.m_song) {
        song?.apply { song_reg_entry.lyrics_listeners.remove(song_reg_lyrics_listener) }
        song = PlayerServiceHost.status.m_song?.apply {
            song_reg_entry.lyrics_listeners.add(song_reg_lyrics_listener)
            lyrics.loadAndGet()
        }
    }

    Column(modifier.animateContentSize()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            RadioBuilderButton(playerProvider)

            var show_lyrics: Boolean by remember { mutableStateOf(Settings.KEY_HP_SHOW_TIMED_LYRICS.get()) }
            var show_visualiser: Boolean by remember { mutableStateOf(Settings.KEY_HP_SHOW_VISUALISER.get()) }

            DisposableEffect(Unit) {
                val prefs_listener = Settings.prefs.addListener(object : ProjectPreferences.Listener {
                    override fun onChanged(prefs: ProjectPreferences, key: String) {
                        if (key == Settings.KEY_HP_SHOW_TIMED_LYRICS.name) {
                            show_lyrics = Settings.KEY_HP_SHOW_TIMED_LYRICS.get(prefs)
                        } else if (key == Settings.KEY_HP_SHOW_VISUALISER.name) {
                            show_visualiser = Settings.KEY_HP_SHOW_VISUALISER.get(prefs)
                        }
                    }
                })

                onDispose {
                    Settings.prefs.removeListener(prefs_listener)
                }
            }

            NoRipple {
                Box(
                    Modifier.fillMaxSize().weight(1f).clickable {
                        when (Settings.getEnum<PlayerViewTopBarAction>(Settings.KEY_HP_TOP_BAR_ACTION)) {
                            PlayerViewTopBarAction.TOGGLE_LYRICS -> Settings.KEY_HP_SHOW_TIMED_LYRICS.set(!show_lyrics)
                            PlayerViewTopBarAction.OPEN_LYRICS -> TODO()
                            PlayerViewTopBarAction.NONE -> {}
                        }
                    }
                ) {
                    val state by remember {
                        derivedStateOf {
                            song?.lyrics?.lyrics.let { lyrics ->
                                if (show_lyrics && lyrics != null && lyrics.sync_type != Song.Lyrics.SyncType.NONE) lyrics
                                else if (show_visualiser && PlayerServiceHost.status.m_playing) 0
                                else null
                            }
                        }
                    }

                    Crossfade(state) { s ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            when (s) {
                                is Song.Lyrics -> LyricsLineDisplay(
                                    s,
                                    { PlayerServiceHost.status.position_ms + 500 },
                                    Theme.current.on_background_provider
                                )
                                0 -> PlayerServiceHost.player.Visualiser(
                                    Color.White,
                                    Modifier.fillMaxSize().padding(vertical = 10.dp),
                                    opacity = 0.5f
                                )
                            }
                        }
                    }
                }
            }

            var show_login_confirmation by remember { mutableStateOf(false) }
            if (show_login_confirmation) {
                YoutubeMusicLoginConfirmation { manual ->
                    show_login_confirmation = false
                    if (manual == true) playerProvider().setOverlayPage(OverlayPage.YTM_MANUAL_LOGIN)
                    else if (manual == false) playerProvider().setOverlayPage(OverlayPage.YTM_LOGIN)
                }
            }

            IconButton({
                if (auth_info.initialised) {
                    playerProvider().onMediaItemClicked(auth_info.own_channel)
                } else {
                    show_login_confirmation = true
                }
            }) {
                Crossfade(auth_info) { info ->
                    if (auth_info.initialised) {
                        info.own_channel.Thumbnail(MediaItemThumbnailProvider.Quality.LOW, Modifier.clip(CircleShape).size(27.dp))
                    } else {
                        Icon(Icons.Filled.Person, null)
                    }
                }
            }
        }

        val multiselect_context = playerProvider().main_multiselect_context
        Crossfade(multiselect_context.is_active) { multiselect_active ->
            if (multiselect_active) {
                multiselect_context.InfoDisplay(Modifier.fillMaxWidth())
            }
            else {
                FilterChipsRow(getFilterChips, getSelectedFilterChip, onFilterChipSelected)
            }
        }
    }
}

@Composable
private fun RadioBuilderButton(playerProvider: () -> PlayerViewContext) {
    IconButton({ playerProvider().setOverlayPage(OverlayPage.RADIO_BUILDER) }) {
        RadioBuilderIcon()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.FilterChipsRow(getFilterChips: () -> List<Pair<Int, String>>?, getSelectedFilterChip: () -> Int?, onFilterChipSelected: (Int?) -> Unit) {
    val enabled: Boolean by mutableSettingsState(Settings.KEY_FEED_SHOW_FILTERS)
    val filter_chips = getFilterChips()

    AnimatedVisibility(enabled && filter_chips?.isNotEmpty() == true) {
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val selected_filter_chip = getSelectedFilterChip()

            items(filter_chips?.size ?: 0) { i ->
                val chip_index = filter_chips!![i].first
                ElevatedFilterChip(
                    i == selected_filter_chip,
                    {
                        onFilterChipSelected(if (i == selected_filter_chip) null else i)
                    },
                    { Text(LocalisedYoutubeString.filterChip(chip_index)) },
                    colors = with(Theme.current) {
                        FilterChipDefaults.elevatedFilterChipColors(
                            containerColor = background,
                            labelColor = on_background,
                            selectedContainerColor = accent,
                            selectedLabelColor = on_accent
                        )
                    },
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = Theme.current.on_background
                    )
                )
            }
        }
    }
}
