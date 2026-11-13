# bench

Mide la calidad del seguimiento 6DoF sobre secuencias grabadas que tienen trayectoria de
referencia.

| Archivo | Qué hace |
|---|---|
| `ate.py` | Error absoluto de trayectoria entre una estimación y la referencia, tras alinearlas |
| `run_eval.py` | Reproduce secuencias EuRoC por el tracker de Monado con un backend VIT y las puntúa, con todas las cámaras y con una sola |
| `test_ate.py` | Pruebas de `ate.py` con trayectorias de error conocido |

Solo corre en Linux: Monado no compila su tracker SLAM ni el reproductor EuRoC en otro
sistema. Lo normal es lanzarlo desde GitHub Actions (workflow `vio-eval`), que instala Basalt,
descarga las secuencias y publica la tabla de resultados.

En local:

```sh
cmake -S . -B build -G Ninja -DOPENVIEW_BUILD_EVAL_TOOLS=ON
cmake --build build --target cli
python3 tools/bench/run_eval.py --cli build/monado/src/xrt/targets/cli/monado-cli \
    --vit-library /usr/lib/x86_64-linux-gnu/libbasalt.so.2.0.1 \
    --calib /usr/share/basalt/msdmo_calib.json \
    --vio-config /usr/share/basalt/msdmo_config.json \
    --work eval data/MOO07_mapping_easy
```

Resultados y su interpretación: `docs/findings/vio-evaluacion.md`.
