package com.example.bankingapp.common.utils

import java.math.BigDecimal
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatAndSecurityUtils {

    private val amountSymbols = DecimalFormatSymbols(Locale.US).apply {
        groupingSeparator = ','
        decimalSeparator = '.'
    }

    private val sensitiveKeyValueRegex = Regex(
        pattern = """(?i)\b(password|pwd|pass|otp|pin|secret|apikey|api_key|token|auth_code)\s*([:=])\s*([^\s,;]+)""",
    )

    private val labeledSixDigitOtpRegex = Regex(
        pattern = """(?i)\b(otp|mã\s*otp|pin)\b([^0-9\n]{0,12})(\d{4,8})\b"""
    )

    fun formatVnd(amount: BigDecimal): String {
        val formatter = DecimalFormat("#,##0", amountSymbols)
        return "${formatter.format(amount)} VNĐ"
    }

    fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Redacts sensitive credentials, OTP codes, passwords, and API keys before storing
     * details in AuditLog so secrets never leak to logs.
     */
    fun sanitizeAuditDetails(raw: String): String {
        if (raw.isBlank()) return ""
        var sanitized = sensitiveKeyValueRegex.replace(raw) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }
        sanitized = labeledSixDigitOtpRegex.replace(sanitized) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }
        return sanitized
    }
}

