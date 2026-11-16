package com.example.valuamsecurity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var inicio = 0L
    private val irAlLogin = Runnable {
        startActivity(Intent(this, login::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        inicio = savedInstanceState?.getLong("inicio") ?: SystemClock.uptimeMillis()
        handler.postAtTime(irAlLogin, inicio + 5000L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("inicio", inicio)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(irAlLogin)
        super.onDestroy()
    }
}
