# Evaluación de seguimiento visual-inercial

Método: `tools/bench/run_eval.py` reproduce secuencias en formato EuRoC a través del tracker
SLAM de Monado (`monado-cli slambatch`), el mismo camino de código que usa el runtime, y
compara la trayectoria con la referencia tras alinearlas con una transformación rígida
(`tools/bench/ate.py`, sin ajustar escala). Se ejecuta en GitHub Actions
(`.github/workflows/vio-eval.yml`), en un runner estándar `ubuntu-24.04`.

Niveles de evidencia según [README.md](README.md).

## Basalt con dos cámaras

Backend: Basalt, fork de Monado, `release-30ece25f` (2026-07-24), binario publicado por el
proyecto, con la calibración y configuración que trae para estas secuencias (`msdmo`).
Datos: Monado SLAM Datasets (Collabora, CC-BY-4.0), visor Samsung Odyssey+, dos cámaras
640×480 de obturador global y referencia de Lighthouse.

**Verificado** (2026-11-12):

| Secuencia | Duración | Recorrido | Error (ATE RMSE) | % del recorrido | Velocidad |
|---|---|---|---|---|---|
| MOO09 arriba-abajo | 4.9 s | 1.6 m | 0.37 cm | 0.23 % | 1.5× tiempo real |
| MOO10 panorama | 9.1 s | 2.6 m | 0.99 cm | 0.39 % | 2.0× |
| MOO11 ida y vuelta | 13.4 s | 7.3 m | 1.90 cm | 0.26 % | 2.1× |
| MOO07 mapeo fácil | 41.1 s | 15.8 m | 1.33 cm | 0.08 % | 2.1× |

Todas con el 99–100 % de la secuencia seguida, y resultados idénticos entre dos ejecuciones
(Basalt en modo determinista).

Qué establece:

- El camino completo funciona en nuestro build: dataset → tracker de Monado → VIT → Basalt →
  trayectoria. Es la primera vez que el proyecto ejecuta seguimiento 6DoF.
- Con un par estéreo de obturador global, Basalt da error de centímetros y le sobra tiempo en
  un procesador de servidor modesto. Es una base adecuada para el hardware propio.

Qué no establece: nada sobre teléfonos. Son cámaras de obturador global, calibradas y
sincronizadas por hardware, y un procesador x86.

## Basalt con una cámara

**Verificado**: no arranca. Con las mismas secuencias, usando solo la primera cámara
(`EUROC_CAM_COUNT=1` y calibración reducida a una cámara):

```
Basalt with cam_count=1, show_gui=0
Assertion failed @Implementation:168
Basalt doesn't support running with 1 cameras
```

La hipótesis "un VIO permisivo existente funciona con una sola cámara" queda **refutada para
Basalt tal como se distribuye**.

*Corrección (2026-11-14):* este párrafo decía que Basalt obtiene la profundidad
triangulando entre las dos cámaras del mismo instante y que con una cámara haría
falta otra inicialización y otra gestión de puntos. Tras leer su código, la
triangulación ya es temporal (contra el primer par con ≥ 5 cm de traslación) y la
gestión de puntos no supone estéreo; el bloqueo es una aserción deliberada en su
capa VIT. Ver [basalt-monocular.md](basalt-monocular.md).

### Con el parche de OpenView (opción A)

**Verificado** (2026-11-14, `.github/workflows/basalt-mono-eval.yml`): el mismo fork
(`release-30ece25f`) compilado desde fuente con
[patches/basalt/0001-allow-single-camera.patch](../../patches/basalt/README.md), mismas
secuencias y mismo bench, calibración reducida a la primera cámara, puntuación **sin
ajustar escala** como siempre:

| Secuencia | Error (ATE RMSE) | % del recorrido | Cobertura | Velocidad |
|---|---|---|---|---|
| MOO09 arriba-abajo | 0.88 cm | 0.56 % | 99 % | 3.0× tiempo real |
| MOO10 panorama | 1.07 cm | 0.42 % | 99 % | 4.1× |
| MOO11 ida y vuelta | 1.96 cm | 0.27 % | 100 % | 4.5× |
| MOO07 mapeo fácil | 1.97 cm | 0.12 % | 100 % | 4.9× |

Qué establece:

- Con una cámara, Basalt parcheado sigue al nivel de centímetros: entre igual y ~2,4× el
  error de su propio estéreo, con la misma cobertura y **más rápido** (una cámara es menos
  trabajo que dos).
- La escala métrica converge sin inicializador monocular dedicado en estas secuencias: la
  puntuación no ajusta escala, así que un error del 0,1–0,6 % del recorrido implica escala
  correcta. La incógnita principal del estudio queda resuelta en datos de visor.

Qué no establece: nada sobre el teléfono todavía. Estas cámaras son de obturador global,
calibradas y sincronizadas; el sensor del teléfono es rolling shutter (29 ms medidos) sin
modelo en Basalt, la calibración del dispositivo está sin hacer y Android aún no entrega
imágenes al tracker. Ese es el siguiente experimento, no una conclusión.

## OKVIS2 con una cámara

**Verificado** (2026-11-14, `.github/workflows/okvis2-eval.yml`, run 43605730465):
OKVIS2 `a2ea006` (repo oficial `smartroboticslab/okvis2`) con la configuración
`tools/okvis2/euroc_mono.yaml` (solo cam0), sobre EuRoC V1_01_easy (146 s, 58,6 m),
puntuado con el mismo `ate.py` y sin ajustar escala:

| Secuencia | Error (ATE RMSE) | % del recorrido | Cobertura | Velocidad |
|---|---|---|---|---|
| V1_01_easy | 4.53 cm (mediana 3.81, máx 15.23) | 0.08 % | 99.96 % | 1.6× tiempo real |

Qué establece: OKVIS2 funciona con una cámara sin parchear nada, con error de
centímetros y escala métrica correcta (sin ajuste de escala). Es el respaldo viable
de la opción B.

Qué no establece: la comparación con Basalt no es cara a cara — las secuencias son
distintas (MOO frente a EuRoC) — y OKVIS2 tampoco modela rolling shutter ni tiene
adaptador VIT para el runtime. En los números disponibles, Basalt mide ~2–5× mejor
y corre ~2–3× más rápido.

## Otros hallazgos del proceso

- El paquete de Basalt no declara dependencias: sin `libfmt9` la biblioteca no carga.
- `monado-cli slambatch` no termina cuando el tracker falla al arrancar; se queda esperando
  poses. `run_eval.py` detiene la corrida cuando el log deja de avanzar.
- El archivo de calibración de Basalt tiene un campo `cam_time_offset_ns`: el desfase
  cámara–IMU se puede pasar al backend aunque la interfaz VIT no lo contemple.
- Basalt publica binarios para Raspberry Pi 4/5 y placas Radxa (ARM64 con Linux).
  **Tercero**: no ejecutados por nosotros, pero indican que el camino a un SoC ARM existe.

## Sin evaluar todavía

- Capturas propias de teléfono: falta calibración de la cámara (el dispositivo no declara
  intrínsecos) y un backend que acepte una cámara.
- Secuencias con rolling shutter y referencia (p. ej. TUM RS-VI).
