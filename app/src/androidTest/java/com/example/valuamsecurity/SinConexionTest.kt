package com.example.valuamsecurity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SinConexionTest {
    private val instrumentacion get() = InstrumentationRegistry.getInstrumentation()
    private val contexto get() = instrumentacion.targetContext

    private fun shell(comando: String): String = instrumentacion.uiAutomation.executeShellCommand(comando).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { lector -> lector.readText().trim() }
    }

    private fun esperar(condicion: () -> Boolean) {
        val limite = System.currentTimeMillis() + 45000
        while (!condicion() && System.currentTimeMillis() < limite) Thread.sleep(300)
        assertTrue("No se alcanzó el estado esperado dentro del plazo.", condicion())
    }

    @Test fun insertaModificaEliminaSinInternetYSincronizaAlReconectar() {
        if (!BuildConfig.API_BASE_URL.contains("127.0.0.1")) return
        val usuarios = Usuarios(contexto)
        val anteriorToken = usuarios.base.meta("token")
        val anteriorId = usuarios.base.meta("usuario_actual")
        fun cuenta() = Usuario(nombres = "Prueba", apellidos = "Sin Conexión",
            correo = "qa_${UUID.randomUUID()}@example.com", usuario = "qa_" + UUID.randomUUID().toString().take(8),
            claveHash = "", esquema = "pbkdf2-120000").conClave("Valuam\$123")
        val actor = cuenta()
        val borrar = cuenta()
        val nueva = cuenta()
        val wifi = shell("settings get global wifi_on")
        val datos = shell("settings get global mobile_data")
        try {
            assertTrue(usuarios.registrar(actor))
            assertTrue(usuarios.registrar(borrar))
            val login = Servidor.solicitar(contexto, JSONObject().put("accion", "login").put("correo", actor.correo).put("credencial", actor.claveHash))
            usuarios.base.meta("token", login.getString("token"))
            usuarios.base.meta("usuario_actual", actor.id)
            usuarios.confirmarAcceso(usuarioJson(login.getJSONObject("usuario")), actor.claveHash)
            shell("svc wifi disable")
            shell("svc data disable")
            esperar { !Servidor.conectado(contexto) }
            assertNotNull(usuarios.accesoLocal(actor.correo, "Valuam\$123"))
            assertTrue(usuarios.guardar(nueva))
            assertTrue(usuarios.guardar(nueva.copy(nombres = "Creada Sin Internet")))
            assertTrue(usuarios.eliminar(borrar.id))
            assertEquals(3, usuarios.pendientes())
            assertEquals("Creada Sin Internet", usuarios.porId(nueva.id)?.nombres)
            assertTrue(usuarios.porId(borrar.id)!!.eliminado)
            if (wifi == "1") shell("svc wifi enable")
            if (datos == "1") shell("svc data enable")
            esperar { Servidor.conectado(contexto) }
            // El callback de conectividad agenda el trabajo, sin pulsar un botón de sincronizar.
            esperar { usuarios.pendientes() == 0 }
            val filas = Servidor.solicitar(contexto, JSONObject().put("accion", "listar")).getJSONArray("usuarios")
            val remoto = (0 until filas.length()).map { filas.getJSONObject(it) }
            assertEquals(1, remoto.count { it.getString("id") == nueva.id })
            assertEquals("Creada Sin Internet", remoto.single { it.getString("id") == nueva.id }.getString("nombres"))
            assertEquals(1, remoto.single { it.getString("id") == borrar.id }.getInt("eliminado"))
        } finally {
            if (wifi == "1") shell("svc wifi enable")
            if (datos == "1") shell("svc data enable")
            usuarios.base.meta("token", anteriorToken)
            usuarios.base.meta("usuario_actual", anteriorId)
        }
    }
}
