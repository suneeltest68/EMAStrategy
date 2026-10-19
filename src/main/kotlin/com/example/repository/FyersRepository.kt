package com.example.repository

import com.tts.`in`.model.FyersClass
import com.tts.`in`.model.StockHistoryModel
import com.tts.`in`.model.HistoryFNOExpiredModel
import org.json.JSONObject
import java.security.MessageDigest
import java.io.File

class FyersRepository {

    private val tokenFile = File("token.json")
    private val cacheDir = File("cache").apply { if (!exists()) mkdirs() }
    private val indexCacheFile = File(cacheDir, "index_cache.json")
    private val derivativesCacheFile = File(cacheDir, "derivatives_cache.json")

    @Synchronized
    private fun loadCacheObject(file: File): JSONObject {
        if (file.exists() && file.length() > 0) {
            try {
                val content = file.readText()
                if (content.isNotEmpty()) {
                    return JSONObject(content)
                }
            } catch (_: Exception) {}
        }
        return JSONObject()
    }

    @Synchronized
    private fun readFromConsolidatedCache(file: File, cacheKey: String): JSONObject? {
        val root = loadCacheObject(file)
        if (root.has(cacheKey)) {
            return root.optJSONObject(cacheKey)
        }
        return null
    }

    @Synchronized
    private fun saveToConsolidatedCache(file: File, cacheKey: String, data: JSONObject) {
        try {
            val root = loadCacheObject(file)
            root.put(cacheKey, data)
            file.writeText(root.toString())
        } catch (_: Exception) {}
    }

    fun clearToken() {
        if (tokenFile.exists()) {
            tokenFile.delete()
        }
    }

    fun getLoginUrl(appId: String, redirectUri: String): String {
        try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId
            fyersClass.GenerateCode(redirectUri)
        } catch (_: Exception) {
            // Ignore
        }
        return "https://api-t1.fyers.in/api/v3/generate-authcode?client_id=$appId&redirect_uri=$redirectUri&response_type=code&state=sample_state"
    }

    fun exchangeAuthCodeForToken(appId: String, secretKey: String, authCode: String): Result<String> {
        return try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId

            val appHashId = sha256("$appId:$secretKey")
            val rawResponse = fyersClass.GenerateToken(authCode, appHashId)
            val jsonResponse = when (rawResponse) {
                is JSONObject -> rawResponse
                else -> JSONObject(rawResponse.toString())
            }

            val accessToken = when {
                jsonResponse.has("access_token") -> jsonResponse.getString("access_token")
                jsonResponse.has("data") && jsonResponse.getJSONObject("data").has("access_token") -> jsonResponse.getJSONObject("data").getString("access_token")
                else -> null
            }

            if (accessToken != null && accessToken.isNotEmpty()) {
                saveToken(accessToken)
                Result.success(accessToken)
            } else {
                val message = jsonResponse.optString("RESPONSE_MESSAGE", jsonResponse.optString("message", "Failed to generate token. Response: $jsonResponse"))
                Result.failure(Exception(message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getHistoricalData(appId: String, accessToken: String, symbol: String, resolution: String, rangeFrom: String, rangeTo: String,isForLiveTrading: Boolean = false): Result<JSONObject> {
        val isIndex = symbol.contains("INDEX", ignoreCase = true) || symbol.contains("NIFTY50", ignoreCase = true) || symbol.contains("BANKNIFTY", ignoreCase = true) || symbol.contains("SENSEX", ignoreCase = true)
        val targetFile = if (isIndex) indexCacheFile else derivativesCacheFile
        val cacheKey = "history_${symbol}_${resolution}_${rangeFrom}_${rangeTo}"

        if (!isForLiveTrading) {
            val cached = readFromConsolidatedCache(targetFile, cacheKey)
            if (cached != null) {
                return Result.success(cached)
            }
        }

        return try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId
            fyersClass.accessToken = accessToken

            val model = StockHistoryModel().apply {
                Symbol = symbol
                Resolution = resolution
                DateFormat = "1"
                RangeFrom = rangeFrom
                RangeTo = rangeTo
                ContFlag = 1
            }

            val tuple = fyersClass.GetStockHistory(model)
            val historyJson = tuple?.Item1()
            val errorJson = tuple?.Item2()

            if (errorJson == null && historyJson != null) {
                saveToConsolidatedCache(targetFile, cacheKey, historyJson)
                Result.success(historyJson)
            } else {
                Result.failure(Exception("Stock History Error: $errorJson"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getOptionChain(appId: String, accessToken: String, symbol: String, strikeCount: Int, isForLiveTrading: Boolean = false): Result<JSONObject> {
        val cacheKey = "optionchain_${symbol}_${strikeCount}"
        if (!isForLiveTrading) {
            val cached = readFromConsolidatedCache(derivativesCacheFile, cacheKey)
            if (cached != null) {
                return Result.success(cached)
            }
        }

        return try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId
            fyersClass.accessToken = accessToken

            val tuple = fyersClass.GetOptionChain(symbol, strikeCount, "", "")
            val chainJson = tuple?.Item1()
            val errorJson = tuple?.Item2()

            if (errorJson == null && chainJson != null) {
                saveToConsolidatedCache(derivativesCacheFile, cacheKey, chainJson)
                Result.success(chainJson)
            } else {
                val errorMsg = errorJson?.toString() ?: "Unknown error"
                Result.failure(Exception("Option Chain Error: $errorMsg"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getHistoryExpiryDates(appId: String, accessToken: String, symbol: String, rangeFrom: String, rangeTo: String): Result<JSONObject> {
        val cacheKey = "expirydates_${symbol}_${rangeFrom}_${rangeTo}"
        val cached = readFromConsolidatedCache(derivativesCacheFile, cacheKey)
        if (cached != null) {
            return Result.success(cached)
        }

        return try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId
            fyersClass.accessToken = accessToken

            val tuple = fyersClass.GetHistoryExpiryDates(symbol, rangeFrom, rangeTo, 1)
            val json = tuple?.Item1()
            val error = tuple?.Item2()

            if (error == null && json != null) {
                saveToConsolidatedCache(derivativesCacheFile, cacheKey, json)
                Result.success(json)
            } else {
                Result.failure(Exception("Expiry Dates Error: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getHistoryUnderlyingSymbols(appId: String, accessToken: String, symbol: String, expiryDate: String): Result<JSONObject> {
        val cacheKey = "underlyingsymbols_${symbol}_${expiryDate}"
        val cached = readFromConsolidatedCache(derivativesCacheFile, cacheKey)
        if (cached != null) {
            return Result.success(cached)
        }

        return try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId
            fyersClass.accessToken = accessToken

            val tuple = fyersClass.GetHistoryUnderlyingSymbols(symbol, expiryDate)
            val json = tuple?.Item1()
            val error = tuple?.Item2()

            if (error == null && json != null) {
                saveToConsolidatedCache(derivativesCacheFile, cacheKey, json)
                Result.success(json)
            } else {
                Result.failure(Exception("Underlying Symbols Error: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getHistoryFNOExpired(appId: String, accessToken: String, fnoSymbol: String, rangeFrom: String, rangeTo: String, resolution: String): Result<JSONObject> {
        val cacheKey = "fno_expired_${fnoSymbol}_${rangeFrom}_${rangeTo}_${resolution}"
        val cached = readFromConsolidatedCache(derivativesCacheFile, cacheKey)
        if (cached != null) {
            return Result.success(cached)
        }

        return try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId
            fyersClass.accessToken = accessToken

            val model = HistoryFNOExpiredModel().apply {
                Symbol = fnoSymbol
                Resolution = resolution
                DateFormat = "1"
                RangeFrom = rangeFrom
                RangeTo = rangeTo
                Greeks = 1
            }

            val tuple = fyersClass.GetHistoryFNOExpired(model)
            val json = tuple?.Item1()
            val error = tuple?.Item2()

            if (error == null && json != null) {
                saveToConsolidatedCache(derivativesCacheFile, cacheKey, json)
                Result.success(json)
            } else {
                Result.failure(Exception("FNO Expired History Error: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getStockQuotes(appId: String, accessToken: String, symbols: String): Result<JSONObject> {
        return try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId
            fyersClass.accessToken = accessToken

            val tuple = fyersClass.GetStockQuotes(symbols)
            val json = tuple?.Item1()
            val error = tuple?.Item2()

            if (error == null && json != null) {
                Result.success(json)
            } else {
                Result.failure(Exception("Stock Quotes Error: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCachedToken(): String? {
        if (!tokenFile.exists()) return null
        try {
            val content = tokenFile.readText()
            val json = JSONObject(content)
            val token = json.optString("access_token", "")
            val timestamp = json.optLong("timestamp", 0L)

            val twentyFourHours = 24 * 60 * 60 * 1000L
            if (token.isNotEmpty() && (System.currentTimeMillis() - timestamp < twentyFourHours)) {
                return token
            }
        } catch (_: Exception) {}
        return null
    }

    private fun saveToken(token: String) {
        try {
            val json = JSONObject().apply {
                put("access_token", token)
                put("timestamp", System.currentTimeMillis())
            }
            tokenFile.writeText(json.toString(4))
        } catch (_: Exception) {}
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
