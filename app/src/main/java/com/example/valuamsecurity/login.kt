package com.example.valuamsecurity

import android.os.Bundle
import android.widget.Button
import android.widget.EditText

class login : Pantalla(R.layout.activity_login) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val correo = findViewById<EditText>(R.id.correo)
        val clave = findViewById<EditText>(R.id.clave)
        val ingresar = findViewById<Button>(R.id.ingresar)
        findViewById<Button>(R.id.registrarme).setOnClickListener { abrir(Registro::class.java) }
        findViewById<Button>(R.id.recuperar).setOnClickListener { abrir(Recuperar::class.java) }
        ingresar.setOnClickListener {
            if (!correoValido(correo) || !validarClave(clave)) return@setOnClickListener
            ingresar.isEnabled = false
            val usuario = usuarios.porCorreo(correo.text.toString().trim())
            if (usuario?.coincide(clave.text.toString()) == true) {
                exito("Bienvenido, ${usuario.nombres}.") {
                    clave.text.clear()
                    ingresar.isEnabled = true
                    abrir(MenuPrincipal::class.java)
                }
            } else {
                ingresar.isEnabled = true
                error("El correo o la contraseña son incorrectos.", clave)
            }
        }
    }
}
