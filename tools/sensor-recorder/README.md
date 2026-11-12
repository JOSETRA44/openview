# sensor-recorder

Graba la cámara trasera y el IMU de un teléfono con los timestamps que da la plataforma, sin
alinear ni remuestrear nada. Sirve para dos cosas: caracterizar un dispositivo (¿comparten
reloj la cámara y el IMU?, ¿cuánto tarda el rolling shutter?) y producir datasets para evaluar
seguimiento visual-inercial fuera del teléfono.

## Grabar

Con la app abierta, botón Grabar/Detener. Sin tocar el teléfono:

```sh
adb shell pm grant dev.openview.recorder android.permission.CAMERA
adb shell am start -n dev.openview.recorder/.MainActivity --ei seconds 30
adb pull /sdcard/Android/data/dev.openview.recorder/files/<fecha-hora> captura
```

El archivo `DONE` aparece cuando la grabación terminó de escribirse.

Para que el desfase cámara–IMU sea estimable hay que **girar** el teléfono durante la captura
(cabeceo y guiñada, movimientos variados). Para evaluar seguimiento, moverse además por una
escena con textura y volver al punto de partida.

## Analizar

```sh
python tools/sensor-recorder/analyze.py captura              # reporte
python tools/sensor-recorder/analyze.py captura --euroc out  # y exportar a EuRoC
python tools/sensor-recorder/test_analyze.py                 # pruebas de los estimadores
```

Requiere Python 3 y numpy.

## Formato de una captura

| Archivo | Contenido |
|---|---|
| `device.json` | Lo que el dispositivo declara de su cámara e IMU. Los campos que no reporta se omiten |
| `frames/<timestamp_ns>.pgm` | Plano de luma de cada frame, 640×480 |
| `frames.csv` | Por frame: timestamp del sensor, exposición, sesgo de rolling shutter, duración, ISO, enfoque, e instante de llegada en `CLOCK_MONOTONIC` |
| `gyro.csv`, `accel.csv` | Eventos crudos: timestamp y los tres ejes |

Ocupa unos 8 MB por segundo.

## Limitaciones

- Una sola cámara. La exposición se fija en 5 ms como máximo tras el primer segundo (antes
  de eso manda la exposición automática); con poca luz las imágenes salen ruidosas u oscuras.
- Sin trayectoria de referencia todavía.
- Si otra app pasa a primer plano la grabación se detiene: Android retira la cámara.
