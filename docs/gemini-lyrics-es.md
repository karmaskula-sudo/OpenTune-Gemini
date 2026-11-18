# Traducción manual de letras con Gemini

Copia de https://github.com/justshivv/Opentune, base `09713602090e232014ad264b154a9d9edead1763`.
Rama local: `feature/gemini-lyrics-es`. No se ha publicado ni enviado nada a upstream.

## Uso

1. Abre una canción con letra y pulsa **Traducción · Original**.
2. Introduce tu clave directamente en el teléfono. Puedes cambiar el identificador del modelo. Pulsa **Guardar configuración**.
3. Pulsa **Traducir al español**. Este es el único paso que puede iniciar una consulta a Gemini.
4. Alterna **Original**, **Español** y **Bilingüe** en ese mismo diálogo. Si existe caché, no se hace otra consulta. Puedes cerrar el diálogo durante la consulta.
5. **Eliminar clave** borra la credencial local. **Borrar caché** elimina las traducciones guardadas y vuelve al modo Original.

La solicitud envía el texto de la letra a Google. Puede consumir cuota o generar cargos según la cuenta. No se consulta automáticamente al abrir una canción. La lectura automática de caché no usa la red. No hay reintentos automáticos de traducción.

El modelo inicial es `gemini-2.5-flash`, configurable. Su disponibilidad con la cuenta del usuario no se ha comprobado. Un error 404 indica que debe seleccionarse un modelo disponible que soporte `generateContent` y salida JSON estructurada.

## Implementación

- Se conservan inicio, fin, voz, partes y desplazamiento de la letra original. Cada fila recibe un identificador, incluso las vacías y repetidas. Se rechazan respuestas con filas faltantes, duplicadas, desordenadas o añadidas.
- El modo Original mantiene la sincronización por palabra. Español y Bilingüe usan sincronización por línea, sin inventar tiempos por palabra. Las voces de fondo permanecen en el idioma original.
- Las letras sin tiempos siguen siendo letras sin sincronización.
- La caché identifica el contenido exacto, los límites de línea, idioma, modelo y versión del formato mediante SHA-256. Conserva como máximo 100 archivos; un cambio de modelo o letra usa otra entrada. Un archivo inválido se ignora.
- La clave se cifra con AES-GCM y una clave de Android Keystore. El archivo cifrado y la caché se guardan en `noBackupFilesDir`. La clave no se incorpora al APK, repositorio, URL, registros o preferencias generales. El diálogo impide capturas mediante la política de ventana segura y no restaura la clave en el estado de pantalla.
- El cliente usa HTTPS, cabecera `x-goog-api-key`, tiempo límite de 60 segundos y no sigue redirecciones. Los mensajes al usuario nunca muestran cuerpos de errores del servidor ni excepciones sin filtrar.
- Cambiar de canción o salir de la vista cancela la consulta y descarta el estado anterior. El código de traducción no controla ni detiene el reproductor.
- Se limita la entrada a 500 líneas y 30 000 caracteres y la respuesta a 1 000 000 bytes. Las respuestas incompletas o bloqueadas no se guardan. Si el disco no permite guardar, la traducción permanece disponible durante esa vista.

## Compilación

La variante debug usa `com.opentune.geminidev` y el nombre **OpenTune Gemini**, para coexistir con la app original. Las variantes release mantienen la configuración de upstream; para estas pruebas usa debug.

Con Java 17 y el SDK de Android configurados:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PuniversalOnly=true
```

El proyecto requiere SDK 37, NDK 27.2.12479018 y CMake 3.22.1 según sus archivos de compilación.

Pruebas aisladas sin SDK ni clave:

```powershell
.\gradlew.bat -p translation-tests test
```

Este proyecto auxiliar compila los archivos reales `LyricsTranslation.kt`, `GeminiLyricsStore.kt` y `Lyrics.kt`. Usa Kotlin 2.2.21, serialization 1.9.0, coroutines 1.10.2, OkHttp 4.12.0 y las clases de Android de Robolectric 14 para comprobación de tipos. No sustituye la compilación Android con las versiones de upstream y no ejecuta Android Keystore ni la interfaz Compose.

## Comprobación pendiente en teléfono

- Instalar el APK debug junto a la app original y verificar la reproducción.
- Guardar la clave, cerrar y volver a abrir la app; confirmar una traducción real y la eliminación de clave.
- Probar Original, Español y Bilingüe, desplazamiento y búsqueda temporal, canción nueva durante una consulta y letras no sincronizadas.
- Verificar uso de caché después de reiniciar y sin conexión, y borrado de caché.
- Revisar pantalla pequeña, orientación, accesibilidad, teclado y bloqueo de capturas del diálogo.
- Verificar errores de cuota, permisos, modelo inválido y red con el servicio o un entorno de pruebas.

No se ha usado ni solicitado la clave del usuario. El cifrado local limita la exposición en almacenamiento; no garantiza protección frente a un dispositivo comprometido.

## Referencias

- [Repositorio original](https://github.com/justshivv/Opentune)
- [Gemini generateContent](https://ai.google.dev/api/generate-content)
- [Salida estructurada de Gemini](https://ai.google.dev/gemini-api/docs/structured-output)
- [Claves de Gemini](https://ai.google.dev/gemini-api/docs/api-key)
