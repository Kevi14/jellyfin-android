package org.jellyfin.mobile.player.ui.libass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.media3.common.Player
import timber.log.Timber

/**
 * Transparent overlay that renders ASS/SSA subtitles via libass, composited in sync with the
 * player's current position. Sits on top of the video surface and replaces ExoPlayer's built-in
 * SubtitleView for ASS tracks (which only supports a limited subset of ASS styling).
 *
 * Usage: [setPlayer], then [setTrack] with the raw .ass bytes to show, or [clear] to hide.
 *
 * NOTE (first pass): compositing runs on the UI thread via [Choreographer]. libass reports
 * "unchanged" for most frames so this is cheap, but very busy karaoke subs on large frames may
 * warrant moving compositing to a render thread later.
 */
class LibassSubtitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val renderer = LibassRenderer()
    private var bitmap: Bitmap? = null
    private var player: Player? = null
    private var trackLoaded = false
    private var ticking = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            renderCurrentPosition()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setPlayer(player: Player?) {
        this.player = player
    }

    /** Loads a raw .ass/.ssa file and begins rendering. */
    fun setTrack(assData: ByteArray) {
        if (!renderer.init()) {
            Timber.w("libass native library unavailable; cannot render ASS subtitles")
            return
        }
        applyFrameSize()
        trackLoaded = renderer.setTrack(assData)
        if (trackLoaded) {
            visibility = VISIBLE
            startTicking()
        } else {
            Timber.w("libass failed to parse subtitle track")
        }
    }

    /** Optional fallback font (e.g. bundled in assets) used when a subtitle references no embedded font. */
    fun addFallbackFont(name: String, data: ByteArray) {
        if (renderer.init()) renderer.addFont(name, data)
    }

    fun clear() {
        trackLoaded = false
        stopTicking()
        bitmap?.eraseColor(0)
        visibility = GONE
        invalidate()
    }

    private fun renderCurrentPosition() {
        val bmp = bitmap ?: return
        val position = player?.currentPosition ?: return
        when (renderer.renderFrame(position, bmp)) {
            -1 -> Unit // unchanged, keep the last frame
            else -> invalidate() // cleared or rendered
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopTicking() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun applyFrameSize() {
        if (width <= 0 || height <= 0) return
        val current = bitmap
        if (current == null || current.width != width || current.height != height) {
            current?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        renderer.setFrameSize(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFrameSize()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (trackLoaded) bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTicking()
        renderer.release()
        bitmap?.recycle()
        bitmap = null
    }
}
