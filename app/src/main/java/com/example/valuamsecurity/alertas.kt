package com.example.valuamsecurity

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import java.text.NumberFormat

private const val API_SENSORES = "https://www.pnk.cl/muestra_datos.php"

class alertas : Pantalla(R.layout.activity_alertas) {
    private val cola by lazy { Volley.newRequestQueue(applicationContext) }
    private lateinit var temperatura: TextView
    private lateinit var humedad: TextView
    private lateinit var estado: TextView
    private lateinit var reintentar: Button
    private var ampolletaEncendida = false
    private var linternaEncendida = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        temperatura = findViewById(R.id.temperatura)
        humedad = findViewById(R.id.humedad)
        estado = findViewById(R.id.estadoDatos)
        reintentar = findViewById(R.id.reintentar)
        reintentar.setOnClickListener { cargarDatos() }
        ampolletaEncendida = savedInstanceState?.getBoolean("ampolleta") ?: false
        linternaEncendida = savedInstanceState?.getBoolean("linterna") ?: false
        findViewById<ImageButton>(R.id.ampolleta).setOnClickListener {
            ampolletaEncendida = !ampolletaEncendida
            mostrarControles()
        }
        findViewById<ImageButton>(R.id.linterna).setOnClickListener {
            linternaEncendida = !linternaEncendida
            mostrarControles()
        }
        mostrarControles()
        cargarDatos()
    }

    private fun mostrarControles() {
        mostrarControl(R.id.ampolleta, R.id.ampolletaEstado, "Ampolleta", ampolletaEncendida,
            R.drawable.ampolleta_on, R.drawable.ampolleta_off)
        mostrarControl(R.id.linterna, R.id.linternaEstado, "Linterna", linternaEncendida,
            R.drawable.linterna_on, R.drawable.linterna_off)
    }

    private fun mostrarControl(botonId: Int, textoId: Int, nombre: String, encendido: Boolean, on: Int, off: Int) {
        val texto = "$nombre\n${if (encendido) "Encendida" else "Apagada"}"
        findViewById<ImageButton>(botonId).apply {
            setImageResource(if (encendido) on else off)
            contentDescription = texto.replace('\n', ' ')
        }
        findViewById<TextView>(textoId).text = texto
    }

    private fun cargarDatos() {
        estado.text = "Consultando sensores…"
        reintentar.visibility = View.GONE
        val solicitud = JsonObjectRequest(Request.Method.GET, API_SENSORES, null, { respuesta ->
            val grados = respuesta.optDouble("temperatura")
            val porcentaje = respuesta.optDouble("humedad")
            if (grados.isFinite() && porcentaje.isFinite() && porcentaje in 0.0..100.0) {
                val formato = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
                temperatura.text = "Temperatura: ${formato.format(grados)} °C"
                humedad.text = "Humedad: ${formato.format(porcentaje)} %"
                val alta = temperaturaAlta(grados)
                findViewById<ImageView>(R.id.temperaturaIcono).apply {
                    setImageResource(if (alta) R.drawable.temperatura_alta else R.drawable.temperatura_baja)
                    contentDescription = if (alta) "Temperatura alta" else "Temperatura baja"
                }
                estado.text = ""
            } else {
                mostrarFallo("La API devolvió datos de sensores inválidos.")
            }
        }, { mostrarFallo("No se pudieron consultar los sensores. Revisa la conexión.") })
        solicitud.setShouldCache(false)
        cola.add(solicitud)
    }

    private fun mostrarFallo(mensaje: String) {
        estado.text = mensaje
        reintentar.visibility = View.VISIBLE
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) error(mensaje)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("ampolleta", ampolletaEncendida)
        outState.putBoolean("linterna", linternaEncendida)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cola.stop()
        super.onDestroy()
    }
}
