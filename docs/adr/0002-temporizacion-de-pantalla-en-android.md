# ADR-0002: Temporización de pantalla en Android

Estado: aceptada (2026-11-12). Ambas partes implementadas y medidas en un dispositivo.

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

**Enganche de fase (parche 0003):** un hilo recibe el vsync de `AChoreographer` y se lo pasa
al pacer por el mismo camino que ya usa el modo directo de escritorio
(`update_vblank_from_display_control`). Resultado medido: frames descartados de ~6 % a 0.2 % y
latencia estable en 29–32 ms.

**Pendiente:** desde API 33 `AChoreographer` da además el instante de presentación esperado y
la fecha límite de cada frame. Usarlos sustituiría el modelo de dos periodos por el dato del
sistema. No se ha hecho: el modelo acierta con 2 ms de error en el dispositivo medido.

## Por qué el enganche es tan pequeño

El pacer de Monado ya sabía realinearse con un vblank real; solo le faltaba una fuente en
Android. Alimentarlo desde `Choreographer` son unas 60 líneas en `comp_target_swapchain.c` y
no toca la lógica de predicción.

## Consecuencias

- La predicción apunta al instante correcto con ~2 ms de error en el dispositivo medido.
- La fase por defecto deja unos 3 ms de margen antes de perder el vsync. Falta comprobarlo
  con una app exigente.
- Solo ABIs de 64 bits: la variante de la API disponible desde nuestro nivel mínimo pasa el
  tiempo en un `long`.
- El modelo de dos periodos está comprobado en un solo dispositivo y a 60 Hz. Hay que
  repetir la medición a 90/120 Hz y en otro SoC antes de proponerlo upstream.
- En hardware propio el camino de presentación se controla y este modelo no aplica: allí
  corresponde el pacer de temporización real de Monado.

## Qué la revertiría

Que en otros dispositivos la latencia no escale con el periodo de refresco. En ese caso el
valor tendría que salir de un perfil por dispositivo (`profiles/`) hasta tener el pacer real.
