# Lineamientos de UX de campo (APP-26)

Reglas para cualquier pantalla de captura. El código vive en `androidApp/src/main/kotlin/com/agrotech/oviapp/android/ui/theme/`.

| Regla | Valor | Dónde |
| :--- | :--- | :--- |
| Objetivo táctil mínimo | **56 dp** | `FieldDimens.MIN_TOUCH_TARGET_DP` |
| Botón primario de captura | **64 dp** de alto, ancho completo | `CaptureButton` |
| Texto de cuerpo / botones | 18 sp / 20 sp en negritas | `OviTheme` (tipografía) |
| Contraste texto-fondo | **≥ 7:1** (WCAG AAA) en tema claro y oscuro | `FieldPalette.textPairs`, verificado por `FieldDesignTest` |
| Sexo del animal | Rosa (hembra) / azul (macho) **siempre con símbolo y palabra** (♀ Hembra, ♂ Macho) | `SexBadge`, `SexIdentity` |

## Contrastes verificados

| Par | Contraste |
| :--- | :---: |
| Texto `#111111` sobre papel `#FFFFFF` | 18.9 : 1 |
| Botón principal: blanco sobre verde `#1B5E20` | 7.9 : 1 |
| Secundario: blanco sobre café `#4E342E` | 11.3 : 1 |
| Terciario: blanco sobre azul `#0D47A1` | 8.6 : 1 |
| Alerta: blanco sobre `#8E0000` | 9.8 : 1 |
| Hembra `#880E4F` sobre `#FCE4EC` | 7.9 : 1 |
| Macho `#0D47A1` sobre `#E3F2FD` | 7.6 : 1 |
| Tema oscuro: hembra `#F48FB1` / macho `#90CAF9` / verde `#A5D6A7` sobre `#121212` | 8.4 / 10.7 / 11.4 : 1 |
| Tema oscuro: secundario `#2B1A12` sobre `#D7CCC8` / terciario `#00264D` sobre `#90CAF9` / alerta `#3B0000` sobre `#FFB4AB` | 10.6 / 8.7 / 10.4 : 1 |
| Campo de texto: etiqueta enfocada `#1B5E20` / texto `#111111` / etiqueta sin foco `#333333` sobre `#F3F6F1` | 7.2 / 17.3 / 11.6 : 1 |
| Tema oscuro, campo de texto: etiqueta enfocada `#A5D6A7` / texto `#FFFFFF` / etiqueta sin foco `#E0E0E0` sobre `#2A2A2A` | 8.7 / 14.4 / 10.9 : 1 |

## Vocabulario del ganadero

Usar siempre estas palabras en etiquetas y botones (`FieldVocabulary`): **Arete**, **Semental**, **Vientre**, **Destete**, **Cría**, **Parto**, **Pesaje**. Evitar términos técnicos o en inglés ("tag", "dam", "sire", "weaning").

## Checklist para una pantalla nueva

- [ ] Botones de captura con `CaptureButton` (o al menos `heightIn(min = 56.dp)`).
- [ ] El sexo se muestra con `SexBadge`, nunca solo con color.
- [ ] Colores tomados de `OviTheme` / `FieldPalette` (todos los roles de Material del tema están definidos explícitamente); si se agrega un par nuevo de texto-fondo, sumarlo a `FieldPalette.textPairs` para que el test lo verifique.
- [ ] Textos con el vocabulario del ganadero.
