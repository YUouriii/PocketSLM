package com.pocketllm.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Captures a mono 16 kHz 16-bit PCM clip and writes a WAV file that llama.cpp's
 * mtmd audio decoder understands.
 */
class VoiceRecorder(private val context: Context, private val directory: File) {

    data class Result(val file: File, val durationMs: Long)

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = java.util.ArrayList<Short>()
    private var outFile: File? = null
    @Volatile private var recordedSamples = 0L

    var isRecording = false
        private set

    val sampleRate = 16_000

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): File? {
        if (isRecording) return outFile
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }

        directory.mkdirs()
        val target = File(directory, "voice_${System.currentTimeMillis()}.wav")
        outFile = target
        pcm.clear()
        isRecording = true
        recorder = rec

        worker = thread(name = "voice-recorder", isDaemon = true) {
            rec.startRecording()
            val buf = ShortArray(minBuf)
            while (isRecording) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    pcm.ensureCapacity(pcm.size + n)
                    for (i in 0 until n) pcm.add(buf[i])
                    recordedSamples += n
                } else {
                    // Underrun; keep waiting for the next buffer.
                    Thread.sleep(10)
                }
            }
            rec.stop()
            rec.release()
            val samples = pcm.toShortArray()
            if (samples.isNotEmpty()) {
                writeWav(target, samples, sampleRate, samples.size)
            }
        }
        return target
    }

    fun stop(): Result? {
        if (!isRecording) return null
        isRecording = false
        worker?.join()
        worker = null
        recorder = null
        val durationMs = sampleCount * 1000L / sampleRate
        return Result(outFile ?: return null, durationMs)
    }

    val sampleCount: Long get() = recordedSamples

    val elapsedSeconds: Int get() = (sampleCount / sampleRate).toInt()

    private fun java.util.ArrayList<Short>.toShortArray(): ShortArray {
        val out = ShortArray(size)
        for (i in indices) out[i] = this[i]
        return out
    }

    private fun writeWav(file: File, samples: ShortArray, rate: Int, sampleCount: Int) {
        val dataSize = sampleCount * 2
        val totalSize = 36 + dataSize
        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray(Charsets.US_ASCII))
            fos.write(intLE(totalSize))
            fos.write("WAVE".toByteArray(Charsets.US_ASCII))
            fos.write("fmt ".toByteArray(Charsets.US_ASCII))
            fos.write(intLE(16)) // PCM chunk size
            fos.write(shortLE(1)) // PCM format
            fos.write(shortLE(1)) // mono
            fos.write(intLE(rate))
            fos.write(intLE(rate * 2)) // byte rate
            fos.write(shortLE(2)) // block align
            fos.write(shortLE(16)) // bits per sample
            fos.write("data".toByteArray(Charsets.US_ASCII))
            fos.write(intLE(dataSize))
            val buf = ByteArray(4096)
            var i = 0
            while (i < samples.size) {
                val n = minOf(samples.size - i, 2048)
                for (j in 0 until n) {
                    val v = samples[i + j].toInt() and 0xFFFF
                    buf[j * 2] = (v and 0xFF).toByte()
                    buf[j * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                fos.write(buf, 0, n * 2)
                i += n
            }
        }
    }

    private fun intLE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun shortLE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )
}
