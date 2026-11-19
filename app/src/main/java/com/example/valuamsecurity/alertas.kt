package com.example.valuamsecurity

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
    private val camaras by lazy { getSystemService(CameraManager::class.java) }
    private var camaraFlash: String? = null
    private val permisoCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) alternarLinterna() else error("Permite el acceso a la cámara para utilizar la linterna.")
    }
    private val cambiosLinterna = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == camaraFlash) { linternaEncendida = enabled; mostrarControles() }
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == camaraFlash) { linternaEncendida = false; mostrarControles() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        temperatura = findViewById(R.id.temperatura)
        humedad = findViewById(R.id.humedad)
        estado = findViewById(R.id.estadoDatos)
        reintentar = findViewById(R.id.reintentar)
        reintentar.setOnClickListener { cargarDatos() }
        ampolletaEncendida = savedInstanceState?.getBoolean("ampolleta") ?: false
        camaraFlash = runCatching {
            camaras.cameraIdList.firstOrNull { camaras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
        camaras.registerTorchCallback(cambiosLinterna, Handler(Looper.getMainLooper()))
        findViewById<ImageButton>(R.id.ampolleta).setOnClickListener {
            ampolletaEncendida = !ampolletaEncendida
            mostrarControles()
        }
        findViewById<ImageButton>(R.id.linterna).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                permisoCamara.launch(Manifest.permission.CAMERA)
            else alternarLinterna()
        }
        mostrarControles()
        cargarDatos()
    }

    private fun alternarLinterna() {
        val id = camaraFlash
        if (id == null) { error("Este teléfono no tiene una linterna disponible."); return }
        runCatching { camaras.setTorchMode(id, !linternaEncendida) }
            .onFailure { error("La linterna está ocupada o no se pudo encender.") }
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
        usuarios.ultimaLectura()?.let { lectura ->
            temperatura.text = "Temperatura: ${lectura.optDouble("temperatura")} °C"
            humedad.text = "Humedad: ${lectura.optDouble("humedad")} %"
            estado.text = "Última lectura guardada: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lectura.getLong("obtenida")))}"
        }
        if (!Servidor.conectado(this)) {
            if (usuarios.ultimaLectura() == null) estado.text = "Sin conexión. Aún no hay lecturas guardadas."
            reintentar.visibility = View.VISIBLE
            return
        }
        estado.text = "Consultando sensores…"
        reintentar.visibility = View.GONE
        val solicitud = JsonObjectRequest(Request.Method.GET, API_SENSORES, null, { respuesta ->
            val grados = respuesta.optDouble("temperatura")
            val porcentaje = respuesta.optDouble("humedad")
            if (grados.isFinite() && porcentaje.isFinite() && porcentaje in 0.0..100.0) {
                val formato = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
                temperatura.text = "Temperatura: ${formato.format(grados)} °C"
                humedad.text = "Humedad: ${formato.format(porcentaje)} %"
                usuarios.guardarLectura(grados, porcentaje)
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
        camaras.unregisterTorchCallback(cambiosLinterna)
        camaraFlash?.let { if (linternaEncendida) runCatching { camaras.setTorchMode(it, false) } }
        cola.stop()
        super.onDestroy()
    }
}
