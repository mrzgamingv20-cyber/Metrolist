/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.constants.DynamicIslandArtwork
import com.metrolist.music.constants.DynamicIslandSubtitle

@Composable
fun getDynamicIslandSubtitleLabel(subtitle: DynamicIslandSubtitle): String = when (subtitle) {
    DynamicIslandSubtitle.ARTIST -> stringResource(R.string.dynamic_island_subtitle_artist)
    DynamicIslandSubtitle.ALBUM -> stringResource(R.string.dynamic_island_subtitle_album)
    DynamicIslandSubtitle.ARTIST_ALBUM -> stringResource(R.string.dynamic_island_subtitle_artist_album)
    DynamicIslandSubtitle.DURATION -> stringResource(R.string.dynamic_island_subtitle_duration)
    DynamicIslandSubtitle.NONE -> stringResource(R.string.dynamic_island_subtitle_none)
}

@Composable
fun getDynamicIslandArtworkLabel(artwork: DynamicIslandArtwork): String = when (artwork) {
    DynamicIslandArtwork.ALBUM_ART -> stringResource(R.string.dynamic_island_artwork_album)
    DynamicIslandArtwork.NOW_PLAYING_CARD -> stringResource(R.string.dynamic_island_artwork_card)
}