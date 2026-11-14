# Arquitectura

## Qué es propio y qué es reutilizado

```
  Apps (Godot, StereoKit, Unity, Unreal, nativas)
        │  OpenXR 1.x estándar, sin SDK propio
        ▼
  libopenxr_openview            ← runtime/src/target.c (cliente IPC, ~30 líneas)
        │  IPC de Monado (socket / Binder en Android)
        ▼
  openview-service              ← runtime/src/service_main.c
   ├─ st_oxr, compositor, IPC   ← Monado, sin modificar
   ├─ drivers (xrt_device)      ← Monado + los nuestros cuando hagan falta
   └─ tracking (VIT)            ← backend VIO propio (fase 3–4)
```

Reutilizado de Monado: state tracker OpenXR, compositor Vulkan (distorsión, reproyección,
pacing), IPC multiproceso, driver Android/Cardboard, grabación y reproducción EuRoC.

Propio: la selección de qué se compila (`CMakeLists.txt` raíz), los puntos de entrada, y más
adelante el backend de tracking, los perfiles de dispositivo y la capa de producto.

## Regla de dependencias

```
apps → OpenXR → st_oxr → interfaces xrt_* ← drivers / compositor / tracking
```

Las interfaces `xrt_*` (`third_party/monado/src/xrt/include/xrt/`) y la interfaz VIT
(`src/external/vit_includes/vit/vit_interface.h`) son los puertos del sistema. OpenView no
define una capa de abstracción encima: añadirla duplicaría esos contratos sin resolver nada.

Consecuencias:

- Código específico de plataforma (Android, Linux, una placa concreta) solo vive en drivers y
  en los puntos de entrada. Nada por encima de `xrt_*` sabe en qué dispositivo corre.
- El backend de tracking no incluye cabeceras de Android ni de Monado salvo `vit_interface.h`.
  Recibe imágenes e IMU con timestamps y devuelve poses. Por eso se puede desarrollar contra
  datasets en un PC y moverse a hardware propio sin cambios.
- Migrar a un visor dedicado significa escribir drivers (`xrt_device`, `xrt_fs`) para sus
  sensores y pantalla, e instalar el runtime como runtime de sistema. Las apps no se recompilan.

## Niveles de tracking

| Nivel | Fuente | Estado |
|---|---|---|
| T0 | 3DoF por IMU (`m_imu_3dof`) | Existe en Monado |
| T1 | VIO abierto detrás de VIT | Por construir; es el aporte central del proyecto |
| — | ARCore | Solo backend opcional y aislado: referencia para medir T1 y respaldo |

La experiencia propia Android (`android/experience`) es un cliente OpenXR independiente,
abierto desde `android/app/HomeActivity`. Usa orientación del runtime y puede componer
vídeo Camera2 con geometría virtual. No incorpora un estimador paralelo de pose. Ver
[ADR-0004](../adr/0004-experiencia-openxr-independiente.md).

T1 tiene ahora dos evidencias offline: Basalt estéreo por VIT y XRSLAM monocular+IMU por
su API pública (5.18 cm ATE RMSE en una secuencia EuRoC). El adaptador XRSLAM→VIT,
la calibración real y la integración cámara/IMU del teléfono siguen pendientes.

## Relación con Monado

Monado se fija por commit en un submódulo. Los cambios que necesitamos sobre Monado viven en
`patches/monado/` hasta que se aceptan upstream; el objetivo es que ese directorio tienda a
vacío. Subir el commit fijado es una decisión explícita, registrada en una ADR si rompe algo.
