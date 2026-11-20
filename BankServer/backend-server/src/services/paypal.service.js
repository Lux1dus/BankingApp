const path = require('path');
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') });

const PAYPAL_BASE_URL = process.env.PAYPAL_BASE_URL || 'https://api-m.sandbox.paypal.com';
const PAYPAL_CLIENT_ID = process.env.PAYPAL_CLIENT_ID;
const PAYPAL_CLIENT_SECRET = process.env.PAYPAL_CLIENT_SECRET;

// Tỷ giá quy đổi VND -> USD cho PayPal (do PayPal REST API không hỗ trợ trực tiếp mã tiền tệ VND)
const VND_TO_USD_RATE = Number(process.env.PAYPAL_VND_TO_USD_RATE) || 25000;
const REQUEST_TIMEOUT_MS = Number(process.env.PAYPAL_TIMEOUT_MS) || 15000;

let cachedToken = null;
let tokenExpiresAt = 0;

/**
 * Helper thực hiện fetch có timeout để tránh treo kết nối
 */
async function fetchWithTimeout(url, options = {}, timeoutMs = REQUEST_TIMEOUT_MS) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        return await fetch(url, {
            ...options,
            signal: controller.signal
        });
    } catch (err) {
        if (err && err.name === 'AbortError') {
            throw new Error(`PayPal API timeout sau ${timeoutMs}ms`);
        }
        throw err;
    } finally {
        clearTimeout(timer);
    }
}

/**
 * Quy đổi số tiền sang USD (tối thiểu 0.01 USD) theo chuẩn 2 chữ số thập phân của PayPal
 */
function convertToUsdValue(amount, currency = 'VND') {
    const numericAmount = Number(amount);
    if (!Number.isFinite(numericAmount) || numericAmount <= 0) {
        throw new Error('Số tiền giao dịch PayPal không hợp lệ');
    }
    if (String(currency).toUpperCase() === 'USD') {
        return Math.max(0.01, numericAmount).toFixed(2);
    }
    const usd = numericAmount / VND_TO_USD_RATE;
    return Math.max(0.01, usd).toFixed(2);
}

/**
 * Lấy OAuth2 Access Token từ PayPal Sandbox (có cache trong bộ nhớ)
 */
async function getAccessToken() {
    if (!PAYPAL_CLIENT_ID || !PAYPAL_CLIENT_SECRET) {
        throw new Error('Thiếu cấu hình PAYPAL_CLIENT_ID hoặc PAYPAL_CLIENT_SECRET trong file .env');
    }

    if (cachedToken && Date.now() < tokenExpiresAt) {
        return cachedToken;
    }

    const credentials = Buffer.from(`${PAYPAL_CLIENT_ID}:${PAYPAL_CLIENT_SECRET}`).toString('base64');
    const response = await fetchWithTimeout(`${PAYPAL_BASE_URL}/v1/oauth2/token`, {
        method: 'POST',
        headers: {
            Authorization: `Basic ${credentials}`,
            'Content-Type': 'application/x-www-form-urlencoded',
            Accept: 'application/json'
        },
        body: 'grant_type=client_credentials'
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok || !data.access_token) {
        const errMsg = data.error_description || data.message || `HTTP ${response.status}`;
        throw new Error(`PayPal OAuth2 lỗi xác thực: ${errMsg}`);
    }

    cachedToken = data.access_token;
    // Trừ hao 60 giây trước khi token hết hạn
    const expiresInSec = Number(data.expires_in) || 3600;
    tokenExpiresAt = Date.now() + Math.max(60, expiresInSec - 60) * 1000;

    return cachedToken;
}

/**
 * Tạo PayPal Order (POST /v2/checkout/orders) cho giao dịch thanh toán hóa đơn / nạp tiền / đặt dịch vụ
 */
async function processPayment(payload = {}) {
    const {
        partnerRefId,
        transaction_id,
        order_id,
        service_type = 'UTILITY',
        provider_code = 'PAYPAL',
        provider_customer_id = '',
        amount,
        currency = 'VND',
        description
    } = payload;

    const refId = partnerRefId || transaction_id || `TXN-${Date.now()}`;
    const usdValue = convertToUsdValue(amount, currency);
    const accessToken = await getAccessToken();

    const orderPayload = {
        intent: 'CAPTURE',
        purchase_units: [
            {
                reference_id: String(refId).slice(0, 127),
                custom_id: String(order_id || refId).slice(0, 127),
                description: String(
                    description || `Thanh toan ${service_type} (${provider_code} - ${provider_customer_id || refId})`
                ).slice(0, 127),
                amount: {
                    currency_code: 'USD',
                    value: usdValue
                }
            }
        ],
        application_context: {
            brand_name: 'BankServer Utilities',
            user_action: 'PAY_NOW'
        }
    };

    const response = await fetchWithTimeout(`${PAYPAL_BASE_URL}/v2/checkout/orders`, {
        method: 'POST',
        headers: {
            Authorization: `Bearer ${accessToken}`,
            'Content-Type': 'application/json',
            'PayPal-Request-Id': String(refId)
        },
        body: JSON.stringify(orderPayload)
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok || !data.id) {
        const detailMsg =
            (Array.isArray(data.details) && data.details[0] && data.details[0].description) ||
            data.message ||
            `HTTP ${response.status}`;
        throw new Error(`PayPal Create Order thất bại: ${detailMsg}`);
    }

    const approveLink = Array.isArray(data.links)
        ? data.links.find(l => l.rel === 'approve')
        : null;

    return {
        success: true,
        errorCode: 0,
        provider: 'PAYPAL',
        partnerRefId: refId,
        paypal_order_id: data.id,
        paypal_status: data.status,
        amount_vnd: Number(amount),
        amount_usd: Number(usdValue),
        currency: 'USD',
        approve_url: approveLink ? approveLink.href : null,
        raw: data
    };
}

/**
 * Capture một PayPal Order đã được khách hàng approve (POST /v2/checkout/orders/:orderId/capture)
 */
async function captureOrder(paypalOrderId) {
    if (!paypalOrderId) {
        throw new Error('Thiếu paypalOrderId để capture');
    }
    const accessToken = await getAccessToken();
    const response = await fetchWithTimeout(`${PAYPAL_BASE_URL}/v2/checkout/orders/${encodeURIComponent(paypalOrderId)}/capture`, {
        method: 'POST',
        headers: {
            Authorization: `Bearer ${accessToken}`,
            'Content-Type': 'application/json'
        }
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
        const detailMsg =
            (Array.isArray(data.details) && data.details[0] && data.details[0].description) ||
            data.message ||
            `HTTP ${response.status}`;
        throw new Error(`PayPal Capture Order thất bại: ${detailMsg}`);
    }

    return {
        success: true,
        errorCode: 0,
        provider: 'PAYPAL',
        paypal_order_id: data.id,
        paypal_status: data.status,
        raw: data
    };
}

/**
 * Các alias tương thích với từng loại dịch vụ tiện ích (nạp điện thoại, thanh toán hóa đơn, đặt vé)
 */
async function topup(payload) {
    return processPayment({ ...payload, service_type: payload.service_type || 'PHONE_TOPUP' });
}

async function payBill(payload) {
    return processPayment({ ...payload, service_type: payload.service_type || 'BILL_PAYMENT' });
}

module.exports = {
    getAccessToken,
    processPayment,
    createOrder: processPayment,
    captureOrder,
    topup,
    topupCharging: topup,
    payBill
};
