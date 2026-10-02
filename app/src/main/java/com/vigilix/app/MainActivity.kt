package com.vigilix.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("vigilix_core")
        }
    }

    private external fun scanThreat(input: String): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val inputTarget = findViewById<EditText>(R.id.etTarget)
        val btnAnalyze = findViewById<Button>(R.id.btnAnalyze)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnAnalyze.setOnClickListener {
            val query = inputTarget.text.toString().trim()
            if (query.isNotEmpty()) {
                val result = scanThreat(query)
                tvStatus.text = result
            } else {
                tvStatus.text = "Por favor ingresa un link, número o nombre de archivo."
            }
        }
    }
}
