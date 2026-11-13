package app.detent.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.detent.R

@OptIn(ExperimentalTextApi::class)
/** Inter, the typeface in the design. One variable font file, used at the four weights the design needs. */
val InterFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.inter, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

/** Archivo at its widest, for the wordmark and screen titles. Set in capitals with a little tracking where it is used. */
@OptIn(ExperimentalTextApi::class)
val ArchivoExpanded = FontFamily(
    Font(
        R.font.archivo, FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.width(125f)),
    ),
)

/** IBM Plex Mono, for the engraved labels, times and small specs. */
val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)
