/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The voice satellite's conversation, as shown on screen: what phase it is in, what was heard
 * and what Home Assistant answered. [VoiceSatelliteService] publishes; hosts render a
 * [VoiceCardView]. All callbacks arrive on the main thread.
 */
object VoiceHub {
  enum class Phase {
    IDLE,
    LISTENING,
    THINKING,
    ANSWERING,
  }

  data class State(
      val phase: Phase = Phase.IDLE,
      val heard: String? = null,
      val answer: String? = null,
  )

  fun interface Listener {
    fun onVoiceState(state: State)
  }

  private val main = Handler(Looper.getMainLooper())
  private val listeners = CopyOnWriteArraySet<Listener>()
  private val inAppHosts = CopyOnWriteArraySet<Listener>()
  @Volatile
  var state = State()
    private set

  private val goIdle = Runnable { publish(State()) }

  fun listen(l: Listener) {
    listeners.add(l)
    main.post { l.onVoiceState(state) }
  }

  fun unlisten(l: Listener) {
    listeners.remove(l)
    inAppHosts.remove(l)
  }

  /**
   * A screen of Immortal's own that draws the card itself (the photo frame / screensaver, which
   * sit above app overlays). While one is attached the system overlay window stays hidden.
   */
  fun attachInApp(l: Listener) {
    inAppHosts.add(l)
    listen(l)
  }

  val hasInAppHost: Boolean
    get() = inAppHosts.isNotEmpty()

  fun listening() {
    publish(State(Phase.LISTENING))
  }

  fun thinking() {
    publish(state.copy(phase = Phase.THINKING))
  }

  fun heard(text: String) {
    publish(state.copy(phase = Phase.THINKING, heard = text))
  }

  fun answer(text: String) {
    publish(state.copy(phase = Phase.ANSWERING, answer = text))
  }

  /** The answer has played: leave the card up long enough to read, then clear it. */
  fun finished() {
    main.post {
      main.removeCallbacks(goIdle)
      main.postDelayed(goIdle, LINGER_MS)
    }
  }

  fun idle() {
    publish(State())
  }

  private fun publish(s: State) {
    main.post {
      main.removeCallbacks(goIdle)
      state = s
      listeners.forEach { it.onVoiceState(s) }
    }
  }

  private const val LINGER_MS = 7000L
}

/**
 * The conversation card: three pulsing dots while listening or thinking, then what you said
 * and the answer. Bottom-centre, dark and rounded so it reads over a photo.
 */
class VoiceCardView(context: Context) : LinearLayout(context), VoiceHub.Listener {
  private val dp = context.resources.displayMetrics.density
  private val dots = DotsView(context)
  private val status = text(20f, 0xCCFFFFFF.toInt(), bold = false)
  private val heard = text(28f, Color.WHITE, bold = true)
  private val answer = text(24f, 0xE6FFFFFF.toInt(), bold = false)

  init {
    orientation = VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    background =
        GradientDrawable().apply {
          cornerRadius = 28 * dp
          setColor(0xF0161618.toInt())
        }
    val pad = (32 * dp).toInt()
    setPadding(pad, (22 * dp).toInt(), pad, (24 * dp).toInt())
    val top = LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(dots, LayoutParams((58 * dp).toInt(), (18 * dp).toInt()))
      addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        leftMargin = (10 * dp).toInt()
      })
    }
    addView(top)
    addView(heard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = (8 * dp).toInt()
    })
    addView(answer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = (6 * dp).toInt()
    })
    visibility = GONE
    alpha = 0f
  }

  private fun text(sp: Float, color: Int, bold: Boolean) =
      TextView(context).apply {
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
      }

  override fun onVoiceState(state: VoiceHub.State) {
    if (state.phase == VoiceHub.Phase.IDLE) {
      dots.stop()
      animate().alpha(0f).setDuration(300).withEndAction { visibility = GONE }.start()
      return
    }
    status.setText(
        when (state.phase) {
          VoiceHub.Phase.LISTENING -> R.string.voice_listening
          VoiceHub.Phase.THINKING -> R.string.voice_thinking
          else -> R.string.voice_answering
        })
    heard.text = state.heard.orEmpty()
    heard.visibility = if (state.heard.isNullOrBlank()) GONE else VISIBLE
    answer.text = state.answer.orEmpty()
    answer.visibility = if (state.answer.isNullOrBlank()) GONE else VISIBLE
    if (state.phase == VoiceHub.Phase.ANSWERING) dots.stop() else dots.start()
    if (visibility != VISIBLE) {
      // Hosts that reorder their children (the photo frame's crossfading layers) could bury it.
      bringToFront()
      visibility = VISIBLE
      animate().alpha(1f).setDuration(200).start()
    }
  }

  /** Layout params for a card at the bottom centre of a [FrameLayout] host. */
  fun frameParams(): FrameLayout.LayoutParams =
      FrameLayout.LayoutParams(
              FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
          .apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (40 * dp).toInt()
          }

  init {
    maxCardWidth()
  }

  private fun maxCardWidth() {
    val w = context.resources.displayMetrics.widthPixels
    minimumWidth = (w * 0.42f).toInt()
    heard.maxWidth = (w * 0.78f).toInt()
    answer.maxWidth = (w * 0.78f).toInt()
  }

  /** Three dots that pulse in turn, the "I'm listening" cue. */
  private class DotsView(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val anim =
        ValueAnimator.ofFloat(0f, 3f).apply {
          duration = 1100
          repeatCount = ValueAnimator.INFINITE
          addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
          }
        }

    fun start() {
      if (!anim.isStarted) anim.start()
    }

    fun stop() {
      anim.cancel()
      phase = 0f
      invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
      val r = height / 2f
      val gap = (width - 6 * r) / 2f
      for (i in 0..2) {
        val d = kotlin.math.abs(phase - i - 0.5f)
        val lit = (1f - d.coerceAtMost(1f))
        paint.color = Color.argb((90 + 165 * lit).toInt(), 0x8A, 0xB4, 0xF8)
        canvas.drawCircle(r + i * (2 * r + gap), r, r * (0.7f + 0.3f * lit), paint)
      }
    }

    override fun onDetachedFromWindow() {
      anim.cancel()
      super.onDetachedFromWindow()
    }
  }
}

/**
 * Hosts the card in a system overlay window, so the conversation shows over any app, Immortal's
 * home included. Stays hidden while one of Immortal's own frame screens draws it (see
 * [VoiceHub.attachInApp]); needs the "display over other apps" grant the provisioning kit gives.
 */
class VoiceOverlayWindow(private val context: Context) : VoiceHub.Listener {
  private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private var card: VoiceCardView? = null

  fun start() = VoiceHub.listen(this)

  fun stop() {
    VoiceHub.unlisten(this)
    remove()
  }

  override fun onVoiceState(state: VoiceHub.State) {
    val show =
        state.phase != VoiceHub.Phase.IDLE &&
            !VoiceHub.hasInAppHost &&
            Settings.canDrawOverlays(context)
    if (!show) {
      card?.onVoiceState(VoiceHub.State())
      return
    }
    val c = card ?: add() ?: return
    c.onVoiceState(state)
  }

  private fun add(): VoiceCardView? {
    val c = VoiceCardView(context)
    val lp =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT)
            .apply {
              gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
              y = (40 * context.resources.displayMetrics.density).toInt()
            }
    return runCatching { wm.addView(c, lp) }.map { c.also { card = it } }.getOrNull()
  }

  private fun remove() {
    card?.let { c -> runCatching { wm.removeView(c) } }
    card = null
  }
}
