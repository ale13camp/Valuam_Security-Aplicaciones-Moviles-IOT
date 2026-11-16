package com.example.valuamsecurity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal fun claveRobusta(clave: String): Boolean =
    clave.length >= 8 && clave.any(Char::isUpperCase) && clave.any(Char::isLowerCase) &&
        clave.any(Char::isDigit) && clave.any { !it.isLetterOrDigit() && !it.isWhitespace() }

internal fun normalizarBusqueda(texto: String): String =
    Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

internal fun temperaturaAlta(valor: Double) = valor >= 25.0

internal data class Usuario(
    val id: String = UUID.randomUUID().toString(),
    val nombres: String,
    val apellidos: String,
    val correo: String,
    val claveHash: String
) {
    fun coincide(clave: String) = claveHash == calcularHash(id, clave)
    fun conClave(clave: String) = copy(claveHash = calcularHash(id, clave))

    private fun calcularHash(sal: String, clave: String): String =
        MessageDigest.getInstance("SHA-256").digest((sal + clave).toByteArray())
            .joinToString("") { "%02x".format(it) }
}

internal class Usuarios(contexto: Context) {
    private val preferencias = contexto.getSharedPreferences("usuarios_demo", Context.MODE_PRIVATE)

    init {
        if (!preferencias.getBoolean("cuentas_iniciales_cargadas", false)) {
            val existentes = listar()
            val clave = "Ventana\$123"
            val cuentasIniciales = listOf(
                Usuario(nombres = "Elián Baltazar", apellidos = "Hernández Campusano",
                    correo = "elian@correo.cl", claveHash = "").conClave(clave),
                Usuario(nombres = "Yerko Mauricio", apellidos = "Valdivia Cruz",
                    correo = "yerko@correo.cl", claveHash = "").conClave(clave)
            )
            val nuevas = cuentasIniciales.filter { inicial ->
                existentes.none { it.correo.equals(inicial.correo, ignoreCase = true) }
            }
            persistir(existentes + nuevas, marcarCargaInicial = true)
        }
    }

    fun listar(): List<Usuario> {
        val datos = JSONArray(preferencias.getString("usuarios", "[]"))
        return List(datos.length()) { indice ->
            val fila = datos.getJSONObject(indice)
            Usuario(fila.getString("id"), fila.getString("nombres"), fila.getString("apellidos"),
                fila.getString("correo"), fila.getString("claveHash"))
        }
    }

    fun porCorreo(correo: String) = listar().firstOrNull { it.correo.equals(correo, ignoreCase = true) }
    fun porId(id: String?) = listar().firstOrNull { it.id == id }

    fun guardar(usuario: Usuario): Boolean {
        val usuarios = listar().toMutableList()
        if (usuarios.any { it.id != usuario.id && it.correo.equals(usuario.correo, ignoreCase = true) }) return false
        val indice = usuarios.indexOfFirst { it.id == usuario.id }
        if (indice < 0) usuarios.add(usuario) else usuarios[indice] = usuario
        return persistir(usuarios)
    }

    fun eliminar(id: String) = persistir(listar().filterNot { it.id == id })

    private fun persistir(usuarios: List<Usuario>, marcarCargaInicial: Boolean = false): Boolean {
        val datos = JSONArray()
        usuarios.forEach {
            datos.put(JSONObject().put("id", it.id).put("nombres", it.nombres)
                .put("apellidos", it.apellidos).put("correo", it.correo).put("claveHash", it.claveHash))
        }
        val editor = preferencias.edit().putString("usuarios", datos.toString())
        if (marcarCargaInicial) editor.putBoolean("cuentas_iniciales_cargadas", true)
        return editor.commit()
    }
}
