package com.example.valuamsecurity

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.pedant.SweetAlert.SweetAlertDialog

abstract class Pantalla(private val layout: Int) : AppCompatActivity() {
    internal val usuarios by lazy { Usuarios(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(layout)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }
    }

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
