package it.xonos.ma.data

import org.json.JSONObject
import java.util.Base64

/**
 * Quello che un token di Music Assistant (un JWT) dice di sé: il nome, quando scade (secondi dal 1970) e se è a
 * lunga durata. La firma non si controlla, è affare del server: serve solo a sapere quando chiederne uno nuovo.
 */
data class TokenInfo(val name: String?, val expiresAt: Long, val longLived: Boolean) {
    companion object {
        /** null se non è un JWT di Music Assistant (per esempio un token delle versioni vecchie). */
        fun read(token: String): TokenInfo? = runCatching {
            val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
            val json = JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
            val expiresAt = json.optLong("exp", 0L).takeIf { it > 0 } ?: return null
            TokenInfo(json.optString("token_name").ifBlank { null }, expiresAt, json.optBoolean("is_long_lived"))
        }.getOrNull()
    }
}
