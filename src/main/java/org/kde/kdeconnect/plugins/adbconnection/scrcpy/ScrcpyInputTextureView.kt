package org.kde.kdeconnect.plugins.adbconnection.scrcpy

import android.content.Context
import android.graphics.SurfaceTexture
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.TextureView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.min

class ScrcpyInputTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextureView(context, attrs, defStyleAttr) {

    interface InputCallbacks {
        fun handleKeyEvent(event: KeyEvent): Boolean
        fun handleCommitText(text: CharSequence): Boolean
        fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    }

    interface SurfaceCallback {
        fun onSurfaceReady(surface: android.view.Surface)
        fun onSurfaceDestroyed()
    }

    var inputCallbacks: InputCallbacks? = null
    var surfaceCallback: SurfaceCallback? = null
    private var commitTextEnabled = false
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var currentSurface: android.view.Surface? = null

    fun setCommitTextEnabled(enabled: Boolean) {
        commitTextEnabled = enabled
        isFocusable = enabled
        isFocusableInTouchMode = enabled
        if (enabled) requestFocus() else clearFocus()
    }

    fun setVideoDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0 && (width != videoWidth || height != videoHeight)) {
            videoWidth = width
            videoHeight = height
            surfaceTexture?.setDefaultBufferSize(width, height)
            requestLayout()
        }
    }

    init {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val s = android.view.Surface(st)
                currentSurface = s
                surfaceCallback?.onSurfaceReady(s)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                if (videoWidth > 0 && videoHeight > 0) {
                    st.setDefaultBufferSize(videoWidth, videoHeight)
                }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                currentSurface = null
                surfaceCallback?.onSurfaceDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val parentAspect = parentWidth.toFloat() / parentHeight.toFloat()

        val width: Int
        val height: Int
        if (videoAspect > parentAspect) {
            width = parentWidth
            height = (parentWidth / videoAspect).toInt()
        } else {
            height = parentHeight
            width = (parentHeight * videoAspect).toInt()
        }

        setMeasuredDimension(
            min(width, parentWidth),
            min(height, parentHeight),
        )
    }

    override fun onCheckIsTextEditor(): Boolean {
        return commitTextEnabled || super.onCheckIsTextEditor()
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyPreIme(keyCode, event)
        if (inputCallbacks?.handleKeyEvent(event) == true) return true
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!commitTextEnabled) return super.onCreateInputConnection(outAttrs)
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (inputCallbacks?.handleCommitText(text) == true) return true
                return super.commitText(text, newCursorPosition)
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (inputCallbacks?.handleDeleteSurroundingText(beforeLength, afterLength) == true) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.sendKeyEvent(event)
                if (inputCallbacks?.handleKeyEvent(event) == true) return true
                return super.sendKeyEvent(event)
            }
        }
    }
}
