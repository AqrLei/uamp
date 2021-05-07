package com.example.android.uamp.media.library

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.annotation.WorkerThread
import com.example.android.uamp.media.extensions.album
import com.example.android.uamp.media.extensions.albumArtUri
import com.example.android.uamp.media.extensions.artist
import com.example.android.uamp.media.extensions.displayDescription
import com.example.android.uamp.media.extensions.displayIconUri
import com.example.android.uamp.media.extensions.displaySubtitle
import com.example.android.uamp.media.extensions.displayTitle
import com.example.android.uamp.media.extensions.downloadStatus
import com.example.android.uamp.media.extensions.duration
import com.example.android.uamp.media.extensions.flag
import com.example.android.uamp.media.extensions.id
import com.example.android.uamp.media.extensions.mediaUri
import com.example.android.uamp.media.extensions.title
import com.example.android.uamp.media.extensions.trackNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private const val ALBUM_ART_CONTENT_URI = "content://media/external/audio/albumart"

class LocalMediaSource(private val contentResolver: ContentResolver) : AbstractMusicSource() {
    private var catalog: List<MediaMetadataCompat> = emptyList()


    init {
        state = STATE_INITIALIZING
    }

    override fun iterator(): Iterator<MediaMetadataCompat> = catalog.iterator()

    override suspend fun load() {
        updateCatalog()?.let { updateCatalog ->
            catalog = updateCatalog
            state = STATE_INITIALIZED
        } ?: run {
            catalog = emptyList()
            state = STATE_ERROR
        }
    }

    private suspend fun updateCatalog(): List<MediaMetadataCompat>? {

        return queryAllAudio(contentResolver) { cursor ->
            val resultList = ArrayList<MediaMetadataCompat>()

            while (cursor.moveToNext()) {
                resultList.add(
                    MediaMetadataCompat.Builder().from(cursor)
                        .build()
                )
            }
            resultList
        }

    }

    @WorkerThread
    suspend fun <T> queryAllAudio(resolver: ContentResolver, block: (cursor: Cursor) -> T): T? {
        return queryMedia(
            resolver,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            null,
            "${MediaStore.Audio.Media.DURATION} >= ?",
            arrayOf(TimeUnit.MILLISECONDS.convert(2, TimeUnit.MINUTES).toString()),
            null,
            null,
            null,
            null,
            block
        )
    }

    /**
     * @param projection arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
     * @param selection "${MediaStore.Video.Media.DURATION} >= ?"
     * @param selectionArgs arrayOf(TimeUnit.MILLISECONDS.convert(5, TimeUnit.MINUTES).toString())
     * @param limit
     * @param offset
     */
    @WorkerThread
    suspend fun <T> queryMedia(
        resolver: ContentResolver,
        @RequiresPermission.Read uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        limit: Int?,
        offset: Int?,
        cancellationSignal: CancellationSignal?,
        block: (cursor: Cursor) -> T
    ): T? {
        var result: T? = null
        withContext(Dispatchers.IO) {

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val limitOrder = if (limit != null || offset != null) {
                    "LIMIT ${limit ?: -1} ${offset?.let { "OFFSET $it" } ?: ""}"
                } else ""
                resolver.query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    "$sortOrder  $limitOrder",
                    cancellationSignal
                )?.use {
                    result = block(it)
                }
            } else {
                val bundle =
                    createSqlQueryBundle(selection, selectionArgs, sortOrder, limit, offset)
                resolver.query(uri, projection, bundle, cancellationSignal)?.use {
                    result = block(it)
                }
            }

        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun createSqlQueryBundle(
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        limit: Int?,
        offset: Int?
    ): Bundle? {
        if (selection == null && selectionArgs == null && sortOrder == null && limit == null && offset == null) {
            return null
        }
        val queryArgs = Bundle()
        if (selection != null) {
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        }
        if (selectionArgs != null) {
            queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        }
        if (sortOrder != null) {
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
        }

        if (limit != null) {
            queryArgs.putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }

        if (offset != null) {
            queryArgs.putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }

        return queryArgs
    }

}

fun MediaMetadataCompat.Builder.from(cursor: Cursor): MediaMetadataCompat.Builder {
    try {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val artistIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
        val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
// API 30       val genre = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
//        val mediaUri =
        val trackNumberColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)




        id = cursor.getLong(idColumn).toString()
        val songDisplayName = "${cursor.getString(titleColumn)}(${cursor.getString(displayNameColumn)})"
        title = songDisplayName
        artist = cursor.getString(artistColumn)


        album = cursor.getString(pathColumn)?.takeIf { it.isNotEmpty() }?.let {
            val index = it.lastIndexOf(File.separatorChar)
            val parent = it.substring(0, index)
            val parentIndex = parent.lastIndexOf(File.separatorChar)
            parent.substring(parentIndex + 1)
        } ?: cursor.getString(albumColumn)

        duration = cursor.getLong(durationColumn)
//        genre =
        mediaUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cursor.getLong(idColumn)
        ).toString()
        val imageUri = ContentUris.withAppendedId(
            Uri.parse(ALBUM_ART_CONTENT_URI),
            cursor.getLong(albumIdColumn)
        ).toString()
        albumArtUri = imageUri


        trackNumber = cursor.getLong(trackNumberColumn)
//        trackCount = jsonMusic.totalTrackCount


        MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI

        flag = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE


        displayTitle = songDisplayName
        displaySubtitle = cursor.getString(artistColumn)
        displayDescription = cursor.getString(albumColumn)
        displayIconUri = imageUri

        downloadStatus = MediaDescriptionCompat.STATUS_DOWNLOADED
    } catch (e: IllegalArgumentException) {
        // ignore
    }
    return this
}