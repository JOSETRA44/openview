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
Basalt tal como se distribuye**. Basalt obtiene la profundidad de cada punto triangulando
entre las dos cámaras del mismo instante; con una sola cámara esa profundidad tendría que
salir del movimiento, que es otra inicialización y otra gestión de puntos.

## Otros hallazgos del proceso

- El paquete de Basalt no declara dependencias: sin `libfmt9` la biblioteca no carga.
- `monado-cli slambatch` no termina cuando el tracker falla al arrancar; se queda esperando
  poses. `run_eval.py` detiene la corrida cuando el log deja de avanzar.
- El archivo de calibración de Basalt tiene un campo `cam_time_offset_ns`: el desfase
  cámara–IMU se puede pasar al backend aunque la interfaz VIT no lo contemple.
- Basalt publica binarios para Raspberry Pi 4/5 y placas Radxa (ARM64 con Linux).
  **Tercero**: no ejecutados por nosotros, pero indican que el camino a un SoC ARM existe.

## Sin evaluar todavía

- OKVIS2 (BSD-3): sus configuraciones publicadas son todas estéreo. Si admite una cámara
  hay que comprobarlo ejecutándolo.
- Capturas propias de teléfono: falta calibración de la cámara (el dispositivo no declara
  intrínsecos) y un backend que acepte una cámara.
- Secuencias con rolling shutter y referencia (p. ej. TUM RS-VI).
