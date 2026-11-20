package com.example.valuamsecurity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.graphics.Color
import android.view.Gravity
import android.view.View
import androidx.core.content.ContextCompat
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
    private lateinit var estadoSincronizacion: TextView
    private lateinit var estadoPendientes: TextView
    private var detalleEstado = ""
    private val actualizarEstado = object : Runnable {
        override fun run() {
            val cantidad = usuarios.pendientes()
            val conflictos = usuarios.conflictos().size
            val migrados = usuarios.sinUsuario()
            val conectado = Servidor.conectado(this@Pantalla)
            val conexion = if (conectado) "Con conexión" else "Sin conexión · trabajo local"
            val estado = "$conexion · $cantidad cambios pendientes" +
                if (conflictos > 0) " · $conflictos conflictos (toca para revisar)" else
                if (migrados > 0) " · $migrados cuentas por completar" else " · ${usuarios.base.meta("estado")}"
            detalleEstado = estado
            actualizarIndicador(estadoConexion, R.drawable.ic_estado_conexion,
                if (conectado) "En línea" else "Sin conexión",
                if (conectado) Color.rgb(46, 125, 50) else Color.rgb(100, 110, 120))
            val sincronizacion = usuarios.base.meta("estado")
            val etiqueta = when {
                !conectado -> "Local"
                conflictos > 0 -> "Revisar"
                sincronizacion == "Sincronización completada" -> "Sincronizado"
                sincronizacion.startsWith("Sincronizando") -> "Sincronizando"
                sincronizacion.startsWith("Servidor sin respuesta") -> "Sin respuesta"
                else -> "Estado"
            }
            actualizarIndicador(estadoSincronizacion, R.drawable.ic_estado_sync, etiqueta, Color.rgb(21, 101, 192))
            estadoPendientes.visibility = if (cantidad > 0 || migrados > 0 || conflictos > 0) View.VISIBLE else View.GONE
            actualizarIndicador(estadoPendientes, R.drawable.ic_estado_pendientes,
                when {
                    cantidad > 0 -> "$cantidad pendientes"
                    conflictos > 0 -> "$conflictos conflictos"
                    else -> "$migrados por completar"
                }, Color.rgb(130, 94, 0), Color.rgb(251, 192, 45))
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

    private fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()

    private fun actualizarIndicador(vista: TextView, icono: Int, etiqueta: String, color: Int, colorIcono: Int = color) {
        vista.text = etiqueta
        vista.contentDescription = "$etiqueta. Toca para ver el estado de conexión y sincronización."
        vista.setTextColor(color)
        val dibujo = ContextCompat.getDrawable(this, icono)?.mutate()?.apply {
            setTint(colorIcono)
            setBounds(0, 0, dp(18), dp(18))
        }
        vista.setCompoundDrawablesRelative(dibujo, null, null, null)
    }

    private fun mostrarEstado() {
        val dialogo = AlertDialog.Builder(this).setTitle("Conexión y sincronización")
            .setMessage(detalleEstado).setNegativeButton("Cerrar", null)
        if (usuarios.conflictos().isNotEmpty()) {
            dialogo.setPositiveButton("Revisar conflictos") { _, _ -> revisarConflictos() }
        } else {
            dialogo.setPositiveButton("Sincronizar") { _, _ -> Sincronizacion.programar(this, inmediato = true) }
        }
        dialogo.show()
    }

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
        val indicadores = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun indicador() = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            compoundDrawablePadding = dp(4)
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { mostrarEstado() }
            indicadores.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        estadoConexion = indicador()
        estadoSincronizacion = indicador()
        estadoPendientes = indicador()
        contenido?.addView(indicadores, 0)
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
