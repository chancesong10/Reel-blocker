package com.snoozy.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView

// Edit the quick lock lengths offered on the home screen.
class SnoozeOptionsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snooze_options)
        findViewById<TextView>(R.id.pageTitle).text = "⏱  Snooze options"
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.optionsIntro).text =
            "Choose the lock lengths shown on the home screen. Up to ${SnoozeOptions.MAX_OPTIONS}, " +
                "each up to 24 hours."

        findViewById<View>(R.id.addOption).setOnClickListener { showAddDialog() }
        findViewById<View>(R.id.resetOptions).setOnClickListener {
            SnoozeOptions.reset(this)
            render()
        }
        render()
    }

    private fun render() {
        val options = SnoozeOptions.get(this)
        val list = findViewById<LinearLayout>(R.id.optionsList)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (minutes in options) {
            val row = inflater.inflate(R.layout.item_option, list, false)
            row.findViewById<TextView>(R.id.optionLabel).text = SnoozeOptions.label(minutes)
            row.findViewById<View>(R.id.optionRemove).setOnClickListener {
                SnoozeOptions.remove(this, minutes)
                render()
            }
            list.addView(row)
        }

        val add = findViewById<TextView>(R.id.addOption)
        val full = options.size >= SnoozeOptions.MAX_OPTIONS
        add.isEnabled = !full
        add.text = if (full) "Full: remove one to add another" else "+  Add option"
        add.setTextColor(getColor(if (full) R.color.ink_soft else R.color.card))
    }

    // Two wheels: hours 0–24 and minutes in 5-minute steps. 24h locks minutes
    // to 0 so nothing can go past 24 hours.
    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_option, null)
        val dialog = Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val hours = view.findViewById<NumberPicker>(R.id.pickHours)
        val minutes = view.findViewById<NumberPicker>(R.id.pickMinutes)
        val addYes = view.findViewById<TextView>(R.id.addYes)
        val error = view.findViewById<TextView>(R.id.addError)

        hours.minValue = 0
        hours.maxValue = SnoozeOptions.MAX_MINUTES / 60
        hours.value = 1
        val minuteSteps = (0 until 60 step SnoozeOptions.MINUTE_STEP).toList()
        minutes.minValue = 0
        minutes.maxValue = minuteSteps.size - 1
        minutes.displayedValues = minuteSteps.map { "%02d".format(it) }.toTypedArray()
        minutes.value = 0

        fun total() = hours.value * 60 + minuteSteps[minutes.value]
        fun update() {
            if (hours.value == hours.maxValue) minutes.value = 0
            minutes.isEnabled = hours.value < hours.maxValue
            addYes.text = if (total() == 0) "Pick a time" else "Add ${SnoozeOptions.label(total())}"
            error.visibility = View.GONE
        }
        hours.setOnValueChangedListener { _, _, _ -> update() }
        minutes.setOnValueChangedListener { _, _, _ -> update() }
        update()

        addYes.setOnClickListener {
            val problem = SnoozeOptions.add(this, total())
            if (problem == null) {
                dialog.dismiss()
                render()
            } else {
                error.text = problem
                error.visibility = View.VISIBLE
            }
        }
        view.findViewById<View>(R.id.addNo).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
