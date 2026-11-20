const express = require('express');
const router = express.Router();
const { sql, getPool } = require('../db');

// Bộ nhớ đệm (Cache) lộ trình di chuyển để chống spam API khi người dùng bấm đổi phương tiện liên tục (UC-N03 2a)
const routeCache = new Map();

// Tọa độ mặc định trung tâm TP.HCM (Fallback khi người dùng từ chối cấp quyền GPS - UC-N01 3a)
const DEFAULT_CITY_CENTER = {
    HCM: { latitude: 10.776889, longitude: 106.706139, name: 'Trung tâm Quận 1, TP.HCM' },
    HAN: { latitude: 21.028511, longitude: 105.854444, name: 'Trung tâm Hoàn Kiếm, Hà Nội' }
};

/**
 * Thuật toán mã hóa Google Encoded Polyline chuẩn (dùng cho PolyUtil.decode() trên Android)
 */
function encodePolyline(points) {
    let lastLat = 0;
    let lastLng = 0;
    let result = '';

    const encodeValue = (val) => {
        let v = val < 0 ? ~(val << 1) : (val << 1);
        let chunk = '';
        while (v >= 0x20) {
            chunk += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
            v >>= 5;
        }
        chunk += String.fromCharCode(v + 63);
        return chunk;
    };

    for (const pt of points) {
        const lat = Math.round(pt.latitude * 1e5);
        const lng = Math.round(pt.longitude * 1e5);
        result += encodeValue(lat - lastLat);
        result += encodeValue(lng - lastLng);
        lastLat = lat;
        lastLng = lng;
    }
    return result;
}

/**
 * Tính khoảng cách Haversine (km) giữa 2 tọa độ
 */
function calculateHaversineKm(lat1, lng1, lat2, lng2) {
    const toRad = (deg) => (deg * Math.PI) / 180;
    const R = 6371;
    const dLat = toRad(lat2 - lat1);
    const dLng = toRad(lng2 - lng1);
    const a =
        Math.sin(dLat / 2) ** 2 +
        Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * Sinh lộ trình chi tiết (Steps, Polyline, Bounds, ETA, Distance) theo phương tiện di chuyển (UC-N03)
 */
function buildRouteProposal(origin, destination, mode = 'walking', branchInfo = null) {
    const straightKm = calculateHaversineKm(origin.latitude, origin.longitude, destination.latitude, destination.longitude);

    // Hệ số đường thực tế & vận tốc trung bình theo từng phương tiện tại đô thị Việt Nam
    const modeConfig = {
        walking: {
            detour_factor: 1.18,
            speed_kmh: 4.8,
            polyline_color: '#22C55E', // Xanh lá cho đi bộ
            polyline_style: 'DOTTED',
            label_vi: 'Đi bộ'
        },
        two_wheeler: {
            detour_factor: 1.25,
            speed_kmh: 28.0,
            polyline_color: '#3B82F6', // Xanh dương cho xe máy
            polyline_style: 'SOLID',
            label_vi: 'Xe máy'
        },
        driving: {
            detour_factor: 1.38, // Ô tô có thể dài hơn do đường 1 chiều (Navigation.txt Mục 3.3)
            speed_kmh: 22.0,
            polyline_color: '#2563EB', // Xanh dương đậm cho ô tô
            polyline_style: 'SOLID',
            label_vi: 'Ô tô'
        },
        transit: {
            detour_factor: 1.45,
            speed_kmh: 18.0,
            polyline_color: '#F59E0B', // Cam cho xe buýt công cộng
            polyline_style: 'SOLID',
            label_vi: 'Xe buýt (Transit)'
        }
    };

    const selectedMode = modeConfig[mode] ? mode : 'walking';
    const cfg = modeConfig[selectedMode];

    // Ngoại lệ 4a (UC-N03): Với chế độ transit (xe buýt), nếu quá gần (< 0.3km) hoặc quá xa (> 35km) hoặc chi nhánh không có tuyến buýt -> trả ZERO_RESULTS
    if (selectedMode === 'transit' && (straightKm < 0.25 || straightKm > 35)) {
        return {
            status: 'ZERO_RESULTS',
            mode: selectedMode,
            message: 'Không tìm thấy tuyến xe công cộng phù hợp cho lộ trình này. Gợi ý chuyển sang Đi bộ hoặc Xe máy.',
            suggested_fallback_modes: ['walking', 'two_wheeler'],
            routes: []
        };
    }

    const routeDistanceKm = Math.max(0.05, Number((straightKm * cfg.detour_factor).toFixed(2)));
    const distanceMeters = Math.round(routeDistanceKm * 1000);
    const durationMinutes = Math.max(1, Math.ceil((routeDistanceKm / cfg.speed_kmh) * 60));
    const durationSeconds = durationMinutes * 60;

    // Tạo các điểm trung gian mô phỏng đường gấp khúc đô thị thực tế
    const mid1 = {
        latitude: Number((origin.latitude + (destination.latitude - origin.latitude) * 0.35 + 0.0004).toFixed(6)),
        longitude: Number((origin.longitude + (destination.longitude - origin.longitude) * 0.25 - 0.0003).toFixed(6))
    };
    const mid2 = {
        latitude: Number((origin.latitude + (destination.latitude - origin.latitude) * 0.72 - 0.0002).toFixed(6)),
        longitude: Number((origin.longitude + (destination.longitude - origin.longitude) * 0.68 + 0.0004).toFixed(6))
    };

    const decodedPath = [
        { latitude: Number(origin.latitude.toFixed(6)), longitude: Number(origin.longitude.toFixed(6)) },
        mid1,
        mid2,
        { latitude: Number(destination.latitude.toFixed(6)), longitude: Number(destination.longitude.toFixed(6)) }
    ];

    const encodedPoints = encodePolyline(decodedPath);

    // Tính LatLngBounds để Android tự động zoom vừa vặn khung hình (Navigation_Analysis.docx Mục 3.3)
    const lats = decodedPath.map(p => p.latitude);
    const lngs = decodedPath.map(p => p.longitude);
    const bounds = {
        northeast: { latitude: Math.max(...lats), longitude: Math.max(...lngs) },
        southwest: { latitude: Math.min(...lats), longitude: Math.min(...lngs) }
    };

    const destName = branchInfo ? branchInfo.name : 'Chi nhánh ngân hàng đích';
    const steps = [
        {
            step_number: 1,
            instruction: `Xuất phát từ vị trí hiện tại của bạn (${cfg.label_vi})`,
            distance_text: `${Math.round(distanceMeters * 0.35)} m`,
            duration_text: `${Math.max(1, Math.round(durationMinutes * 0.35))} phút`,
            start_location: decodedPath[0],
            end_location: decodedPath[1]
        },
        {
            step_number: 2,
            instruction: selectedMode === 'walking'
                ? 'Đi thẳng dọc theo vỉa hè, băng qua ngã tư tại lối sang đường dành cho người đi bộ'
                : 'Tiếp tục di chuyển theo trục đường chính hướng về phía chi nhánh',
            distance_text: `${Math.round(distanceMeters * 0.4)} m`,
            duration_text: `${Math.max(1, Math.round(durationMinutes * 0.4))} phút`,
            start_location: decodedPath[1],
            end_location: decodedPath[2]
        },
        {
            step_number: 3,
            instruction: `Rẽ phải vào điểm đến: ${destName}`,
            distance_text: `${Math.round(distanceMeters * 0.25)} m`,
            duration_text: `${Math.max(1, Math.round(durationMinutes * 0.25))} phút`,
            start_location: decodedPath[2],
            end_location: decodedPath[3]
        }
    ];

    const distanceText = distanceMeters < 1000 ? `${distanceMeters} m` : `${routeDistanceKm} km`;
    const durationText = `${durationMinutes} phút`;

    return {
        status: 'OK',
        mode: selectedMode,
        mode_label: cfg.label_vi,
        origin,
        destination,
        branch: branchInfo,
        summary: {
            distance_meters: distanceMeters,
            distance_km: routeDistanceKm,
            distance_text: distanceText,
            duration_seconds: durationSeconds,
            duration_minutes: durationMinutes,
            duration_text: durationText,
            polyline_color: cfg.polyline_color,
            polyline_style: cfg.polyline_style
        },
        bounds,
        overview_polyline: {
            points: encodedPoints
        },
        decoded_path: decodedPath,
        steps,
        google_maps_intent_uri: `google.navigation:q=${destination.latitude},${destination.longitude}&mode=${selectedMode === 'walking' ? 'w' : (selectedMode === 'two_wheeler' ? 'l' : 'd')}`
    };
}

/**
 * GET /v1/navigation/branches
 * Lấy danh sách tất cả chi nhánh/ATM ngân hàng (hỗ trợ tìm kiếm theo q, lọc theo type = BRANCH | ATM | BOTH, status)
 */
router.get('/branches', async (req, res) => {
    const { q, type, status } = req.query;

    try {
        const pool = await getPool();
        const request = pool.request();

        let query = `SELECT branch_id, name, branch_type, address, latitude, longitude, opening_hours, status FROM dbo.bank_branches WHERE 1=1`;
        if (q) {
            query += ` AND (name LIKE @q OR address LIKE @q)`;
            request.input('q', sql.NVarChar(150), `%${q}%`);
        }
        if (type) {
            const upperType = type.toUpperCase();
            if (upperType === 'ATM') {
                query += ` AND branch_type IN ('ATM', 'BOTH')`;
            } else if (upperType === 'BRANCH') {
                query += ` AND branch_type IN ('BRANCH', 'BOTH')`;
            } else {
                query += ` AND branch_type = @type`;
                request.input('type', sql.NVarChar(20), upperType);
            }
        }
        if (status) {
            query += ` AND status = @status`;
            request.input('status', sql.NVarChar(20), status.toUpperCase());
        }

        const result = await request.query(query);
        return res.status(200).json({
            items: result.recordset,
            total: result.recordset.length
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi truy vấn danh sách chi nhánh' });
    }
});

/**
 * GET /v1/navigation/nearby
 * Tìm các chi nhánh ngân hàng / ATM gần vị trí User nhất (UC-N02)
 * Tự động mở rộng bán kính nếu không có chi nhánh trong bán kính gần (Ngoại lệ 2a)
 * Đề xuất lộ trình đi bộ mặc định đến chi nhánh gần nhất (Top 1)
 */
router.get('/nearby', async (req, res) => {
    let userLat = parseFloat(req.query.lat);
    let userLng = parseFloat(req.query.lng);
    const fallbackCity = (req.query.fallback_city || '').toUpperCase();
    const radiusKm = parseFloat(req.query.radius_km) || 10;
    const limit = Math.min(20, Math.max(1, parseInt(req.query.limit, 10) || 5));
    const typeFilter = (req.query.type || '').toUpperCase(); // BRANCH | ATM | BOTH

    let isFallbackLocation = false;
    if ((isNaN(userLat) || isNaN(userLng)) && DEFAULT_CITY_CENTER[fallbackCity]) {
        userLat = DEFAULT_CITY_CENTER[fallbackCity].latitude;
        userLng = DEFAULT_CITY_CENTER[fallbackCity].longitude;
        isFallbackLocation = true;
    }

    if (isNaN(userLat) || isNaN(userLng) || userLat < -90 || userLat > 90 || userLng < -180 || userLng > 180) {
        return res.status(400).json({ code: 'INVALID_COORDINATES', message: 'Tọa độ lat/lng không hợp lệ' });
    }

    try {
        const pool = await getPool();

        const runNearbyQuery = async (searchRadiusKm) => {
            const reqDb = pool.request()
                .input('lat', sql.Decimal(9, 6), userLat)
                .input('lng', sql.Decimal(9, 6), userLng)
                .input('radius', sql.Float, searchRadiusKm)
                .input('limit', sql.Int, limit);

            let typeWhere = '';
            if (typeFilter === 'ATM') {
                typeWhere = `AND branch_type IN ('ATM', 'BOTH')`;
            } else if (typeFilter === 'BRANCH') {
                typeWhere = `AND branch_type IN ('BRANCH', 'BOTH')`;
            }

            const sqlRes = await reqDb.query(`
                SELECT TOP (@limit) *
                FROM (
                    SELECT 
                        branch_id,
                        name,
                        branch_type,
                        address,
                        latitude,
                        longitude,
                        opening_hours,
                        status,
                        (
                            6371 * ACOS(
                                CASE 
                                    WHEN (COS(RADIANS(@lat)) * COS(RADIANS(latitude)) * COS(RADIANS(longitude) - RADIANS(@lng)) + SIN(RADIANS(@lat)) * SIN(RADIANS(latitude))) > 1.0 THEN 1.0
                                    WHEN (COS(RADIANS(@lat)) * COS(RADIANS(latitude)) * COS(RADIANS(longitude) - RADIANS(@lng)) + SIN(RADIANS(@lat)) * SIN(RADIANS(latitude))) < -1.0 THEN -1.0
                                    ELSE (COS(RADIANS(@lat)) * COS(RADIANS(latitude)) * COS(RADIANS(longitude) - RADIANS(@lng)) + SIN(RADIANS(@lat)) * SIN(RADIANS(latitude)))
                                END
                            )
                        ) AS distance_km
                    FROM dbo.bank_branches
                    WHERE latitude IS NOT NULL AND longitude IS NOT NULL ${typeWhere}
                ) AS BranchesWithDistance
                WHERE distance_km <= @radius
                ORDER BY distance_km ASC;
            `);
            return sqlRes.recordset;
        };

        let records = await runNearbyQuery(radiusKm);
        let radiusExpanded = false;
        let effectiveRadiusKm = radiusKm;

        // Ngoại lệ 2a (UC-N02): Không có chi nhánh trong bán kính tìm kiếm gần -> Tự động mở rộng bán kính
        if (records.length === 0) {
            effectiveRadiusKm = 100;
            records = await runNearbyQuery(effectiveRadiusKm);
            radiusExpanded = true;
        }

        const branches = records.map((b, index) => {
            const distKm = Number(b.distance_km.toFixed(2));
            const walkingMinutes = Math.max(1, Math.ceil(((distKm * 1.18) / 4.8) * 60));
            return {
                ...b,
                distance_km: distKm,
                distance_meters: Math.round(distKm * 1000),
                is_nearest: index === 0,
                recommended_mode: distKm <= 1.0 ? 'walking' : 'two_wheeler',
                estimated_walking_minutes: walkingMinutes
            };
        });

        // Tự động đính kèm đề xuất đường đi bộ đến chi nhánh gần nhất (Top 1 - theo Navigation_Analysis.docx Mục 3.1)
        const nearestBranch = branches[0] || null;
        const walkingRouteProposal = nearestBranch
            ? buildRouteProposal(
                { latitude: userLat, longitude: userLng },
                { latitude: Number(nearestBranch.latitude), longitude: Number(nearestBranch.longitude) },
                'walking',
                nearestBranch
            )
            : null;

        return res.status(200).json({
            user_location: {
                latitude: userLat,
                longitude: userLng,
                is_fallback: isFallbackLocation
            },
            radius_km: effectiveRadiusKm,
            radius_expanded: radiusExpanded,
            message: radiusExpanded
                ? `Không có chi nhánh trong bán kính ${radiusKm}km. Đã tự động mở rộng bán kính tìm kiếm.`
                : undefined,
            nearest_branch: nearestBranch,
            walking_route_to_nearest: walkingRouteProposal,
            items: branches
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi truy vấn khoảng cách chi nhánh' });
    }
});

/**
 * GET /v1/navigation/directions
 * Đề xuất & vẽ đường đi đến chi nhánh theo phương tiện (walking | two_wheeler | driving | transit) (UC-N03)
 * Hỗ trợ truyền branch_id hoặc dest_lat/dest_lng (nếu không truyền đích sẽ tự động chỉ đường tới chi nhánh gần nhất)
 */
router.get('/directions', async (req, res) => {
    const originLat = parseFloat(req.query.origin_lat || req.query.lat);
    const originLng = parseFloat(req.query.origin_lng || req.query.lng);
    const branchId = req.query.branch_id;
    let destLat = parseFloat(req.query.dest_lat);
    let destLng = parseFloat(req.query.dest_lng);
    const mode = (req.query.mode || 'walking').toLowerCase();

    if (isNaN(originLat) || isNaN(originLng)) {
        return res.status(400).json({
            code: 'INVALID_ORIGIN',
            message: 'Bắt buộc truyền tọa độ điểm xuất phát (origin_lat, origin_lng)'
        });
    }

    const validModes = ['walking', 'two_wheeler', 'driving', 'transit'];
    if (!validModes.includes(mode)) {
        return res.status(400).json({
            code: 'INVALID_MODE',
            message: 'Phương tiện không hợp lệ. Chỉ hỗ trợ: walking, two_wheeler, driving, transit'
        });
    }

    try {
        const pool = await getPool();
        let targetBranch = null;

        if (branchId) {
            const brRes = await pool.request()
                .input('branch_id', sql.NVarChar(36), branchId)
                .query(`SELECT * FROM dbo.bank_branches WHERE branch_id = @branch_id`);
            if (brRes.recordset.length === 0) {
                return res.status(404).json({ code: 'BRANCH_NOT_FOUND', message: 'Không tìm thấy chi nhánh ngân hàng' });
            }
            targetBranch = brRes.recordset[0];
            destLat = Number(targetBranch.latitude);
            destLng = Number(targetBranch.longitude);
        } else if (isNaN(destLat) || isNaN(destLng)) {
            // Nếu không truyền điểm đích, tự động chọn chi nhánh gần nhất (UC-N03)
            const nearestRes = await pool.request()
                .input('lat', sql.Decimal(9, 6), originLat)
                .input('lng', sql.Decimal(9, 6), originLng)
                .query(`
                    SELECT TOP 1 *,
                        (
                            6371 * ACOS(
                                CASE 
                                    WHEN (COS(RADIANS(@lat)) * COS(RADIANS(latitude)) * COS(RADIANS(longitude) - RADIANS(@lng)) + SIN(RADIANS(@lat)) * SIN(RADIANS(latitude))) > 1.0 THEN 1.0
                                    WHEN (COS(RADIANS(@lat)) * COS(RADIANS(latitude)) * COS(RADIANS(longitude) - RADIANS(@lng)) + SIN(RADIANS(@lat)) * SIN(RADIANS(latitude))) < -1.0 THEN -1.0
                                    ELSE (COS(RADIANS(@lat)) * COS(RADIANS(latitude)) * COS(RADIANS(longitude) - RADIANS(@lng)) + SIN(RADIANS(@lat)) * SIN(RADIANS(latitude)))
                                END
                            )
                        ) AS distance_km
                    FROM dbo.bank_branches
                    WHERE latitude IS NOT NULL AND longitude IS NOT NULL
                    ORDER BY distance_km ASC
                `);
            if (nearestRes.recordset.length === 0) {
                return res.status(404).json({ code: 'NO_BRANCHES', message: 'Chưa có dữ liệu chi nhánh ngân hàng' });
            }
            targetBranch = nearestRes.recordset[0];
            destLat = Number(targetBranch.latitude);
            destLng = Number(targetBranch.longitude);
        }

        // Kiểm tra Cache lộ trình theo cặp (origin, dest, mode) để chống spam API (UC-N03 2a)
        const cacheKey = `${originLat.toFixed(4)}_${originLng.toFixed(4)}_${destLat.toFixed(4)}_${destLng.toFixed(4)}_${mode}`;
        if (routeCache.has(cacheKey)) {
            return res.status(200).json({
                ...routeCache.get(cacheKey),
                cached: true
            });
        }

        const routeData = buildRouteProposal(
            { latitude: originLat, longitude: originLng },
            { latitude: destLat, longitude: destLng },
            mode,
            targetBranch
        );

        routeCache.set(cacheKey, routeData);
        return res.status(200).json({
            ...routeData,
            cached: false
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi tính toán lộ trình chỉ đường' });
    }
});

module.exports = router;
module.exports.buildRouteProposal = buildRouteProposal;
module.exports.calculateHaversineKm = calculateHaversineKm;
