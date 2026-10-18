/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.session.MediaConstants.EXTRAS_KEY_IS_EXPLICIT
import androidx.media3.session.MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.constants.DynamicIslandSettings
import com.metrolist.music.constants.DynamicIslandSubtitle
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.makeTimeString

val MediaItem.metadata: MediaMetadata?
    get() = localConfiguration?.tag as? MediaMetadata

fun Song.toMediaItem() = toMediaMetadata().toMediaItem()

fun SongItem.toMediaItem() = toMediaMetadata().toMediaItem()

fun MediaMetadata.toMediaItem(): MediaItem {
    val resolvedMetadata = withResolvedArtistNameAliases()
    val subtitle = resolvedMetadata.dynamicIslandSubtitle()
    return MediaItem.Builder()
        .setMediaId(resolvedMetadata.id)
        .setUri(resolvedMetadata.id)
        .setCustomCacheKey(resolvedMetadata.id)
        .setTag(resolvedMetadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(resolvedMetadata.title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(resolvedMetadata.thumbnailUrl?.toUri())
                .setAlbumTitle(resolvedMetadata.album?.title)
                .setAlbumArtist(resolvedMetadata.artists.firstOrNull()?.name)
                .setDisplayTitle(resolvedMetadata.title)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setExtras(Bundle().apply {
                    resolvedMetadata.thumbnailUrl?.let { putString("artwork_uri", it) }
                    if (resolvedMetadata.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                })
                .build(),
        ).build()
}

fun MediaMetadata.withResolvedArtistNameAliases(): MediaMetadata {
    val resolvedArtists =
        artists.map { artist ->
            artist.copy(name = ArtistNameAliases.resolve(artist.id, artist.name))
        }
    return if (resolvedArtists == artists) this else copy(artists = resolvedArtists)
}

/**
 * Secondary line for external consumers such as the OEM dynamic island, the lock screen and
 * Android Auto. Reads a cached value because media items are built per queued song.
 */
fun MediaMetadata.dynamicIslandSubtitle(): String? {
    val artists = artists.joinToString(", ") { it.name }.trim()
    val albumTitle = album?.title?.trim().orEmpty()
    return when (DynamicIslandSettings.subtitle) {
        DynamicIslandSubtitle.ARTIST -> artists.ifBlank { null }
        DynamicIslandSubtitle.ALBUM -> albumTitle.ifBlank { null }
        DynamicIslandSubtitle.ARTIST_ALBUM -> listOf(artists, albumTitle).filter { it.isNotBlank() }
            .joinToString(" • ").ifBlank { null }

        DynamicIslandSubtitle.DURATION -> duration.toLong()
            .takeIf { it > 0 }
            ?.let(::makeTimeString)
            ?.takeIf { it.isNotBlank() }

        DynamicIslandSubtitle.NONE -> null
    }
}

fun MediaItem.withUpdatedMetadata(updatedMetadata: MediaMetadata): MediaItem {
    val resolvedMetadata = updatedMetadata.withResolvedArtistNameAliases()
    val subtitle = resolvedMetadata.dynamicIslandSubtitle()
    return buildUpon()
        .setTag(resolvedMetadata)
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setTitle(resolvedMetadata.title)
                .setDisplayTitle(resolvedMetadata.title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(resolvedMetadata.thumbnailUrl?.toUri())
                .setAlbumTitle(resolvedMetadata.album?.title)
                .setAlbumArtist(resolvedMetadata.artists.firstOrNull()?.name)
                .setExtras(Bundle().apply {
                    resolvedMetadata.thumbnailUrl?.let { putString("artwork_uri", it) }
                    if (resolvedMetadata.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                })
                .build(),
        ).build()
}
