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
| Alerta: blanco sobre `#8E0000` | 9.8 : 1 |
| Hembra `#880E4F` sobre `#FCE4EC` | 7.9 : 1 |
| Macho `#0D47A1` sobre `#E3F2FD` | 7.6 : 1 |
| Tema oscuro: hembra `#F48FB1` / macho `#90CAF9` / verde `#A5D6A7` sobre `#121212` | 8.4 / 10.7 / 11.4 : 1 |

## Vocabulario del ganadero

Usar siempre estas palabras en etiquetas y botones (`FieldVocabulary`): **Arete**, **Semental**, **Vientre**, **Destete**, **Cría**, **Parto**, **Pesaje**. Evitar términos técnicos o en inglés ("tag", "dam", "sire", "weaning").

## Checklist para una pantalla nueva

- [ ] Botones de captura con `CaptureButton` (o al menos `heightIn(min = 56.dp)`).
- [ ] El sexo se muestra con `SexBadge`, nunca solo con color.
- [ ] Colores tomados de `OviTheme` / `FieldPalette`; si se agrega un par nuevo de texto-fondo, sumarlo a `FieldPalette.textPairs` para que el test lo verifique.
- [ ] Textos con el vocabulario del ganadero.
