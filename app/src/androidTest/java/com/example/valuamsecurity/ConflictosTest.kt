package com.example.valuamsecurity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConflictosTest {
    private fun conBase(accion: (BaseLocal, Usuarios, Usuario) -> Unit) {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val nombre = "qa_conflictos_${UUID.randomUUID()}.db"
        val base = BaseLocal(contexto, nombre)
        val usuarios = Usuarios(contexto, base)
        val u = Usuario(nombres = "Alejandra", apellidos = "Campusano", correo = "qa@example.com",
            usuario = "ale", claveHash = "credencial-local", version = 1)
        try {
            base.transaccion { base.escribirUsuario(it, u) }
            accion(base, usuarios, u)
        } finally { base.close(); contexto.deleteDatabase(nombre) }
    }

    @Test fun resuelveConflictoGuardadoConPerfilIgualYRespetaBloqueoYRevisionDeClave() = conBase { base, usuarios, local ->
        usuarios.confirmarAcceso(local, local.claveHash)
        val remoto = local.copy(version = 4, revisionClave = 2, activo = false, claveHash = "")
        base.transaccion { db ->
            base.encolar(db, local.id, "editar", local.json())
            base.encolar(db, local.id, "crear", local.json())
            val problema = JSONObject().put("error", "conflicto").put("remoto", remoto.json())
            db.execSQL("UPDATE pendientes SET conflicto=? WHERE entidad=?", arrayOf(problema.toString(), local.id))
        }
        assertEquals(1, usuarios.conflictos().size)
        usuarios.incorporar(remoto)
        assertEquals(0, usuarios.pendientes())
        assertTrue(usuarios.conflictos().isEmpty())
        val actualizado = usuarios.porId(local.id)!!
        assertEquals(4L, actualizado.version)
        assertEquals(2L, actualizado.revisionClave)
        assertFalse(actualizado.activo)
        assertEquals("", actualizado.claveHash)
        base.readableDatabase.rawQuery("SELECT COUNT(*) FROM acceso WHERE id=?", arrayOf(local.id)).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun conservaCambiosRealesYEliminacionesParaRevision() = conBase { base, usuarios, local ->
        base.transaccion { base.encolar(it, local.id, "editar", local.json()) }
        assertFalse(usuarios.resolverCoincidentes(local.copy(nombres = "Otro nombre", version = 2)))
        assertEquals(1, usuarios.pendientes())
        base.transaccion { db ->
            db.delete("pendientes", null, null)
            base.encolar(db, local.id, "eliminar", local.json())
        }
        assertFalse(usuarios.resolverCoincidentes(local.copy(version = 2)))
        assertEquals(1, usuarios.pendientes())
    }

    @Test fun conservaOperacionesConOtraCredencialORespuestaAntigua() = conBase { base, usuarios, local ->
        base.transaccion { base.encolar(it, local.id, "editar", local.copy(claveHash = "otra-credencial").json()) }
        assertFalse(usuarios.resolverCoincidentes(local.copy(version = 2)))
        assertEquals(1, usuarios.pendientes())
        base.transaccion { db ->
            db.delete("pendientes", null, null)
            base.encolar(db, local.id, "editar", local.json())
        }
        assertFalse(usuarios.resolverCoincidentes(local.copy(version = 0)))
        assertEquals(1, usuarios.pendientes())
    }

    @Test fun conservaCredencialVigenteCuandoSoloCambiaLaVersion() = conBase { base, usuarios, local ->
        base.transaccion { base.encolar(it, local.id, "editar", local.json()) }
        assertTrue(usuarios.resolverCoincidentes(local.copy(version = 2, claveHash = "")))
        assertEquals(local.claveHash, usuarios.porId(local.id)!!.claveHash)
        assertEquals(0, usuarios.pendientes())
    }
}
