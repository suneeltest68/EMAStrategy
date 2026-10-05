package com.example.util

import java.net.URI
import java.net.URLDecoder

object AuthUtils {
    fun extractAuthCode(input: String): String {
        val trimmed = input.trim()
        if (!trimmed.contains("auth_code=")) {
            return trimmed
        }
        return try {
            val uri = URI(if (trimmed.contains("?")) trimmed else trimmed.replaceFirst("/", "/?"))
            val query = uri.query ?: ""
            for (pair in query.split("&")) {
                val idx = pair.indexOf("=")
                if (idx > 0) {
                    val key = pair.substring(0, idx)
                    val value = pair.substring(idx + 1)
                    if (key == "auth_code") {
                        return URLDecoder.decode(value, "UTF-8")
                    }
                }
            }
            trimmed
        } catch (_: Exception) {
            val regex = "auth_code=([^&]+)".toRegex()
            val match = regex.find(trimmed)
            match?.groupValues?.get(1) ?: trimmed
        }
    }
}
