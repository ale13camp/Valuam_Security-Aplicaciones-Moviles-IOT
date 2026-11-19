package com.example.valuamsecurity

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import org.json.JSONObject

internal const val USUARIO_ID = "usuario_id"

class Recuperar : Pantalla(R.layout.activity_recuperar) {
    private var recuperacion = ""
    private var limite = 0L
    private var reloj: CountDownTimer? = null
    private var verificando = false
    private var abriendoClave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recuperacion = savedInstanceState?.getString("recuperacion") ?: usuarios.base.meta("recuperacion")
        limite = savedInstanceState?.getLong("limite") ?: usuarios.base.meta("recuperacion_limite").toLongOrNull() ?: 0
        val correo = findViewById<EditText>(R.id.correo)
        if (savedInstanceState == null) correo.setText(usuarios.base.meta("recuperacion_correo"))
        val recuperar = findViewById<Button>(R.id.recuperar)
        val verificar = findViewById<Button>(R.id.verificar)
        val casillas = listOf(R.id.digito1, R.id.digito2, R.id.digito3, R.id.digito4, R.id.digito5, R.id.digito6)
            .map { findViewById<EditText>(it) }
        casillas.zipWithNext().forEach { (actual, siguiente) -> actual.doAfterTextChanged {
            if (actual.hasFocus() && it?.length == 1) siguiente.requestFocus()
        } }
        if (recuperacion.isNotBlank()) mostrarReloj()
        recuperar.setOnClickListener {
            if (!correoValido(correo)) return@setOnClickListener
            if (!Servidor.conectado(this)) { error("Necesitas Internet para recibir el código por correo."); return@setOnClickListener }
            recuperar.isEnabled = false
            val email = correo.text.toString().trim()
            ejecutarEnPantalla({
                val datos = Servidor.solicitar(this, JSONObject().put("accion", "recuperar").put("correo", email))
                usuarios.base.meta("recuperacion", datos.getString("recuperacion"))
                usuarios.base.meta("recuperacion_correo", email)
                usuarios.base.meta("recuperacion_limite", (SystemClock.elapsedRealtime() +
                    ((datos.getLong("vence") - datos.getLong("ahora")) * 1000).coerceAtLeast(0)).toString())
                usuarios.porCorreo(email)?.let { usuarios.invalidarAcceso(it.id) }
                datos
            }) { r ->
                recuperar.isEnabled = true
                r.onSuccess { datos ->
                    recuperacion = datos.getString("recuperacion")
                    limite = SystemClock.elapsedRealtime() + ((datos.getLong("vence") - datos.getLong("ahora")) * 1000).coerceAtLeast(0)
                    usuarios.porCorreo(email)?.let { usuarios.invalidarAcceso(it.id) }
                    casillas.forEach { it.text.clear() }
                    mostrarReloj()
                    exito("Código enviado. Revisa tu correo.")
                }.onFailure { error(it.message ?: "No se pudo enviar el código.") }
            }
        }
        verificar.setOnClickListener {
            val codigo = casillas.joinToString("") { it.text.toString() }
            if (!Regex("[0-9]{6}").matches(codigo)) { error("Completa los seis dígitos."); return@setOnClickListener }
            verificar.isEnabled = false
            verificando = true
            ejecutarEnPantalla({
                val datos = Servidor.solicitar(this, JSONObject().put("accion", "verificar").put("recuperacion", recuperacion).put("codigo", codigo))
                usuarios.base.meta("token_recuperacion", datos.getString("token"))
                usuarios.base.meta("id_recuperacion", datos.getString("id"))
                usuarios.base.meta("token_recuperacion_hasta", (System.currentTimeMillis() + 300000).toString())
                datos
            }) { r ->
                verificando = false
                verificar.isEnabled = SystemClock.elapsedRealtime() < limite
                r.onSuccess { datos ->
                    abrirNuevaClave(datos.getString("token"), datos.getString("id"))
                }.onFailure { error(it.message ?: "No se pudo verificar el código.") }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        revisarOperacionPendiente()
    }

    override fun revisarOperacionPendiente() {
        val guardada = usuarios.base.meta("recuperacion")
        if (guardada.isNotBlank() && guardada != recuperacion) {
            recuperacion = guardada
            limite = usuarios.base.meta("recuperacion_limite").toLongOrNull() ?: 0
            mostrarReloj()
        }
        val token = usuarios.base.meta("token_recuperacion")
        val vence = usuarios.base.meta("token_recuperacion_hasta").toLongOrNull() ?: 0
        if (token.isNotBlank() && vence > System.currentTimeMillis()) {
            abrirNuevaClave(token, usuarios.base.meta("id_recuperacion"))
        }
    }

    private fun abrirNuevaClave(token: String, id: String) {
        if (abriendoClave || isFinishing) return
        abriendoClave = true
        startActivity(Intent(this, CrearClave::class.java).putExtra("token_recuperacion", token).putExtra(USUARIO_ID, id))
        finish()
    }

    private fun mostrarReloj() {
        findViewById<View>(R.id.panelCodigo).visibility = View.VISIBLE
        val verificar = findViewById<Button>(R.id.verificar)
        val recuperar = findViewById<Button>(R.id.recuperar)
        val ayuda = findViewById<TextView>(R.id.ayudaCodigo)
        reloj?.cancel()
        reloj = object : CountDownTimer((limite - SystemClock.elapsedRealtime()).coerceAtLeast(0), 1000) {
            override fun onTick(restante: Long) {
                ayuda.text = "Código enviado a tu correo. Vence en ${(restante + 999) / 1000} segundos."
                verificar.isEnabled = !verificando
                recuperar.isEnabled = false
            }
            override fun onFinish() {
                ayuda.text = "El código venció. Solicita uno nuevo."
                verificar.isEnabled = false
                recuperar.isEnabled = true
                recuperar.text = "Solicitar otro código"
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("recuperacion", recuperacion)
        outState.putLong("limite", limite)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { reloj?.cancel(); super.onDestroy() }
}

class CrearClave : Pantalla(R.layout.activity_crear_clave) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clave = findViewById<EditText>(R.id.clave)
        val confirmacion = findViewById<EditText>(R.id.confirmacion)
        val guardar = findViewById<Button>(R.id.guardar)
        guardar.setOnClickListener {
            if (!confirmarClave(clave, confirmacion)) return@setOnClickListener
            val id = intent.getStringExtra(USUARIO_ID) ?: return@setOnClickListener
            val password = clave.text.toString()
            guardar.isEnabled = false
            ejecutarEnPantalla({
                val u = Usuario(id = id, nombres = "", apellidos = "", correo = "", claveHash = "", esquema = "pbkdf2-120000").conClave(password)
                Servidor.solicitar(this, JSONObject().put("accion", "nueva_clave").put("token", intent.getStringExtra("token_recuperacion"))
                    .put("credencial", u.claveHash).put("esquema", u.esquema))
                usuarios.invalidarAcceso(id)
                usuarios.base.meta("token_recuperacion", "")
                usuarios.base.meta("recuperacion", "")
            }) { r ->
                guardar.isEnabled = true
                r.onSuccess { exito("Contraseña actualizada y cuenta reactivada.", ::volverAlLogin) }
                    .onFailure { error(it.message ?: "No se pudo actualizar la contraseña.") }
            }
        }
    }
}
