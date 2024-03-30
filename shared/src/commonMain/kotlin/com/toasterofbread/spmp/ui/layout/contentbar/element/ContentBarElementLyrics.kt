package com.toasterofbread.spmp.ui.layout.contentbar.element

import LocalPlayerState
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.*
import androidx.compose.ui.text.style.TextAlign
import com.toasterofbread.composekit.utils.common.getValue
import com.toasterofbread.composekit.utils.composable.*
import com.toasterofbread.spmp.model.mediaitem.loader.SongLyricsLoader
import com.toasterofbread.spmp.model.mediaitem.song.Song
import com.toasterofbread.spmp.resources.getString
import com.toasterofbread.spmp.service.playercontroller.PlayerState
import com.toasterofbread.spmp.ui.component.*
import kotlin.math.sign
import kotlinx.serialization.Serializable

@Serializable
data class ContentBarElementLyrics(
    override val size_mode: ContentBarElement.SizeMode = DEFAULT_SIZE_MODE,
    override val size: Int = DEFAULT_SIZE,
    val alignment: Int = 0,
    val linger: Boolean = false,
    val show_furigana: Boolean = true
    // val max_lines: Int = 1,
    // val preallocate_max_space: Boolean = false,
): ContentBarElement() {
    override fun getType(): ContentBarElement.Type = ContentBarElement.Type.LYRICS

    override fun copyWithSize(size_mode: ContentBarElement.SizeMode, size: Int): ContentBarElement =
        copy(size_mode = size_mode, size = size)

    private var lyrics_state: SongLyricsLoader.ItemState? by mutableStateOf(null)

    @Composable
    override fun isDisplaying(): Boolean {
        val player: PlayerState = LocalPlayerState.current
        val current_song: Song? by player.status.song_state
        lyrics_state = current_song?.let { SongLyricsLoader.rememberItemState(it, player.context) }

        return lyrics_state?.lyrics?.synced == true
    }

    @Composable
    override fun ElementContent(vertical: Boolean, enable_interaction: Boolean, modifier: Modifier) {
        val player: PlayerState = LocalPlayerState.current
        val current_song: Song? by player.status.song_state
        val lyrics_sync_offset: Long? by current_song?.getLyricsSyncOffset(player.database, true)

        AlignableCrossfade(
            lyrics_state?.lyrics,
            modifier,
            contentAlignment =
                if (alignment == 0) Alignment.Center
                else if (vertical) {
                    if (alignment < 0) Alignment.TopCenter
                    else Alignment.BottomCenter
                }
                else {
                    if (alignment < 0) Alignment.CenterStart
                    else Alignment.CenterEnd
                }
        ) { lyrics ->
            if (lyrics?.synced != true) {
                return@AlignableCrossfade
            }

            val getTime: () -> Long = {
                (player.controller?.current_position_ms ?: 0) + (lyrics_sync_offset ?: 0)
            }

            if (vertical) {
                VerticalLyricsLineDisplay(
                    lyrics = lyrics,
                    getTime = getTime,
                    lyrics_linger = linger,
                    show_furigana = show_furigana
                )
            }
            else {
                HorizontalLyricsLineDisplay(
                    lyrics = lyrics,
                    getTime = getTime,
                    lyrics_linger = linger,
                    show_furigana = show_furigana,
                    text_align =
                        if (alignment < 0) TextAlign.Start
                        else if (alignment == 0) TextAlign.Center
                        else TextAlign.End
                    // max_lines = max_lines,
                    // preallocate_max_space = preallocate_max_space
                )
            }
        }
    }

    @Composable
    override fun SubConfigurationItems(item_modifier: Modifier, onModification: (ContentBarElement) -> Unit) {
        var show_alignment_selector: Boolean by remember { mutableStateOf(false) }

        LargeDropdownMenu(
            expanded = show_alignment_selector,
            onDismissRequest = { show_alignment_selector = false },
            item_count = 3,
            selected = alignment.sign + 1,
            itemContent = {
                Text(
                    if (it == 0) getString("s_option_lyrics_text_alignment_start")
                    else if (it == 1) getString("s_option_lyrics_text_alignment_center")
                    else getString("s_option_lyrics_text_alignment_end")
                )
            },
            onSelected = {
                onModification(copy(alignment = it - 1))
                show_alignment_selector = false
            }
        )

        FlowRow(
            item_modifier,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                getString("content_bar_element_lyrics_config_alignment"),
                Modifier.align(Alignment.CenterVertically),
                softWrap = false
            )

            Button({ show_alignment_selector = !show_alignment_selector }) {
                Text(
                    if (alignment < 0) getString("s_option_lyrics_text_alignment_start")
                    else if (alignment == 0) getString("s_option_lyrics_text_alignment_center")
                    else getString("s_option_lyrics_text_alignment_end")
                )
            }
        }

        FlowRow(
            item_modifier,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                getString("content_bar_element_lyrics_config_linger"),
                Modifier.align(Alignment.CenterVertically),
                softWrap = false
            )

            Switch(
                linger,
                { onModification(copy(linger = it)) }
            )
        }

        FlowRow(
            item_modifier,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                getString("content_bar_element_lyrics_config_show_furigana"),
                Modifier.align(Alignment.CenterVertically),
                softWrap = false
            )

            Switch(
                show_furigana,
                { onModification(copy(show_furigana = it)) }
            )
        }

        // FlowRow(
        //     item_modifier,
        //     horizontalArrangement = Arrangement.SpaceBetween
        // ) {
        //     Text(
        //         getString("content_bar_element_lyrics_config_max_lines"),
        //         Modifier.align(Alignment.CenterVertically),
        //         softWrap = false
        //     )

        //     Row(
        //         verticalAlignment = Alignment.CenterVertically
        //     ) {
        //         IconButton({ onModification(copy(max_lines = (max_lines - 1).coerceAtLeast(1))) }) {
        //             Icon(Icons.Default.Remove, null)
        //         }

        //         Text((max_lines.coerceAtLeast(1)).toString())

        //         IconButton({ onModification(copy(max_lines = max_lines + 1)) }) {
        //             Icon(Icons.Default.Add, null)
        //         }
        //     }
        // }

        // FlowRow(
        //     item_modifier,
        //     horizontalArrangement = Arrangement.SpaceBetween
        // ) {
        //     Text(
        //         getString("content_bar_element_lyrics_config_preallocate_max_space"),
        //         Modifier.align(Alignment.CenterVertically),
        //         softWrap = false
        //     )

        //     Switch(
        //         preallocate_max_space,
        //         { onModification(copy(preallocate_max_space = it)) }
        //     )
        // }
    }
}