package com.agrotech.oviapp.android.ui.theme

import com.agrotech.oviapp.domain.model.Sex
import kotlin.math.pow

/**
 * Lineamientos de UX para usar la app en el corral: botones grandes para manos con guantes o sucias,
 * contraste alto para leer bajo el sol y lenguaje del ganadero.
 */
object FieldDimens {
    /** Objetivo táctil mínimo de cualquier control de captura. */
    const val MIN_TOUCH_TARGET_DP = 56

    /** Botones primarios de captura (Guardar, Registrar parto, Pesar…). */
    const val CAPTURE_BUTTON_HEIGHT_DP = 64

    const val BODY_TEXT_SP = 18
    const val BUTTON_TEXT_SP = 20
    const val SCREEN_PADDING_DP = 16
}

/**
 * Colores en ARGB. Todas las combinaciones de texto/fondo de [textPairs] pasan [FieldContrast.OUTDOOR_MINIMUM_RATIO].
 * Cada rol de color que usa [OviTheme] debe estar aquí para que el test lo verifique.
 */
object FieldPalette {
    const val INK = 0xFF111111
    const val PAPER = 0xFFFFFFFF
    const val PASTURE = 0xFF1B5E20
    const val ON_PASTURE = 0xFFFFFFFF
    const val ALERT = 0xFF8E0000
    const val ON_ALERT = 0xFFFFFFFF
    const val EARTH = 0xFF4E342E
    const val ON_EARTH = 0xFFFFFFFF
    const val SKY = 0xFF0D47A1
    const val ON_SKY = 0xFFFFFFFF

    /** Rosa de hembra y azul de macho, siempre acompañados de texto y símbolo (ver [SexIdentity]). */
    const val FEMALE = 0xFF880E4F
    const val FEMALE_CONTAINER = 0xFFFCE4EC
    const val MALE = 0xFF0D47A1
    const val MALE_CONTAINER = 0xFFE3F2FD

    const val NIGHT_SURFACE = 0xFF121212
    const val NIGHT_INK = 0xFFFFFFFF
    const val NIGHT_PASTURE = 0xFFA5D6A7
    const val NIGHT_ON_PASTURE = 0xFF003300
    const val NIGHT_EARTH = 0xFFD7CCC8
    const val NIGHT_ON_EARTH = 0xFF2B1A12
    const val NIGHT_SKY = 0xFF90CAF9
    const val NIGHT_ON_SKY = 0xFF00264D
    const val NIGHT_ALERT = 0xFFFFB4AB
    const val NIGHT_ON_ALERT = 0xFF3B0000
    const val NIGHT_FEMALE = 0xFFF48FB1
    const val NIGHT_MALE = 0xFF90CAF9

    val textPairs: Map<String, Pair<Long, Long>> = mapOf(
        "Texto sobre papel" to (INK to PAPER),
        "Botón principal" to (ON_PASTURE to PASTURE),
        "Secundario" to (ON_EARTH to EARTH),
        "Terciario" to (ON_SKY to SKY),
        "Alerta" to (ON_ALERT to ALERT),
        "Hembra" to (FEMALE to FEMALE_CONTAINER),
        "Macho" to (MALE to MALE_CONTAINER),
        "Texto nocturno" to (NIGHT_INK to NIGHT_SURFACE),
        "Botón principal nocturno" to (NIGHT_ON_PASTURE to NIGHT_PASTURE),
        "Secundario nocturno" to (NIGHT_ON_EARTH to NIGHT_EARTH),
        "Terciario nocturno" to (NIGHT_ON_SKY to NIGHT_SKY),
        "Alerta nocturna" to (NIGHT_ON_ALERT to NIGHT_ALERT),
        "Hembra nocturna" to (NIGHT_FEMALE to NIGHT_SURFACE),
        "Macho nocturno" to (NIGHT_MALE to NIGHT_SURFACE),
    )
}

/** Relación de contraste WCAG 2.x entre dos colores ARGB opacos. */
object FieldContrast {
    /** WCAG AAA para texto normal: legible a pleno sol en el corral. */
    const val OUTDOOR_MINIMUM_RATIO = 7.0

    fun ratio(foreground: Long, background: Long): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}

/** El sexo nunca se comunica solo con color: rosa/azul + símbolo + palabra. */
data class SexIdentity(
    val label: String,
    val symbol: String,
    val content: Long,
    val container: Long,
) {
    companion object {
        fun of(sex: Sex): SexIdentity = when (sex) {
            Sex.FEMALE -> SexIdentity("Hembra", "♀", FieldPalette.FEMALE, FieldPalette.FEMALE_CONTAINER)
            Sex.MALE -> SexIdentity("Macho", "♂", FieldPalette.MALE, FieldPalette.MALE_CONTAINER)
        }
    }
}

/** Palabras del ganadero para etiquetas y botones. */
object FieldVocabulary {
    const val EAR_TAG = "Arete"
    const val STUD_RAM = "Semental"
    const val BREEDING_EWE = "Vientre"
    const val WEANING = "Destete"
    const val LAMB = "Cría"
    const val BIRTH = "Parto"
    const val WEIGHING = "Pesaje"
}
