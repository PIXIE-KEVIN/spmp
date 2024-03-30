package com.toasterofbread.spmp.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.toasterofbread.composekit.utils.composable.*
import com.toasterofbread.spmp.model.mediaitem.*
import com.toasterofbread.spmp.model.mediaitem.db.rememberPinnedItems
import com.toasterofbread.spmp.ui.component.longpressmenu.LongPressMenuData
import com.toasterofbread.spmp.ui.component.mediaitempreview.*
import com.toasterofbread.spmp.ui.component.multiselect.MediaItemMultiSelectContext
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

@Composable
private fun Item(
    item: MediaItem,
    vertical: Boolean,
    multiselect_context: MediaItemMultiSelectContext?,
    enable_interaction: Boolean,
    modifier: Modifier = Modifier
) {
    val long_press_menu_data: LongPressMenuData = remember(item) {
        item.getLongPressMenuData(multiselect_context)
    }

    val loaded_item: MediaItem? = item.loadIfLocalPlaylist()
    if (loaded_item == null) {
        return
    }

    val fill_modifier: Modifier =
        Modifier
            .then(
                if (vertical) Modifier.fillMaxWidth()
                else Modifier.fillMaxHeight()
            )
            .aspectRatio(1f)

    Box(
        modifier
            .then(fill_modifier)
            .clip(item.getType().getThumbShape())
    ) {
        item.Thumbnail(
            ThumbnailProvider.Quality.LOW,
            fill_modifier.mediaItemPreviewInteraction(
                loaded_item,
                long_press_menu_data,
                enabled = enable_interaction
            )
        )

        multiselect_context?.also { ctx ->
            ctx.SelectableItemOverlay(
                loaded_item,
                fill_modifier,
                key = long_press_menu_data.multiselect_key
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedItemsList(
    vertical: Boolean,
    modifier: Modifier = Modifier,
    multiselect_context: MediaItemMultiSelectContext? = null,
    enable_interaction: Boolean = true,
    scrolling: Boolean = true
) {
    val pinned_items: List<MediaItem> = rememberPinnedItems() ?: emptyList()
    val arrangement: Arrangement.HorizontalOrVertical = Arrangement.spacedBy(10.dp)

    RowOrColumn(!vertical, modifier) {
        multiselect_context?.CollectionToggleButton(pinned_items, enter = expandVertically(), exit = shrinkVertically())

        if (scrolling) {
            ScrollBarLazyRowOrColumn(
                !vertical,
                arrangement = arrangement,
                alignment = -1,
                show_scrollbar = false
            ) {
                items(pinned_items) { item ->
                    Item(item, vertical, multiselect_context, enable_interaction, Modifier.animateItemPlacement())
                }
            }
        }
        else {
            RowOrColumn(
                !vertical,
                arrangement = arrangement,
                alignment = -1,
                modifier =
                    if (vertical) Modifier.verticalScroll(rememberScrollState())
                    else Modifier.horizontalScroll(rememberScrollState())
            ) {
                for (item in pinned_items) {
                    Item(item, vertical, multiselect_context, enable_interaction)
                }
            }
        }
    }
}