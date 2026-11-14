# Parches sobre el fork de Basalt

Basalt no es un submódulo de OpenView: la evaluación usa la biblioteca que
publica el fork de Monado (`mateosss/basalt`). Estos parches se aplican sobre
ese código al compilarlo en CI, igual que `patches/monado/` se aplica sobre el
submódulo. El objetivo es el mismo: que la lista tienda a vacío si upstream
acepta los cambios.

| Parche | Sobre qué | Qué hace |
|---|---|---|
| `0001-allow-single-camera.patch` | `release-30ece25f` (la versión evaluada en [vio-evaluacion](../../docs/findings/vio-evaluacion.md)) | Permite `cam_count == 1`: rebaja la aserción de la capa VIT que lo prohibía y evita el acceso fuera de rango a `T_i_c[1]` al construir las matrices esenciales, que en monocular no se usan |

Por qué debería funcionar, y qué riesgo queda, está en
[basalt-monocular.md](../../docs/findings/basalt-monocular.md): los landmarks
ya se triangulan en el tiempo; la incógnita es la convergencia de la escala
sin inicializador monocular, y medirla es para lo que existe este parche.

Conocido y **no** parcheado: `NfrMapper::match_stereo()` también lee
`T_i_c[1]`, pero es una herramienta de mapeo fuera de línea que no está en el
camino del tracker VIT que usa el runtime; si algún día se usa con una
cámara, necesitará la misma guarda.
