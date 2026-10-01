package com.behi.zoha

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object Ui {

    class Progress internal constructor(
        private val dialog: AlertDialog,
        private val message: TextView
    ) {
        fun setMessage(text: String) {
            message.text = text
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    fun progress(context: Context, message: String): Progress {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
        val text = view.findViewById<TextView>(R.id.message)
        text.text = message
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()
        return Progress(dialog, text)
    }

    fun confirm(
        context: Context,
        title: String,
        message: String,
        okText: String,
        onOk: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(okText) { _, _ -> onOk() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
