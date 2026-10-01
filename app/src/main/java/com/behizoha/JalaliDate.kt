package com.behi.zoha

object JalaliDate {

    private val MONTHS = arrayOf(
        "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
        "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand"
    )

    // Convert Gregorian date to Jalali; returns Triple(year, month, day)
    fun fromGregorian(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 +
                (gy2 + 399) / 400 + gd + cum[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
        val jd = 1 + if (days < 186) days % 31 else (days - 186) % 30
        return Triple(jy, jm, jd)
    }

    // Convert Jalali date to Gregorian; returns Triple(year, month, day)
    fun toGregorian(jy: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
        val jy2 = jy + 1595
        var days = -355668 + 365 * jy2 + (jy2 / 33) * 8 + ((jy2 % 33) + 3) / 4 + jd +
                (if (jm < 7) (jm - 1) * 31 else (jm - 7) * 30 + 186)
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            days--
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val leap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
        val months = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        while (gm < 12 && gd > months[gm]) {
            gd -= months[gm]
            gm++
        }
        return Triple(gy, gm + 1, gd)
    }

    // "2025-03-21" (Gregorian) -> "1404/01/01" (Jalali)
    fun formatFromIso(iso: String): String {
        val p = iso.split("-")
        if (p.size != 3) return iso
        val (y, m, d) = fromGregorian(p[0].toInt(), p[1].toInt(), p[2].toInt())
        return "%04d/%02d/%02d".format(y, m, d)
    }

    // "1404/01/01" -> "2025-03-21"
    fun toIso(jalali: String): String? {
        val p = jalali.trim().split("/")
        if (p.size != 3) return null
        val (y, m, d) = toGregorian(p[0].toInt(), p[1].toInt(), p[2].toInt())
        return "%04d-%02d-%02d".format(y, m, d)
    }

    fun monthName(m: Int) = MONTHS[m - 1]
}