package com.kylecorry.trail_sense.shared.colors

import androidx.annotation.ColorInt
import com.kylecorry.trail_sense.shared.data.Identifiable

// Topo: a sixteen-grey panel shows no hue, so every entry is a grey, each distinct (colours are
// looked up by value). The names stay, because stored paths and beacons refer to them by id.
enum class AppColor(override val id: Long, @ColorInt override val color: Int) : IAppColor {
    Red(0, 0xFF1A1A1A.toInt()),
    Orange(1, 0xFF000000.toInt()),     // black: the default for tracks and marks
    Yellow(2, 0xFF8C8C8C.toInt()),
    Green(3, 0xFFC8C8C8.toInt()),
    Blue(4, 0xFFB4B4B4.toInt()),
    Purple(5, 0xFF5A5A5A.toInt()),
    Pink(6, 0xFFA8A8A8.toInt()),
    Gray(7, 0xFF9E9E9E.toInt()),
    Brown(8, 0xFF6E6E6E.toInt()),
    DarkBlue(9, 0xFF3C3C3C.toInt()),
}

/** The greys offered when picking a colour: five steps the panel keeps apart. */
val PICKABLE_COLORS = listOf(AppColor.Orange, AppColor.DarkBlue, AppColor.Brown, AppColor.Gray, AppColor.Green)

fun Array<AppColor>.fromColor(@ColorInt color: Int): AppColor? {
    return firstOrNull { it.color == color }
}

interface IAppColor : Identifiable {
    val color: Int
}
