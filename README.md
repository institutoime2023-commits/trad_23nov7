# TRADUCTOR Android V0.6.2 — ETAPA 2B

Esta versión traslada el flujo de interpretación al servicio de primer plano para que el procesamiento no dependa del ciclo de vida de `MainActivity`.

## Pipeline de interpretación

1. `AudioRecord` captura el micrófono del teléfono a 16 kHz, mono.
2. Se forman ventanas de audio de 4 segundos con superposición de 0.7 segundos y filtro RMS.
3. Whisper.cpp (`ggml-tiny.en.bin`) reconoce el diálogo en inglés mediante JNI desde `TranslationForegroundService`.
4. El texto reconocido se envía a Google ML Kit Translation (inglés → español).
5. El servicio reproduce la traducción con Android TextToSpeech, priorizando voces españolas offline y respetando voz, velocidad y silencio configurados desde la pantalla.
6. El servicio usa `PARTIAL_WAKE_LOCK` y una notificación persistente con acción para detener.

`MainActivity` queda como interfaz, control del servicio y presentación de los eventos. No libera Whisper, ML Kit ni el TTS del servicio cuando la pantalla pasa a segundo plano o se destruye la Activity.

## Requisitos

- Permiso de micrófono concedido.
- Modelo Whisper `ggml-tiny.en.bin` descargado en el almacenamiento privado de la aplicación.
- Modelo inglés-español de ML Kit disponible. El servicio vuelve a comprobar/preparar el modelo al iniciar.
- Auriculares Bluetooth conectados si se desea escuchar la locución por Bluetooth.

## Prueba recomendada

1. Instalar la APK generada por GitHub Actions.
2. Abrir la aplicación y verificar que ambos modelos estén disponibles.
3. Conectar auriculares Bluetooth y probar la voz.
4. Iniciar **INICIAR TRADUCCIÓN CONTINUA** y reproducir diálogo claro en inglés cerca del micrófono del teléfono.
5. Confirmar que el cuadro de inglés se actualiza y luego aparece el español y se escucha la locución.
6. Apagar la pantalla durante 10 minutos y comprobar que continúa la locución.
7. Volver a la aplicación y comprobar que muestra el último texto recibido.
8. Detener desde el botón de la aplicación o desde **DETENER** en la notificación.

## Nota de validación

La revisión estática del código no sustituye la compilación Android ni una prueba física. La APK debe generarse primero con el workflow de GitHub Actions y probarse en el dispositivo objetivo antes de considerar esta etapa validada.


## V0.6.3 — Modo experimental de oraciones completas

Se añade un segundo botón independiente para diálogos de películas. El modo original de traducción continua conserva su ruta de captura y procesamiento V0.6.2. El nuevo modo agrupa audio hasta 800 ms de pausa, conserva 300 ms de pre-roll y limita cada segmento a 12 segundos con 800 ms de solapamiento. Ambos modos usan el servicio de primer plano y solo puede ejecutarse uno a la vez.


## V0.6.4 — Parámetros configurables del modo películas

El modo de reconocimiento de oraciones completas permite configurar desde la pantalla: sensibilidad RMS, pausa para cerrar oración (300–2500 ms), duración máxima del fragmento (6–20 s), audio previo (100–1000 ms), solapamiento (200–2000 ms) y duración mínima de diálogo (400–3000 ms). Los valores se guardan en el dispositivo y solo se aplican al modo películas. El modo de traducción continua original conserva sus parámetros y comportamiento.
