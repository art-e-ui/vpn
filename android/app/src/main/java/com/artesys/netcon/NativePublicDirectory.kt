package com.artesys.netcon

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal object NativePublicDirectory {
    private const val BASE_URL = "https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/"
    private const val MAX_BYTES = 2_000_000
    private const val MAX_LINES = 5_000

    val countryCodes = listOf("AE", "AU", "CA", "DE", "FI", "FR", "GB", "HK", "IN", "IR", "JP", "KR", "NL", "SE", "SG", "TR", "US")

    data class Node(
        val id: String,
        val name: String,
        val protocol: String,
        val country: String,
        val countryCode: String?,
        val configJson: String,
    )

    data class Result(val nodes: List<Node>, val skipped: Int, val truncated: Boolean)

    fun fetch(countryCode: String): Result {
        if (countryCode.isNotEmpty() && countryCode !in countryCodes) {
            throw IllegalArgumentException("Choose a supported source country.")
        }
        val path = if (countryCode.isEmpty()) "top.txt" else "countries/$countryCode.txt"
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/plain")
        connection.setRequestProperty("User-Agent", "Netcon-Android")
        connection.instanceFollowRedirects = true
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Directory returned HTTP $status.")
            if (connection.contentLengthLong > MAX_BYTES) throw IllegalStateException("Directory feed exceeds the size limit.")
            val text = connection.inputStream.use(::readBounded)
            return parse(text, countryCode.ifEmpty { null })
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(feed: String, countryCode: String?): Result {
        if (feed.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            throw IllegalArgumentException("Directory feed exceeds the size limit.")
        }
        val content = if (feed.contains(Regex("(?m)^(vless|vmess|trojan|ss)://"))) {
            feed
        } else {
            try {
                String(Base64.decode(feed.trim(), Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Directory feed is not valid Base64 or a supported config list.", error)
            }
        }

        val result = ArrayList<Node>()
        val seenConfigs = HashSet<String>()
        var skipped = 0
        var lineCount = 0
        var truncated = false
        for (line in content.lineSequence()) {
            val value = line.trim()
            if (value.isEmpty() || value.startsWith("#")) continue
            lineCount++
            if (lineCount > MAX_LINES) {
                truncated = true
                break
            }
            try {
                val imported = NativeXrayConfig.fromInput(value)
                val protocol = imported.config.optJSONArray("outbounds")
                    ?.optJSONObject(0)
                    ?.optString("protocol")
                    ?.lowercase(Locale.ROOT)
                    ?: throw IllegalArgumentException("Server protocol is unavailable.")
                if (protocol !in setOf("vless", "vmess", "trojan", "shadowsocks")) {
                    throw IllegalArgumentException("Unsupported server protocol.")
                }
                if (!seenConfigs.add(sha256(value))) continue
                val id = UUID.randomUUID().toString()
                val index = result.size + 1
                val country = countryCode?.let { Locale.Builder().setRegion(it).build().displayCountry.ifBlank { it } } ?: "Unknown location"
                result.add(
                    Node(
                        id = id,
                        name = "${protocol.uppercase(Locale.ROOT)} · $index",
                        protocol = protocol,
                        country = country,
                        countryCode = countryCode,
                        configJson = imported.config.toString(),
                    ),
                )
            } catch (_: Exception) {
                skipped++
            }
        }
        return Result(result, skipped, truncated)
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BYTES) throw IllegalStateException("Directory feed exceeds the size limit.")
            output.write(buffer, 0, count)
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}