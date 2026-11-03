package com.gambit.chess

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Short synthesised board sounds, so the app ships no audio assets. Plays off the main thread. */
class Sfx {
    enum class Kind { MOVE, CAPTURE, CHECK, END }

    private val worker = Executors.newSingleThreadExecutor()
    private val tracks = HashMap<Kind, AudioTrack>()

    fun play(kind: Kind) {
        worker.execute {
            runCatching {
                val track = tracks.getOrPut(kind) { build(kind) }
                track.stop()
                track.reloadStaticData()
                track.play()
            }
        }
    }

    private fun build(kind: Kind): AudioTrack {
        val noise = Random(7)
        fun wood(t: Double, low: Double, high: Double, gain: Double) = gain * (
            0.65 * sin(2 * PI * low * t) * exp(-t * 55) +
                0.35 * sin(2 * PI * high * t) * exp(-t * 120) +
                (if (t < 0.004) (noise.nextDouble() * 2 - 1) * 0.5 else 0.0)
            )
        fun bell(t: Double, f: Double) = 0.45 * (sin(2 * PI * f * t) + 0.4 * sin(2 * PI * f * 1.5 * t)) * exp(-t * 9)
        return when (kind) {
            Kind.MOVE -> synth(90) { wood(it, 430.0, 1180.0, 0.9) }
            Kind.CAPTURE -> synth(130) { wood(it, 300.0, 880.0, 1.0) + if (it > 0.035) wood(it - 0.035, 330.0, 950.0, 0.6) else 0.0 }
            Kind.CHECK -> synth(260) { wood(it, 430.0, 1180.0, 0.7) + bell(it, 990.0) * 0.6 }
            Kind.END -> synth(700) { bell(it, 659.0) + if (it > 0.16) bell(it - 0.16, 988.0) else 0.0 }
        }
    }

    private fun synth(ms: Int, wave: (Double) -> Double): AudioTrack {
        val rate = 44_100
        val n = rate * ms / 1000
        val pcm = ShortArray(n) { i ->
            val fadeOut = ((n - i) / (rate * 0.01)).coerceAtMost(1.0) // avoid a click at the end
            (wave(i.toDouble() / rate).coerceIn(-1.0, 1.0) * fadeOut * 26_000).toInt().toShort()
        }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
            .apply { write(pcm, 0, n) }
    }
}
