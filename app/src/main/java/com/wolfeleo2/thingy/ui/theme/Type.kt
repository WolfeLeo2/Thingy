package com.wolfeleo2.thingy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.wolfeleo2.thingy.R

// Google Sans Flex is a variable font; map each weight to its `wght` axis so the type
// scale's per-role weights render correctly from the single ttf.
private fun gsf(weight: Int) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val GoogleSansFlex = FontFamily(gsf(300), gsf(400), gsf(500), gsf(600), gsf(700))

// Keep the Expressive type scale (sizes/weights/emphasized styles); swap only the typeface.
private val base = Typography()
val AppTypography = base.copy(
    displayLarge = base.displayLargeEmphasized.copy(fontFamily = GoogleSansFlex),
    displayMedium = base.displayMediumEmphasized.copy(fontFamily = GoogleSansFlex),
    displaySmall = base.displaySmallEmphasized.copy(fontFamily = GoogleSansFlex),
    headlineLarge = base.headlineLargeEmphasized.copy(fontFamily = GoogleSansFlex),
    headlineMedium = base.headlineMediumEmphasized.copy(fontFamily = GoogleSansFlex),
    headlineSmall = base.headlineSmallEmphasized.copy(fontFamily = GoogleSansFlex),
    titleLarge = base.titleLargeEmphasized.copy(fontFamily = GoogleSansFlex),
    titleMedium = base.titleMediumEmphasized.copy(fontFamily = GoogleSansFlex),
    titleSmall = base.titleSmallEmphasized.copy(fontFamily = GoogleSansFlex),
    bodyLarge = base.bodyLargeEmphasized.copy(fontFamily = GoogleSansFlex),
    bodyMedium = base.bodyMediumEmphasized.copy(fontFamily = GoogleSansFlex),
    bodySmall = base.bodySmallEmphasized.copy(fontFamily = GoogleSansFlex),
    labelLarge = base.labelLargeEmphasized.copy(fontFamily = GoogleSansFlex),
    labelMedium = base.labelMediumEmphasized.copy(fontFamily = GoogleSansFlex),
    labelSmall = base.labelSmallEmphasized.copy(fontFamily = GoogleSansFlex),
)
