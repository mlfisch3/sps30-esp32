package com.sensirion.sps30.wear

/** Shared presentation for every watch surface (app, Tile, complication):
 *  number/unit formatting and the PM2.5 series colour. */
object Format {
    /** The PM2.5 series colour, matching the phone chart's PM2.5 green
     *  (`ui.SeriesColors`). An identity colour - "this is the PM2.5 reading" -
     *  never a judgement about the air. The watch deliberately shows the number
     *  and its unit with no health category attached: the EPA-style category
     *  labels this app once drew were removed because their breakpoints were
     *  out of date and defined for 24-hour averages, not instantaneous samples. */
    const val PM_COLOR = 0xFF2ECC40.toInt()

    /** PM mass-concentration unit, with the real Greek mu and a superscript 3:
     *  "μg/m³" = micrograms per cubic metre. */
    const val PM_UNIT = "μg/m³"

    /** The PM2.5 label, kept as one constant so all surfaces read identically. */
    const val PM_LABEL = "PM2.5"

    /** One decimal below 100, whole numbers above (a 3-digit value needs no tenths). */
    fun pm(v: Double): String =
        if (v >= 100) v.toInt().toString() else String.format("%.1f", v)

    /** e.g. "3.1 μg/m³" - the value with its unit. */
    fun pmWithUnit(v: Double): String = "${pm(v)} $PM_UNIT"
}
