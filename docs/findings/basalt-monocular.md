# Basalt con una cámara: lectura de su código

Estudio del fork de Basalt que usa OpenView (`mateosss/basalt`, `release-30ece25f`)
y de su upstream, 2026-11-14. Responde al punto 2 de «qué falta para decidir» de
[ADR-0003](../adr/0003-seguimiento-6dof-con-una-camara.md). Todo es **Verificado**
por lectura de código; las rutas son del fork salvo que se indique lo contrario.

## El rechazo es una aserción, no una limitación del núcleo

El «Basalt doesn't support running with 1 cameras» de
[vio-evaluacion.md](vio-evaluacion.md) sale de una única aserción en la capa VIT
del fork, `src/vit/vit_tracker.cpp:168`. El comentario que la precede (líneas
163–167) dice que debería ser posible adaptarlo y enlaza las issues upstream
n.º 2 y n.º 25. El upstream ni siquiera contiene esa aserción.

El único otro punto del núcleo que rompería con una cámara es un acceso fuera de
rango: `OpticalFlowTyped` lee `calib.T_i_c[1]` al construir las matrices
esenciales (`include/basalt/optical_flow/optical_flow.h:207-213`), un resultado
que en monocular nunca se usaría.

## Basalt ya triangula en el tiempo

La ADR-0003 suponía que Basalt obtiene la profundidad de cada punto triangulando
entre las dos cámaras del mismo instante y que con una cámara «esa profundidad
tendría que salir del movimiento, que es otra inicialización y otra gestión de
puntos». La segunda parte no se sostiene tras leer el código:

- En `SqrtKeypointVioEstimator::measure()` (`src/vi_estimator/sqrt_keypoint_vio.cpp:474-560`),
  al tomar un keyframe se recopilan **todas** las observaciones guardadas de cada
  punto nuevo —cualquier frame, cualquier cámara— y se triangula por DLT
  (`include/basalt/utils/ba_base.h:89`) contra la primera pareja con traslación
  relativa ≥ 5 cm (`vio_min_triangulation_dist`, `src/utils/vio_config.cpp:85`).
- Con estéreo gana siempre el par simultáneo, porque la base supera el umbral
  desde el primer keyframe. Con una cámara ganaría el par temporal, sin cambiar
  el mecanismo.
- La parametrización del landmark (dirección y distancia inversa desde el
  keyframe anfitrión, `include/basalt/vi_estimator/landmark_database.h:55-67`) y
  los factores de reproyección (genéricos por `cam_id`,
  `src/utils/ba_base.cpp:81-82`) no suponen estéreo en absoluto.

## El riesgo real es el arranque de escala

No existe inicialización monocular: ni SfM ni estimación de escala. El estimador
arranca estático desde la IMU (velocidad cero, `sqrt_keypoint_vio.cpp:275`;
gravedad por el acelerómetro, `:278`) y con estéreo la escala métrica entra
gratis por la base entre cámaras. En monocular, los primeros keyframes pueden
crear cero landmarks y **si la escala converge, y en cuánto tiempo, es una
incógnita que solo se resuelve midiendo**.

## Estimación de la opción A

| Trabajo | Estimación |
|---|---|
| Quitar la aserción, corregir el acceso `T_i_c[1]`, plomería de calibración de 1 cámara, hasta que corra EuRoC solo con cam0 | 2–5 días |
| Experimentación con el banco ATE propio hasta un veredicto de convergencia de escala | 1–3 semanas |
| Solo si la escala no converge: injertar un inicializador dedicado | Varias semanas más |

## Consecuencia

La opción A merece su experimento medido antes de descartarla frente a OKVIS2
(opción B) o el filtro propio (opción C): es mucho más barata de lo que la ADR
suponía. El experimento es compilar el fork con esos dos cambios y correr el
modo monocular que `tools/bench/run_eval.py` ya implementa. El rolling shutter
del teléfono queda fuera de esta opción en cualquier caso: Basalt no lo modela.
