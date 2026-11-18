// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
package dev.openview.experience.test

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Runs a [GuidedTestProtocol] in real time: speaks each instruction, beeps on
 * every change of step and tells its [Listener] what to show and log. Main
 * thread only. The voice is a convenience; the test runs without it.
 */
class GuidedTestController(context: Context, private val listener: Listener) {
    interface Listener {
        /** A step began. [index] is -1 when the test ends or is cancelled. */
        fun onStep(index: Int, step: TestStep?)
        /** About five times a second while running. */
        fun onTick(step: TestStep, index: Int, count: Int, secondsLeft: Int)
        fun onFinished(cancelled: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private var steps: List<TestStep> = emptyList()
    private var startedAt = 0L
    private var current = -1
    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onVoice(status) }

    private fun onVoice(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS &&
            tts.setLanguage(Locale("es")) >= TextToSpeech.LANG_AVAILABLE
        Log.i(TAG, "guided test voice ${if (ttsReady) "ready" else "unavailable"}")
    }
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    } catch (e: RuntimeException) {
        null
    }

    val running: Boolean get() = current >= 0

    private val tick = object : Runnable {
        override fun run() {
            val position = GuidedTestProtocol.at(steps, SystemClock.elapsedRealtime() - startedAt)
            if (position == null) {
                finish(cancelled = false)
                return
            }
            if (position.index != current) enter(position.index)
            listener.onTick(steps[current], current, steps.size, position.secondsLeft)
            main.postDelayed(this, 200)
        }
    }

    fun start(protocol: List<TestStep> = GuidedTestProtocol.WALK) {
        if (running) return
        steps = protocol
        startedAt = SystemClock.elapsedRealtime()
        current = -1
        Log.i(TAG, "OV_TEST_BEGIN steps=${steps.size} seconds=${GuidedTestProtocol.totalSeconds(steps)}")
        main.post(tick)
    }

    fun cancel() {
        if (running) finish(cancelled = true)
    }

    fun shutdown() {
        cancel()
        tts.shutdown()
        tone?.release()
    }

    private fun enter(index: Int) {
        current = index
        val step = steps[index]
        val expected = step.expected?.let { "${it.x},${it.y},${it.z}" } ?: "any"
        Log.i(TAG, "OV_TEST_STEP index=$index id=${step.id} kind=${step.kind} seconds=${step.seconds} expected=$expected")
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        if (ttsReady) tts.speak(step.spoken, TextToSpeech.QUEUE_FLUSH, null, step.id)
        listener.onStep(index, step)
    }

    private fun finish(cancelled: Boolean) {
        main.removeCallbacks(tick)
        current = -1
        Log.i(TAG, if (cancelled) "OV_TEST_CANCEL" else "OV_TEST_END")
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        if (ttsReady) tts.speak(if (cancelled) "Prueba cancelada." else "Prueba terminada. Gracias.",
            TextToSpeech.QUEUE_FLUSH, null, "end")
        listener.onStep(-1, null)
        listener.onFinished(cancelled)
    }

    private companion object { const val TAG = "OpenViewXR" }
}
