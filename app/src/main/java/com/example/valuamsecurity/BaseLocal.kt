package com.example.valuamsecurity

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun Usuario.json() = JSONObject().put("id", id).put("usuario", usuario)
    .put("nombres", nombres).put("apellidos", apellidos).put("correo", correo)
    .put("credencial", claveHash).put("esquema", esquema).put("version", version)
    .put("revision_clave", revisionClave).put("activo", activo).put("eliminado", eliminado)

internal fun usuarioJson(j: JSONObject) = Usuario(
    id = j.getString("id"), nombres = j.getString("nombres"), apellidos = j.getString("apellidos"),
    correo = j.getString("correo"), claveHash = j.optString("credencial", j.optString("claveHash")),
    usuario = j.optString("usuario"), esquema = j.optString("esquema", "sha256-legacy"),
    version = j.optLong("version"), revisionClave = j.optLong("revision_clave", 1), activo = j.optBoolean("activo", j.optInt("activo", 1) == 1),
    eliminado = j.optBoolean("eliminado", j.optInt("eliminado", 0) == 1)
)

internal class BaseLocal(contexto: Context, nombre: String = "valuam.db") : SQLiteOpenHelper(contexto, nombre, null, 2) {
    private val contexto = contexto.applicationContext
    companion object {
        @Volatile private var instancia: BaseLocal? = null
        fun compartida(contexto: Context): BaseLocal = instancia ?: synchronized(this) {
            instancia ?: BaseLocal(contexto.applicationContext).also { instancia = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usuarios (id TEXT PRIMARY KEY, datos TEXT NOT NULL, eliminado INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE pendientes (orden INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, entidad TEXT NOT NULL, tipo TEXT NOT NULL, datos TEXT NOT NULL, conflicto TEXT, envio TEXT)")
        db.execSQL("CREATE TABLE lecturas (id TEXT PRIMARY KEY, datos TEXT NOT NULL)")
        db.execSQL("CREATE TABLE acceso (id TEXT PRIMARY KEY, credencial TEXT NOT NULL, esquema TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (clave TEXT PRIMARY KEY, valor TEXT NOT NULL)")
        crearRevision(db)
        val anteriores = JSONArray(contexto.getSharedPreferences("usuarios_demo", Context.MODE_PRIVATE).getString("usuarios", "[]"))
        for (i in 0 until anteriores.length()) {
            val u = usuarioJson(anteriores.getJSONObject(i))
            escribirUsuario(db, u)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) crearRevision(db)
    }

    private fun crearRevision(db: SQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('revision_datos','0')")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS usuarios_insertados AFTER INSERT ON usuarios BEGIN UPDATE meta SET valor=CAST(valor AS INTEGER)+1 WHERE clave='revision_datos'; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS usuarios_actualizados AFTER UPDATE ON usuarios BEGIN UPDATE meta SET valor=CAST(valor AS INTEGER)+1 WHERE clave='revision_datos'; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS usuarios_eliminados AFTER DELETE ON usuarios BEGIN UPDATE meta SET valor=CAST(valor AS INTEGER)+1 WHERE clave='revision_datos'; END")
    }

    fun <T> transaccion(accion: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val resultado = accion(db)
            db.setTransactionSuccessful()
            resultado
        } finally {
            db.endTransaction()
        }
    }

    fun escribirUsuario(db: SQLiteDatabase, u: Usuario) {
        db.insertWithOnConflict("usuarios", null, ContentValues().apply {
            put("id", u.id); put("datos", u.json().toString()); put("eliminado", if (u.eliminado) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun meta(clave: String): String = readableDatabase.rawQuery("SELECT valor FROM meta WHERE clave=?", arrayOf(clave)).use {
        if (it.moveToFirst()) it.getString(0) else ""
    }

    fun meta(clave: String, valor: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply {
            put("clave", clave); put("valor", valor)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun encolar(db: SQLiteDatabase, entidad: String, tipo: String, datos: JSONObject) {
        db.insertOrThrow("pendientes", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("entidad", entidad)
            put("tipo", tipo); put("datos", datos.toString())
        })
    }
}

internal class Usuarios(contexto: Context) {
    private val contexto = contexto.applicationContext
    internal val base = BaseLocal.compartida(this.contexto)

    fun listar(): List<Usuario> = base.readableDatabase.rawQuery("SELECT datos FROM usuarios WHERE eliminado=0", null).use {
        buildList { while (it.moveToNext()) add(usuarioJson(JSONObject(it.getString(0)))) }
    }

    fun porCorreo(correo: String) = listar().firstOrNull { it.correo.equals(correo, ignoreCase = true) }
    fun porId(id: String?): Usuario? = base.readableDatabase.rawQuery("SELECT datos FROM usuarios WHERE id=?", arrayOf(id ?: "")).use {
        if (it.moveToFirst()) usuarioJson(JSONObject(it.getString(0))) else null
    }

    fun guardar(u: Usuario): Boolean = runCatching {
        base.transaccion { db ->
            check(listar().none { it.id != u.id && (it.correo.equals(u.correo, true) ||
                (u.usuario.isNotBlank() && it.usuario.equals(u.usuario, true))) }) { "Usuario o correo duplicado." }
            val anterior = porId(u.id)
            base.escribirUsuario(db, u)
            if (u.usuario.isNotBlank()) {
                if (conflictos().any { it.first == u.id && !it.second.has("remoto") }) {
                    db.delete("pendientes", "entidad=?", arrayOf(u.id))
                }
                base.encolar(db, u.id, if (anterior == null || anterior.version == 0L) "crear" else "editar", u.json())
            }
        }
        Sincronizacion.programar(contexto)
    }.isSuccess

    fun registrar(u: Usuario): Boolean {
        check(listar().none { it.correo.equals(u.correo, true) || it.usuario.equals(u.usuario, true) }) {
            "El usuario o correo ya está registrado en el teléfono."
        }
        if (!Servidor.conectado(contexto)) {
            check(guardar(u)) { "No se pudo guardar el registro local." }
            return false
        }
        val op = JSONObject().put("id", UUID.randomUUID().toString()).put("tipo", "crear").put("version", 0).put("datos", u.json())
        base.transaccion { db ->
            base.escribirUsuario(db, u)
            db.insertOrThrow("pendientes", null, ContentValues().apply {
                put("id", op.getString("id")); put("entidad", u.id); put("tipo", "crear")
                put("datos", u.json().toString()); put("envio", op.toString())
            })
        }
        val respuesta = try {
            Servidor.solicitar(contexto, JSONObject().put("accion", "operacion").put("operacion", op))
        } catch (e: ErrorApi) {
            if (e.estado == 400 || e.estado == 409) {
                base.transaccion { db ->
                    db.delete("pendientes", "id=?", arrayOf(op.getString("id")))
                    db.delete("usuarios", "id=?", arrayOf(u.id))
                }
            } else Sincronizacion.programar(contexto)
            throw e
        } catch (e: Exception) {
            // La respuesta puede perderse después de que MySQL haya confirmado la inserción.
            Sincronizacion.programar(contexto)
            return false
        }
        base.transaccion { db ->
            base.escribirUsuario(db, usuarioJson(respuesta.getJSONObject("usuario")).copy(claveHash = u.claveHash))
            db.delete("pendientes", "id=?", arrayOf(op.getString("id")))
        }
        return true
    }

    fun eliminar(id: String): Boolean = runCatching {
        base.transaccion { db ->
            val u = checkNotNull(porId(id))
            base.escribirUsuario(db, u.copy(eliminado = true))
            base.encolar(db, id, "eliminar", u.json())
        }
        Sincronizacion.programar(contexto)
    }.isSuccess

    fun pendientes(): Int = base.readableDatabase.rawQuery("SELECT COUNT(*) FROM pendientes", null).use { it.moveToFirst(); it.getInt(0) }
    fun sinUsuario() = listar().count { it.usuario.isBlank() }

    fun confirmarAcceso(u: Usuario, credencial: String) {
        base.transaccion { db ->
            if (!hayPendientes(u.id)) base.escribirUsuario(db, u.copy(claveHash = credencial))
            db.insertWithOnConflict("acceso", null, ContentValues().apply {
                put("id", u.id); put("credencial", credencial); put("esquema", u.esquema)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun accesoLocal(correo: String, clave: String): Usuario? {
        return listar().firstOrNull { u ->
            u.correo.equals(correo, true) && u.activo && !u.eliminado && base.readableDatabase.rawQuery(
                "SELECT credencial,esquema FROM acceso WHERE id=?", arrayOf(u.id)
            ).use {
                it.moveToFirst() && u.copy(claveHash = it.getString(0), esquema = it.getString(1)).coincide(clave)
            }
        }
    }

    fun invalidarAcceso(id: String) { base.writableDatabase.delete("acceso", "id=?", arrayOf(id)) }

    fun hayPendientes(id: String): Boolean = base.readableDatabase.rawQuery("SELECT 1 FROM pendientes WHERE entidad=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    fun incorporar(remoto: Usuario) {
        base.transaccion { db ->
            val local = porId(remoto.id)
            if (!remoto.activo || remoto.eliminado || local?.esquema != remoto.esquema || local?.revisionClave != remoto.revisionClave) invalidarAcceso(remoto.id)
            if (!hayPendientes(remoto.id)) base.escribirUsuario(db, remoto.copy(claveHash = local?.claveHash ?: ""))
        }
    }

    fun conflictos(): List<Pair<String, JSONObject>> = base.readableDatabase.rawQuery(
        "SELECT entidad,conflicto FROM pendientes WHERE conflicto IS NOT NULL GROUP BY entidad", null
    ).use { buildList { while (it.moveToNext()) add(it.getString(0) to JSONObject(it.getString(1))) } }

    fun resolver(id: String, usarLocal: Boolean) {
        base.transaccion { db ->
            val problema = conflictos().first { it.first == id }.second
            val remoto = problema.optJSONObject("remoto")?.let(::usuarioJson)
            val local = porId(id)
            db.delete("pendientes", "entidad=?", arrayOf(id))
            if (usarLocal && local != null) {
                val actualizado = local.copy(version = remoto?.version ?: 0)
                base.escribirUsuario(db, actualizado)
                base.encolar(db, id, if (local.eliminado) "eliminar" else if (remoto == null) "crear" else "editar", actualizado.json())
            } else if (remoto != null) {
                base.escribirUsuario(db, remoto.copy(claveHash = local?.claveHash ?: ""))
                invalidarAcceso(id)
            } else {
                db.delete("usuarios", "id=?", arrayOf(id))
                invalidarAcceso(id)
            }
        }
        Sincronizacion.programar(contexto)
    }

    fun guardarLectura(temperatura: Double, humedad: Double) {
        val lectura = JSONObject().put("id", UUID.randomUUID().toString()).put("temperatura", temperatura)
            .put("humedad", humedad).put("obtenida", System.currentTimeMillis())
        base.transaccion { db ->
            db.insertOrThrow("lecturas", null, ContentValues().apply { put("id", lectura.getString("id")); put("datos", lectura.toString()) })
            base.encolar(db, lectura.getString("id"), "lectura", lectura)
        }
        Sincronizacion.programar(contexto)
    }

    fun ultimaLectura(): JSONObject? = base.readableDatabase.rawQuery("SELECT datos FROM lecturas ORDER BY rowid DESC LIMIT 1", null).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
}
