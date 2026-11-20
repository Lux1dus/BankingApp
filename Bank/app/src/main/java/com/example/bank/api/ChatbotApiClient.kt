package com.example.bank.api

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Client gọi API AI Chatbot (Gemini) trên `BankServer` (`POST /v1/chat`).
 */
object ChatbotApiClient {

    @Volatile
    private var customBaseUrl: String? = null

    val baseUrl: String
        get() = customBaseUrl ?: UtilitiesApiClient.baseUrl

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setServerBaseUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isNotEmpty()) {
            customBaseUrl = if (cleaned.endsWith("/v1")) cleaned else "$cleaned/v1"
        }
    }

    /**
     * Gửi tin nhắn tới `POST /v1/chat` trên `BankServer` (BankServer gọi Gemini API kèm danh sách tiện ích app).
     */
    fun sendMessage(
        message: String,
        inputType: String = "TEXT",
        sessionId: String? = null,
        userLat: Double? = null,
        userLng: Double? = null,
        callback: (Result<ChatbotReplyDto>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                sendMessageSync(message, inputType, sessionId, userLat, userLng)
            }
            mainHandler.post { callback(result) }
        }
    }

    @Throws(Exception::class)
    fun sendMessageSync(
        message: String,
        inputType: String = "TEXT",
        sessionId: String? = null,
        userLat: Double? = null,
        userLng: Double? = null
    ): ChatbotReplyDto {
        val token = try {
            UtilitiesApiClient.ensureAuthenticatedSync()
        } catch (_: Exception) {
            UtilitiesApiClient.accessToken
        }

        val payload = JSONObject().apply {
            put("message", message)
            put("input_type", inputType)
            if (!sessionId.isNullOrBlank()) {
                put("session_id", sessionId)
            }
            if (userLat != null && userLng != null) {
                put(
                    "user_location",
                    JSONObject().apply {
                        put("latitude", userLat)
                        put("longitude", userLng)
                    }
                )
            }
        }

        val fullUrl = URL("$baseUrl/chat")
        val conn = (fullUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 45000 // Nâng thời gian chờ để đảm bảo nhận trọn vẹn phản hồi từ Gemini API
            doInput = true
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val rawResponse = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.orEmpty()

            if (code !in 200..299) {
                val errMsg = try {
                    val errJson = JSONObject(rawResponse)
                    errJson.optString("message").ifBlank { errJson.optString("error", "HTTP $code") }
                } catch (_: Exception) {
                    "HTTP $code: $rawResponse"
                }
                throw RuntimeException(errMsg)
            }

            val json = JSONObject(rawResponse)
            return parseReply(json)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseReply(json: JSONObject): ChatbotReplyDto {
        val intent = json.optString("intent", "GENERAL_HELP")
        val replyText = json.optString("message_text").ifBlank {
            json.optString("message", "Em đã tiếp nhận yêu cầu của anh/chị!")
        }

        val actionObj = json.optJSONObject("action")
        val topToolTitle = json.optString("tool_title").takeIf { it.isNotBlank() && it != "null" }

        val action = if (actionObj != null && actionObj.optString("type", "NAVIGATE") != "NONE") {
            val deepLink = actionObj.optString("action_deep_link")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: actionObj.optString("destination").takeIf { it.isNotBlank() && it != "null" }
                ?: "app://utilities/dashboard"
            val toolTitle = actionObj.optString("tool_title")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: topToolTitle
                ?: inferToolTitleFromIntentOrLink(intent, deepLink)
            val actionLabel = actionObj.optString("action_label")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: "Mở $toolTitle"

            ChatbotActionDto(
                type = actionObj.optString("type", "NAVIGATE"),
                actionCode = actionObj.optString("action_code").takeIf { it.isNotBlank() && it != "null" },
                actionLabel = actionLabel,
                toolTitle = toolTitle,
                destination = actionObj.optString("destination").takeIf { it.isNotBlank() && it != "null" },
                deepLink = deepLink,
                params = actionObj.optJSONObject("params") ?: JSONObject()
            )
        } else {
            null
        }

        val suggestions = mutableListOf<String>()
        val sugArr: JSONArray? = json.optJSONArray("suggestions")
        if (sugArr != null) {
            for (i in 0 until sugArr.length()) {
                val s = sugArr.optString(i)
                if (s.isNotBlank()) suggestions.add(s)
            }
        }

        return ChatbotReplyDto(
            sessionId = json.optString("session_id").takeIf { it.isNotBlank() && it != "null" },
            messageId = json.optString("message_id").takeIf { it.isNotBlank() && it != "null" },
            messageText = replyText,
            intent = intent,
            toolTitle = action?.toolTitle ?: topToolTitle,
            action = action,
            suggestions = suggestions,
            aiProvider = json.optString("ai_provider").takeIf { it.isNotBlank() && it != "null" },
            aiModel = json.optString("ai_model").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun inferToolTitleFromIntentOrLink(intent: String, deepLink: String): String {
        val lowerLink = deepLink.lowercase()
        return when {
            lowerLink.contains("navigation") || intent in listOf("Find_Nearest_ATM", "Find_Nearest_Branch", "FIND_BRANCH") -> "Chi nhánh & ATM"
            lowerLink.contains("bill") || intent == "Pay_Utility_Bill" -> "Nhắc hóa đơn"
            lowerLink.contains("topup") || intent == "TOPUP_PHONE" -> "Nạp điện thoại"
            lowerLink.contains("flight") || intent == "Book_Flight" -> "Đặt vé máy bay"
            lowerLink.contains("movie") -> "Vé xem phim CGV"
            lowerLink.contains("hotel") -> "Đặt khách sạn"
            lowerLink.contains("ecommerce") -> "Mua sắm TMĐT"
            lowerLink.contains("fx") -> "Đổi ngoại tệ"
            lowerLink.contains("note") -> "Ghi chú nhanh"
            else -> "Tính khoản vay"
        }
    }
}
