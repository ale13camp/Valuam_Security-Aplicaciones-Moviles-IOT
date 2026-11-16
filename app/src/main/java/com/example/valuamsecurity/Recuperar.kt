package com.example.valuamsecurity

import android.content.Intent
import android.os.Bundle
import android.text.TextWatcher
import android.text.Editable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

private const val CODIGO_DEMO = "48216"
internal const val USUARIO_ID = "usuario_id"

class Recuperar : Pantalla(R.layout.activity_recuperar) {
    private var usuarioId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val correo = findViewById<EditText>(R.id.correo)
        val panel = findViewById<View>(R.id.panelCodigo)
        val recuperar = findViewById<Button>(R.id.recuperar)
        val casillas = listOf(R.id.digito1, R.id.digito2, R.id.digito3, R.id.digito4, R.id.digito5)
            .map { findViewById<EditText>(it) }
        usuarioId = savedInstanceState?.getString(USUARIO_ID)

        fun mostrarCodigo() {
            panel.visibility = View.VISIBLE
            correo.isEnabled = false
            recuperar.isEnabled = false
            findViewById<TextView>(R.id.ayudaCodigo).text = "Código de demostración: $CODIGO_DEMO"
        }

        if (usuarioId != null) mostrarCodigo()
        casillas.zipWithNext().forEach { (actual, siguiente) ->
            actual.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (actual.hasFocus() && s?.length == 1) siguiente.requestFocus()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        recuperar.setOnClickListener {
            if (!correoValido(correo)) return@setOnClickListener
            val usuario = usuarios.porCorreo(correo.text.toString().trim())
            if (usuario == null) {
                error("Ese correo no está registrado.", correo)
                return@setOnClickListener
            }
            usuarioId = usuario.id
            exito("Envío simulado. Ingresa los cinco dígitos.") {
                mostrarCodigo()
                casillas.first().requestFocus()
            }
        }
        findViewById<Button>(R.id.verificar).setOnClickListener {
            val vacia = casillas.firstOrNull { it.text.isEmpty() }
            if (vacia != null) {
                error("Completa los cinco dígitos.", vacia)
                return@setOnClickListener
            }
            if (casillas.joinToString("") { it.text.toString() } != CODIGO_DEMO) {
                error("El código es incorrecto.", casillas.first())
                return@setOnClickListener
            }
            startActivity(Intent(this, CrearClave::class.java).putExtra(USUARIO_ID, usuarioId))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(USUARIO_ID, usuarioId)
        super.onSaveInstanceState(outState)
    }
}

class CrearClave : Pantalla(R.layout.activity_crear_clave) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clave = findViewById<EditText>(R.id.clave)
        val confirmacion = findViewById<EditText>(R.id.confirmacion)
        findViewById<Button>(R.id.guardar).setOnClickListener {
            if (!confirmarClave(clave, confirmacion)) return@setOnClickListener
            val usuario = usuarios.porId(intent.getStringExtra(USUARIO_ID))
            if (usuario != null && usuarios.guardar(usuario.conClave(clave.text.toString()))) {
                exito("Tu contraseña se actualizó.", ::volverAlLogin)
            } else {
                error("No se pudo actualizar la contraseña.")
            }
        }
    }
}
