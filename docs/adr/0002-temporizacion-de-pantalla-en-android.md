# ADR-0002: Temporización de pantalla en Android

Estado: aceptada la medida provisional (2026-11-12). La solución definitiva está propuesta,
sin implementar.

## Contexto

Para que la imagen no "nade" al mover la cabeza, el runtime tiene que saber cuándo llegará
cada frame a la pantalla y pedir la pose para ese instante. En Android, Monado:

- supone 4 ms entre presentar y mostrar, un valor de visor de escritorio;
- estima el vsync con un temporizador (`u_pacing_compositor_fake.c`) en vez de leerlo del sistema.

Medido en un teléfono a 60 Hz ([dispositivo-infinix-x6885.md](../findings/dispositivo-infinix-x6885.md)):
la latencia real es de 26–44 ms (mediana 36), repartida de forma uniforme a lo ancho de un
periodo, y un 6 % de los frames llega un vsync tarde.

## Decisión

**Ahora (parche 0002):** en Android el pacer asume dos periodos de refresco entre presentar y
mostrar. Es la estructura de SurfaceFlinger (el buffer se toma en un vsync y se muestra en el
siguiente) y coincide con la mediana medida. El límite de predicción del driver se amplía en
consecuencia a 5 periodos (parche 0001).

**Después:** un pacer de compositor para Android basado en `AChoreographer`, que da el vsync
real y, desde API 33, el instante de presentación esperado de cada frame. Sustituye la
estimación por el dato del sistema y engancha el compositor en fase.

## Por qué no directamente el pacer con Choreographer

Es una pieza nueva en la parte más sensible del compositor y necesita medirse en varios
dispositivos y tasas de refresco. El modelo de dos periodos es una línea, corrige el error
grande (≈30 ms) y deja el pequeño (±9 ms de fase).

## Consecuencias

- La predicción apunta al instante correcto en promedio; queda un error de fase de hasta
  medio periodo por frame.
- El 6 % de frames tardíos sigue ahí hasta tener el pacer real.
- El modelo de dos periodos está comprobado en un solo dispositivo y a 60 Hz. Hay que
  repetir la medición a 90/120 Hz y en otro SoC antes de proponerlo upstream.
- En hardware propio el camino de presentación se controla y este modelo no aplica: allí
  corresponde el pacer de temporización real de Monado.

## Qué la revertiría

Que en otros dispositivos la latencia no escale con el periodo de refresco. En ese caso el
valor tendría que salir de un perfil por dispositivo (`profiles/`) hasta tener el pacer real.
