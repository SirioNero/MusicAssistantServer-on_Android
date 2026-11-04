package it.xonos.ma.phone.ui

import androidx.compose.ui.unit.dp

/**
 * Gli spazi dell'interfaccia: 2, 4, 8, 12, 16, il margine laterale 20, 24 e 32 fra le sezioni della Home. Si
 * usano questi invece di scrivere i dp a mano in ogni composable, così un ritocco vale ovunque.
 */
internal object Space {
    /** 2 dp: fra righe di testo strette. */
    val hair = 2.dp

    /** 4 dp. */
    val tiny = 4.dp

    /** 8 dp. */
    val small = 8.dp

    /** 12 dp: fra copertina e testo. */
    val medium = 12.dp

    /** 16 dp. */
    val large = 16.dp

    /** 20 dp: il margine a sinistra e a destra delle pagine. */
    val gutter = 20.dp

    /** 24 dp. */
    val big = 24.dp

    /** 32 dp: fra le sezioni della Home. */
    val section = 32.dp
}
