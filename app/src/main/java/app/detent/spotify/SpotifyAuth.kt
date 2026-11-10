package app.detent.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.detent.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Spotify sign-in with Authorization Code + PKCE: the browser does the login, Detent only receives a
 * one-time code on [REDIRECT_URI] and trades it for tokens. No client secret is involved.
 * Tokens live in the app's private storage.
 */
class SpotifyAuth(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()

    val clientId: String = BuildConfig.SPOTIFY_CLIENT_ID
    val configured: Boolean get() = clientId.isNotBlank()
    /** Signed in with every permission Detent now asks for; an older sign-in with fewer permissions counts as signed out. */
    val signedIn: Boolean get() = prefs.getString(KEY_REFRESH, null) != null && prefs.getString(KEY_SCOPES, null) == SCOPES

    /** Opens Spotify's login page in the browser. Detent gets the answer back through [REDIRECT_URI]. */
    fun startLogin() {
        val verifier = randomString(64)
        val state = randomString(16)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()

        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
        appContext.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Called with the redirect Spotify sends back. Returns null on success, or a short reason on failure. */
    suspend fun completeLogin(redirect: Uri): String? {
        val error = redirect.getQueryParameter("error")
        if (error != null) return error
        val code = redirect.getQueryParameter("code") ?: return "no code"
        val expectedState = prefs.getString(KEY_STATE, null)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        if (expectedState == null || verifier == null || redirect.getQueryParameter("state") != expectedState) {
            return "sign-in was not started here"
        }
        return try {
            val json = postForm(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                ),
            )
            save(json)
            prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).putString(KEY_SCOPES, SCOPES).apply()
            null
        } catch (e: Exception) {
            e.message ?: "token request failed"
        }
    }

    /** A valid access token, refreshed when it is about to expire, or null when not signed in. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = refreshLock.withLock {
        val token = prefs.getString(KEY_ACCESS, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (!forceRefresh && token != null && System.currentTimeMillis() < expiresAt - 60_000) return token
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        try {
            save(
                postForm(
                    mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId),
                ),
            )
            prefs.getString(KEY_ACCESS, null)
        } catch (e: Exception) {
            // A revoked or expired refresh token means the person has to sign in again.
            if (e.message?.contains("invalid_grant") == true) signOut()
            null
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    private fun save(json: JSONObject) {
        val edit = prefs.edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
        // Spotify only sends a new refresh token sometimes; keep the old one otherwise.
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { edit.putString(KEY_REFRESH, it) }
        edit.apply()
    }

    private suspend fun postForm(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (conn.responseCode !in 200..299) {
                val reason = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw IllegalStateException(reason.ifEmpty { "HTTP ${conn.responseCode}" })
            }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun randomString(bytes: Int): String {
        val raw = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        /** Must be added, exactly as written, to the app's Redirect URIs in the Spotify developer dashboard. */
        const val REDIRECT_URI = "app.detent://spotify-callback"

        // Library and playlists to browse; playback control for when the player is in.
        // app-remote-control lets Detent steer the Spotify app through its App Remote.
        const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative " +
            "user-read-playback-state user-modify-playback-state app-remote-control"

        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_VERIFIER = "verifier"
        private const val KEY_STATE = "state"
        private const val KEY_SCOPES = "scopes"
    }
}
