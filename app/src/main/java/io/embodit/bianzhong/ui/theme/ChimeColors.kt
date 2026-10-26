package io.embodit.bianzhong.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's brand tokens (design package `palette.brandTokens`). This is the only file with colour
 * literals. They colour domain content only (bells, rack, plates, ripples, snap outlines, ghost); every
 * panel colour comes from the inherited SpatialUI scheme.
 */
object ChimeColors {
    val Bronze = Color(0xFF8C7349) // design-style: fixed-figma-color bronze: bell body
    val BronzeDark = Color(0xFF4E3F26) // design-style: fixed-figma-color bronzeDark: bands, recesses
    val Patina = Color(0xFF5E8A78) // design-style: fixed-figma-color patina: verdigris mottling
    val GoldInlay = Color(0xFFE0B85A) // design-style: fixed-figma-color goldInlay: decals, ripples, snap frames
    val LacquerBlack = Color(0xFF221C18) // design-style: fixed-figma-color lacquerBlack: rack
    val Cinnabar = Color(0xFFB5382A) // design-style: fixed-figma-color cinnabar: beam band
    val PlateInk = Color(0xFF2A2108) // design-style: fixed-figma-color plateInk: plate ground
    val TapeInk = Color(0xFFF3E7C9) // design-style: fixed-figma-color tapeInk: plate text
    val ErrorRed = Color(0xFFFF5F52) // design-style: fixed-figma-color error: ghost too close
    val InkOnFill = Color(0xFF16181C) // design-style: fixed-figma-color ink: text on the interaction fill
}
