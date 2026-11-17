package com.example.valuamsecurity

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import cn.pedant.SweetAlert.SweetAlertDialog

class MenuPrincipal : Pantalla(R.layout.activity_menu) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<Button>(R.id.gestion).setOnClickListener { abrir(Gestion::class.java) }
        findViewById<Button>(R.id.sensores).setOnClickListener { abrir(alertas::class.java) }
        findViewById<Button>(R.id.equipo).setOnClickListener { abrir(Equipo::class.java) }
    }
}

class Equipo : Pantalla(R.layout.activity_equipo) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<TextView>(R.id.datosAcademicos).text =
            listOf(R.string.equipo_institucion, R.string.equipo_carrera, R.string.equipo_seccion)
                .joinToString(" · ") { getString(it) }
    }
}

class Gestion : Pantalla(R.layout.activity_gestion) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<Button>(R.id.registrarme).setOnClickListener { abrir(Registro::class.java) }
        findViewById<Button>(R.id.listar).setOnClickListener { abrir(ListaUsuarios::class.java) }
    }
}

class ListaUsuarios : Pantalla(R.layout.activity_lista) {
    private lateinit var buscar: EditText
    private lateinit var tabla: TableLayout
    private lateinit var listaVacia: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buscar = findViewById(R.id.buscar)
        tabla = findViewById(R.id.tabla)
        listaVacia = findViewById(R.id.listaVacia)
        buscar.doAfterTextChanged { mostrarUsuarios() }
    }

    override fun onResume() {
        super.onResume()
        mostrarUsuarios()
    }

    private fun mostrarUsuarios() {
        tabla.removeAllViews()
        val consulta = normalizarBusqueda(buscar.text.toString())
        val resultados = usuarios.listar().filter {
            normalizarBusqueda("${it.nombres} ${it.apellidos}").contains(consulta)
        }
        listaVacia.visibility = if (resultados.isEmpty()) View.VISIBLE else View.GONE
        listaVacia.text = if (consulta.isEmpty()) "Todavía no hay usuarios registrados." else "Sin coincidencias."
        resultados.forEach { usuario ->
            val fila = TableRow(this)
            val boton = Button(this)
            boton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.valuam_turquesa))
            boton.setTextColor(getColor(R.color.valuam_azul_oscuro))
            boton.text = "${usuario.nombres} ${usuario.apellidos}"
            boton.setOnClickListener {
                startActivity(Intent(this, EditarUsuario::class.java).putExtra(USUARIO_ID, usuario.id))
            }
            fila.addView(boton)
            tabla.addView(fila)
        }
    }
}

class EditarUsuario : Pantalla(R.layout.activity_editar) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val usuario = usuarios.porId(intent.getStringExtra(USUARIO_ID))
        if (usuario == null) {
            finish()
            return
        }
        val nombres = findViewById<EditText>(R.id.nombres)
        val apellidos = findViewById<EditText>(R.id.apellidos)
        val correo = findViewById<EditText>(R.id.correo)
        nombres.setText(usuario.nombres)
        apellidos.setText(usuario.apellidos)
        correo.setText(usuario.correo)

        findViewById<Button>(R.id.guardar).setOnClickListener {
            if (!nombreValido(nombres, "los nombres") || !nombreValido(apellidos, "los apellidos") ||
                !correoValido(correo)) return@setOnClickListener
            val email = correo.text.toString().trim()
            val duplicado = usuarios.porCorreo(email)
            if (duplicado != null && duplicado.id != usuario.id) {
                error("Ese correo ya está registrado.", correo)
                return@setOnClickListener
            }
            val actualizado = usuario.copy(nombres = nombres.text.toString().trim(),
                apellidos = apellidos.text.toString().trim(), correo = email)
            if (usuarios.guardar(actualizado)) {
                exito("Datos actualizados.", ::finish)
            } else {
                error("No se pudieron guardar los cambios.")
            }
        }

        findViewById<Button>(R.id.eliminar).setOnClickListener {
            SweetAlertDialog(this, SweetAlertDialog.WARNING_TYPE)
                .setTitleText("¿Eliminar usuario?")
                .setContentText("${usuario.nombres} ${usuario.apellidos}")
                .setCancelText("Cancelar").setConfirmText("Eliminar")
                .setCancelClickListener { it.dismiss() }
                .setConfirmClickListener {
                    it.dismiss()
                    if (usuarios.eliminar(usuario.id)) exito("Usuario eliminado.", ::finish)
                    else error("No se pudo eliminar el usuario.")
                }.show()
        }
    }
}
