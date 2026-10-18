/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first

val DynamicIslandSubtitleKey = stringPreferencesKey("dynamicIslandSubtitle")
val DynamicIslandArtworkKey = stringPreferencesKey("dynamicIslandArtwork")

/** Secondary line rendered under the title in the OEM dynamic island / media card. */
enum class DynamicIslandSubtitle {
    ARTIST,
    ALBUM,
    ARTIST_ALBUM,
    DURATION,
    NONE,
}

/** Artwork rendered in the OEM dynamic island / media card for the playing song. */
enum class DynamicIslandArtwork {
    ALBUM_ART,
    NOW_PLAYING_CARD,
}

/**
 * Media items are built from extension functions that have no context, and they run for every
 * queued song, so the values are cached here instead of hitting DataStore on each conversion.
 */
object DynamicIslandSettings {
    @Volatile var subtitle: DynamicIslandSubtitle = DynamicIslandSubtitle.ARTIST
        private set

    @Volatile var artwork: DynamicIslandArtwork = DynamicIslandArtwork.ALBUM_ART
        private set

    /**
     * Suspending on purpose: the synchronous DataStore accessor blocks its caller, which stalls
     * app startup when it is read from onCreate.
     */
    suspend fun refresh(context: Context) {
        val values = context.dataStore.data.first()
        subtitle = values[DynamicIslandSubtitleKey].toEnum(DynamicIslandSubtitle.ARTIST)
        artwork = values[DynamicIslandArtworkKey].toEnum(DynamicIslandArtwork.ALBUM_ART)
    }
}