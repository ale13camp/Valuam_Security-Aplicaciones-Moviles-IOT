package com.example.valuamsecurity

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

internal class ErrorApi(val datos: JSONObject, val estado: Int) : Exception(datos.optString("mensaje", "Error del servidor."))

internal object Servidor {
    val ejecutor = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())

    fun conectado(contexto: Context): Boolean {
        val gestor = contexto.getSystemService(ConnectivityManager::class.java)
        return gestor.getNetworkCapabilities(gestor.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    fun esFalloDeTransporte(error: Throwable) = error is java.io.IOException || error is org.json.JSONException

    fun solicitar(contexto: Context, datos: JSONObject): JSONObject {
        val url = BuildConfig.API_BASE_URL
        check(url.isNotBlank()) { "Falta configurar la dirección del servidor." }
        val conexion = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = 15000
            conexion.readTimeout = 45000
            conexion.doOutput = true
            conexion.instanceFollowRedirects = false
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val token = BaseLocal.compartida(contexto).meta("token")
            if (token.isNotBlank()) conexion.setRequestProperty("Authorization", "Bearer $token")
            conexion.outputStream.use { it.write(datos.toString().toByteArray(Charsets.UTF_8)) }
            val estado = conexion.responseCode
            val flujo = if (estado in 200..299) conexion.inputStream else conexion.errorStream
            val respuesta = flujo?.bufferedReader(Charsets.UTF_8)?.use { JSONObject(it.readText()) }
                ?: throw IllegalStateException("Respuesta vacía del servidor.")
            if (!respuesta.optBoolean("ok")) throw ErrorApi(respuesta, estado)
            return respuesta
        } finally { conexion.disconnect() }
    }

    fun <T> ejecutar(tarea: () -> T, respuesta: (Result<T>) -> Unit) {
        ejecutor.execute {
            val resultado = runCatching(tarea)
            principal.post { respuesta(resultado) }
        }
    }
}

class ValuamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) Sincronizacion.programar(this@ValuamApp)
            }
        })
        Sincronizacion.programar(this)
    }
}

class Sincronizacion(contexto: Context, parametros: WorkerParameters) : Worker(contexto, parametros) {
    companion object {
        fun programar(contexto: Context) {
            val trabajo = OneTimeWorkRequestBuilder<Sincronizacion>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(contexto).enqueueUniqueWork("sincronizacion-valuam", ExistingWorkPolicy.APPEND_OR_REPLACE, trabajo)
        }
    }

    override fun doWork(): Result {
        val usuarios = Usuarios(applicationContext)
        val base = usuarios.base
        if (BuildConfig.API_BASE_URL.isBlank()) {
            base.meta("estado", "Falta configurar el servidor")
            return Result.success()
        }
        return try {
            base.meta("estado", "Sincronizando…")
            val ordenes = base.readableDatabase.rawQuery("SELECT orden FROM pendientes WHERE conflicto IS NULL ORDER BY orden", null).use {
                buildList { while (it.moveToNext()) add(it.getLong(0)) }
            }
            for (orden in ordenes) {
                if (isStopped) return Result.retry()
                val pendiente = base.readableDatabase.rawQuery("SELECT id,entidad,tipo,datos,envio FROM pendientes WHERE orden=? AND conflicto IS NULL", arrayOf(orden.toString())).use {
                    if (!it.moveToFirst()) null else List(5) { indice -> if (it.isNull(indice)) "" else it.getString(indice) }
                } ?: continue
                val id = pendiente[0]
                val entidad = pendiente[1]
                if (usuarios.conflictos().any { it.first == entidad }) continue
                val local = usuarios.porId(entidad)
                val tipo = if (pendiente[2] == "crear" && (local?.version ?: 0) > 0) "editar" else pendiente[2]
                if (tipo != "crear" && base.meta("token").isBlank()) continue
                // Se persiste el sobre exacto antes del envío para repetirlo después de una respuesta perdida.
                val op = if (pendiente[4].isNotBlank()) JSONObject(pendiente[4]) else JSONObject()
                    .put("id", id).put("tipo", tipo).put("version", JSONObject(pendiente[3]).optLong("version")).put("datos", JSONObject(pendiente[3]))
                base.writableDatabase.execSQL("UPDATE pendientes SET envio=? WHERE orden=?", arrayOf<Any>(op.toString(), orden))
                try {
                    val respuesta = Servidor.solicitar(applicationContext, JSONObject().put("accion", "operacion").put("operacion", op))
                    base.transaccion { db ->
                        respuesta.optJSONObject("usuario")?.let {
                            val remoto = usuarioJson(it)
                            val actual = usuarios.porId(entidad)
                            if (actual != null) base.escribirUsuario(db, actual.copy(version = remoto.version, activo = remoto.activo))
                            db.rawQuery("SELECT orden,datos FROM pendientes WHERE entidad=? AND orden>? AND envio IS NULL", arrayOf(entidad, orden.toString())).use { siguientes ->
                                while (siguientes.moveToNext()) {
                                    val datos = JSONObject(siguientes.getString(1))
                                    if (datos.optLong("version") == op.optLong("version")) {
                                        datos.put("version", remoto.version)
                                        db.execSQL("UPDATE pendientes SET datos=? WHERE orden=?", arrayOf<Any>(datos.toString(), siguientes.getLong(0)))
                                    }
                                }
                            }
                        }
                        db.delete("pendientes", "id=?", arrayOf(id))
                    }
                } catch (e: ErrorApi) {
                    if (e.estado == 409 || e.estado == 400) {
                        base.writableDatabase.execSQL("UPDATE pendientes SET conflicto=? WHERE id=?", arrayOf(e.datos.toString(), id))
                    } else throw e
                }
            }
            if (base.meta("token").isNotBlank()) {
                val lista = Servidor.solicitar(applicationContext, JSONObject().put("accion", "listar")).getJSONArray("usuarios")
                for (i in 0 until lista.length()) usuarios.incorporar(usuarioJson(lista.getJSONObject(i)))
            }
            val estado = when {
                usuarios.pendientes() > 0 -> "Hay cambios pendientes"
                base.meta("token").isBlank() -> "Inicia sesión para consultar MySQL"
                else -> "Sincronización completada"
            }
            base.meta("estado", estado)
            Result.success()
        } catch (e: ErrorApi) {
            base.meta("estado", e.message ?: "Error de sincronización")
            if (e.estado == 401 || e.estado == 403) {
                base.meta("token", "")
                // Una respuesta de autenticación negativa revoca el permiso local previamente confirmado.
                usuarios.invalidarAcceso(base.meta("usuario_actual"))
                Result.success()
            } else Result.retry()
        } catch (e: Exception) {
            base.meta("estado", "Servidor sin respuesta. Los cambios siguen guardados.")
            Result.retry()
        }
    }
}
