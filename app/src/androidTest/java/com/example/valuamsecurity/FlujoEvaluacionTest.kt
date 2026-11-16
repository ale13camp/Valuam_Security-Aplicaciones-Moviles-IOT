package com.example.valuamsecurity

import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.widget.EditText
import androidx.test.filters.SdkSuppress
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class FlujoEvaluacionTest {
    private val instrumentacion get() = InstrumentationRegistry.getInstrumentation()

    private fun pulsar(id: Int) = enPantalla { check(it.findViewById<View>(id).performClick()) }
    private fun pulsar(texto: String) = enPantalla { check(buscarTexto(it.window.decorView, texto)?.performClick() == true) }
    private fun escribir(id: Int, texto: String) = enPantalla { it.findViewById<EditText>(id).setText(texto) }
    private fun visible(texto: String) = enPantalla { check(buscarTexto(it.window.decorView, texto) != null) { texto } }

    private fun enPantalla(accion: (ComponentActivity) -> Unit) {
        var resultado = Result.success(Unit)
        instrumentacion.runOnMainSync {
            val actual = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .single() as ComponentActivity
            resultado = runCatching { accion(actual) }
        }
        resultado.getOrThrow()
        SystemClock.sleep(350)
    }

    private fun opcionDialogo(texto: String) = buscarEnDialogo(texto, pulsar = true)
    private fun aceptar() = opcionDialogo("Aceptar")
    private fun aviso(texto: String) = buscarEnDialogo(texto, pulsar = false)

    private fun buscarEnDialogo(texto: String, pulsar: Boolean) {
        val instrumentacion = InstrumentationRegistry.getInstrumentation()
        val limite = SystemClock.elapsedRealtime() + 5000
        do {
            var hallado = false
            instrumentacion.runOnMainSync {
                val vista = WindowInspector.getGlobalWindowViews().asReversed()
                    .filter { it.layoutParams is android.view.WindowManager.LayoutParams &&
                        (it.layoutParams as android.view.WindowManager.LayoutParams).type ==
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION }
                    .firstNotNullOfOrNull { buscarTexto(it, texto) }
                hallado = vista != null && (!pulsar || vista.performClick())
            }
            if (hallado) {
                if (pulsar) SystemClock.sleep(300)
                return
            }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < limite)
        error("No se encontró el texto del diálogo: $texto")
    }

    private fun buscarTexto(vista: View, texto: String): TextView? {
        if (!vista.isShown) return null
        if (vista is TextView && vista.text.toString() == texto) return vista
        if (vista is ViewGroup) {
            for (indice in 0 until vista.childCount) {
                buscarTexto(vista.getChildAt(indice), texto)?.let { return it }
            }
        }
        return null
    }

    @Test fun registroIngresoRecuperacionCrudYSensores() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val correo = "qa${UUID.randomUUID().toString().take(8)}@example.com"
        val clave = "Valuam1!"
        val nueva = "Valuam2!"
        try {
            val automatizacion = InstrumentationRegistry.getInstrumentation().uiAutomation
            automatizacion.executeShellCommand("am start -W -n com.example.valuamsecurity/.MainActivity").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
            }
            run {
                SystemClock.sleep(6000)
                pulsar(R.id.ingresar)
                aviso("Ingresa el correo electrónico.")
                aceptar()
                enPantalla { check(it.findViewById<EditText>(R.id.correo).hasFocus()) }
                pulsar(R.id.registrarme)
                escribir(R.id.nombres, "María")
                escribir(R.id.apellidos, "Núñez")
                escribir(R.id.correo, correo)
                escribir(R.id.clave, clave)
                escribir(R.id.confirmacion, nueva)
                pulsar(R.id.guardar)
                aviso("Repite la misma contraseña.")
                aceptar()
                escribir(R.id.confirmacion, clave)
                pulsar(R.id.guardar)
                aviso("Usuario registrado correctamente.")
                aceptar()

                pulsar(R.id.recuperar)
                escribir(R.id.correo, correo)
                pulsar(R.id.recuperar)
                aviso("Envío simulado. Ingresa los cinco dígitos.")
                aceptar()
                pulsar(R.id.verificar)
                aviso("Completa los cinco dígitos.")
                aceptar()
                val casillas = listOf(R.id.digito1, R.id.digito2, R.id.digito3, R.id.digito4, R.id.digito5)
                casillas.forEach { escribir(it, "0") }
                pulsar(R.id.verificar)
                aviso("El código es incorrecto.")
                aceptar()
                casillas.zip("48216".toList()).forEach { (id, digito) -> escribir(id, digito.toString()) }
                pulsar(R.id.verificar)
                escribir(R.id.clave, nueva)
                escribir(R.id.confirmacion, nueva)
                pulsar(R.id.guardar)
                aviso("Tu contraseña se actualizó.")
                aceptar()

                escribir(R.id.correo, correo.uppercase())
                escribir(R.id.clave, clave)
                pulsar(R.id.ingresar)
                aviso("El correo o la contraseña son incorrectos.")
                aceptar()
                escribir(R.id.clave, nueva)
                pulsar(R.id.ingresar)
                aviso("Bienvenido, María.")
                aceptar()

                pulsar(R.id.sensores)
                pulsar(R.id.ampolleta)
                visible("Ampolleta\nEncendida")
                visible("Linterna\nApagada")
                pulsar(R.id.linterna)
                visible("Linterna\nEncendida")
                pulsar(R.id.ampolleta)
                visible("Ampolleta\nApagada")
                verificarAPI()

                volver()
                pulsar(R.id.equipo)
                visible("Alejandra Campusano")
                visible("Mauro Valdivia")
                volver()
                pulsar(R.id.gestion)
                pulsar(R.id.listar)
                escribir(R.id.buscar, "NUNEZ")
                pulsar("María Núñez")
                escribir(R.id.nombres, "Ana")
                pulsar(R.id.guardar)
                aviso("Datos actualizados.")
                aceptar()
                pulsar("Ana Núñez")
                pulsar(R.id.eliminar)
                opcionDialogo("Cancelar")
                pulsar(R.id.eliminar)
                opcionDialogo("Eliminar")
                aviso("Usuario eliminado.")
                aceptar()
                visible("Sin coincidencias.")
                volver()
                pulsar(R.id.registrarme)
                escribir(R.id.nombres, "María")
                escribir(R.id.apellidos, "Núñez")
                escribir(R.id.correo, correo)
                escribir(R.id.clave, clave)
                escribir(R.id.confirmacion, clave)
                pulsar(R.id.guardar)
                aviso("Usuario registrado correctamente.")
                aceptar()
                enPantalla { check(it is Gestion) }

                pulsar(R.id.registrarme)
                escribir(R.id.nombres, "María")
                escribir(R.id.apellidos, "Núñez")
                escribir(R.id.correo, correo.uppercase())
                escribir(R.id.clave, clave)
                escribir(R.id.confirmacion, clave)
                pulsar(R.id.guardar)
                aviso("Ese correo ya está registrado.")
                aceptar()
            }
        } finally {
            limpiarCuenta(context, correo)
        }
    }

    private fun volver() = enPantalla { it.onBackPressedDispatcher.onBackPressed() }

    private fun verificarAPI() {
        val limite = SystemClock.elapsedRealtime() + 15000
        do {
            var hayDatos = false
            enPantalla {
                hayDatos = it.findViewById<TextView>(R.id.temperatura).text.contains("°C") &&
                    it.findViewById<TextView>(R.id.humedad).text.contains("%")
            }
            if (hayDatos) return
        } while (SystemClock.elapsedRealtime() < limite)
        error("La pantalla no mostró los valores de la API.")
    }
    private fun limpiarCuenta(context: Context, correo: String) {
        val usuarios = Usuarios(context)
        usuarios.porCorreo(correo)?.let { usuarios.eliminar(it.id) }
    }
}
