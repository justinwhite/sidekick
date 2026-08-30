package com.cloudcrm.app.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * State of the native voice recorder.
 */
data class VoiceRecorderState(
    val isRecording: Boolean = false,
    val durationSeconds: Int = 0,
    val error: String? = null
)

/**
 * Utility for recording native AAC audio notes for direct multimodal processing via Gemini API.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var timerJob: Job? = null

    private val _recorderState = MutableStateFlow(VoiceRecorderState())
    val recorderState: StateFlow<VoiceRecorderState> = _recorderState.asStateFlow()

    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        if (_recorderState.value.isRecording) return false

        return try {
            val audioDir = File(context.cacheDir, "audio_notes").apply { mkdirs() }
            val audioFile = File(audioDir, "crm_voice_${System.currentTimeMillis()}.m4a")
            currentAudioFile = audioFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(audioFile.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            _recorderState.value = VoiceRecorderState(isRecording = true, durationSeconds = 0)

            timerJob?.cancel()
            timerJob = coroutineScope.launch(Dispatchers.Main) {
                while (_recorderState.value.isRecording) {
                    delay(1000)
                    _recorderState.value = _recorderState.value.copy(
                        durationSeconds = _recorderState.value.durationSeconds + 1
                    )
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording: ${e.message}", e)
            _recorderState.value = VoiceRecorderState(isRecording = false, error = e.localizedMessage)
            cleanup()
            false
        }
    }

    fun stopRecording(): ByteArray? {
        timerJob?.cancel()
        timerJob = null

        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            _recorderState.value = VoiceRecorderState(isRecording = false)

            val file = currentAudioFile
            if (file != null && file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                Log.d(TAG, "Audio recorded successfully: ${bytes.size} bytes (${file.name})")
                file.delete()
                bytes
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording: ${e.message}", e)
            _recorderState.value = VoiceRecorderState(isRecording = false, error = e.localizedMessage)
            cleanup()
            null
        }
    }

    fun cancelRecording() {
        timerJob?.cancel()
        timerJob = null
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {}
        cleanup()
        _recorderState.value = VoiceRecorderState(isRecording = false)
    }

    private fun cleanup() {
        mediaRecorder = null
        currentAudioFile?.delete()
        currentAudioFile = null
    }
}
