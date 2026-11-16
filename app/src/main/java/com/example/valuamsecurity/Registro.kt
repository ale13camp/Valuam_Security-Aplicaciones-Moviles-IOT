package com.example.valuamsecurity

import android.os.Bundle
import android.widget.Button
import android.widget.EditText

class Registro : Pantalla(R.layout.activity_registro) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nombres = findViewById<EditText>(R.id.nombres)
        val apellidos = findViewById<EditText>(R.id.apellidos)
        val correo = findViewById<EditText>(R.id.correo)
        val clave = findViewById<EditText>(R.id.clave)
        val confirmacion = findViewById<EditText>(R.id.confirmacion)

        findViewById<Button>(R.id.guardar).setOnClickListener {
            if (!nombreValido(nombres, "los nombres") || !nombreValido(apellidos, "los apellidos") ||
                !correoValido(correo) || !confirmarClave(clave, confirmacion)) return@setOnClickListener

            val email = correo.text.toString().trim()
            if (usuarios.porCorreo(email) != null) {
                error("Ese correo ya está registrado.", correo)
                return@setOnClickListener
            }
            val usuario = Usuario(nombres = nombres.text.toString().trim(),
                apellidos = apellidos.text.toString().trim(), correo = email, claveHash = "")
            if (usuarios.guardar(usuario.conClave(clave.text.toString()))) {
                exito("Usuario registrado correctamente.", ::finish)
            } else {
                error("No se pudo guardar el usuario.")
            }
        }
    }
}
