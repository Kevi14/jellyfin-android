package org.jellyfin.mobile.player.ui.libass

import android.graphics.Bitmap

/**
 * Thin Kotlin wrapper over the native libass bridge (libjellyfin_ass.so).
 *
 * Not thread-safe: create and call from a single rendering thread (the UI thread,
 * driven by [LibassSubtitleView]). All methods are no-ops until [init] succeeds.
 */
class LibassRenderer {
    private var handle: Long = 0L

    val isInitialized: Boolean get() = handle != 0L

    fun init(): Boolean {
        if (handle != 0L) return true
        if (!ensureLibraryLoaded()) return false
        handle = nativeInit()
        return handle != 0L
    }

    fun setFrameSize(width: Int, height: Int) {
        if (handle != 0L) nativeSetFrameSize(handle, width, height)
    }

    fun addFont(name: String, data: ByteArray) {
        if (handle != 0L) nativeAddFont(handle, name, data)
    }

    fun setTrack(assData: ByteArray): Boolean = handle != 0L && nativeSetTrack(handle, assData)

    /**
     * Renders the subtitle state at [timeMs] into [bitmap].
     * @return -1 if unchanged since the last call, 0 if cleared (no subtitle now), 1 if rendered.
     */
    fun renderFrame(timeMs: Long, bitmap: Bitmap): Int =
        if (handle != 0L) nativeRenderFrame(handle, timeMs, bitmap) else -1

    fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeSetFrameSize(handle: Long, width: Int, height: Int)
    private external fun nativeAddFont(handle: Long, name: String, data: ByteArray)
    private external fun nativeSetTrack(handle: Long, data: ByteArray): Boolean
    private external fun nativeRenderFrame(handle: Long, timeMs: Long, bitmap: Bitmap): Int
    private external fun nativeRelease(handle: Long)

    companion object {
        @Volatile
        private var loaded = false

        /** Loads libjellyfin_ass.so once. Returns false if the native lib is missing for this ABI. */
        @Synchronized
        fun ensureLibraryLoaded(): Boolean {
            if (loaded) return true
            return try {
                System.loadLibrary("jellyfin_ass")
                loaded = true
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }
    }
}
