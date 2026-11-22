package com.agrotech.oviapp.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agrotech.oviapp.domain.model.Sex

internal val FieldLightColors = lightColorScheme(
    primary = Color(FieldPalette.PASTURE),
    onPrimary = Color(FieldPalette.ON_PASTURE),
    secondary = Color(FieldPalette.EARTH),
    onSecondary = Color(FieldPalette.ON_EARTH),
    tertiary = Color(FieldPalette.SKY),
    onTertiary = Color(FieldPalette.ON_SKY),
    background = Color(FieldPalette.PAPER),
    onBackground = Color(FieldPalette.INK),
    surface = Color(FieldPalette.PAPER),
    onSurface = Color(FieldPalette.INK),
    error = Color(FieldPalette.ALERT),
    onError = Color(FieldPalette.ON_ALERT),
)

internal val FieldDarkColors = darkColorScheme(
    primary = Color(FieldPalette.NIGHT_PASTURE),
    onPrimary = Color(FieldPalette.NIGHT_ON_PASTURE),
    secondary = Color(FieldPalette.NIGHT_EARTH),
    onSecondary = Color(FieldPalette.NIGHT_ON_EARTH),
    tertiary = Color(FieldPalette.NIGHT_SKY),
    onTertiary = Color(FieldPalette.NIGHT_ON_SKY),
    background = Color(FieldPalette.NIGHT_SURFACE),
    onBackground = Color(FieldPalette.NIGHT_INK),
    surface = Color(FieldPalette.NIGHT_SURFACE),
    onSurface = Color(FieldPalette.NIGHT_INK),
    error = Color(FieldPalette.NIGHT_ALERT),
    onError = Color(FieldPalette.NIGHT_ON_ALERT),
)

private val FieldTypography = Typography(
    bodyLarge = TextStyle(fontSize = FieldDimens.BODY_TEXT_SP.sp),
    bodyMedium = TextStyle(fontSize = (FieldDimens.BODY_TEXT_SP - 2).sp),
    labelLarge = TextStyle(fontSize = FieldDimens.BUTTON_TEXT_SP.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
)

/** Tema de la app: alto contraste y texto grande para leer en exteriores. */
@Composable
fun OviTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) FieldDarkColors else FieldLightColors,
        typography = FieldTypography,
        content = content,
    )
}

/** Botón primario de captura: ancho completo y al menos 64 dp de alto. */
@Composable
fun CaptureButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FieldDimens.CAPTURE_BUTTON_HEIGHT_DP.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Etiqueta de sexo: color rosa/azul acompañado siempre de símbolo y palabra (accesible sin distinguir colores). */
@Composable
fun SexBadge(sex: Sex, modifier: Modifier = Modifier) {
    val identity = SexIdentity.of(sex)
    Surface(
        color = Color(identity.container),
        contentColor = Color(identity.content),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .heightIn(min = FieldDimens.MIN_TOUCH_TARGET_DP.dp)
            .semantics { contentDescription = identity.label },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = identity.symbol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(text = identity.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
