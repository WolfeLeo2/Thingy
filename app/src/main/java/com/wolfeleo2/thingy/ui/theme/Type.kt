package com.wolfeleo2.thingy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.wolfeleo2.thingy.R

// Nunito Sans is a variable font; map each weight to its `wght` axis so the type
// scale's per-role weights render correctly from the single ttf.
private fun ns(weight: Int) = Font(
    R.font.nunito_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val NunitoSans = FontFamily(ns(300), ns(400), ns(500), ns(600), ns(700))

// Keep the Expressive type scale (sizes/weights/emphasized styles); swap only the typeface.
private val base = Typography()
val AppTypography = base.copy(
    displayLarge = base.displayLargeEmphasized.copy(fontFamily = NunitoSans),
    displayMedium = base.displayMediumEmphasized.copy(fontFamily = NunitoSans),
    displaySmall = base.displaySmallEmphasized.copy(fontFamily = NunitoSans),
    headlineLarge = base.headlineLargeEmphasized.copy(fontFamily = NunitoSans),
    headlineMedium = base.headlineMediumEmphasized.copy(fontFamily = NunitoSans),
    headlineSmall = base.headlineSmallEmphasized.copy(fontFamily = NunitoSans),
    titleLarge = base.titleLargeEmphasized.copy(fontFamily = NunitoSans),
    titleMedium = base.titleMediumEmphasized.copy(fontFamily = NunitoSans),
    titleSmall = base.titleSmallEmphasized.copy(fontFamily = NunitoSans),
    bodyLarge = base.bodyLargeEmphasized.copy(fontFamily = NunitoSans),
    bodyMedium = base.bodyMediumEmphasized.copy(fontFamily = NunitoSans),
    bodySmall = base.bodySmallEmphasized.copy(fontFamily = NunitoSans),
    labelLarge = base.labelLargeEmphasized.copy(fontFamily = NunitoSans),
    labelMedium = base.labelMediumEmphasized.copy(fontFamily = NunitoSans),
    labelSmall = base.labelSmallEmphasized.copy(fontFamily = NunitoSans),
)
