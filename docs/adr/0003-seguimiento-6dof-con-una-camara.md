# ADR-0003: Seguimiento 6DoF con una sola cámara

Estado: abierta (2026-11-12). Hay evidencia para descartar una opción; falta para elegir.

## Contexto

El plan suponía que el nivel de seguimiento T1 en teléfonos podría construirse adoptando o
adaptando un VIO permisivo existente. Las mediciones dicen:

- Basalt funciona muy bien con dos cámaras de obturador global (1.3 cm en 41 s), pero se niega
  a arrancar con una ([vio-evaluacion.md](../findings/vio-evaluacion.md)).
- El teléfono de referencia tiene una sola cámara trasera utilizable, con 29 ms de rolling
  shutter, sin intrínsecos declarados y con desenfoque fuerte en interior
  ([dispositivo-infinix-x6885.md](../findings/dispositivo-infinix-x6885.md)).
- La interfaz VIT no modela rolling shutter ni desfase temporal
  ([monado-codigo.md](../findings/monado-codigo.md)).

Así que T1 en teléfono necesita tres cosas que Basalt no trae: inicialización y triangulación
monocular, modelo de rolling shutter, y calibración por dispositivo.

## Lo que ya está decidido

- **En hardware propio se usa Basalt con un par estéreo de obturador global.** Es el caso
  para el que existe, está medido en nuestro build y ya se distribuye para SoC ARM. Esto fija
  un requisito del visor propio: dos cámaras de seguimiento de obturador global sincronizadas.
- **El backend de teléfono se integra por VIT**, igual que Basalt, para que el runtime no
  distinga entre uno y otro.

## Opciones para el teléfono

| Opción | A favor | En contra | Estado |
|---|---|---|---|
| A. Extender Basalt a una cámara | Reutiliza un optimizador probado y la integración VIT existente | Hay que añadir inicialización por movimiento y triangulación temporal dentro de un código ajeno; seguiría sin rolling shutter | Sin estudiar su código |
| B. OKVIS2 (BSD-3) | Calibración online opcional | Sus desarrolladores declaran no soportado monocular ni monocular+IMU por la inicialización de puntos | Sin ejecutar; descartado como integración directa soportada |
| C. Filtro propio tipo MSCKF | Una cámara es su caso natural (no necesita profundidad inicial); el rolling shutter y el desfase temporal entran como estados; código propio y permisivo | Es el trabajo más grande; las implementaciones de referencia son GPL y solo se pueden usar como literatura | Sin empezar |
| D. Exigir dos cámaras traseras como par estéreo | Usaría Basalt tal cual | Android no garantiza abrir dos cámaras a la vez, y el teléfono de referencia no las agrupa; base de dispositivos reducida | Descartada como requisito general |

## Qué falta para decidir

1. Evaluar un candidato que contemple monocular+IMU: XRSLAM merece un experimento fijado por revisión, sin adoptarlo aún. OKVIS2 no es ya el supuesto camino corto.
2. Leer cómo Basalt crea puntos nuevos, para estimar el tamaño real de la opción A.
3. Una secuencia pública con rolling shutter y referencia, para medir cuánto degrada a un
   backend que lo ignora.

Actualización de investigación (2026-11-12): un contribuidor de OKVIS2 explica en
[issue 21](https://github.com/ethz-mrl/okvis2/issues/21) que su inicialización no soporta
monocular, tampoco agregando IMU. No es un resultado de ejecución propia y no descarta
adaptaciones. Se añade XRSLAM como candidato experimental, no como backend elegido;
ver [avance monocular](../findings/monocular-prototype.md). La herramienta de calibración
intrínseca ya está implementada en `tools/mono`, pero falta una captura física del tablero.

## Consecuencia inmediata

Mientras tanto el producto en teléfono sigue en 3DoF, que ya está medido y afinado. La
opción de ARCore como backend opcional y aislado (ADR-0001) cobra más peso como puente.
