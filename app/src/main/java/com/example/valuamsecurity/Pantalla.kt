package com.example.valuamsecurity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.pedant.SweetAlert.SweetAlertDialog

abstract class Pantalla(private val layout: Int) : AppCompatActivity() {
    internal val usuarios by lazy { Usuarios(this) }
    private val relojEstado = Handler(Looper.getMainLooper())
    private var estadoAnterior = ""
    private lateinit var estadoConexion: TextView
    private val actualizarEstado = object : Runnable {
        override fun run() {
            val cantidad = usuarios.pendientes()
            val conflictos = usuarios.conflictos().size
            val migrados = usuarios.sinUsuario()
            val conexion = if (Servidor.conectado(this@Pantalla)) "Con conexión" else "Sin conexión · trabajo local"
            val estado = "$conexion · $cantidad cambios pendientes" +
                if (conflictos > 0) " · $conflictos conflictos (toca para revisar)" else
                if (migrados > 0) " · $migrados cuentas por completar" else " · ${usuarios.base.meta("estado")}"
            estadoConexion.text = estado
            val revision = estado + usuarios.base.meta("revision_datos")
            if (revision != estadoAnterior) {
                estadoAnterior = revision
                datosActualizados()
            }
            revisarOperacionPendiente()
            relojEstado.postDelayed(this, 1500)
        }
    }

    protected open fun datosActualizados() = Unit
    protected open fun revisarOperacionPendiente() = Unit

    protected fun <T> ejecutarEnPantalla(tarea: () -> T, respuesta: (Result<T>) -> Unit) {
        Servidor.ejecutar(tarea) { resultado ->
            if (!isFinishing && !isDestroyed) respuesta(resultado)
        }
    }

    private fun revisarConflictos() {
        val conflicto = usuarios.conflictos().firstOrNull()
        if (conflicto == null) {
            Sincronizacion.programar(this, inmediato = true)
            exito("Se reintentará la sincronización cuando el servidor esté disponible.")
            return
        }
        val (id, problema) = conflicto
        val local = usuarios.porId(id)
        val remoto = problema.optJSONObject("remoto")
        val resumenLocal = local?.let { "${it.usuario}\n${it.nombres} ${it.apellidos}\n${it.correo}" } ?: "Sin registro local"
        val resumenRemoto = remoto?.let { "${it.optString("usuario")}\n${it.optString("nombres")} ${it.optString("apellidos")}\n${it.optString("correo")}" }
            ?: "El servidor rechazó los datos. Corrige el usuario o correo en el listado."
        val dialogo = AlertDialog.Builder(this).setTitle("Revisar conflicto")
            .setMessage("${problema.optString("mensaje")}\n\nEn el teléfono\n$resumenLocal\n\nEn el servidor\n$resumenRemoto")
            .setNegativeButton("Revisar después", null)
        if (remoto != null) {
            dialogo.setNeutralButton("Usar servidor") { _, _ -> usuarios.resolver(id, false); datosActualizados() }
            if (remoto.optInt("eliminado") == 0) dialogo.setPositiveButton("Usar teléfono") { _, _ -> usuarios.resolver(id, true); datosActualizados() }
        } else {
            dialogo.setPositiveButton("Reintentar corregido") { _, _ -> usuarios.resolver(id, true); datosActualizados() }
        }
        dialogo.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(layout)
        val contenido = (findViewById<android.view.ViewGroup>(R.id.main)).getChildAt(0) as? LinearLayout
        estadoConexion = TextView(this).apply {
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(16, 36, 58))
            setPadding(0, 8, 0, 12)
            setOnClickListener { revisarConflictos() }
        }
        contenido?.addView(estadoConexion, 0)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }
    }

    override fun onResume() { super.onResume(); relojEstado.post(actualizarEstado) }
    override fun onPause() { relojEstado.removeCallbacks(actualizarEstado); super.onPause() }

    protected fun abrir(destino: Class<*>) = startActivity(Intent(this, destino))

    protected fun volverAlLogin() {
        startActivity(Intent(this, login::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    protected fun error(mensaje: String, campo: EditText? = null) {
        val alerta = SweetAlertDialog(this, SweetAlertDialog.ERROR_TYPE)
            .setTitleText("Revisa los datos").setContentText(mensaje).setConfirmText("Aceptar")
        alerta.setConfirmClickListener { it.dismiss() }
        alerta.setOnDismissListener { campo?.requestFocus() }
        alerta.show()
    }

    protected fun exito(mensaje: String, continuar: () -> Unit = {}) {
        val alerta = SweetAlertDialog(this, SweetAlertDialog.SUCCESS_TYPE)
            .setTitleText("Listo").setContentText(mensaje).setConfirmText("Aceptar")
        alerta.setCancelable(false)
        alerta.setConfirmClickListener { it.dismiss()
            continuar() }
        alerta.show()
    }

    protected fun correoValido(campo: EditText): Boolean {
        val valor = campo.text.toString().trim()
        if (valor.isEmpty()) {
            error("Ingresa el correo electrónico.", campo)
            return false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(valor).matches()) {
            error("Ingresa un correo electrónico válido.", campo)
            return false
        }
        return true
    }

    protected fun validarClave(campo: EditText): Boolean {
        val clave = campo.text.toString()
        if (clave.isEmpty()) {
            error("Ingresa la contraseña.", campo)
            return false
        }
        if (!claveRobusta(clave)) {
            error("Usa 8 caracteres o más, una mayúscula, una minúscula, un número y un carácter especial.", campo)
            return false
        }
        return true
    }

    protected fun confirmarClave(clave: EditText, confirmacion: EditText): Boolean {
        if (!validarClave(clave)) return false
        if (confirmacion.text.isEmpty() || clave.text.toString() != confirmacion.text.toString()) {
            error("Repite la misma contraseña.", confirmacion)
            return false
        }
        return true
    }

    protected fun nombreValido(campo: EditText, etiqueta: String): Boolean {
        val valor = campo.text.toString().trim()
        if (valor.isEmpty() || !valor.all { it.isLetter() || it == ' ' }) {
            error("Ingresa $etiqueta usando solo letras y espacios.", campo)
            return false
        }
        return true
    }
}
