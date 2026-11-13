# OpenView Experience

Cliente OpenXR propio, separado del APK del runtime. No enlaza interfaces internas de
Monado ni reutiliza `hello_xr`: Android gestiona permisos/cámara/ciclo de vida y C++ dibuja
una escena estéreo con OpenGL ES a través del loader oficial de Khronos.

## Construir y abrir

El módulo raíz `:experience` genera `android/experience/build/outputs/apk/debug/experience-debug.apk`.
Se firma con la misma clave pública de depuración del runtime. Instalar ambos APK de la
misma compilación; abrir OpenView y elegir **Entrar al espacio** o **Ver mi entorno**.

Para pruebas:

```sh
adb shell am start -n dev.openview.experience/.MainActivity --ez camera false
adb shell am start -n dev.openview.experience/.MainActivity --ez camera true
adb logcat -s OpenViewXR:I
```

El modo inicial llega mediante el extra booleano `camera`. Cada lanzamiento crea una
actividad independiente; salir antes de iniciar otra prueba. No hay icono adicional en el
launcher: la entrada de producto está en el APK `dev.openview.runtime`.

## Controles

- **Volumen +:** centra el rumbo de la vista conservando el suelo horizontal.
- **Volumen −:** alterna espacio/cámara. Pide permiso de cámara la primera vez.
- **Atrás:** sale de la experiencia.
- Los botones Android son una alternativa si el runtime no cubre la actividad con su
  ventana de composición. Los botones físicos evitan depender de esa ventana.

Al pausar se solicita terminar la sesión y se cierra la cámara. La liberación de recursos
GL sucede en el hilo de render; al reanudar se crea una sesión nueva. No se bloquea el hilo
de interfaz esperando al compositor. Los errores de inicialización quedan en pantalla y
en logcat; no se reintentan indefinidamente. Una cámara que falla o deja de enviar frames
por más de 3 segundos vuelve al espacio, sin mantener una imagen congelada como vídeo vivo.

## Qué hace y qué no hace

La escena contiene un suelo cuadriculado, tres esculturas flotantes y un aro. Usa las poses,
FOV y tiempos predichos de `xrLocateViews` y `xrWaitFrame`, con swapchains por ojo. El runtime
se encarga del tracking y de la distorsión del visor. Con el driver Android actual el
seguimiento es de orientación, sin desplazamiento físico medido.

La cámara trasera se abre con Camera2 y entrega frames a una `SurfaceTexture` consumida
en el mismo contexto EGL que renderiza el cliente. Se compone un fondo de cámara común
para ambos ojos, más los objetos virtuales. **No es passthrough estéreo calibrado**, ni
hay profundidad, oclusión real, detección de superficies o anclajes al mundo. El FOV del
vídeo no está calibrado al visor y el modo no compensa la latencia de la cámara. Es una
primera integración funcional para ver cámara y contenido virtual juntos.

El vídeo conserva su proporción mediante un recorte centrado para cada ojo, aplicado antes
de la transformación de `SurfaceTexture`; no se estira la imagen a la proporción del visor.
Camera2 escribe la orientación del sensor en la transformación de `SurfaceTexture`. Por
eso se aplica **solo la rotación de pantalla restante**, antes de esa matriz, y se calcula
la proporción final a partir de las dimensiones, `SENSOR_ORIENTATION` y `Display.rotation`.
La rotación de muestreo es `-displayRotation` en coordenadas GL (Y hacia arriba), equivalente a la
rotación de vista `-displayRotation` de Android (Y hacia abajo). Se desactiva el recorte/rotación
automático del HAL cuando admite `SCALER_ROTATE_AND_CROP_NONE`. El log registra orientación
del sensor, pantalla, corrección y matriz de productor al primer frame. El test de host
`tests/camera_uv_test.cpp` comprueba cuatro rotaciones mediante correspondencias de esquinas,
centro y límites de recorte; la verificación visual de otros dispositivos sigue pendiente.

## Verificación y telemetría

Contrato de log a nivel INFO, etiqueta `OpenViewXR`:

```text
OpenXR initialized; stereo=2 GLES=… eye=…
session READY
frames=120 cameraFrames=60 mode=camera
view recentered
session STOPPING
session closed frames=…
```

`frames` cuenta frames estéreo enviados; `cameraFrames` cuenta callbacks recibidos de la
cámara (no todos tienen por qué mostrarse). Se emite una línea aproximadamente cada segundo.
No se guardan imágenes ni vídeo. La ausencia de fallos en los logs no verifica por sí sola
la orientación visual de la cámara ni la comodidad del visor: eso requiere inspección.

Validación inicial de desarrollo: C++ pasó `clang++ -fsyntax-only` para Android arm64 con
NDK 27 y headers Khronos locales. Compilación completa, instalación y pruebas de dispositivo
se registran en el informe de integración del repositorio; no se presuponen aquí.

## Dependencias y referencias

- Loader oficial fijado a `org.khronos.openxr:openxr_loader_for_android:1.1.36`, importado
  por Prefab como `OpenXR::openxr_loader`; la app solicita OpenXR 1.0.
- [SDK y changelog oficial de Khronos](https://github.com/KhronosGroup/OpenXR-SDK/blob/main/CHANGELOG.SDK.md).
- [Inicialización Android del loader](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrLoaderInitInfoAndroidKHR.html).
- [Binding OpenGL ES Android](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrGraphicsBindingOpenGLESAndroidKHR.html).
- [Transformación de cámara ya presente en la Surface](https://developer.android.com/reference/androidx/camera/core/SurfaceRequest.TransformationInfo#hasCameraTransform()).
- [Rotación de pantalla sobre preview orientado al dispositivo natural](https://developer.android.com/codelabs/android-camera2-preview).

El loader se distribuye bajo Apache-2.0 o MIT; el código propio usa BSL-1.0.
