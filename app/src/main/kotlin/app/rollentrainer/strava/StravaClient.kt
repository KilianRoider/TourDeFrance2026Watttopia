package app.rollentrainer.strava

import android.net.Uri
import app.rollentrainer.data.SettingsStore
import app.rollentrainer.data.StravaAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Strava OAuth and activity upload, using the user's own API application
 * (client id and secret from strava.com/settings/api, callback domain "localhost").
 */
class StravaClient(private val settings: SettingsStore) {
    val isConnected: Boolean get() = settings.value.strava != null

    fun authorizeUri(): Uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
        .appendQueryParameter("client_id", settings.value.stravaClientId.trim())
        .appendQueryParameter("redirect_uri", REDIRECT_URI)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("approval_prompt", "auto")
        .appendQueryParameter("scope", "activity:write,read")
        .build()

    /** Handles rollentrainer://localhost/strava?code=… from the browser. */
    suspend fun handleRedirect(uri: Uri): Result<String> = runCatching {
        uri.getQueryParameter("error")?.let { throw IOException("Strava: $it") }
        val code = uri.getQueryParameter("code") ?: throw IOException("Kein Code von Strava erhalten")
        val json = tokenRequest(mapOf("code" to code, "grant_type" to "authorization_code"))
        val athlete = json["athlete"]?.jsonObject
        val name = listOfNotNull(
            athlete?.get("firstname")?.jsonPrimitive?.contentOrNull,
            athlete?.get("lastname")?.jsonPrimitive?.contentOrNull,
        ).joinToString(" ")
        val auth = authFrom(json).copy(athleteName = name)
        settings.update { it.copy(strava = auth) }
        name
    }

    fun disconnect() = settings.update { it.copy(strava = null) }

    private suspend fun validToken(): String {
        val auth = settings.value.strava ?: throw IOException("Nicht mit Strava verbunden")
        if (auth.expiresAt > System.currentTimeMillis() / 1000 + 120) return auth.accessToken
        val json = tokenRequest(mapOf("refresh_token" to auth.refreshToken, "grant_type" to "refresh_token"))
        val fresh = authFrom(json).copy(athleteName = auth.athleteName)
        settings.update { it.copy(strava = fresh) }
        return fresh.accessToken
    }

    private fun authFrom(json: JsonObject) = StravaAuth(
        accessToken = json.str("access_token") ?: throw IOException("Antwort ohne Token"),
        refreshToken = json.str("refresh_token") ?: throw IOException("Antwort ohne Refresh-Token"),
        expiresAt = json["expires_at"]?.jsonPrimitive?.longOrNull ?: 0,
    )

    private suspend fun tokenRequest(params: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val s = settings.value
        val body = (params + mapOf("client_id" to s.stravaClientId.trim(), "client_secret" to s.stravaClientSecret.trim()))
            .entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val conn = (URL("https://www.strava.com/oauth/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        readJson(conn)
    }

    /** Uploads a FIT file and waits until Strava has created the activity. Returns its id. */
    suspend fun upload(fit: File, name: String, description: String): Long = withContext(Dispatchers.IO) {
        val token = validToken()
        val boundary = "----rollentrainer${UUID.randomUUID()}"
        val conn = (URL("$API/uploads").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { out ->
            fun field(k: String, v: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
            }
            field("data_type", "fit")
            field("name", name)
            field("description", description)
            field("trainer", "1")
            field("external_id", fit.nameWithoutExtension)
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${fit.name}\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
            )
            out.write(fit.readBytes())
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        var json = readJson(conn)
        val uploadId = json["id"]?.jsonPrimitive?.longOrNull ?: throw IOException("Upload ohne ID")
        repeat(15) {
            json.str("error")?.let { throw IOException(it) }
            json["activity_id"]?.jsonPrimitive?.longOrNull?.let { return@withContext it }
            delay(2000)
            val poll = (URL("$API/uploads/$uploadId").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
            }
            json = readJson(poll)
        }
        throw IOException("Strava verarbeitet die Datei noch. Bitte später in Strava nachsehen.")
    }

    private fun readJson(conn: HttpURLConnection): JsonObject {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code !in 200..299) {
            val msg = json?.str("message") ?: text.take(200)
            throw IOException("Strava-Fehler $code: $msg")
        }
        return json ?: throw IOException("Unerwartete Antwort von Strava")
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val REDIRECT_URI = "rollentrainer://localhost/strava"
        // Strava announced api-v3.strava.com as the new base URL from January 2027.
        const val API = "https://www.strava.com/api/v3"
    }
}
