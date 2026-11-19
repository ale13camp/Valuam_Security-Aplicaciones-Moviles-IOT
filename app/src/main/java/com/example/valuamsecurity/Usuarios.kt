package com.example.valuamsecurity

import android.content.Context
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.os.Build
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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

internal fun mismoPerfil(local: Usuario, remoto: Usuario): Boolean =
    local.id == remoto.id && !local.eliminado && !remoto.eliminado &&
        local.usuario == remoto.usuario && local.nombres == remoto.nombres &&
        local.apellidos == remoto.apellidos && local.correo == remoto.correo

internal data class Usuario(
    val id: String = UUID.randomUUID().toString(),
    val nombres: String,
    val apellidos: String,
    val correo: String,
    val claveHash: String,
    val usuario: String = "",
    val esquema: String = "sha256-legacy",
    val version: Long = 0,
    val revisionClave: Long = 1,
    val activo: Boolean = true,
    val eliminado: Boolean = false
) {
    fun coincide(clave: String) = claveHash == calcularHash(id, clave)
    fun conClave(clave: String) = copy(claveHash = calcularHash(id, clave))

    private fun calcularHash(sal: String, clave: String): String {
        if (esquema == "pbkdf2-120000") {
            if (Build.VERSION.SDK_INT < 26) {
                // PBKDF2-HMAC-SHA256 de un bloque (256 bits), compatible con Android 7.
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(clave.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                var bloque = mac.doFinal(sal.toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0, 1))
                val derivada = bloque.copyOf()
                repeat(119999) {
                    bloque = mac.doFinal(bloque)
                    for (indice in derivada.indices) derivada[indice] = (derivada[indice].toInt() xor bloque[indice].toInt()).toByte()
                }
                return derivada.joinToString("") { "%02x".format(it) }
            }
            val spec = PBEKeySpec(clave.toCharArray(), sal.toByteArray(Charsets.UTF_8), 120000, 256)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                .joinToString("") { "%02x".format(it) }.also { spec.clearPassword() }
        }
        return MessageDigest.getInstance("SHA-256").digest((sal + clave).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
