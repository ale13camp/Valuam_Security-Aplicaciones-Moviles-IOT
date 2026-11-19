package com.example.valuamsecurity

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FlujoEvaluacionTest {
    private val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun limpiarFixturesLocales() {
        val usuarios = Usuarios(contexto)
        val ids = usuarios.base.readableDatabase.rawQuery("SELECT datos FROM usuarios", null).use { filas ->
            buildList {
                while (filas.moveToNext()) {
                    val u = usuarioJson(JSONObject(filas.getString(0)))
                    if (Regex("qa_[a-f0-9-]{36}@example\\.com").matches(u.correo) &&
                        Regex("qa_[a-f0-9]{8}").matches(u.usuario) &&
                        u.apellidos in listOf("Evaluación", "Sin Conexión")) add(u.id)
                }
            }
        }
        usuarios.base.transaccion { db ->
            ids.forEach { id ->
                db.delete("pendientes", "entidad=?", arrayOf(id))
                db.delete("acceso", "id=?", arrayOf(id))
                db.delete("usuarios", "id=?", arrayOf(id))
            }
            if (usuarios.base.meta("usuario_actual") in ids) {
                usuarios.base.meta("usuario_actual", "")
                usuarios.base.meta("token", "")
            }
        }
        assertTrue(ids.none { usuarios.porId(it) != null })
    }

    @Test fun migraPreferenciasYConservaCambiosTrasReabrirSQLite() {
        val etiqueta = UUID.randomUUID().toString()
        val preferences = "qa_$etiqueta"
        val nombreDb = "qa_$etiqueta.db"
        val aislado = object : ContextWrapper(contexto) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(if (name == "usuarios_demo") preferences else name, mode)
        }
        val original = Usuario(nombres = "José", apellidos = "Muñoz", correo = "qa@example.com", claveHash = "").conClave("Valuam$123")
        aislado.getSharedPreferences("usuarios_demo", 0).edit().putString("usuarios", JSONArray().put(original.json()).toString()).commit()
        var base = BaseLocal(aislado, nombreDb)
        try {
            base.readableDatabase.rawQuery("SELECT datos FROM usuarios WHERE id=?", arrayOf(original.id)).use {
                assertTrue(it.moveToFirst())
                val migrado = usuarioJson(JSONObject(it.getString(0)))
                assertEquals(original.id, migrado.id)
                assertTrue(migrado.coincide("Valuam$123"))
                assertEquals("", migrado.usuario)
            }
            base.transaccion { db ->
                base.escribirUsuario(db, original.copy(nombres = "María"))
                base.encolar(db, original.id, "editar", original.copy(nombres = "María").json())
            }
            base.close()
            base = BaseLocal(aislado, nombreDb)
            base.readableDatabase.rawQuery("SELECT datos FROM usuarios WHERE id=?", arrayOf(original.id)).use {
                assertTrue(it.moveToFirst())
                assertEquals("María", usuarioJson(JSONObject(it.getString(0))).nombres)
            }
            base.readableDatabase.rawQuery("SELECT COUNT(*) FROM pendientes", null).use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            assertEquals(1, JSONArray(aislado.getSharedPreferences("usuarios_demo", 0).getString("usuarios", "[]")).length())
        } finally {
            base.close()
            contexto.deleteDatabase(nombreDb)
            contexto.getSharedPreferences(preferences, 0).edit().clear().commit()
        }
    }

    @Test fun unaTransaccionFallidaNoPierdeNiEncolaCambiosParciales() {
        val nombre = "qa_${UUID.randomUUID()}.db"
        val base = BaseLocal(contexto, nombre)
        val original = Usuario(nombres = "Ana", apellidos = "Díaz", correo = "ana@example.com", claveHash = "")
        try {
            base.transaccion { base.escribirUsuario(it, original) }
            runCatching {
                base.transaccion {
                    base.escribirUsuario(it, original.copy(nombres = "Cambio"))
                    base.encolar(it, original.id, "editar", original.json())
                    throw IllegalStateException("Interrupción de prueba")
                }
            }
            base.readableDatabase.rawQuery("SELECT datos FROM usuarios WHERE id=?", arrayOf(original.id)).use {
                assertTrue(it.moveToFirst())
                assertEquals("Ana", usuarioJson(JSONObject(it.getString(0))).nombres)
            }
            base.readableDatabase.rawQuery("SELECT COUNT(*) FROM pendientes", null).use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        } finally { base.close(); contexto.deleteDatabase(nombre) }
    }

    @Test fun credencialPbkdf2CoincideConElServidor() {
        val u = Usuario(id = "qa-salt", nombres = "", apellidos = "", correo = "", claveHash = "", esquema = "pbkdf2-120000").conClave("Valuam$123")
        assertEquals("0a0b388c06e138e6279d8c313ae59cf08752bc319fbaeca632f566685ecd7817", u.claveHash)
        assertTrue(u.coincide("Valuam$123"))
        assertFalse(u.coincide("incorrecta"))
    }

    @Test fun registroLoginYActualizacionConServidorLocal() {
        if (!BuildConfig.API_BASE_URL.contains("127.0.0.1")) return
        val usuario = Usuario(nombres = "Prueba", apellidos = "Evaluación", usuario = "qa_" + UUID.randomUUID().toString().take(8),
            correo = "qa_${UUID.randomUUID()}@example.com", claveHash = "", esquema = "pbkdf2-120000").conClave("Valuam$123")
        val usuarios = Usuarios(contexto)
        val tokenAnterior = usuarios.base.meta("token")
        val idAnterior = usuarios.base.meta("usuario_actual")
        try {
        assertTrue(usuarios.registrar(usuario))
        val login = Servidor.solicitar(contexto, JSONObject().put("accion", "login").put("correo", usuario.correo).put("credencial", usuario.claveHash))
        usuarios.base.meta("token", login.getString("token"))
        usuarios.base.meta("usuario_actual", usuario.id)
        usuarios.confirmarAcceso(usuarioJson(login.getJSONObject("usuario")), usuario.claveHash)
        assertNotNull(usuarios.accesoLocal(usuario.correo, "Valuam$123"))
        assertTrue(usuarios.guardar(usuarios.porId(usuario.id)!!.copy(nombres = "Cambio")))
        val inicio = System.currentTimeMillis()
        while (usuarios.pendientes() > 0 && System.currentTimeMillis() - inicio < 30000) Thread.sleep(300)
        assertEquals(0, usuarios.pendientes())
        val listado = Servidor.solicitar(contexto, JSONObject().put("accion", "listar")).getJSONArray("usuarios")
        assertTrue((0 until listado.length()).any { listado.getJSONObject(it).optString("id") == usuario.id && listado.getJSONObject(it).optString("nombres") == "Cambio" })
        } finally {
            usuarios.base.meta("token", tokenAnterior)
            usuarios.base.meta("usuario_actual", idAnterior)
        }
    }
}
