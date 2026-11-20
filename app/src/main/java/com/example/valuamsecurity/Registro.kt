package com.example.valuamsecurity

import android.os.Bundle
import android.widget.Button
import android.widget.EditText

internal fun nombreUsuarioValido(nombre: String) = Regex("[a-zA-Z0-9_.-]{3,80}").matches(nombre)

class Registro : Pantalla(R.layout.activity_registro) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nombres = findViewById<EditText>(R.id.nombres)
        val apellidos = findViewById<EditText>(R.id.apellidos)
        val correo = findViewById<EditText>(R.id.correo)
        val clave = findViewById<EditText>(R.id.clave)
        val confirmacion = findViewById<EditText>(R.id.confirmacion)
        val guardar = findViewById<Button>(R.id.guardar)
        guardar.setOnClickListener {
            if (!nombreValido(nombres, "los nombres") || !nombreValido(apellidos, "los apellidos") ||
                !correoValido(correo) || !confirmarClave(clave, confirmacion)) return@setOnClickListener
            val id = java.util.UUID.randomUUID().toString()
            val u = Usuario(id = id, nombres = nombres.text.toString().trim(), apellidos = apellidos.text.toString().trim(),
                correo = correo.text.toString().trim(), usuario = "u_" + id.replace("-", ""), claveHash = "", esquema = "pbkdf2-120000")
            val password = clave.text.toString()
            guardar.isEnabled = false
            ejecutarEnPantalla({ usuarios.registrar(u.conClave(password)) }) { resultado ->
                guardar.isEnabled = true
                resultado.onSuccess { confirmado ->
                    exito(if (confirmado) "Usuario registrado correctamente en el servidor." else
                        "Usuario guardado localmente. Su registro se confirmará al sincronizar.", ::finish)
                }.onFailure { error(it.message ?: "No se pudo registrar el usuario.") }
            }
        }
    }
}
