package com.example.valuamsecurity

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

class login : Pantalla(R.layout.activity_login) {
    override fun onResume() {
        super.onResume()
        if (usuarios.conflictos().isNotEmpty()) Sincronizacion.programar(this, inmediato = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val correo = findViewById<EditText>(R.id.correo)
        val clave = findViewById<EditText>(R.id.clave)
        val ingresar = findViewById<Button>(R.id.ingresar)
        findViewById<Button>(R.id.registrarme).setOnClickListener { abrir(Registro::class.java) }
        findViewById<Button>(R.id.recuperar).setOnClickListener { abrir(Recuperar::class.java) }
        ingresar.setOnClickListener {
            if (!correoValido(correo)) return@setOnClickListener
            if (clave.text.isEmpty()) { error("Ingresa la contraseña.", clave); return@setOnClickListener }
            val email = correo.text.toString().trim()
            val password = clave.text.toString()
            val anterior = usuarios.porCorreo(email)
            if (anterior != null && anterior.version == 0L && anterior.usuario.isNotBlank() && anterior.coincide(password)) {
                AlertDialog.Builder(this).setTitle("Cuenta pendiente de sincronización")
                    .setMessage("El registro está guardado en el teléfono y aún falta confirmarlo en el servidor. Pulsa Reintentar para sincronizarlo ahora.")
                    .setPositiveButton("Corregir datos") { _, _ ->
                        startActivity(android.content.Intent(this, EditarUsuario::class.java).putExtra(USUARIO_ID, anterior.id))
                    }.setNeutralButton("Reintentar") { _, _ -> Sincronizacion.programar(this, inmediato = true) }
                    .setNegativeButton("Cancelar", null).show()
                return@setOnClickListener
            }
            if (anterior != null && anterior.usuario.isBlank() && anterior.coincide(password)) {
                val campo = EditText(this).apply { hint = "Nombre de usuario único" }
                AlertDialog.Builder(this).setTitle("Completar cuenta existente").setView(campo)
                    .setMessage("Elige un nombre de usuario para conservar y sincronizar tu cuenta.")
                    .setPositiveButton("Guardar") { _, _ ->
                        val nombre = campo.text.toString().trim()
                        if (!nombreUsuarioValido(nombre)) error("Usa de 3 a 80 letras sin tildes, números, puntos o guiones.")
                        else if (usuarios.guardar(anterior.copy(usuario = nombre)))
                            exito("Cuenta conservada. Espera su sincronización y vuelve a ingresar.")
                        else error("No se pudo guardar el nombre de usuario.")
                    }.setNegativeButton("Cancelar", null).show()
                return@setOnClickListener
            }
            ingresar.isEnabled = false
            ejecutarEnPantalla({
                if (!Servidor.conectado(this)) {
                    val local = checkNotNull(usuarios.accesoLocal(email, password)) { "Sin conexión. Usa una cuenta validada previamente con Internet." }
                    if (usuarios.base.meta("usuario_actual") != local.id) usuarios.base.meta("token", "")
                    usuarios.base.meta("usuario_actual", local.id)
                    local
                } else {
                    val identidad = Servidor.solicitar(this, JSONObject().put("accion", "identificar").put("correo", email))
                    val calculado = Usuario(id = identidad.getString("id"), nombres = "", apellidos = "", correo = email,
                        claveHash = "", esquema = identidad.getString("esquema")).conClave(password)
                    val respuesta = Servidor.solicitar(this, JSONObject().put("accion", "login").put("correo", email).put("credencial", calculado.claveHash))
                    val usuario = usuarioJson(respuesta.getJSONObject("usuario"))
                    usuarios.confirmarAcceso(usuario, calculado.claveHash)
                    usuarios.base.meta("token", respuesta.getString("token"))
                    usuarios.base.meta("usuario_actual", usuario.id)
                    Sincronizacion.programar(this)
                    usuario
                }
            }) { resultado ->
                ingresar.isEnabled = true
                resultado.onSuccess { u ->
                    exito("Bienvenido, ${u.nombres}.") { clave.text.clear(); abrir(MenuPrincipal::class.java) }
                }.onFailure { e ->
                    if (e is ErrorApi && e.datos.optString("error") == "bloqueada") anterior?.let { usuarios.invalidarAcceso(it.id) }
                    if (Servidor.esFalloDeTransporte(e)) {
                        ejecutarEnPantalla({ usuarios.accesoLocal(email, password) }) { local ->
                            val u = local.getOrNull()
                            if (u == null) error("El servidor no responde y esta cuenta no tiene acceso local validado.")
                            else {
                                if (usuarios.base.meta("usuario_actual") != u.id) usuarios.base.meta("token", "")
                                usuarios.base.meta("usuario_actual", u.id)
                                usuarios.base.meta("estado", "Servidor no disponible. Trabajo local.")
                                exito("Acceso local. Los cambios se sincronizarán cuando vuelva el servidor.") {
                                    clave.text.clear(); abrir(MenuPrincipal::class.java)
                                }
                            }
                        }
                    } else error(e.message ?: "No se pudo iniciar sesión.")
                }
            }
        }
    }
}
