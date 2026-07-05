package org.jellyfin.mobile.player.source

import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.io.IOException

/**
 * Aggressively pre-downloads every external subtitle track of a [JellyfinMediaSource] into a
 * dedicated read-write cache as soon as playback is prepared. Because the subtitle sidecars read
 * from the same cache, selecting a subtitle track later resolves instantly from disk instead of
 * triggering a fresh network fetch.
 *
 * Each track is warmed on its own coroutine so a slow or failing track never blocks the others,
 * and a new [preload] call cancels any in-flight warming from a previous media source.
 */
class SubtitlePreloader(
    private val apiClient: ApiClient,
    private val cacheDataSourceFactory: CacheDataSource.Factory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun preload(source: JellyfinMediaSource) {
        cancel()

        val streams = source.externalSubtitleStreams
        if (streams.isEmpty()) return

        Timber.d("Preloading %d external subtitle track(s)", streams.size)
        for (stream in streams) {
            scope.launch {
                val uri = apiClient.createUrl(stream.deliveryUrl).toUri()
                try {
                    val dataSource = cacheDataSourceFactory.createDataSource()
                    CacheWriter(dataSource, DataSpec(uri), null, null).cache()
                    Timber.d("Preloaded subtitle: %s", stream.displayTitle)
                } catch (e: IOException) {
                    Timber.w(e, "Failed to preload subtitle: %s", stream.displayTitle)
                }
            }
        }
    }

    fun cancel() {
        scope.coroutineContext[Job]?.cancelChildren()
    }
}
