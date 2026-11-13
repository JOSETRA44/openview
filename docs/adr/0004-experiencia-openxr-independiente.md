# ADR-0004: Experiencia propia como cliente OpenXR

Estado: aceptada para el primer prototipo integrado (2026-11-12).

## Decisión

La entrada visible de OpenView vive en `dev.openview.runtime.HomeActivity`. Ofrece entrar
en el espacio, ver la cámara con objetos virtuales y configurar el visor. La experiencia
se implementa en otro APK, `dev.openview.experience`, usando exclusivamente el loader de
Khronos y OpenXR con OpenGL ES. No incluye cabeceras ni código privado de Monado.

La separación de procesos mantiene el contrato existente:

`experiencia → OpenXR → cliente IPC del runtime → servicio/compositor/drivers`.

El segundo APK no tiene icono de launcher; se abre desde OpenView. Ambos se distribuyen
como artefactos del mismo build y se firman con la clave de depuración ya existente.
Un instalador/distribución de producto podrá simplificar su entrega posteriormente.

## Cámara y seguimiento

Camera2 entrega un flujo local a SurfaceTexture, consumido en el hilo de render con su
contexto EGL activo. Se compone como fondo en ambas vistas OpenXR; los objetos virtuales
usan las poses y proyecciones del runtime. No se capturan archivos ni se transmite vídeo.

La experiencia no estima por su cuenta la pose del teléfono. Actualmente usa la orientación
del runtime; un backend posicional futuro se conecta por los puertos `xrt_*`/VIT y la app
recibe sus poses por OpenXR. El fondo monocular no es passthrough calibrado: faltan
intrínsecos/extrínsecos, profundidad, sincronización y reproyección de cámara. No hay
anclajes de objetos sobre superficies físicas.

El acceso a cámara se solicita al elegir ese modo. Salir o pasar a segundo plano libera
la cámara y la sesión. Denegar el permiso debe conservar el acceso al espacio virtual.

## Verificación

CI compila las dos ABIs del cliente y el runtime. `tools/device-smoke.py` verifica sesiones
y contadores en el teléfono; las capturas se inspeccionan por separado. Los contadores
no prueban corrección visual, precisión de pose, calibración, confort ni 6DoF.
