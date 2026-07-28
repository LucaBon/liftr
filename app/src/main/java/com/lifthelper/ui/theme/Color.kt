package com.lifthelper.ui.theme

import androidx.compose.ui.graphics.Color

// ── App Brand Palette — "Volt Performance" ─────────────────────────
// Dark-first, athletic identity: near-black surfaces, an electric volt
// (lime) primary for energy and big performance numbers, with a cool
// cyan secondary and electric-blue tertiary for data accents.

// Volt — primary accent (electric lime)
val Volt = Color(0xFFCBFF1F)
val VoltBright = Color(0xFFD9FF4D)
val VoltDeep = Color(0xFF9FCC00)

// Primary
val PrimaryLight = Color(0xFF4A6300)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFC8F542)
val OnPrimaryContainerLight = Color(0xFF141F00)

val PrimaryDark = Volt
val OnPrimaryDark = Color(0xFF0B0E04)
val PrimaryContainerDark = Color(0xFF2C3A00)
val OnPrimaryContainerDark = Color(0xFFD9FF66)

// Secondary — electric cyan/teal
val SecondaryLight = Color(0xFF0F6E61)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFF9DF2E2)
val OnSecondaryContainerLight = Color(0xFF00201B)

val SecondaryDark = Color(0xFF2EE6C5)
val OnSecondaryDark = Color(0xFF00382F)
val SecondaryContainerDark = Color(0xFF124A41)
val OnSecondaryContainerDark = Color(0xFF8FF6E1)

// Tertiary — electric blue/violet for chart + data accents
val TertiaryLight = Color(0xFF3A4ED6)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFDEE0FF)
val OnTertiaryContainerLight = Color(0xFF000C5F)

val TertiaryDark = Color(0xFF9AA7FF)
val OnTertiaryDark = Color(0xFF11215C)
val TertiaryContainerDark = Color(0xFF2A3A8F)
val OnTertiaryContainerDark = Color(0xFFDEE0FF)

// Error
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFF6B6B)
val OnErrorDark = Color(0xFF5F0008)
val ErrorContainerDark = Color(0xFF8E1015)
val OnErrorContainerDark = Color(0xFFFFDAD6)

// Surfaces — Light
val BackgroundLight = Color(0xFFFBFCF7)
val OnBackgroundLight = Color(0xFF1A1C16)
val SurfaceLight = Color(0xFFFBFCF7)
val OnSurfaceLight = Color(0xFF1A1C16)
val SurfaceVariantLight = Color(0xFFE5E7DC)
val OnSurfaceVariantLight = Color(0xFF474A41)
val OutlineLight = Color(0xFF777A6F)
val OutlineVariantLight = Color(0xFFC7CABA)

// Surfaces — Dark (near-black, neutral-cool charcoal stack)
val BackgroundDark = Color(0xFF0A0B0D)
val OnBackgroundDark = Color(0xFFF1F3F4)
val SurfaceDark = Color(0xFF0A0B0D)
val OnSurfaceDark = Color(0xFFF1F3F4)
val SurfaceVariantDark = Color(0xFF20242A)
val OnSurfaceVariantDark = Color(0xFFA3AAB2)
val OutlineDark = Color(0xFF3A3F46)
val OutlineVariantDark = Color(0xFF24282E)

// Surface container roles (tonal elevation, dark)
val SurfaceDimDark = Color(0xFF0A0B0D)
val SurfaceBrightDark = Color(0xFF2A2E34)
val SurfaceContainerLowestDark = Color(0xFF050608)
val SurfaceContainerLowDark = Color(0xFF121418)
val SurfaceContainerDark = Color(0xFF16191D)
val SurfaceContainerHighDark = Color(0xFF1C2026)
val SurfaceContainerHighestDark = Color(0xFF23272E)

// Brand gradient endpoints (volt → cyan)
val GradientVoltStart = Color(0xFFD9FF4D)
val GradientVoltEnd = Color(0xFF2EE6C5)

// ── Domain Colors (unchanged) ──────────────────────────────────────

// Velocity Zone Colors
val VelocityRed = Color(0xFFF44336)
val VelocityOrange = Color(0xFFFF9800)
val VelocityYellow = Color(0xFFFFEB3B)
val VelocityLightGreen = Color(0xFF8BC34A)
val VelocityGreen = Color(0xFF4CAF50)

// VBT Zone Colors (Gonzalez-Badillo zones)
val VbtGrinding = Color(0xFFE53935)
val VbtMaxStrength = Color(0xFFFF9800)
val VbtStrengthSpeed = Color(0xFFFFEB3B)
val VbtSpeedStrength = Color(0xFF4CAF50)
val VbtSpeedPower = Color(0xFF2196F3)
val VbtStopAlert = Color(0xFFD32F2F)

// Chart Colors
val ChartPrimary = Color(0xFF006B5E)
val ChartBackSquat = Color(0xFF4CAF50)
val ChartFrontSquat = Color(0xFF8BC34A)
val ChartBenchPress = Color(0xFF2196F3)
val ChartDeadlift = Color(0xFFFF9800)
val ChartClean = Color(0xFF9C27B0)
val ChartSnatch = Color(0xFFE91E63)

// Bar Path Overlay Colors
val BarPathGreen = Color(0xCC4CAF50)    // >0.75 m/s — fast/explosive
val BarPathYellow = Color(0xCCFFEB3B)   // 0.50-0.75 — moderate
val BarPathOrange = Color(0xCCFF9800)   // 0.30-0.50 — slow
val BarPathRed = Color(0xCCF44336)      // <0.30 — grinding
val BarPathReference = Color(0x80FFFFFF) // Dashed reference line
val BarPathStart = Color(0xFF4CAF50)     // Start marker
val BarPathEnd = Color(0xFFF44336)       // End marker

// PR Celebration
val PrGold = Color(0xFFFFD700)
