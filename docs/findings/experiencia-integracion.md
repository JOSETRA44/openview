# Integración de la experiencia OpenView

Registro iniciado: 2026-11-12. Se actualizará con resultados del build y del teléfono.

## Implementado

- Entrada propia `HomeActivity`, acceso al espacio y modo cámara, ajustes de visor existentes.
- Módulo cliente OpenXR independiente `android/experience`, con renderizado OpenGL ES.
- Compilación y artefacto del cliente en el workflow `build`.
- Prueba ADB reproducible `tools/device-smoke.py`, con contadores de frames, logs y captura.

## Estado de verificación

Pendiente de completar durante la integración: build CI, instalación y pruebas visuales,
continuidad del render y de la cámara, controles y ciclo de vida. No se consideran
verificados por el solo hecho de existir el código.

## Límites del hito

El seguimiento del teléfono sigue siendo 3DoF. La cámara se muestra como fondo monocular;
los objetos no están anclados al mundo físico. No se ha medido la latencia cámara-pantalla,
el error angular ni el confort con un visor puesto. Ver ADR-0004 para la separación de capas.
