package com.orvex.traductor.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.orvex.traductor.MainActivity
import com.orvex.traductor.R
import com.orvex.traductor.audio.TtsBluetoothManager
import com.orvex.traductor.translation.TranslationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** Servicio propietario del pipeline completo: micrófono -> Whisper -> ML Kit -> TTS. */
class TranslationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pipelineJob: Job? = null
    private var captureJob: Job? = null
    private var processorJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var translator: TranslationManager? = null
    private var ttsManager: TtsBluetoothManager? = null
    @Volatile private var stopping = false
    @Volatile private var lastStatus = "Servicio listo."
    @Volatile private var lastEnglish = ""
    @Volatile private var lastSpanish = ""
    @Volatile private var lastRms = 0f

    init { System.loadLibrary("whisper_jni") }
    private external fun transcribeAudioNative(modelPath: String, audioData: FloatArray): String
    private external fun freeWhisperNative()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPipeline(); return START_NOT_STICKY }
            ACTION_QUERY_STATE -> {
                if (pipelineJob?.isActive == true) {
                    sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_STATUS, lastStatus).putExtra(EXTRA_RMS, lastRms)
                        .putExtra(EXTRA_ENGLISH, lastEnglish).putExtra(EXTRA_SPANISH, lastSpanish))
                } else stopSelf()
            }
            ACTION_SET_MUTE -> { ttsManager?.setMuted(intent.getBooleanExtra(EXTRA_MUTED, false)); stopIfIdle() }
            ACTION_SET_RATE -> { ttsManager?.setSpeechRate(intent.getFloatExtra(EXTRA_RATE, 1.25f)); stopIfIdle() }
            ACTION_SELECT_VOICE -> { ttsManager?.selectVoiceById(intent.getStringExtra(EXTRA_VOICE_ID) ?: ""); stopIfIdle() }
            ACTION_STOP_SPEECH -> { ttsManager?.stopSpeaking(); stopIfIdle() }
            ACTION_TEST_SPEECH -> { ttsManager?.speak("Modo intérprete de películas listo en tus auriculares."); stopIfIdle() }
            ACTION_START, ACTION_START_MOVIE -> {
                if (pipelineJob?.isActive == true) return START_NOT_STICKY
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    sendStatus("Error: falta el permiso de micrófono."); stopSelf(); return START_NOT_STICKY
                }
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(NOTIFICATION_ID, notification)
                acquireCpuLock()
                val threshold = intent.getFloatExtra(EXTRA_RMS_THRESHOLD, 0.012f)
                val rate = intent.getFloatExtra(EXTRA_RATE, 1.25f)
                val muted = intent.getBooleanExtra(EXTRA_MUTED, false)
                val voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: ""
                val movieSentenceMode = intent.action == ACTION_START_MOVIE
                val moviePauseMs = intent.getIntExtra(EXTRA_MOVIE_PAUSE_MS, 800)
                val movieSegmentMs = intent.getIntExtra(EXTRA_MOVIE_SEGMENT_MS, 12000)
                val moviePreRollMs = intent.getIntExtra(EXTRA_MOVIE_PREROLL_MS, 300)
                val movieOverlapMs = intent.getIntExtra(EXTRA_MOVIE_OVERLAP_MS, 800)
                val movieMinSpeechMs = intent.getIntExtra(EXTRA_MOVIE_MIN_SPEECH_MS, 1200)
                pipelineJob = serviceScope.launch {
                    initializeAndRun(threshold, rate, muted, voiceId, movieSentenceMode,
                        moviePauseMs, movieSegmentMs, moviePreRollMs, movieOverlapMs, movieMinSpeechMs)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun initializeAndRun(
        rmsThreshold: Float, speechRate: Float, muted: Boolean, voiceId: String, movieSentenceMode: Boolean,
        moviePauseMs: Int, movieSegmentMs: Int, moviePreRollMs: Int, movieOverlapMs: Int, movieMinSpeechMs: Int
    ) {
        val modelFile = File(filesDir, "ggml-tiny.en.bin")
        if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
            sendStatus("Error: no se encuentra el modelo Whisper. Descárgalo desde la pantalla principal.")
            stopPipeline(); return
        }
        sendStatus("Preparando modelo de traducción inglés-español...")
        translator = TranslationManager()
        val ready = suspendCancellableCoroutine<Boolean> { cont ->
            val activeTranslator = translator
            if (activeTranslator != null) {
                activeTranslator.downloadModel { ok, message ->
                    sendStatus(message)
                    if (cont.isActive) cont.resume(ok)
                }
            } else {
                if (cont.isActive) cont.resume(false)
            }
        }
        if (!ready || !serviceScope.isActive || stopping) {
            if (!stopping) sendStatus("No se pudo preparar el modelo de traducción.")
            stopPipeline(); return
        }
        ttsManager = TtsBluetoothManager(
            context = applicationContext,
            onTtsStatusChanged = { sendStatus(it) },
            onSpeakingStateChanged = { sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_SPEAKING, it)) },
            onBluetoothStatusChanged = { sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_BT_STATUS, it)) },
            onVoicesLoaded = { voices -> sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_VOICE_COUNT, voices.size)) }
        )
        ttsManager?.setSpeechRate(speechRate)
        ttsManager?.setMuted(muted)
        if (voiceId.isNotBlank()) ttsManager?.selectVoiceById(voiceId)
        if (movieSentenceMode) {
            startMovieSentencePipeline(modelFile, rmsThreshold, moviePauseMs, movieSegmentMs, moviePreRollMs, movieOverlapMs, movieMinSpeechMs)
        } else {
            startContinuousAudioPipeline(modelFile, rmsThreshold)
        }
    }

    // Modo original V0.6.2: se conserva sin cambios para evitar regresiones.
    private fun startContinuousAudioPipeline(modelFile: File, rmsThreshold: Float) {
        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { sendStatus("Error al preparar el micrófono ($minBuffer). "); stopPipeline(); return }
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, sampleRate * 2))
        } catch (e: Exception) { sendStatus("Error al abrir micrófono: ${e.message}"); stopPipeline(); return }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); sendStatus("AudioRecord no pudo inicializarse."); stopPipeline(); return }
        audioRecord = recorder
        val chunks = Channel<FloatArray>(capacity = 3)
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val windowSamples = sampleRate * 4
            val overlapSamples = (sampleRate * 0.7).toInt()
            val stepSamples = windowSamples - overlapSamples
            val ring = FloatArray(windowSamples)
            var filled = 0
            val buffer = ShortArray(1600)
            try {
                recorder.startRecording()
                sendStatus("Escuchando y transcribiendo continuamente. Puedes apagar la pantalla.")
                while (isActive && !stopping) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        var squares = 0.0
                        for (i in 0 until count) {
                            val sample = buffer[i] / 32768.0f
                            squares += sample * sample
                            if (filled < windowSamples) ring[filled++] = sample
                        }
                        val rms = sqrt(squares / count).toFloat()
                        lastRms = rms
                        sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_RMS, rms))
                        if (filled >= windowSamples) {
                            val window = ring.copyOf()
                            System.arraycopy(ring, stepSamples, ring, 0, overlapSamples)
                            filled = overlapSamples
                            val windowRms = sqrt(window.sumOf { (it * it).toDouble() } / window.size).toFloat()
                            if (windowRms >= rmsThreshold) {
                                if (chunks.trySend(window).isFailure) sendStatus("Procesamiento ocupado; se omitió un fragmento de audio.")
                            }
                        }
                    } else if (count < 0) { sendStatus("Error de lectura del micrófono: $count"); break }
                }
            } catch (e: Exception) { if (!stopping) sendStatus("Error de captura: ${e.message}") }
            finally { chunks.close(); try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: Exception) {} }
        }
        processorJob = serviceScope.launch(Dispatchers.Default) {
            var previousEnglish = ""
            var previousSpanish = ""
            try {
                for (chunk in chunks) {
                    if (stopping) break
                    sendStatus("Reconociendo diálogo con Whisper...")
                    val started = android.os.SystemClock.elapsedRealtime()
                    val english = transcribeAudioNative(modelFile.absolutePath, chunk).trim()
                    val latency = android.os.SystemClock.elapsedRealtime() - started
                    val cleaned = cleanWhisperText(english)
                    if (cleaned.isBlank()) { sendStatus("No se detectó diálogo claro (${latency} ms). Escuchando..."); continue }
                    val dedup = removeOverlapDuplicate(previousEnglish, cleaned)
                    if (dedup.isBlank()) continue
                    previousEnglish = cleaned
                    lastEnglish = dedup
                    sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_ENGLISH, dedup).putExtra(EXTRA_LATENCY, latency))
                    sendStatus("Traduciendo al español...")
                    val spanish = translateSuspend(dedup).trim()
                    if (spanish.isNotBlank() && !spanish.equals(previousSpanish, true)) {
                        previousSpanish = spanish
                        lastSpanish = spanish
                        sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_SPANISH, spanish).putExtra(EXTRA_LATENCY, latency))
                        ttsManager?.speak(spanish)
                        sendStatus("Traducción activa · último ciclo ${latency} ms")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { sendStatus("Error en Whisper/traducción: ${e.message}") }
        }
    }


    /** Modo películas configurable: los valores solo afectan a este segundo modo. */
    private fun startMovieSentencePipeline(
        modelFile: File, rmsThreshold: Float, pauseMs: Int, segmentMs: Int,
        preRollMs: Int, overlapMs: Int, minSpeechMs: Int
    ) {
        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { sendStatus("Error al preparar el micrófono ($minBuffer). "); stopPipeline(); return }
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, sampleRate * 2))
        } catch (e: Exception) { sendStatus("Error al abrir micrófono: ${e.message}"); stopPipeline(); return }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); sendStatus("AudioRecord no pudo inicializarse."); stopPipeline(); return }
        audioRecord = recorder
        val chunks = Channel<FloatArray>(capacity = 3)
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val frameSamples = 1600 // 100 ms a 16 kHz
            // La captura trabaja en bloques de 100 ms; redondeamos los tiempos a ese paso.
            val preRollFrames = ((preRollMs.coerceIn(100, 1000) + 99) / 100).coerceAtLeast(1)
            val silenceFramesToEnd = ((pauseMs.coerceIn(300, 2500) + 99) / 100).coerceAtLeast(3)
            val minSpeechSamples = (sampleRate * minSpeechMs.coerceIn(400, 3000) / 1000)
            val maxSegmentSamples = (sampleRate * segmentMs.coerceIn(6000, 20000) / 1000)
            val overlapSamples = (sampleRate * overlapMs.coerceIn(200, 2000) / 1000).coerceAtMost(maxSegmentSamples / 2)
            val preRoll = java.util.ArrayDeque<ShortArray>()
            val segment = ArrayList<Float>(maxSegmentSamples)
            val buffer = ShortArray(frameSamples)
            var speechStarted = false
            var silenceFrames = 0
            var speechSamples = 0

            fun appendFrame(frame: ShortArray) {
                for (value in frame) segment.add(value / 32768.0f)
            }
            fun resetSegment() {
                segment.clear(); preRoll.clear(); speechStarted = false; silenceFrames = 0; speechSamples = 0
            }
            try {
                recorder.startRecording()
                sendStatus("Modo películas: esperando pausas para reconocer oraciones completas. Puedes apagar la pantalla.")
                while (isActive && !stopping) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        var squares = 0.0
                        for (i in 0 until count) { val value = buffer[i] / 32768.0; squares += value * value }
                        val rms = sqrt(squares / count).toFloat()
                        lastRms = rms
                        sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_RMS, rms))
                        val frame = if (count == buffer.size) buffer.copyOf() else buffer.copyOf(count)

                        if (!speechStarted) {
                            preRoll.addLast(frame)
                            while (preRoll.size > preRollFrames) preRoll.removeFirst()
                            if (rms >= rmsThreshold) {
                                speechStarted = true
                                preRoll.forEach { appendFrame(it) }
                                speechSamples = segment.size
                                silenceFrames = 0
                            }
                        } else {
                            appendFrame(frame)
                            if (rms >= rmsThreshold) {
                                silenceFrames = 0
                                speechSamples += count
                            } else {
                                silenceFrames++
                            }

                            val reachedPause = silenceFrames >= silenceFramesToEnd && speechSamples >= minSpeechSamples
                            val reachedLimit = segment.size >= maxSegmentSamples
                            if (reachedPause || reachedLimit) {
                                if (speechSamples >= minSpeechSamples) {
                                    val audio = segment.toFloatArray()
                                    if (chunks.trySend(audio).isFailure) sendStatus("El reconocimiento está ocupado; se omitió una oración. Intenta reducir el ruido de fondo.")
                                }
                                if (reachedLimit && !reachedPause && segment.size > overlapSamples) {
                                    val tail = segment.takeLast(overlapSamples)
                                    segment.clear(); segment.addAll(tail)
                                    preRoll.clear(); speechStarted = true
                                    speechSamples = overlapSamples; silenceFrames = 0
                                } else {
                                    resetSegment()
                                }
                            }
                        }
                    } else if (count < 0) {
                        sendStatus("Error de lectura del micrófono: $count")
                        break
                    }
                }
            } catch (e: Exception) {
                if (!stopping) sendStatus("Error de captura en modo películas: ${e.message}")
            } finally {
                chunks.close()
                try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: Exception) {}
            }
        }

        processorJob = serviceScope.launch(Dispatchers.Default) {
            var previousEnglish = ""
            var previousSpanish = ""
            try {
                for (chunk in chunks) {
                    if (stopping) break
                    sendStatus("Reconociendo oración completa con Whisper...")
                    val started = android.os.SystemClock.elapsedRealtime()
                    val english = transcribeAudioNative(modelFile.absolutePath, chunk).trim()
                    val latency = android.os.SystemClock.elapsedRealtime() - started
                    val cleaned = cleanWhisperText(english)
                    if (cleaned.isBlank()) { sendStatus("No se detectó una oración clara (${latency} ms). Esperando el siguiente diálogo..."); continue }
                    val dedup = removeOverlapDuplicate(previousEnglish, cleaned)
                    if (dedup.isBlank()) continue
                    previousEnglish = cleaned
                    lastEnglish = dedup
                    sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_ENGLISH, dedup).putExtra(EXTRA_LATENCY, latency))
                    sendStatus("Traduciendo oración completa al español...")
                    val spanish = translateSuspend(dedup).trim()
                    if (spanish.isNotBlank() && !spanish.equals(previousSpanish, true)) {
                        previousSpanish = spanish
                        lastSpanish = spanish
                        sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_SPANISH, spanish).putExtra(EXTRA_LATENCY, latency))
                        ttsManager?.speak(spanish)
                        sendStatus("Modo películas activo · oración traducida · ${latency} ms")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { sendStatus("Error en reconocimiento de películas: ${e.message}") }
        }
    }

    private suspend fun translateSuspend(text: String): String = suspendCancellableCoroutine { cont ->
        val activeTranslator = translator
        if (activeTranslator != null) {
            activeTranslator.translateText(text) { result -> if (cont.isActive) cont.resume(result) }
        } else {
            if (cont.isActive) cont.resume("Traductor no disponible.")
        }
    }

    private fun cleanWhisperText(text: String): String {
        val t = text.trim(); if (t.isBlank()) return ""
        val lower = t.lowercase()
        if ((lower.startsWith("[") && lower.endsWith("]")) || (lower.startsWith("(") && lower.endsWith(")"))) return ""
        if (lower in setOf("you", "you.", "thank you.", "thanks for watching.")) return ""
        return t
    }

    private fun removeOverlapDuplicate(previous: String, current: String): String {
        if (previous.isBlank()) return current
        val prev = previous.lowercase().replace(Regex("[^a-z0-9\\s']"), "").split(Regex("\\s+")).filter { it.isNotBlank() }
        val raw = current.split(Regex("\\s+")).filter { it.isNotBlank() }
        val norm = raw.map { it.lowercase().replace(Regex("[^a-z0-9']"), "") }
        for (k in minOf(4, prev.size, norm.size) downTo 1) if (prev.takeLast(k) == norm.take(k)) return raw.drop(k).joinToString(" ")
        return current
    }

    private fun stopPipeline() {
        if (stopping) return
        stopping = true
        val jobsToFinish = listOfNotNull(captureJob, processorJob)
        captureJob?.cancel(); captureJob = null
        processorJob?.cancel(); processorJob = null
        pipelineJob?.cancel(); pipelineJob = null
        try { audioRecord?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } } catch (_: Exception) {}
        serviceScope.launch {
            jobsToFinish.joinAll()
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
            try { freeWhisperNative() } catch (_: Exception) { Log.w(TAG, "Whisper cleanup failed") }
            translator?.close(); translator = null
            ttsManager?.release(); ttsManager = null
            wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            sendStatus("Intérprete detenido.")
            stopSelf()
        }
    }

    private fun stopIfIdle() {
        if (pipelineJob?.isActive != true) stopSelf()
    }

    private fun acquireCpuLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:TranslationCpuLock").apply { setReferenceCounted(false); acquire() }
    }

    private fun sendStatus(value: String) {
        lastStatus = value
        sendEvent(Intent(ACTION_EVENT).putExtra(EXTRA_STATUS, value))
    }
    private fun sendEvent(intent: Intent) { intent.action = ACTION_EVENT; intent.setPackage(packageName); sendBroadcast(intent) }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TranslationForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Traductor simultáneo activo").setContentText("Escuchando · Whisper · Traducción · Voz española").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, "DETENER", stop).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Traductor simultáneo", NotificationManager.IMPORTANCE_LOW).apply { description = "Traducción simultánea en segundo plano"; setShowBadge(false) })
    }

    override fun onDestroy() {
        stopping = true
        pipelineJob?.cancel(); captureJob?.cancel(); processorJob?.cancel()
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { freeWhisperNative() } catch (_: Exception) {}
        translator?.close(); ttsManager?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.orvex.traductor.action.START_TRANSLATION_SERVICE"
        const val ACTION_START_MOVIE = "com.orvex.traductor.action.START_MOVIE_SENTENCE_SERVICE"
        const val ACTION_STOP = "com.orvex.traductor.action.STOP_TRANSLATION_SERVICE"
        const val ACTION_QUERY_STATE = "com.orvex.traductor.action.QUERY_TRANSLATION_STATE"
        const val ACTION_EVENT = "com.orvex.traductor.action.SERVICE_EVENT"
        const val ACTION_SET_MUTE = "com.orvex.traductor.action.SET_MUTE"
        const val ACTION_SET_RATE = "com.orvex.traductor.action.SET_RATE"
        const val ACTION_SELECT_VOICE = "com.orvex.traductor.action.SELECT_VOICE"
        const val ACTION_STOP_SPEECH = "com.orvex.traductor.action.STOP_SPEECH"
        const val ACTION_TEST_SPEECH = "com.orvex.traductor.action.TEST_SPEECH"
        const val EXTRA_STATUS = "status"; const val EXTRA_RMS = "rms"; const val EXTRA_RMS_THRESHOLD = "rms_threshold"
        const val EXTRA_MOVIE_PAUSE_MS = "movie_pause_ms"
        const val EXTRA_MOVIE_SEGMENT_MS = "movie_segment_ms"
        const val EXTRA_MOVIE_PREROLL_MS = "movie_preroll_ms"
        const val EXTRA_MOVIE_OVERLAP_MS = "movie_overlap_ms"
        const val EXTRA_MOVIE_MIN_SPEECH_MS = "movie_min_speech_ms"
        const val EXTRA_ENGLISH = "english"; const val EXTRA_SPANISH = "spanish"; const val EXTRA_LATENCY = "latency"
        const val EXTRA_SPEAKING = "speaking"; const val EXTRA_BT_STATUS = "bt_status"; const val EXTRA_VOICE_COUNT = "voice_count"
        const val EXTRA_MUTED = "muted"; const val EXTRA_RATE = "rate"; const val EXTRA_VOICE_ID = "voice_id"
        private const val CHANNEL_ID = "translation_foreground_channel"; private const val NOTIFICATION_ID = 6201; private const val TAG = "TranslatorService"
    }
}
