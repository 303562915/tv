package com.tv.ball.presentation.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

class GestureHandler(
    private val activity: Activity,
    private val onSeek: (Long) -> Unit,
    private val onPlayPause: () -> Unit,
    private val onFullscreen: () -> Unit,
    private val onGestureUpdate: (GestureType, Float) -> Unit
) : GestureDetector.SimpleOnGestureListener() {
    
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    
    private var startX = 0f
    private var startY = 0f
    private var startBrightness = 0f
    private var startVolume = 0
    private var startPosition = 0L
    
    private var isAdjustingBrightness = false
    private var isAdjustingVolume = false
    private var isAdjustingProgress = false
    
    fun onTouchEvent(event: MotionEvent, view: View, currentPosition: Long, duration: Long): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startBrightness = getCurrentBrightness()
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                startPosition = currentPosition
                isAdjustingBrightness = false
                isAdjustingVolume = false
                isAdjustingProgress = false
            }
            
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - startX
                val deltaY = event.y - startY
                
                if (!isAdjustingBrightness && !isAdjustingVolume && !isAdjustingProgress) {
                    if (abs(deltaX) > abs(deltaY) && abs(deltaX) > 50) {
                        isAdjustingProgress = true
                    } else if (abs(deltaY) > 50) {
                        if (startX < view.width / 2) {
                            isAdjustingBrightness = true
                        } else {
                            isAdjustingVolume = true
                        }
                    }
                }
                
                when {
                    isAdjustingBrightness -> {
                        val brightnessChange = -deltaY / view.height
                        val newBrightness = (startBrightness + brightnessChange).coerceIn(0f, 1f)
                        setBrightness(newBrightness)
                        onGestureUpdate(GestureType.BRIGHTNESS, newBrightness)
                    }
                    
                    isAdjustingVolume -> {
                        val volumeChange = (-deltaY / view.height * maxVolume).toInt()
                        val newVolume = (startVolume + volumeChange).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                        onGestureUpdate(GestureType.VOLUME, newVolume.toFloat() / maxVolume)
                    }
                    
                    isAdjustingProgress -> {
                        val progressChange = (deltaX / view.width * duration * 0.5).toLong()
                        val newPosition = (startPosition + progressChange).coerceIn(0, duration)
                        onGestureUpdate(GestureType.PROGRESS, newPosition.toFloat() / duration)
                    }
                }
            }
            
            MotionEvent.ACTION_UP -> {
                if (isAdjustingProgress) {
                    val deltaX = event.x - startX
                    val progressChange = (deltaX / view.width * duration * 0.5).toLong()
                    val newPosition = (startPosition + progressChange).coerceIn(0, duration)
                    onSeek(newPosition)
                }
                
                isAdjustingBrightness = false
                isAdjustingVolume = false
                isAdjustingProgress = false
                onGestureUpdate(GestureType.NONE, 0f)
            }
        }
        
        return true
    }
    
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        onPlayPause()
        return true
    }
    
    override fun onDoubleTap(e: MotionEvent): Boolean {
        onFullscreen()
        return true
    }
    
    private fun getCurrentBrightness(): Float {
        val layoutParams = activity.window.attributes
        return if (layoutParams.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            try {
                Settings.System.getInt(
                    activity.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (e: Exception) {
                0.5f
            }
        } else {
            layoutParams.screenBrightness
        }
    }
    
    private fun setBrightness(brightness: Float) {
        val layoutParams = activity.window.attributes
        layoutParams.screenBrightness = brightness
        activity.window.attributes = layoutParams
    }
    
    enum class GestureType {
        NONE, BRIGHTNESS, VOLUME, PROGRESS
    }
}
