const jwt = require('jsonwebtoken');
const JWT_SECRET = process.env.JWT_SECRET || 'super_secret_bank_key_2026';
const DEFAULT_DEMO_USER = {
    user_id: 'u1111111-1111-1111-1111-111111111111',
    username: 'nguyenvana',
    role: 'CUSTOMER'
};

const authenticateJWT = (req, res, next) => {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ code: 'UNAUTHORIZED', message: 'Thiếu hoặc sai token' });
    }

    const token = authHeader.split(' ')[1];
    try {
        const decoded = jwt.verify(token, JWT_SECRET);
        req.user = { user_id: decoded.user_id, username: decoded.username, role: decoded.role };
        next();
    } catch (err) {
        return res.status(401).json({ code: 'UNAUTHORIZED', message: 'Token không hợp lệ hoặc đã hết hạn' });
    }
};

/**
 * Middleware xác thực linh hoạt cho các API truy vấn (Read/Discovery):
 * - Nếu client gửi Bearer token thì giải mã và gán req.user theo token.
 * - Nếu không gửi header Authorization thì fallback về tài khoản mẫu Nguyễn Văn A để App dễ dàng truy vấn.
 */
const optionalAuthenticateJWT = (req, res, next) => {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        req.user = { ...DEFAULT_DEMO_USER };
        return next();
    }

    const token = authHeader.split(' ')[1];
    try {
        const decoded = jwt.verify(token, JWT_SECRET);
        req.user = { user_id: decoded.user_id, username: decoded.username, role: decoded.role };
        return next();
    } catch (err) {
        return res.status(401).json({ code: 'UNAUTHORIZED', message: 'Token không hợp lệ hoặc đã hết hạn' });
    }
};

module.exports = { authenticateJWT, optionalAuthenticateJWT, JWT_SECRET, DEFAULT_DEMO_USER };