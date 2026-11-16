package com.example.valuamsecurity

import org.junit.Assert.*
import org.junit.Test

class ReglasTest {
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
