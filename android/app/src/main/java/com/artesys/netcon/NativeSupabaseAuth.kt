package com.artesys.netcon

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

internal object NativeSupabaseAuth {
    const val CALLBACK_URI = "com.artesys.netcon.client://auth/callback"

    class SessionExpiredException : IllegalStateException("Your Google session expired. Sign in again.")

    data class AuthorizationRequest(val url: String, val codeVerifier: String)

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val email: String,
    )

    fun createAuthorizationRequest(supabaseUrl: String, publishableKey: String): AuthorizationRequest {
        requireConfiguration(supabaseUrl, publishableKey)
        val secureRandom = SecureRandom()
        val verifierBytes = ByteArray(32).also(secureRandom::nextBytes)
        val verifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val authorizationUri = Uri.parse("${supabaseUrl.trimEnd('/')}/auth/v1/authorize")
            .buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", CALLBACK_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build()

        val connection = URL(authorizationUri.toString()).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("apikey", publishableKey)
        try {
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            val providerUri = location?.let { Uri.parse(it) }
            if (status !in 300..399 || providerUri?.scheme != "https" || providerUri.host != "accounts.google.com") {
                throw IllegalStateException("Google sign-in could not be started (HTTP $status).")
            }
            return AuthorizationRequest(location, verifier)
        } finally {
            connection.disconnect()
        }
    }

    fun exchangeAuthorizationCode(
        supabaseUrl: String,
        publishableKey: String,
        code: String,
        codeVerifier: String,
    ): Session {
        requireConfiguration(supabaseUrl, publishableKey)
        require(code.isNotBlank() && code.length <= 4096) { "Google sign-in returned an invalid authorization code." }
        require(codeVerifier.isNotBlank() && codeVerifier.length <= 256) { "Google sign-in could not be verified. Please try again." }
        val response = postJson(
            supabaseUrl,
            publishableKey,
            "/auth/v1/token?grant_type=pkce",
            JSONObject().put("auth_code", code).put("code_verifier", codeVerifier),
        )
        return parseSession(response)
    }

    fun refreshIfNeeded(
        supabaseUrl: String,
        publishableKey: String,
        session: Session,
    ): Session {
        if (session.expiresAtMillis - 60_000 > System.currentTimeMillis()) return session
        if (session.refreshToken.isBlank()) throw SessionExpiredException()
        val response = postJson(
            supabaseUrl,
            publishableKey,
            "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", session.refreshToken),
        )
        return parseSession(response, session)
    }

    fun revokeSession(supabaseUrl: String, publishableKey: String, session: Session) {
        requireConfiguration(supabaseUrl, publishableKey)
        val connection = URL("${supabaseUrl.trimEnd('/')}/auth/v1/logout").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "POST"
        connection.setRequestProperty("apikey", publishableKey)
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Supabase sign-out failed (HTTP $status).")
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(
        supabaseUrl: String,
        publishableKey: String,
        path: String,
        body: JSONObject,
    ): JSONObject {
        val connection = URL("${supabaseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("apikey", publishableKey)
        connection.setRequestProperty("Content-Type", "application/json")
        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseText.length > 32_000) throw IllegalStateException("Supabase returned an oversized response.")
            if (status !in 200..299) throw IllegalStateException("Supabase authentication failed (HTTP $status).")
            return try {
                JSONObject(responseText)
            } catch (error: Exception) {
                throw IllegalStateException("Supabase returned an invalid authentication response.", error)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSession(response: JSONObject, previous: Session? = null): Session {
        val accessToken = response.optString("access_token")
        val refreshToken = response.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
        val expiresIn = response.optLong("expires_in", 0L)
        require(accessToken.isNotBlank() && refreshToken.isNotBlank() && expiresIn > 0) {
            "Supabase returned an incomplete sign-in session."
        }
        val email = response.optJSONObject("user")?.optString("email").orEmpty()
        return Session(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000,
            email = email.ifBlank { previous?.email.orEmpty() },
        )
    }

    private fun requireConfiguration(supabaseUrl: String, publishableKey: String) {
        val uri = Uri.parse(supabaseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Supabase sign-in is not configured with a secure project URL."
        }
        require(publishableKey.isNotBlank()) { "Supabase sign-in is not configured in this build." }
    }
}
