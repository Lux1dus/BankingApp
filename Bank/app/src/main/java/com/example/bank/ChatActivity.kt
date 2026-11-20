package com.example.bank

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.example.bank.api.ChatbotActionDto
import com.example.bank.api.ChatbotApiClient
import com.example.bank.api.ChatbotReplyDto
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity() {

    private lateinit var chatScrollView: NestedScrollView
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var layoutTypingIndicator: LinearLayout
    private lateinit var etChatInput: EditText

    private var currentSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)

        val rootView = findViewById<View>(R.id.chatRoot)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        chatScrollView = findViewById(R.id.chatScrollView)
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer)
        layoutTypingIndicator = findViewById(R.id.layoutTypingIndicator)
        etChatInput = findViewById(R.id.etChatInput)

        findViewById<View>(R.id.btnChatBack).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.tvDeepLinkDemo).setOnClickListener {
            navigateWithDeepLink("Nhắc hóa đơn", "app://utilities/bill-payment?code=PE123456")
        }

        findViewById<View>(R.id.btnChatSend).setOnClickListener {
            sendCurrentInputMessage()
        }

        etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInputMessage()
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.chipSuggestBill).setOnClickListener {
            sendUserMessage("Thanh toán tiền điện tháng này giúp tôi", "SUGGESTION_CHIP")
        }
        findViewById<View>(R.id.chipSuggestTopup).setOnClickListener {
            sendUserMessage("Nạp 100k điện thoại cho số của tôi", "SUGGESTION_CHIP")
        }
        findViewById<View>(R.id.chipSuggestBranch).setOnClickListener {
            sendUserMessage("Chỉ mình cách tìm cây ATM gần đây đi bộ tới", "SUGGESTION_CHIP")
        }
        findViewById<View>(R.id.chipSuggestBalance).setOnClickListener {
            sendUserMessage("Xem số dư tài khoản", "SUGGESTION_CHIP")
        }

        findViewById<View>(R.id.btnChatPlus).setOnClickListener {
            Toast.makeText(this, "Chọn câu hỏi gợi ý hoặc nhập yêu cầu tài chính cho Gemini AI", Toast.LENGTH_SHORT).show()
        }

        layoutTypingIndicator.visibility = View.GONE
        scrollToBottom()
    }

    private fun sendCurrentInputMessage() {
        val text = etChatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        etChatInput.setText("")
        sendUserMessage(text, "TEXT")
    }

    private fun sendUserMessage(messageText: String, inputType: String) {
        appendOutgoingBubble(messageText)
        layoutTypingIndicator.visibility = View.VISIBLE
        scrollToBottom()

        ChatbotApiClient.sendMessage(
            message = messageText,
            inputType = inputType,
            sessionId = currentSessionId
        ) { result ->
            if (isFinishing || isDestroyed) return@sendMessage
            layoutTypingIndicator.visibility = View.GONE

            val reply = result.getOrElse {
                buildLocalFallbackReply(messageText)
            }
            if (!reply.sessionId.isNullOrBlank()) {
                currentSessionId = reply.sessionId
            }

            appendIncomingBotReply(
                replyText = reply.messageText,
                action = reply.action,
                fallbackTool = reply.toolTitle
            )
            scrollToBottom()
        }
    }

    private fun appendOutgoingBubble(text: String) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            setPadding(dp(64), 0, 0, 0)
        }

        val bubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_bubble_outgoing)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }

        wrapper.addView(bubble)
        insertBeforeTypingIndicator(wrapper)
    }

    private fun appendIncomingBotReply(
        replyText: String,
        action: ChatbotActionDto?,
        fallbackTool: String?
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            setPadding(0, 0, dp(48), 0)
        }

        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setBackgroundResource(R.drawable.bg_focus_bot_avatar)
            gravity = Gravity.CENTER
            text = "NX"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(null, Typeface.BOLD)
        }

        val bubblesCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        val textBubble = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_bubble_incoming)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            text = replyText
            setTextColor(Color.parseColor("#1C1E21"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        bubblesCol.addView(textBubble)

        if (action != null && action.deepLink.isNotBlank()) {
            val targetTool = action.toolTitle ?: fallbackTool ?: "Tính khoản vay"
            val ctaButton = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                }
                setBackgroundResource(R.drawable.bg_primary_button)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                text = "${action.actionLabel} →"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(null, Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    navigateWithDeepLink(targetTool, action.deepLink)
                }
            }
            bubblesCol.addView(ctaButton)
        }

        row.addView(avatar)
        row.addView(bubblesCol)
        insertBeforeTypingIndicator(row)
    }

    private fun insertBeforeTypingIndicator(view: View) {
        val typingIndex = chatMessagesContainer.indexOfChild(layoutTypingIndicator)
        if (typingIndex >= 0) {
            chatMessagesContainer.addView(view, typingIndex)
        } else {
            chatMessagesContainer.addView(view)
        }
    }

    private fun navigateWithDeepLink(toolTitle: String, deepLink: String) {
        Toast.makeText(this, "Đang mở: $toolTitle", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_TOOL, toolTitle)
            putExtra(EXTRA_DEEP_LINK, deepLink)
        }
        startActivity(intent)
        finish()
    }

    /**
     * Fallback cục bộ khi thiết bị chưa kết nối được tới BankServer.
     */
    private fun buildLocalFallbackReply(message: String): ChatbotReplyDto {
        val lower = message.lowercase()
        return when {
            lower.contains("atm") || lower.contains("chi nhánh") || lower.contains("gần") || lower.contains("chỉ đường") -> {
                val isAtm = lower.contains("atm") || lower.contains("rút tiền")
                val mode = when {
                    lower.contains("xe máy") || lower.contains("xe may") -> "two_wheeler"
                    lower.contains("ô tô") || lower.contains("o to") -> "driving"
                    lower.contains("xe buýt") || lower.contains("bus") -> "transit"
                    else -> "walking"
                }
                val targetType = if (isAtm) "ATM" else "BOTH"
                val modeVi = when (mode) {
                    "two_wheeler" -> "xe máy"
                    "driving" -> "ô tô"
                    "transit" -> "xe buýt"
                    else -> "đi bộ"
                }
                val deepLink = "app://navigation/branch-map?mode=$mode&type=$targetType&autoRoute=true"
                ChatbotReplyDto(
                    sessionId = currentSessionId,
                    messageId = null,
                    messageText = "Để tìm cây ATM/chi nhánh gần nhất và xem đường $modeVi, anh/chị chạm vào nút bên dưới để em mở ngay bản đồ chi nhánh ở chế độ $modeVi nhé!",
                    intent = if (isAtm) "Find_Nearest_ATM" else "Find_Nearest_Branch",
                    toolTitle = "Chi nhánh & ATM",
                    action = ChatbotActionDto(
                        type = "NAVIGATE",
                        actionCode = if (isAtm) "OPEN_ATM_MAP" else "OPEN_BRANCH_MAP",
                        actionLabel = "Mở Chi nhánh & ATM",
                        toolTitle = "Chi nhánh & ATM",
                        destination = "app://navigation/branch-map",
                        deepLink = deepLink
                    ),
                    aiProvider = "offline_fallback"
                )
            }
            lower.contains("điện") || lower.contains("nước") || lower.contains("hóa đơn") -> ChatbotReplyDto(
                sessionId = currentSessionId,
                messageId = null,
                messageText = "Em tìm thấy hóa đơn tiền điện tháng này của mã KH PE123456 (850.000 ₫). Anh/chị bấm nút bên dưới để tra cứu và thanh toán ngay nhé!",
                intent = "Pay_Utility_Bill",
                toolTitle = "Nhắc hóa đơn",
                action = ChatbotActionDto(
                    type = "NAVIGATE",
                    actionCode = "OPEN_BILL_PAYMENT",
                    actionLabel = "Mở Thanh toán hóa đơn",
                    toolTitle = "Nhắc hóa đơn",
                    destination = "app://utilities/bill-payment",
                    deepLink = "app://utilities/bill-payment?type=ELECTRICITY&code=PE123456"
                ),
                aiProvider = "offline_fallback"
            )
            lower.contains("nạp") || lower.contains("điện thoại") -> ChatbotReplyDto(
                sessionId = currentSessionId,
                messageId = null,
                messageText = "Lệnh nạp 100.000 ₫ vào số điện thoại 0987654321 (Viettel) đã sẵn sàng. Anh/chị bấm nút bên dưới để thực hiện nhé!",
                intent = "TOPUP_PHONE",
                toolTitle = "Nạp điện thoại",
                action = ChatbotActionDto(
                    type = "NAVIGATE",
                    actionCode = "OPEN_TOPUP_SCREEN",
                    actionLabel = "Mở Nạp điện thoại",
                    toolTitle = "Nạp điện thoại",
                    destination = "app://utilities/mobile-topup",
                    deepLink = "app://utilities/mobile-topup?phone=0987654321&amount=100000"
                ),
                aiProvider = "offline_fallback"
            )
            lower.contains("máy bay") || lower.contains("chuyến bay") -> ChatbotReplyDto(
                sessionId = currentSessionId,
                messageId = null,
                messageText = "Em có thể giúp anh/chị tìm chuyến bay Vietnam Airlines / Vietjet Air ngay trên ứng dụng. Bấm nút bên dưới để mở mục Đặt vé máy bay nhé!",
                intent = "Book_Flight",
                toolTitle = "Đặt vé máy bay",
                action = ChatbotActionDto(
                    type = "NAVIGATE",
                    actionCode = "OPEN_FLIGHT_BOOKING",
                    actionLabel = "Mở Đặt vé máy bay",
                    toolTitle = "Đặt vé máy bay",
                    destination = "app://booking/flights",
                    deepLink = "app://booking/flights?origin=SGN&dest=HAN"
                ),
                aiProvider = "offline_fallback"
            )
            lower.contains("số dư") || lower.contains("tài khoản") -> ChatbotReplyDto(
                sessionId = currentSessionId,
                messageId = null,
                messageText = "Tài khoản thanh toán (190088889999) của anh/chị hiện có số dư khả dụng là 50.000.000 ₫.",
                intent = "CHECK_BALANCE",
                toolTitle = null,
                action = null,
                aiProvider = "offline_fallback"
            )
            else -> ChatbotReplyDto(
                sessionId = currentSessionId,
                messageId = null,
                messageText = "Em đã ghi nhận yêu cầu \"$message\". Anh/chị có thể mở Trung tâm công cụ để thực hiện giao dịch ngay ạ!",
                intent = "GENERAL_HELP",
                toolTitle = "Tính khoản vay",
                action = ChatbotActionDto(
                    type = "NAVIGATE",
                    actionCode = "OPEN_UTILITIES_DASHBOARD",
                    actionLabel = "Mở Trung tâm tiện ích",
                    toolTitle = "Tính khoản vay",
                    destination = "app://utilities/dashboard",
                    deepLink = "app://utilities/dashboard"
                ),
                aiProvider = "offline_fallback"
            )
        }
    }

    private fun scrollToBottom() {
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    companion object {
        const val EXTRA_OPEN_TOOL = "extra_open_tool"
        const val EXTRA_DEEP_LINK = "extra_deep_link"
    }
}
