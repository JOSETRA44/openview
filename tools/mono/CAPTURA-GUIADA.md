# Captura física para OpenView

Esta sesión prepara la cámara del teléfono para seguimiento. Una calibración de
intrínsecos aceptada **todavía no equivale a seguimiento 6DoF**: después hacen falta
extrínsecos cámara–IMU, sincronización y validación del backend con estos sensores.

## Preparar el tablero

Abrir [checkerboard-20mm.svg](targets/checkerboard-20mm.svg), imprimir en A4
horizontal al **100 %, sin ajustar a página** y pegar sobre una superficie rígida
y plana. El documento mide 220 × 180 mm. El patrón tiene 9 × 7 cuadrados y
8 × 6 esquinas internas. Medir varios cuadrados con una regla: cinco cuadrados
deben sumar 100 mm. Si no coinciden, usar en el calibrador el tamaño real de un
cuadrado, no el indicado en el nombre. Evitar papel curvado y reflejos.

No usar una pantalla como sustitución silenciosa del patrón impreso: su tamaño,
parpadeo y reflejos requieren otra evaluación.

## Comprobar primero los controles

Instalar el APK `sensor-recorder-debug-apk` del build de esta rama, abrir la
grabadora y conceder Cámara. Cerrar antes el modo cámara de OpenView. El comando
siguiente captura seis segundos y comprueba resultados reales de Camera2:

```sh
python tools/mono/capture_session.py --serial SERIAL --seconds 6 --exposure-ms 5 --focus-diopters 0
```

Usar `--adb RUTA/adb` si ADB no está en PATH. El script comprueba espacio libre,
espera `DONE`, copia exclusivamente la nueva carpeta y escribe `quality.json`.
No borra archivos ni capturas del teléfono. `accepted_fixed_controls` confirma
estabilidad de exposición/ISO/enfoque y AE/AF desactivados en al menos 30 frames;
no mide nitidez ni confirma que haya un tablero. La primera parte automática
queda conservada para diagnóstico, pero se excluye de calibración estricta.

El enfoque 0 D (infinito) conserva el ajuste usado hasta ahora. Revisar en la vista
previa que se distingan los bordes del tablero; si hace falta cambiar el enfoque,
fijarlo explícitamente y conservar **ese mismo valor** en las capturas posteriores
y en el futuro tracker. No mezclar calibraciones de diferentes focos. ISO 0 elige
una ganancia una vez y después la fija; puede especificarse `--iso 400` para una
repetición controlada. Con 5 ms se necesita buena iluminación continua.

## Captura del tablero: unos 45 segundos

```sh
python tools/mono/capture_session.py --serial SERIAL --seconds 45 --exposure-ms 5 --focus-diopters 0
```

Mantener el tablero inmóvil y mover el teléfono despacio entre posiciones. En
cada posición detenerse aproximadamente dos segundos, con el patrón completo
visible y margen blanco alrededor:

1. Centro, primero pequeño y luego grande, sin acercarse tanto que se desenfoque.
2. Parte izquierda, derecha, superior e inferior del encuadre.
3. Cuatro esquinas del encuadre, manteniendo visibles todos los cuadrados.
4. Varias vistas inclinadas entre 20° y 35°, tanto horizontal como verticalmente.
5. Repetir algunas posiciones a otra distancia: mínimo doce poses diferentes,
   idealmente dieciocho o más.

Las transiciones rápidas no ayudan: el teléfono tiene rolling shutter. Si el
encuadre no deja espacio para variar distancia, mover el tablero más lejos y
usar un patrón físico mayor; no recortar digitalmente las imágenes.

## Calcular y revisar

```sh
python tools/mono/calibrate.py build/calibration-captures/FECHA-HORA --require-fixed-controls --square-mm 20 --stride 10 --output build/calibration.json
```

Requiere NumPy y OpenCV. Una aceptación tiene estado `accepted_intrinsics_only`
y código de salida 0. Un rechazo devuelve código 2 y sus motivos; no sustituirlos
por números supuestos ni relajar umbrales para forzar la aceptación. Se comprueban
diversidad de posiciones, tamaños e inclinaciones y error en vistas reservadas.

Guardar juntos imágenes, `device.json`, `capture.json`, `frames.csv`, IMU y el
reporte. Las imágenes quedan locales; `build/` está excluido de Git. Conservar
también el tamaño medido del tablero y el ajuste de enfoque. Después de aceptar,
hacer una segunda captura independiente para comprobar repetibilidad antes de
usar la calibración como configuración del dispositivo.

## Siguiente captura: movimiento visual-inercial

Es una sesión distinta de la calibración quieta: escena con textura, movimientos
suaves de cabeceo/guiñada y traslación, regresando al punto inicial. Debe conservar
resolución, foco y controles. Sirve para estudiar sincronización y trayectoria;
una vuelta aproximada al inicio no constituye ground truth. La extrínseca
cámara–IMU no debe fijarse a identidad para hacer que el tracker arranque.
