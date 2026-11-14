# Roadmap

Visión: una plataforma de computación espacial abierta comparable en experiencia a un
visor comercial como Meta Quest, construida sobre OpenXR y Monado, que arranca en
teléfonos y puede llegar a hardware propio sin reescribir el sistema.

Este documento es el plan. El estado verificado de cada pieza vive en
[docs/findings/estado-de-verificacion.md](docs/findings/estado-de-verificacion.md);
aquí no se afirma nada que allí no esté respaldado.

## Dónde estamos (2026-11-14)

- Runtime derivado de Monado funcionando en Linux y Android, con CI en verde.
- Temporización de pantalla resuelta y medida en un teléfono a 60 Hz
  ([ADR-0002](docs/adr/0002-temporizacion-de-pantalla-en-android.md)).
- Seguimiento en teléfono: 3DoF. El 6DoF es el bloqueante principal.
- Sin capa de producto todavía.

## Fase A — Organización del repositorio

- Limpiar artefactos versionados por error (logs de JVM, `__pycache__`) y completar
  `.gitignore` para que no vuelvan.
- README y estado alineados con lo verificado; este roadmap enlazado.
- Descripción, topics e issues en GitHub que reflejen las fases siguientes.

## Fase B — Cerrar la decisión de 6DoF en teléfono ([ADR-0003](docs/adr/0003-seguimiento-6dof-con-una-camara.md))

Experimentos que faltan para decidir el backend monocular:

1. Ejecutar OKVIS2 con una cámara sobre las mismas secuencias y medir su error.
2. Leer cómo Basalt crea puntos nuevos y estimar el coste de extenderlo a monocular.
3. Medir con una secuencia con rolling shutter y referencia (p. ej. TUM RS-VI) cuánto
   degrada ignorar los 29,4 ms de lectura medidos en el dispositivo de referencia.
4. Calibración de cámara por dispositivo y repetición del modelo de latencia a
   90/120 Hz y en un segundo SoC.

Salida de la fase: ADR-0003 cerrada con mediciones, no con opiniones.

## Fase C — Primera experiencia real comparable

- Una aplicación real (Godot o StereoKit) corriendo sobre OpenView, no solo
  `hello_xr`.
- Medición de error angular y latencia percibida, además de la de presentación.
- Prueba térmica con una aplicación exigente.

## Fase D — Capa de producto mínima

Lo que un usuario percibe como «un visor», en este orden:

1. Home / launcher OpenXR propio para abrir aplicaciones.
2. Boundary estacionario (seguridad) y aviso al salir de la zona.
3. Passthrough experimental desde la cámara.
4. Entrada sin mandos: primero puntero por mirada/cabeza; el seguimiento de manos
   queda como investigación aparte.

## Fase E — Hardware propio

- Drivers (`xrt_device`, `xrt_fs`) para un par estéreo de obturador global
  sincronizado, el caso donde Basalt ya demostró 0,4–1,9 cm de error.
- El runtime pasa a ser runtime de sistema; las aplicaciones no se recompilan.

## Fuera de alcance por ahora

- Tienda de aplicaciones, cuentas y capa social.
- Streaming PC → visor (WiVRn es GPL-3 y ALVR depende de SteamVR; ver
  [docs/findings/ecosistema.md](docs/findings/ecosistema.md)).
- Seguimiento ocular y facial.
