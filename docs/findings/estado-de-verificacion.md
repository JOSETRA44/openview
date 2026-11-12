# Estado de verificación

Actualizado: 2026-11-11.

| Elemento | Estado |
|---|---|
| Submódulo Monado fijado, con tags (`git describe` → `v25.1.0-1107-ga32ea353`) | Hecho |
| `CMakeLists.txt` raíz y `runtime/` | Escritos; **no configurados ni compilados** |
| `patches/monado/0001` (predicción de pose en Android) | Escrito; **no compilado ni probado** |
| Build Linux + tests de Monado + `hello_xr` | No ejecutado |
| Módulo Gradle / APK propio | No escrito |
| `hello_xr` en teléfono, medición de frame time y térmica | No ejecutado |
| CI | No configurado |

## Por qué

En la máquina de desarrollo actual:

- Disco del sistema con 3.7 GB libres. Las dependencias de build en WSL ocupan 1.4 GB más
  405 MB de descarga, y el build de Android necesita NDK y cachés de Gradle de varios GB.
- La distribución WSL disponible (Kali) no tiene compilador ni CMake instalados.
- Ningún dispositivo Android conectado por `adb`.

## Para desbloquear

1. Liberar ~20 GB.
2. Instalar las dependencias listadas en el README dentro de WSL y ejecutar el build.
3. Conectar un teléfono con Vulkan y giroscopio, con depuración USB.
