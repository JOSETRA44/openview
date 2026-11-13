# Ecosistema XR abierto (noviembre 2026)

## Runtimes y bases candidatas

| Proyecto | Licencia | Hallazgo | Nivel |
|---|---|---|---|
| Monado | BSL-1.0 | Última estable 25.1.0 (2025-12-09). OpenXR 1.1 implementado, "intended to be conformant but not yet submitted". Driver Cardboard en Android con calibración por QR; solo 3DoF | Verificado (changelog, blog de Collabora 2025-10-31, código) |
| Google Cardboard SDK | Apache-2.0 | Activo (v1.36.0, 2026-10-14). Biblioteca enlazada por app; sin OpenXR | Verificado (repo y releases) |
| OpenHMD | BSL-1.0 | "NOT ACTIVELY MAINTAINED… development has largely moved to Monado" | Verificado (README) |
| PhoneXR | sin LICENSE | 34 commits; 6DoF vía ARCore; el README redirige a otro proyecto | Verificado (repo) |
| FullyMobileVR | AGPL-3.0 sobre BSL | Fork de Monado, 4 commits; cambios "mainly ARCore-side" | Verificado (repo) |
| Android XR (Google) | cerrado | Conforme a OpenXR 1.1; 36 extensiones `XR_ANDROID_*` | Verificado (developer.android.com) |

"Android XR, Snapdragon Spaces, Pico, CloudXR y Hololight usan componentes de Monado":
**Tercero** (Collabora, 2026-02 y 2026-03). No dicen qué componentes.

## VIO / SLAM abiertos

| Sistema | Licencia | Notas | Usable en núcleo BSL |
|---|---|---|---|
| Basalt | BSD-3 | Backend VIT por defecto de Monado; orientado a estéreo | Sí |
| OKVIS2 | BSD-3 | Calibración online opcional; exige buena sincronía | Sí |
| Kimera-VIO | BSD-2 | Estéreo+IMU, mono opcional; depende de GTSAM | Sí |
| OpenVINS | GPL-3 | Mono y estéreo, offset temporal cámara–IMU | No |
| ORB-SLAM3 | GPL-3 | Licencia comercial aparte | No |
| VINS-Fusion / VINS-Mobile | GPL-3 | VINS-Mobile corrió en iPhone (2017) | No |
| HybVIO | GPL-3 | Monocular; "not intended for production use" | No |

Licencias: **Verificado** en cada repositorio. No se revisaron las licencias de sus dependencias.

**Límite**: no encontramos ningún VIO monocular con licencia permisiva y demostrado en un
teléfono Android. Los dos proyectos que dan 6DoF en teléfono usan ARCore.

La hipótesis de mayor riesgo del proyecto era que Basalt u OKVIS2 funcionaran con una sola
cámara. Para Basalt quedó **refutada** (rechaza arrancar con una cámara); para OKVIS2 sigue
sin probar. Ver [vio-evaluacion.md](vio-evaluacion.md).

## Aplicaciones

StereoKit (MIT) y Godot (MIT) son clientes OpenXR que exportan a Android; StereoKit lista
Monado como plataforma. **Verificado** en sus README. Que corran sin cambios sobre OpenView
es **Hipótesis** hasta probarlo.

## Streaming PC → visor

WiVRn es GPL-3 (no reutilizable en el núcleo). ALVR es MIT pero depende de SteamVR.
Fuera del alcance inicial.
