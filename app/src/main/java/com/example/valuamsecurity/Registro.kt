package com.example.valuamsecurity

import android.os.Bundle
import android.widget.Button
import android.widget.EditText

internal fun nombreUsuarioValido(nombre: String) = Regex("[a-zA-Z0-9_.-]{3,80}").matches(nombre)

class Registro : Pantalla(R.layout.activity_registro) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nombreUsuario = findViewById<EditText>(R.id.nombreUsuario)
        val nombres = findViewById<EditText>(R.id.nombres)
        val apellidos = findViewById<EditText>(R.id.apellidos)
        val correo = findViewById<EditText>(R.id.correo)
        val clave = findViewById<EditText>(R.id.clave)
        val confirmacion = findViewById<EditText>(R.id.confirmacion)
        val guardar = findViewById<Button>(R.id.guardar)
        guardar.setOnClickListener {
            val nombre = nombreUsuario.text.toString().trim()
            if (!nombreUsuarioValido(nombre)) {
                error("El usuario debe tener entre 3 y 80 letras sin tildes, números, puntos o guiones.", nombreUsuario)
                return@setOnClickListener
            }
            if (!nombreValido(nombres, "los nombres") || !nombreValido(apellidos, "los apellidos") ||
                !correoValido(correo) || !confirmarClave(clave, confirmacion)) return@setOnClickListener
            val u = Usuario(nombres = nombres.text.toString().trim(), apellidos = apellidos.text.toString().trim(),
                correo = correo.text.toString().trim(), usuario = nombre, claveHash = "", esquema = "pbkdf2-120000")
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
