package com.example.infinitetodo

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

class AudioRecorderHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var currentRecordFile: File? = null

    fun startRecording(): String? {
        val file = File(context.cacheDir, "voice_memo_${System.currentTimeMillis()}.mp4")
        currentRecordFile = file

        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            try {
                prepare()
                start()
            } catch (e: IOException) {
                return null
            }
        }
        return file.absolutePath
    }

    fun stopRecording(): String? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            currentRecordFile?.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun playAudio(filePath: String, onFinished: () -> Unit) {
        stopPlayback()
        player = MediaPlayer().apply {
            try {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener {
                    onFinished()
                }
                start()
            } catch (_: Exception) {
                onFinished()
            }
        }
    }

    fun stopPlayback() {
        try {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
            player = null
        } catch (_: Exception) {}
    }
}
