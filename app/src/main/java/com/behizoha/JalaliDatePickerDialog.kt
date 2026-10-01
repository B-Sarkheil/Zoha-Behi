package com.behi.zoha

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.NumberPicker
import com.behi.zoha.R

class JalaliDatePickerDialog(
    context: Context,
    private val initialYear: Int,
    private val initialMonth: Int,
    private val initialDay: Int,
    private val onDateSelected: (year: Int, month: Int, day: Int) -> Unit
) {

    private val dialog: AlertDialog

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_jalali_date_picker, null)
        val yearPicker = view.findViewById<NumberPicker>(R.id.yearPicker)
        val monthPicker = view.findViewById<NumberPicker>(R.id.monthPicker)
        val dayPicker = view.findViewById<NumberPicker>(R.id.dayPicker)

        yearPicker.minValue = initialYear - 100
        yearPicker.maxValue = initialYear + 100
        yearPicker.value = initialYear
        yearPicker.wrapSelectorWheel = false

        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        monthPicker.displayedValues = Array(12) { JalaliDate.monthName(it + 1) }
        monthPicker.value = initialMonth
        monthPicker.wrapSelectorWheel = false

        fun updateDayPicker() {
            val maxDay = maxDayOfMonth(yearPicker.value, monthPicker.value)
            dayPicker.minValue = 1
            dayPicker.maxValue = maxDay
            if (dayPicker.value > maxDay) dayPicker.value = maxDay
        }

        dayPicker.minValue = 1
        dayPicker.maxValue = maxDayOfMonth(initialYear, initialMonth)
        dayPicker.value = initialDay
        dayPicker.wrapSelectorWheel = false

        yearPicker.setOnValueChangedListener { _, _, _ -> updateDayPicker() }
        monthPicker.setOnValueChangedListener { _, _, _ -> updateDayPicker() }

        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.select_date)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                onDateSelected(yearPicker.value, monthPicker.value, dayPicker.value)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    fun show() = dialog.show()

    private fun maxDayOfMonth(year: Int, month: Int): Int {
        return if (month <= 6) 31
        else if (month <= 11) 30
        else if (isLeapYear(year)) 30
        else 29
    }

    private fun isLeapYear(year: Int): Boolean {
        val breaks = intArrayOf(1, 5, 9, 13, 17, 22, 26, 30, 34, 38, 42, 46, 50, 54, 58, 62, 66, 70, 74, 78, 82, 86, 90, 94, 98, 102, 106, 110, 114, 118, 122, 126)
        val mod = year % 128
        return mod in breaks
    }
}
