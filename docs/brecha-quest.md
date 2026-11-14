# Brecha frente a Meta Quest (2026-11-14)

Qué tiene hoy OpenView, qué le falta para ser comparable a un Quest y en qué
orden se ataca. Cada afirmación sobre el estado actual es **verificada** en
este repositorio (CI, dispositivo o artefacto); lo demás está etiquetado como
*falta* o *hipótesis*. La visión no cambia: comparable a Quest empezando en
teléfonos tipo Cardboard.

## 1. Seguimiento 6DoF de la cabeza

- **Quest:** 6DoF inside-out, precisión de centímetros, poses a 90–120 Hz.
- **OpenView hoy (verificado):** motor decidido (ADR-0003) y medido en banco:
  Basalt con **una cámara** da 0,88–1,97 cm de ATE en las secuencias MOO con
  cobertura 99–100 % y la escala métrica converge sin inicializador. OKVIS2
  queda de respaldo (4,53 cm en EuRoC) y XRSLAM entra por VIT (5,18 cm en
  EuRoC directo; su adaptador VIT ya compila y pasa el contrato).
- **Falta:** (a) correr Basalt con la captura real del teléfono — exportación
  EuRoC y calibraciones candidatas listas, ejecución en local en curso;
  (b) conectar el motor al runtime Android por VIT para que `experience`
  reciba poses 6DoF (hoy la rotación viene del driver de sensores 3DoF);
  (c) driver de cámara Android que entregue frames e IMU con relojes
  coherentes y modelo de rolling shutter (29 ms medidos).

## 2. Renderizado y latencia

- **Quest:** 90–120 Hz con reproyección, motion-to-photon en torno a 20 ms.
- **OpenView hoy (verificado, un solo dispositivo, 60 Hz):** parches propios
  de predicción de pose, modelo de latencia de 2 periodos y enganche a vsync
  de Choreographer: frames descartados del ~6 % al 0,2 % y latencia medida
  de 29–32 ms en el Infinix X6885.
- **Falta:** medir a 90/120 Hz y en un segundo SoC (issue #5); reproyección
  propia equivalente a ATW; presupuesto de latencia publicado por etapa.

## 3. Passthrough y realidad mixta

- **Quest:** passthrough a color de baja latencia, anclado al mundo.
- **OpenView hoy (verificado):** la app `experience` tiene modo cámara
  probado en el dispositivo.
- **Falta:** passthrough corregido de distorsión, con latencia medida y
  anclado por el 6DoF (depende de §1); sin eso no hay MR, solo una ventana
  con vídeo.

## 4. Interacción

- **Quest:** seguimiento de manos y mandos 6DoF.
- **OpenView hoy (verificado):** interacción por mirada en `experience`
  (rayo contra esferas + activación por permanencia de 1,25 s o gatillo),
  con su lógica probada por tests.
- **Falta:** seguimiento de manos con la cámara (ML en el dispositivo);
  mandos no existen como hardware — la mirada es el sustituto parcial
  mientras tanto.

## 5. Límites, anclas y escena

- **Quest:** Guardian/boundary, anclas persistentes, malla de la habitación
  y oclusión.
- **OpenView hoy:** nada de esto existe.
- **Falta:** todo, en este orden: boundary básico sobre el 6DoF; anclas
  espaciales; exponer el mapa disperso que Basalt ya calcula como base de
  escena.

## 6. Sistema y producto

- **Quest:** home, biblioteca, tienda, cuentas, actualizaciones OTA.
- **OpenView hoy (verificado):** runtime + una app de experiencia; sin
  launcher propio y sin ninguna release publicada.
- **Falta:** home espacial mínimo que lance apps OpenXR; una release
  instalable (v0.1) con notas; actualizaciones manuales documentadas antes
  que OTA.

## 7. Ecosistema de aplicaciones

- **Quest:** tienda y SDK propios.
- **OpenView hoy:** OpenXR estándar — la ventaja estructural: cualquier app
  OpenXR es candidata sin portarla a un SDK cerrado.
- **Falta (Fase C, issue #2):** probar UNA aplicación o motor real (Godot
  con su plugin OpenXR) sobre el runtime y documentar el camino del
  desarrollador. Hoy es *hipótesis*, no hecho.

## 8. Hardware

- **Hoy (verificado):** un dispositivo, Infinix X6885 (Android 16, 60 Hz).
- **Falta:** segundo teléfono/SoC (issue #5) antes de hablar de
  compatibilidad; el visor propio es la Fase E, un horizonte, no el
  siguiente paso.

## Camino crítico (orden de ataque)

1. 6DoF real en el teléfono: captura → Basalt en local → VIT en Android →
   poses 6DoF en `experience`. **(en curso)**
2. Passthrough anclado y medido.
3. Home espacial mínimo + primera release instalable.
4. Una app real de terceros (Godot/OpenXR) funcionando.
5. Boundary básico.
6. Medición a 90/120 Hz y segundo SoC; manos después.
