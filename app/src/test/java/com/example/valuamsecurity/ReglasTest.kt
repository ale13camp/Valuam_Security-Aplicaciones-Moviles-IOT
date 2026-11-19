package com.example.valuamsecurity

import org.junit.Assert.*
import org.junit.Test

class ReglasTest {
    @Test fun comparaPerfilSinConfundirVersionConCambiosDeDatos() {
        val local = Usuario(nombres = "Alejandra", apellidos = "Campusano", correo = "ale@example.com", usuario = "ale", claveHash = "hash", version = 1)
        assertTrue(mismoPerfil(local, local.copy(version = 3, revisionClave = 2, activo = false)))
        assertFalse(mismoPerfil(local, local.copy(nombres = "Otro nombre")))
        assertFalse(mismoPerfil(local, local.copy(apellidos = "Otro apellido")))
        assertFalse(mismoPerfil(local, local.copy(usuario = "otro")))
        assertFalse(mismoPerfil(local, local.copy(correo = "otro@example.com")))
        assertFalse(mismoPerfil(local, local.copy(eliminado = true)))
        assertFalse(mismoPerfil(local, local.copy(id = "otro-id")))
    }
    @Test fun validaCadaRequisitoDeClave() {
        assertTrue(claveRobusta("Valuam1!"))
        listOf("Valua1!", "valuam1!", "VALUAM1!", "Valuam!!", "Valuam12", "Valuam1 ").forEach {
            assertFalse(it, claveRobusta(it))
        }
    }

    @Test fun buscaSinTildesNiMayusculas() {
        assertEquals("alejandra nunez", normalizarBusqueda("Alejandra Núñez"))
    }

    @Test fun aplicaUmbralDeTemperatura() {
        assertFalse(temperaturaAlta(24.99))
        assertTrue(temperaturaAlta(25.0))
    }
}
