const express = require('express');
const router = express.Router();
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const { sql, getPool } = require('../db');
const { authenticateJWT, JWT_SECRET } = require('../middleware/auth');

/**
 * POST /v1/auth/login
 * Đăng nhập lấy JWT Token & ghi nhận vào bảng security_audit_logs (theo ERD_new.html)
 */
router.post('/auth/login', async (req, res) => {
    const { username = 'nguyenvana' } = req.body;
    try {
        const pool = await getPool();
        const userRes = await pool.request()
            .input('username', sql.NVarChar(50), username)
            .query(`SELECT * FROM dbo.users WHERE username = @username`);

        if (userRes.recordset.length === 0) {
            return res.status(401).json({ code: 'INVALID_CREDENTIALS', message: 'Tài khoản không tồn tại' });
        }

        const user = userRes.recordset[0];
        if (user.status === 'LOCKED' || user.status === 'SUSPENDED') {
            return res.status(403).json({ code: 'USER_LOCKED', message: 'Tài khoản người dùng đang bị khóa' });
        }

        const accRes = await pool.request()
            .input('user_id', sql.NVarChar(36), user.user_id)
            .query(`SELECT * FROM dbo.accounts WHERE user_id = @user_id`);

        // Ghi log bảo mật vào bảng security_audit_logs
        await pool.request()
            .input('log_id', sql.NVarChar(36), uuidv4())
            .input('actor_id', sql.NVarChar(36), user.user_id)
            .input('action', sql.NVarChar(100), 'USER_LOGIN')
            .input('entity_name', sql.NVarChar(100), 'users')
            .input('entity_id', sql.NVarChar(100), user.user_id)
            .input('status', sql.NVarChar(50), 'SUCCESS')
            .input('details', sql.NVarChar(sql.MAX), `Người dùng ${user.username} đăng nhập thành công`)
            .input('ip_address', sql.NVarChar(50), req.ip || '127.0.0.1')
            .query(`
                INSERT INTO dbo.security_audit_logs (log_id, actor_id, action, entity_name, entity_id, status, details, ip_address)
                VALUES (@log_id, @actor_id, @action, @entity_name, @entity_id, @status, @details, @ip_address)
            `);

        const token = jwt.sign(
            { user_id: user.user_id, username: user.username, role: user.role },
            JWT_SECRET,
            { expiresIn: '7d' }
        );

        return res.status(200).json({
            access_token: token,
            token_type: 'Bearer',
            expires_in: 604800,
            user: {
                user_id: user.user_id,
                username: user.username,
                full_name: user.full_name,
                email: user.email,
                phone_number: user.phone_number,
                role: user.role,
                status: user.status
            },
            accounts: accRes.recordset
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi đăng nhập' });
    }
});

/**
 * GET /v1/auth/me
 * Lấy thông tin người dùng hiện tại kèm danh sách tài khoản & trạng thái eKYC
 */
router.get('/auth/me', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const userRes = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`SELECT user_id, username, full_name, email, phone_number, role, status, created_at FROM dbo.users WHERE user_id = @user_id`);

        const accRes = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`SELECT * FROM dbo.accounts WHERE user_id = @user_id`);

        const ekycRes = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`SELECT TOP 1 * FROM dbo.ekyc_verifications WHERE user_id = @user_id ORDER BY created_at DESC`);

        return res.status(200).json({
            user: userRes.recordset[0] || null,
            accounts: accRes.recordset,
            ekyc: ekycRes.recordset[0] || null
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy thông tin cá nhân' });
    }
});

/**
 * GET /v1/accounts
 * Danh sách tài khoản ngân hàng (CHECKING, SAVING, MORTGAGE) kèm chi tiết saving_details & mortgage_details
 */
router.get('/accounts', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const result = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT 
                    a.*,
                    sd.rate_id AS saving_rate_id,
                    sd.initial_deposit,
                    sd.start_date AS saving_start_date,
                    sd.maturity_date AS saving_maturity_date,
                    sd.monthly_profit,
                    sd.auto_renew,
                    md.total_loan,
                    md.remaining_debt,
                    md.interest_rate AS mortgage_interest_rate,
                    md.payment_frequency,
                    md.payment_amount,
                    md.next_due_date
                FROM dbo.accounts a
                LEFT JOIN dbo.saving_details sd ON a.account_id = sd.account_id
                LEFT JOIN dbo.mortgage_details md ON a.account_id = md.account_id
                WHERE a.user_id = @user_id
                ORDER BY a.created_at ASC
            `);
        return res.status(200).json({
            items: result.recordset,
            total: result.recordset.length
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy danh sách tài khoản' });
    }
});

/**
 * GET /v1/transactions
 * Lịch sử giao dịch của người dùng theo bảng transactions trong ERD_new.html
 */
router.get('/transactions', authenticateJWT, async (req, res) => {
    const typeFilter = req.query.transaction_type || req.query.txn_type;
    const { status } = req.query;
    try {
        const pool = await getPool();
        const request = pool.request().input('user_id', sql.NVarChar(36), req.user.user_id);
        let where = 'WHERE t.user_id = @user_id';
        if (typeFilter) {
            where += ' AND t.transaction_type = @transaction_type';
            request.input('transaction_type', sql.NVarChar(30), typeFilter.toUpperCase());
        }
        if (status) {
            where += ' AND t.status = @status';
            request.input('status', sql.NVarChar(20), status.toUpperCase());
        }

        const result = await request.query(`
            SELECT t.*
            FROM dbo.transactions t
            ${where}
            ORDER BY t.created_at DESC
        `);

        return res.status(200).json({
            items: result.recordset,
            total: result.recordset.length
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy lịch sử giao dịch' });
    }
});

/**
 * GET /v1/saving-rates
 * Bảng lãi suất tiết kiệm theo kỳ hạn (saving_rates)
 */
router.get('/saving-rates', async (req, res) => {
    try {
        const pool = await getPool();
        const result = await pool.request().query(`
            SELECT rate_id, term_months, interest_rate, updated_by_officer_id, updated_at
            FROM dbo.saving_rates
            ORDER BY term_months ASC
        `);
        return res.status(200).json({
            items: result.recordset,
            total: result.recordset.length
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy bảng lãi suất tiết kiệm' });
    }
});

/**
 * GET /v1/ekyc
 * Lấy danh sách hồ sơ định danh điện tử (ekyc_verifications theo ERD_new.html)
 */
router.get('/ekyc', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const result = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`SELECT * FROM dbo.ekyc_verifications WHERE user_id = @user_id ORDER BY created_at DESC`);
        return res.status(200).json({
            items: result.recordset,
            total: result.recordset.length
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy thông tin eKYC' });
    }
});

/**
 * GET /v1/audit-logs
 * Tra cứu nhật ký bảo mật (security_audit_logs theo ERD_new.html)
 */
router.get('/audit-logs', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const result = await pool.request()
            .input('actor_id', sql.NVarChar(36), req.user.user_id)
            .query(`SELECT TOP 50 * FROM dbo.security_audit_logs WHERE actor_id = @actor_id ORDER BY timestamp DESC`);
        return res.status(200).json({
            items: result.recordset,
            total: result.recordset.length
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy nhật ký bảo mật' });
    }
});

module.exports = router;
