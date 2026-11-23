package com.orvex.traductor

import android.Manifest
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.orvex.traductor.audio.SpanishVoiceOption
import com.orvex.traductor.audio.TtsBluetoothManager
import com.orvex.traductor.translation.TranslationManager
import com.orvex.traductor.service.TranslationForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MainActivity : ComponentActivity() {

    private val translationManager = TranslationManager()
    private var ttsManager: TtsBluetoothManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var serviceStatusCallback: ((String) -> Unit)? = null
    private var serviceRmsCallback: ((Float) -> Unit)? = null
    private var serviceSegmentCallback: ((String, String, Long) -> Unit)? = null
    private var pendingEnglish = ""
    private var lastSpeechRate = 1.25f
    private var lastMuted = false
    private var lastVoiceId = ""
    private var serviceSpeakingCallback: ((Boolean) -> Unit)? = null
    private var serviceReceiverRegistered = false
    private val serviceEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != TranslationForegroundService.ACTION_EVENT) return
            intent.getStringExtra(TranslationForegroundService.EXTRA_STATUS)?.let { serviceStatusCallback?.invoke(it) }
            if (intent.hasExtra(TranslationForegroundService.EXTRA_RMS)) serviceRmsCallback?.invoke(intent.getFloatExtra(TranslationForegroundService.EXTRA_RMS, 0f))
            if (intent.hasExtra(TranslationForegroundService.EXTRA_SPEAKING)) serviceSpeakingCallback?.invoke(intent.getBooleanExtra(TranslationForegroundService.EXTRA_SPEAKING, false))
            intent.getStringExtra(TranslationForegroundService.EXTRA_ENGLISH)?.let { english ->
                pendingEnglish = english
                serviceSegmentCallback?.invoke(english, "", intent.getLongExtra(TranslationForegroundService.EXTRA_LATENCY, 0L))
            }
            intent.getStringExtra(TranslationForegroundService.EXTRA_SPANISH)?.let { spanish ->
                serviceSegmentCallback?.invoke("", spanish, intent.getLongExtra(TranslationForegroundService.EXTRA_LATENCY, 0L))
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        ttsManager?.updateAudioRouteStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            var ttsStatus by remember { mutableStateOf("Inicializando TTS...") }
            var btStatus by remember { mutableStateOf("Comprobando salida de audio...") }
            var isSpeaking by remember { mutableStateOf(false) }
            serviceSpeakingCallback = { speaking -> isSpeaking = speaking }
            var availableVoices by remember { mutableStateOf<List<SpanishVoiceOption>>(emptyList()) }
            var selectedVoiceId by remember { mutableStateOf("") }

            DisposableEffect(Unit) {
                val manager = TtsBluetoothManager(
                    context = this@MainActivity,
                    onTtsStatusChanged = { ttsStatus = it },
                    onSpeakingStateChanged = { isSpeaking = it },
                    onBluetoothStatusChanged = { btStatus = it },
                    onVoicesLoaded = { voices ->
                        availableVoices = voices
                        voices.firstOrNull { it.isOffline }?.let {
                            selectedVoiceId = it.id
                        }
                    }
                )
                ttsManager = manager
                onDispose {
                    // El servicio de micrófono tiene ciclo de vida independiente de la pantalla.
                    manager.release()
                    ttsManager = null
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MainScreen(
                    ttsStatus = ttsStatus,
                    btStatus = btStatus,
                    isSpeaking = isSpeaking,
                    availableVoices = availableVoices,
                    selectedVoiceId = selectedVoiceId,
                    onSelectVoice = { option ->
                        selectedVoiceId = option.id
                        lastVoiceId = option.id
                        ttsManager?.selectVoice(option)
                        startService(Intent(this@MainActivity, TranslationForegroundService::class.java).setAction(TranslationForegroundService.ACTION_SELECT_VOICE).putExtra(TranslationForegroundService.EXTRA_VOICE_ID, option.id))
                    },
                    onSpeechRateChange = { rate ->
                        lastSpeechRate = rate
                        ttsManager?.setSpeechRate(rate)
                        startService(Intent(this@MainActivity, TranslationForegroundService::class.java).setAction(TranslationForegroundService.ACTION_SET_RATE).putExtra(TranslationForegroundService.EXTRA_RATE, rate))
                    },
                    onMuteToggle = { muted ->
                        lastMuted = muted
                        ttsManager?.setMuted(muted)
                        startService(Intent(this@MainActivity, TranslationForegroundService::class.java).setAction(TranslationForegroundService.ACTION_SET_MUTE).putExtra(TranslationForegroundService.EXTRA_MUTED, muted))
                    },
                    onStopSpeech = {
                        ttsManager?.stopSpeaking()
                        startService(Intent(this@MainActivity, TranslationForegroundService::class.java).setAction(TranslationForegroundService.ACTION_STOP_SPEECH))
                    },
                    onTestSpeech = {
                        ttsManager?.speak("Modo intérprete de películas listo en tus auriculares.")
                    },
                    onDownloadWhisper = { onStatus -> downloadWhisperModel(onStatus) },
                    onDownloadTranslation = { onStatus ->
                        translationManager.downloadModel { _, msg ->
                            onStatus(msg)
                        }
                    },
                    onStartContinuous = { rmsThreshold, onProgress, onSegment, onRmsUpdate ->
                        serviceStatusCallback = onProgress
                        serviceRmsCallback = onRmsUpdate
                        serviceSegmentCallback = onSegment
                        pendingEnglish = ""
                        val modelFile = File(filesDir, "ggml-tiny.en.bin")
                        if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
                            onProgress("Error: descarga primero el modelo Whisper.")
                            false
                        } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            onProgress("Se necesita permiso de micrófono. Autorízalo para iniciar.")
                            requestRequiredPermissions()
                            false
                        } else {
                            val serviceIntent = Intent(this@MainActivity, TranslationForegroundService::class.java)
                                .setAction(TranslationForegroundService.ACTION_START)
                                .putExtra(TranslationForegroundService.EXTRA_RMS_THRESHOLD, rmsThreshold)
                                .putExtra(TranslationForegroundService.EXTRA_RATE, lastSpeechRate)
                                .putExtra(TranslationForegroundService.EXTRA_MUTED, lastMuted)
                                .putExtra(TranslationForegroundService.EXTRA_VOICE_ID, lastVoiceId)
                            try {
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                true
                            } catch (e: Exception) {
                                onProgress("No se pudo iniciar el servicio: ${e.message}")
                                false
                            }
                        }
                    },
                    onStartMovieSentences = { rmsThreshold, pauseMs, segmentMs, preRollMs, overlapMs, minSpeechMs, onProgress, onSegment, onRmsUpdate ->
                        serviceStatusCallback = onProgress
                        serviceRmsCallback = onRmsUpdate
                        serviceSegmentCallback = onSegment
                        pendingEnglish = ""
                        val modelFile = File(filesDir, "ggml-tiny.en.bin")
                        if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
                            onProgress("Error: descarga primero el modelo Whisper.")
                            false
                        } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            onProgress("Se necesita permiso de micrófono. Autorízalo para iniciar.")
                            requestRequiredPermissions()
                            false
                        } else {
                            val serviceIntent = Intent(this@MainActivity, TranslationForegroundService::class.java)
                                .setAction(TranslationForegroundService.ACTION_START_MOVIE)
                                .putExtra(TranslationForegroundService.EXTRA_RMS_THRESHOLD, rmsThreshold)
                                .putExtra(TranslationForegroundService.EXTRA_MOVIE_PAUSE_MS, pauseMs)
                                .putExtra(TranslationForegroundService.EXTRA_MOVIE_SEGMENT_MS, segmentMs)
                                .putExtra(TranslationForegroundService.EXTRA_MOVIE_PREROLL_MS, preRollMs)
                                .putExtra(TranslationForegroundService.EXTRA_MOVIE_OVERLAP_MS, overlapMs)
                                .putExtra(TranslationForegroundService.EXTRA_MOVIE_MIN_SPEECH_MS, minSpeechMs)
                                .putExtra(TranslationForegroundService.EXTRA_RATE, lastSpeechRate)
                                .putExtra(TranslationForegroundService.EXTRA_MUTED, lastMuted)
                                .putExtra(TranslationForegroundService.EXTRA_VOICE_ID, lastVoiceId)
                            try {
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                true
                            } catch (e: Exception) {
                                onProgress("No se pudo iniciar el modo películas: ${e.message}")
                                false
                            }
                        }
                    },
                    onStopContinuous = {
                        stopBackgroundMicrophoneService()
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!serviceReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                serviceEventsReceiver,
                IntentFilter(TranslationForegroundService.ACTION_EVENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            serviceReceiverRegistered = true
            startService(Intent(this, TranslationForegroundService::class.java).setAction(TranslationForegroundService.ACTION_QUERY_STATE))
        }
    }

    override fun onStop() {
        if (serviceReceiverRegistered) {
            unregisterReceiver(serviceEventsReceiver)
            serviceReceiverRegistered = false
        }
        super.onStop()
    }

    private fun stopBackgroundMicrophoneService() {
        startService(Intent(this, TranslationForegroundService::class.java).setAction(TranslationForegroundService.ACTION_STOP))
        serviceSegmentCallback = null
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun downloadWhisperModel(onStatus: (String) -> Unit) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { onStatus("Descargando modelo Whisper...") }
                val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
                val file = File(filesDir, "ggml-tiny.en.bin")

                if (file.exists() && file.length() > 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        onStatus("Modelo Whisper listo (${file.length() / (1024 * 1024)} MB).")
                    }
                    return@launch
                }

                URL(modelUrl).openStream().use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) { onStatus("Modelo Whisper descargado con éxito.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("Error al descargar Whisper: ${e.message}") }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // El servicio de primer plano conserva el pipeline aunque esta Activity se destruya.
        ttsManager?.release()
        ttsManager = null
        translationManager.close()
    }

}

@Composable
fun MainScreen(
    ttsStatus: String,
    btStatus: String,
    isSpeaking: Boolean,
    availableVoices: List<SpanishVoiceOption>,
    selectedVoiceId: String,
    onSelectVoice: (SpanishVoiceOption) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onStopSpeech: () -> Unit,
    onTestSpeech: () -> Unit,
    onDownloadWhisper: ((String) -> Unit) -> Unit,
    onDownloadTranslation: ((String) -> Unit) -> Unit,
    onStartContinuous: (Float, (String) -> Unit, (String, String, Long) -> Unit, (Float) -> Unit) -> Boolean,
    onStartMovieSentences: (Float, Int, Int, Int, Int, Int, (String) -> Unit, (String, String, Long) -> Unit, (Float) -> Unit) -> Boolean,
    onStopContinuous: () -> Unit
) {
    var whisperStatus by remember { mutableStateOf("Comprobar estado") }
    var translationStatus by remember { mutableStateOf("Pendiente") }
    var recognizedHistory by remember { mutableStateOf("Aquí aparecerá el diálogo original en inglés...") }
    var translatedHistory by remember { mutableStateOf("Aquí aparecerá la traducción en español...") }
    var processingState by remember { mutableStateOf("Listo para iniciar modo continuo") }
    var lastLatencyMs by remember { mutableStateOf(0L) }
    var isRunning by remember { mutableStateOf(false) }
    var isMovieRunning by remember { mutableStateOf(false) }
    var isVoiceEnabled by remember { mutableStateOf(true) }
    var selectedRate by remember { mutableStateOf(1.25f) }
    var rmsThreshold by remember { mutableStateOf(0.012f) }
    val context = LocalContext.current
    val moviePrefs = remember { context.getSharedPreferences("movie_recognition_settings", android.content.Context.MODE_PRIVATE) }
    var movieRmsThreshold by remember { mutableStateOf(moviePrefs.getFloat("threshold", 0.006f)) }
    var moviePauseMs by remember { mutableStateOf(moviePrefs.getInt("pause_ms", 800)) }
    var movieSegmentMs by remember { mutableStateOf(moviePrefs.getInt("segment_ms", 12000)) }
    var moviePreRollMs by remember { mutableStateOf(moviePrefs.getInt("preroll_ms", 300)) }
    var movieOverlapMs by remember { mutableStateOf(moviePrefs.getInt("overlap_ms", 800)) }
    var movieMinSpeechMs by remember { mutableStateOf(moviePrefs.getInt("min_speech_ms", 1200)) }
    var currentRms by remember { mutableStateOf(0f) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(movieRmsThreshold, moviePauseMs, movieSegmentMs, moviePreRollMs, movieOverlapMs, movieMinSpeechMs) {
        moviePrefs.edit()
            .putFloat("threshold", movieRmsThreshold)
            .putInt("pause_ms", moviePauseMs)
            .putInt("segment_ms", movieSegmentMs)
            .putInt("preroll_ms", moviePreRollMs)
            .putInt("overlap_ms", movieOverlapMs)
            .putInt("min_speech_ms", movieMinSpeechMs)
            .apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "TRADUCTOR V0.6.4 · AJUSTES PARA PELÍCULAS", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Whisper, traducción inglés-español y voz española se ejecutan desde el servicio de primer plano, incluso con la pantalla apagada.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "AUDIO BLUETOOTH Y VOZ ESPAÑOLA", style = MaterialTheme.typography.titleMedium)
                Text(text = btStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(text = ttsStatus, style = MaterialTheme.typography.bodySmall)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Voz automática en auriculares", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = isVoiceEnabled,
                        onCheckedChange = { enabled ->
                            isVoiceEnabled = enabled
                            onMuteToggle(!enabled)
                        }
                    )
                }

                Text(text = "Velocidad de habla: ${selectedRate}x", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0f, 1.15f, 1.25f, 1.45f).forEach { rate ->
                        FilterChip(
                            selected = selectedRate == rate,
                            onClick = {
                                selectedRate = rate
                                onSpeechRateChange(rate)
                            },
                            label = { Text("${rate}x") }
                        )
                    }
                }

                if (availableVoices.isNotEmpty()) {
                    Box {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { voiceMenuExpanded = true }
                        ) {
                            val currentLabel = availableVoices.find { it.id == selectedVoiceId }?.label ?: "Seleccionar voz española"
                            Text(currentLabel)
                        }
                        DropdownMenu(
                            expanded = voiceMenuExpanded,
                            onDismissRequest = { voiceMenuExpanded = false }
                        ) {
                            availableVoices.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        onSelectVoice(option)
                                        voiceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = onTestSpeech) {
                        Text("PROBAR VOZ BT")
                    }
                    OutlinedButton(modifier = Modifier.weight(1f), enabled = isSpeaking, onClick = onStopSpeech) {
                        Text("CALLAR VOZ")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "SENSIBILIDAD DE MICRÓFONO (FILTRO SILENCIO)", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Umbral RMS: ${"%.3f".format(rmsThreshold)} | Nivel actual: ${"%.3f".format(currentRms)}",
                    style = MaterialTheme.typography.bodySmall
                )
                LinearProgressIndicator(
                    progress = { (currentRms * 10f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0.005f to "Alta (TV baja)",
                        0.012f to "Normal",
                        0.025f to "Baja (Ruido)"
                    ).forEach { (thresh, label) ->
                        FilterChip(
                            selected = rmsThreshold == thresh,
                            onClick = { rmsThreshold = thresh },
                            label = { Text(label) }
                        )
                    }
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning && !isMovieRunning,
                        onClick = { onDownloadWhisper { whisperStatus = it } }
                    ) {
                        Text("WHISPER")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !isRunning && !isMovieRunning,
                        onClick = { onDownloadTranslation { translationStatus = it } }
                    ) {
                        Text("TRADUCCIÓN")
                    }
                }
                Text(text = "Whisper: $whisperStatus | Traducción: $translationStatus", style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isMovieRunning || isRunning,
            colors = if (isRunning) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            onClick = {
                if (!isRunning) {
                    recognizedHistory = ""
                    translatedHistory = ""
                    isRunning = onStartContinuous(
                        rmsThreshold,
                        { status -> processingState = status },
                        { eng, esp, lat ->
                            lastLatencyMs = lat
                            if (eng.isNotBlank()) recognizedHistory = if (recognizedHistory.isBlank()) eng else (eng + "\n• " + recognizedHistory).take(600)
                            if (esp.isNotBlank()) translatedHistory = if (translatedHistory.isBlank()) esp else (esp + "\n• " + translatedHistory).take(600)
                        },
                        { rms -> currentRms = rms }
                    )
                } else {
                    isRunning = false
                    onStopContinuous()
                    processingState = "Intérprete detenido."
                }
            }
        ) {
            Text(if (isRunning) "DETENER TRADUCCIÓN" else "INICIAR TRADUCCIÓN CONTINUA")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning || isMovieRunning,
            colors = if (isMovieRunning) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            onClick = {
                if (!isMovieRunning) {
                    recognizedHistory = ""
                    translatedHistory = ""
                    isMovieRunning = onStartMovieSentences(
                        movieRmsThreshold, moviePauseMs, movieSegmentMs, moviePreRollMs, movieOverlapMs, movieMinSpeechMs,
                        { status -> processingState = status },
                        { eng, esp, lat ->
                            lastLatencyMs = lat
                            if (eng.isNotBlank()) recognizedHistory = if (recognizedHistory.isBlank()) eng else (eng + "\n• " + recognizedHistory).take(1200)
                            if (esp.isNotBlank()) translatedHistory = if (translatedHistory.isBlank()) esp else (esp + "\n• " + translatedHistory).take(1200)
                        },
                        { rms -> currentRms = rms }
                    )
                } else {
                    isMovieRunning = false
                    onStopContinuous()
                    processingState = "Reconocimiento de oraciones detenido."
                }
            }
        ) {
            Text(if (isMovieRunning) "DETENER RECONOCIMIENTO DE ORACIONES" else "RECONOCIMIENTO DE ORACIONES COMPLETAS")
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("AJUSTES DEL MODO PELÍCULAS", style = MaterialTheme.typography.titleSmall)
                Text("Sensibilidad del diálogo: ${"%.3f".format(movieRmsThreshold)}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.003f to "Alta", 0.006f to "Normal", 0.010f to "Menos ruido").forEach { (threshold, label) ->
                        FilterChip(selected = movieRmsThreshold == threshold, onClick = { movieRmsThreshold = threshold }, enabled = !isMovieRunning, label = { Text(label) })
                    }
                }
                HorizontalDivider()
                Text("Pausa para cerrar oración: ${moviePauseMs} ms", style = MaterialTheme.typography.bodySmall)
                Slider(value = moviePauseMs.toFloat(), onValueChange = { moviePauseMs = it.toInt() }, valueRange = 300f..2500f, steps = 21, enabled = !isMovieRunning)
                Text("Duración máxima del fragmento: ${movieSegmentMs / 1000f} s", style = MaterialTheme.typography.bodySmall)
                Slider(value = movieSegmentMs.toFloat(), onValueChange = { movieSegmentMs = it.toInt() }, valueRange = 6000f..20000f, steps = 13, enabled = !isMovieRunning)
                Text("Audio previo al diálogo: ${moviePreRollMs} ms", style = MaterialTheme.typography.bodySmall)
                Slider(value = moviePreRollMs.toFloat(), onValueChange = { moviePreRollMs = it.toInt() }, valueRange = 100f..1000f, steps = 8, enabled = !isMovieRunning)
                Text("Solapamiento entre fragmentos: ${movieOverlapMs} ms", style = MaterialTheme.typography.bodySmall)
                Slider(value = movieOverlapMs.toFloat(), onValueChange = { movieOverlapMs = it.toInt() }, valueRange = 200f..2000f, steps = 17, enabled = !isMovieRunning)
                Text("Duración mínima de diálogo: ${movieMinSpeechMs} ms", style = MaterialTheme.typography.bodySmall)
                Slider(value = movieMinSpeechMs.toFloat(), onValueChange = { movieMinSpeechMs = it.toInt() }, valueRange = 400f..3000f, steps = 25, enabled = !isMovieRunning)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !isMovieRunning, onClick = {
                        movieRmsThreshold = 0.006f; moviePauseMs = 800; movieSegmentMs = 12000
                        moviePreRollMs = 300; movieOverlapMs = 800; movieMinSpeechMs = 1200
                    }) { Text("RESTABLECER") }
                    Text("Los cambios se guardan automáticamente y solo afectan al modo películas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                }
            }
        }
        Text(
            text = "Ajusta los parámetros con el reconocimiento detenido. El modo continuo original conserva sus propios valores.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Text(
            text = if (lastLatencyMs > 0) "$processingState | Inferencia: ${lastLatencyMs} ms" else processingState,
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "DIÁLOGO ORIGINAL (INGLÉS)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = recognizedHistory.ifBlank { "Esperando diálogo en inglés..." },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "TRADUCCIÓN EN VIVO (ESPAÑOL)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = translatedHistory.ifBlank { "Esperando traducción..." },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
