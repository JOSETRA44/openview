# ADR-0003: Seguimiento 6DoF con una sola cámara

Estado: **decidida (2026-11-14)**. Basalt parcheado es el backend del teléfono; OKVIS2 queda
como respaldo. La decisión se tomó con las dos opciones medidas en datos de visor; lo que
queda por medir es el teléfono real (calibración, driver de cámara y rolling shutter).

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

1. ~~Ejecutar OKVIS2 con una cámara sobre las mismas secuencias. Si funciona, medir su error.~~
   Hecho: corre sin parchear y mide **4,53 cm de ATE, 99,96 % de cobertura, 1,6× tiempo
   real** en EuRoC V1_01_easy ([vio-evaluacion.md](../findings/vio-evaluacion.md)).
2. ~~Leer cómo Basalt crea puntos nuevos, para estimar el tamaño real de la opción A.~~
   Hecho: [basalt-monocular.md](../findings/basalt-monocular.md). La estimación anterior
   era pesimista: Basalt ya triangula en el tiempo y el bloqueo es una aserción en la
   capa VIT más un acceso fuera de rango. Lo que no se sabe sin medir es si la escala
   converge sin un inicializador monocular.
3. Una secuencia pública con rolling shutter y referencia, para medir cuánto degrada a un
   backend que lo ignora.

Si B funciona con una cámara, es el camino corto y C queda como mejora posterior. Si no, la
elección es entre A y C, y el punto 2 decide.

## Actualización 2026-11-14 (Fase B): la opción A medida

El punto 2 se resolvió y la opción A dejó de ser una estimación: Basalt ya triangula en el
tiempo y el bloqueo era una aserción de su capa VIT más un acceso fuera de rango. Con el
parche de dos cambios ([patches/basalt](../../patches/basalt/README.md)), el experimento
corrió y midió **0,9–2,0 cm de ATE, 99–100 % de cobertura y 3,0–4,9× tiempo real** con una
cámara en las mismas secuencias, sin ajustar escala: la convergencia de escala, la
incógnita que el estudio marcaba como riesgo real, se da en datos de visor. Detalle en
[vio-evaluacion.md](../findings/vio-evaluacion.md).

## Decisión (2026-11-14): Basalt parcheado como motor, OKVIS2 como respaldo

La comparación completa, en datos de visor y sin ajustar escala:

| Backend | Secuencias | ATE RMSE | Cobertura | Velocidad |
|---|---|---|---|---|
| Basalt + parche (opción A) | MOO09/10/11/07 | 0,9–2,0 cm | 99–100 % | 3,0–4,9× |
| OKVIS2 (opción B) | EuRoC V1_01_easy | 4,53 cm | 99,96 % | 1,6× |

Matiz: no es un cara a cara en la misma pista (secuencias distintas), pero en los números
disponibles Basalt mide ~2–5× mejor y corre ~2–3× más rápido, y ya tiene la integración
VIT que el runtime usa. OKVIS2 queda como respaldo: funciona con una cámara sin
parchear, pero le faltan el adaptador VIT y velocidad. La opción C (filtro propio) queda
aparcada: no hay razón para construirla mientras el camino A funcione.

Lo que la decisión NO cubre, y queda como trabajo siguiente: el punto 3 (rolling
shutter), la calibración real de la cámara del teléfono y el driver de cámara en
Android. El producto en teléfono sigue en 3DoF hasta que ese experimento mida.

## Consecuencia inmediata

El siguiente trabajo es el experimento con la captura real del Infinix: calibración
nominal, driver de cámara y medición sobre datos de rolling shutter. La opción de
ARCore como backend opcional y aislado (ADR-0001) sigue disponible como puente si
ese experimento sale mal.
