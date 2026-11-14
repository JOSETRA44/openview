# ADR-0003: Seguimiento 6DoF con una sola cámara

Estado: abierta (2026-11-12). Hay evidencia para descartar una opción; falta para elegir.

## Contexto

El plan suponía que el nivel de seguimiento T1 en teléfonos podría construirse adoptando o
adaptando un VIO permisivo existente. Las mediciones dicen:

- Basalt funciona muy bien con dos cámaras de obturador global (1.3 cm en 41 s), pero se niega
  a arrancar con una ([vio-evaluacion.md](../findings/vio-evaluacion.md)).
- El teléfono de referencia tiene una sola cámara trasera utilizable, con 29 ms de rolling
  shutter, sin intrínsecos declarados y con desenfoque fuerte en interior
  ([dispositivo-infinix-x6885.md](../findings/dispositivo-infinix-x6885.md)).
- La interfaz VIT no modela rolling shutter ni desfase temporal
  ([monado-codigo.md](../findings/monado-codigo.md)).

Así que T1 en teléfono necesita tres cosas que Basalt no trae: inicialización y triangulación
monocular, modelo de rolling shutter, y calibración por dispositivo.

## Lo que ya está decidido

- **En hardware propio se usa Basalt con un par estéreo de obturador global.** Es el caso
  para el que existe, está medido en nuestro build y ya se distribuye para SoC ARM. Esto fija
  un requisito del visor propio: dos cámaras de seguimiento de obturador global sincronizadas.
- **El backend de teléfono se integra por VIT**, igual que Basalt, para que el runtime no
  distinga entre uno y otro.

## Opciones para el teléfono

| Opción | A favor | En contra | Estado |
|---|---|---|---|
| A. Extender Basalt a una cámara | Reutiliza un optimizador probado y la integración VIT existente | Seguiría sin rolling shutter; la convergencia de escala sin inicializador es una incógnita | Estudiada: ver [basalt-monocular.md](../findings/basalt-monocular.md) |
| B. OKVIS2 (BSD-3) | Activo (2026-08), calibración online opcional | Solo publica configuraciones estéreo; dependencias pesadas; habría que escribirle el adaptador VIT | Sin ejecutar |
| C. Filtro propio tipo MSCKF | Una cámara es su caso natural (no necesita profundidad inicial); el rolling shutter y el desfase temporal entran como estados; código propio y permisivo | Es el trabajo más grande; las implementaciones de referencia son GPL y solo se pueden usar como literatura | Sin empezar |
| D. Exigir dos cámaras traseras como par estéreo | Usaría Basalt tal cual | Android no garantiza abrir dos cámaras a la vez, y el teléfono de referencia no las agrupa; base de dispositivos reducida | Descartada como requisito general |

## Qué falta para decidir

1. Ejecutar OKVIS2 con una cámara sobre las mismas secuencias. Si funciona, medir su error.
   *En curso (Fase B, 2026-11-14): su código acepta una cámara por diseño y el
   experimento sobre EuRoC está montado en `tools/okvis2/`.*
2. ~~Leer cómo Basalt crea puntos nuevos, para estimar el tamaño real de la opción A.~~
   Hecho: [basalt-monocular.md](../findings/basalt-monocular.md). La estimación anterior
   era pesimista: Basalt ya triangula en el tiempo y el bloqueo es una aserción en la
   capa VIT más un acceso fuera de rango. Lo que no se sabe sin medir es si la escala
   converge sin un inicializador monocular.
3. Una secuencia pública con rolling shutter y referencia, para medir cuánto degrada a un
   backend que lo ignora.

Si B funciona con una cámara, es el camino corto y C queda como mejora posterior. Si no, la
elección es entre A y C, y el punto 2 decide.

## Consecuencia inmediata

Mientras tanto el producto en teléfono sigue en 3DoF, que ya está medido y afinado. La
opción de ARCore como backend opcional y aislado (ADR-0001) cobra más peso como puente.
