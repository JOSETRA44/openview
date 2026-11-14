# Preparación de seguimiento monocular

`calibrate.py` resuelve los intrínsecos que el Infinix no declara. Usa imágenes de la
grabadora, detecta un tablero, separa vistas de validación y exporta un reporte JSON.
No calcula poses del teléfono ni sustituye un backend VIO.

Dependencias: Python 3, NumPy y OpenCV con `findChessboardCornersSB`. En Ubuntu:

```sh
sudo apt-get install python3-opencv python3-numpy
python3 tools/mono/test_calibrate.py
```

## Captura y uso

```sh
python3 tools/mono/calibrate.py board.svg --board --square-mm 15
python3 tools/mono/calibrate.py /ruta/captura --square-mm 15 --stride 10 --output calibration.json
```

1. Imprimir el SVG al 100 %, sin ajustar a página. Medir físicamente el cuadrado;
   pasar su tamaño real con `--square-mm`. El patrón predeterminado tiene **8×6
   esquinas internas**, es decir, **9×7 cuadrados**. Pegar sobre una base plana.
2. Grabar con la cámara y resolución que se van a usar en tracking. Mantener fijo
   el enfoque; una calibración no se puede transferir sin comprobar a otro foco,
   recorte digital o resolución. En la grabadora actual verificar `frames.csv`.
3. Tomar al menos doce vistas diferentes: centro, bordes, cerca/lejos y con
   inclinaciones en ambos ejes. Mantener cada pose quieta un momento: el rolling
   shutter de este teléfono hace inadecuadas las transiciones rápidas.
4. El comando muestrea una imagen de cada diez, hasta 300. Ajustar `--stride`
   si no se detectan doce vistas. Se pueden usar carpetas PGM, PNG o JPEG.

Salida exitosa: código 0 y `status: accepted_intrinsics_only`. Reporta `fx`, `fy`,
`cx`, `cy` en píxeles y `k1,k2,p1,p2,k3` del modelo pinhole-radtan5; `k3` queda fijo
a cero para evitar un parámetro pobremente observable. Adjunta resolución, versión
OpenCV, nombres de imágenes, métricas y `device.json` cuando existe.

Rechazo: código 2 y `status: rejected`, con motivos y **sin campo intrinsics**.
Un error de entrada puede terminar sin escribir reporte: el consumidor siempre
debe comprobar el código de salida, no reutilizar un JSON anterior. No se elimina
silenciosamente ninguna vista mala para hacer que la calibración pase.

Los controles exigen variedad de posición, distancia e inclinación, al menos doce
vistas distintas y error de reproyección ≤1 píxel tanto en ajuste como en cada
vista reservada. Las vistas reservadas ajustan únicamente la pose del tablero,
no los intrínsecos. Estos umbrales detectan algunos errores comunes; no certifican
la exactitud física ni corrigen rolling shutter. Cambios de enfoque detectados en
la metadata también invalidan el resultado.

Faltan después: extrínsecos cámara–IMU, sincronización, ruido/bias IMU, tratamiento
de rolling shutter y un backend monocular integrado por VIT. No exportamos una
calibración Basalt falsa con extrínsecos identidad.

El workflow `mono-tools` ejecuta pruebas sintéticas de recuperación, rechazo y
detección de imágenes, y publica un tablero imprimible. No contiene ni ejecuta un
tracker 6DoF. Hallazgos y próximos experimentos en
[`monocular-prototype.md`](../../docs/findings/monocular-prototype.md).
