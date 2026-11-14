# Evaluación de OKVIS2 monocular

Experimento de la [ADR-0003](../../docs/adr/0003-seguimiento-6dof-con-una-camara.md):
¿puede un VIO permisivo (BSD-3) dar 6DoF con **una** cámara, el caso de un teléfono?

Basalt ya respondió que no para sí mismo (rechaza `cam_count=1` al arrancar).
OKVIS2 acepta una cámara por diseño: con una sola entrada en `cameras:` su lector
de dataset solo abre `cam0` y los puntos se inicializan por la vía temporal.
Lo que faltaba era medir su error, que es lo que hace esta carpeta.

## Piezas

| Archivo | Qué es |
|---|---|
| `euroc_mono.yaml` | Configuración de EuRoC reducida a cam0 (`sync_cameras: [0]`), derivada de la `euroc.yaml` de OKVIS2 |
| `run_okvis2_eval.py` | Corre `okvis_app_synchronous` sobre secuencias EuRoC y puntúa la trayectoria con `tools/bench/ate.py`, sin ajustar escala |

## Cómo se ejecuta

En GitHub Actions, a mano: workflow `okvis2-eval`
(`.github/workflows/okvis2-eval.yml`). Compila OKVIS2 fijado a un commit
(`smartroboticslab/okvis2` @ `a2ea006`) con Ceres del sistema, descarga la
secuencia (por defecto EuRoC V1_01_easy desde un espejo en Hugging Face; la
descarga original de ETH ya no responde) y deja `results.md`/`results.json`
como artefacto y en el resumen del run.

En local, con OKVIS2 ya compilado:

```sh
python3 tools/okvis2/run_okvis2_eval.py \
  --app /ruta/a/okvis_app_synchronous \
  --config tools/okvis2/euroc_mono.yaml \
  --work eval \
  /ruta/a/V1_01_easy
```

Notas:

- OKVIS2 necesita `small_voc.yml.gz` junto al ejecutable (está en
  `resources/` de su repo) y una pantalla X aunque no dibuje nada: el runner
  lo envuelve en `xvfb-run`.
- La trayectoria final la escribe OKVIS2 dentro de la propia carpeta `mav0/`
  de la secuencia (`okvis2-slam-final_trajectory.csv`).
- EuRoC es obturador global: un buen resultado aquí **no** se traslada sin más
  a un teléfono con rolling shutter de ~29 ms. Esa medición es otro paso de la
  ADR-0003.
