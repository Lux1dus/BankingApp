package com.example.bank.api

import org.json.JSONObject

/**
 * Hành động điều hướng (CTA Button + Deep Link) trả về từ AI Chatbot (`BankServer` + Gemini API).
 */
data class ChatbotActionDto(
    val type: String,               // NAVIGATE | NONE
    val actionCode: String?,        // OPEN_ATM_MAP, OPEN_BRANCH_MAP, OPEN_BILL_PAYMENT...
    val actionLabel: String,        // VD: "Mở Chi nhánh & ATM"
    val toolTitle: String?,         // VD: "Chi nhánh & ATM"
    val destination: String?,       // VD: "app://navigation/branch-map"
    val deepLink: String,           // VD: "app://navigation/branch-map?mode=walking&type=ATM&autoRoute=true"
    val params: JSONObject = JSONObject()
)

/**
 * Phản hồi hoàn chỉnh của AI Chatbot từ `POST /v1/chat` (hoặc `POST /v1/chatbot/message`).
 */
data class ChatbotReplyDto(
    val sessionId: String?,
    val messageId: String?,
    val messageText: String,
    val intent: String,
    val toolTitle: String?,
    val action: ChatbotActionDto?,
    val suggestions: List<String> = emptyList(),
    val aiProvider: String? = null,
    val aiModel: String? = null
)
