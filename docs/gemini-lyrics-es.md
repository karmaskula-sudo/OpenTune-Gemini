# Traducción de letras con Gemini

Copia de https://github.com/justshivv/Opentune, base `09713602090e232014ad264b154a9d9edead1763`.
Rama local: `feature/gemini-lyrics-es`. No se ha publicado ni enviado nada a upstream.

## Uso

1. Abre una canción con letra y pulsa **Traducción · Original**.
2. La primera vez, entra en **Configurar Gemini** e introduce la clave directamente en el teléfono. Pulsa **Guardar configuración**. La clave y el modelo sirven para todas las canciones.
3. Activa **Traducción automática** una vez para que cada canción nueva se traduzca al tener la vista de letras abierta. Conserva **Español** o **Bilingüe** para que siga activa. Seleccionar **Original** pausa las consultas automáticas.
4. Si prefieres iniciar cada consulta tú, deja el interruptor apagado y pulsa **Traducir al español**. **Volver a traducir** solicita una versión nueva. Cambiar de modo usa la traducción guardada cuando está disponible.
5. En **Configurar Gemini**, **Eliminar clave** borra la credencial local y **Borrar caché** elimina las traducciones guardadas.

La solicitud envía el texto de la letra a Google. Puede consumir cuota o generar cargos según la cuenta. El modo automático está apagado hasta que el usuario lo active y consulta primero la caché. Solo solicita traducciones con la vista de letras activa y la app en primer plano. Una solicitud fallida puede repetirse desde el botón.

Si una solicitud falla, la app conserva la letra original y señala el motivo en **Traducción**. Un rechazo de acceso o un límite de cuota pausa las solicitudes automáticas en todas las canciones y muestra los originales. La pausa persiste al volver a abrir la app. Si Google indica un plazo de espera, **Reintentar con Gemini** se habilita al terminar ese plazo. La pausa se levanta cuando ese reintento recibe una traducción completa de Gemini. Las traducciones guardadas siguen disponibles para volver a mostrarlas al levantarse la pausa.

El modelo inicial es `gemini-3.5-flash-lite`, el [reemplazo recomendado por Google](https://aistudio.google.com/docs/deprecations#gemini-2.5-flash-models) para `gemini-2.5-flash`. El modelo es configurable. Su disponibilidad con la cuenta del usuario no se ha comprobado. Un error 404 indica que debe seleccionarse un modelo disponible que soporte `generateContent` y salida JSON estructurada.

## Implementación

- Se conservan inicio, fin, voz, partes y desplazamiento de la letra original. Cada fila recibe un identificador, incluso las vacías y repetidas. Se rechazan respuestas con filas faltantes, duplicadas, desordenadas o añadidas.
- El modo Original mantiene la sincronización por palabra. Español y Bilingüe usan sincronización por línea, sin inventar tiempos por palabra. Las voces de fondo permanecen en el idioma original.
- Las letras sin tiempos siguen siendo letras sin sincronización.
- La caché identifica las líneas, idioma, modelo y versión del formato mediante SHA-256. Las diferencias de espacios o tiempos de la misma letra no fuerzan otra consulta. Los cambios de texto, de división en líneas o de modelo usan otra entrada. Se conservan las traducciones anteriores que aún existan en el dispositivo. El caché no tiene vencimiento automático.
- La clave se cifra con AES-GCM y una clave de Android Keystore. El archivo cifrado y la caché se guardan en `noBackupFilesDir`. La clave no se incorpora al APK, repositorio, URL, registros o preferencias generales. El diálogo impide capturas mediante la política de ventana segura y no restaura la clave en el estado de pantalla.
- El cliente usa HTTPS, cabecera `x-goog-api-key`, tiempo límite de 60 segundos y no sigue redirecciones. Los mensajes al usuario nunca muestran cuerpos de errores del servidor ni excepciones sin filtrar.
- Cambiar de canción o salir de la vista cancela la consulta y descarta el estado anterior. El código de traducción no controla ni detiene el reproductor.
- Se limita la entrada a 500 líneas y 30 000 caracteres y la respuesta a 1 000 000 bytes. Las respuestas incompletas o bloqueadas no se guardan. Si el disco no permite guardar, la traducción permanece disponible durante esa vista.

## Compilación

La variante debug usa `com.opentune.geminidev` y el nombre **OpenTune Gemini**, para coexistir con la app original. Las variantes release mantienen la configuración de upstream; para estas pruebas usa debug.

Con Java 21 y el SDK de Android configurados. Las pruebas que cargan InnerTubeX requieren Java 21:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PgeminiArm64Only=true
```

El proyecto requiere SDK 37, NDK 27.2.12479018 y CMake 3.22.1 según sus archivos de compilación.

Pruebas aisladas con Java 17, sin SDK ni clave:

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
