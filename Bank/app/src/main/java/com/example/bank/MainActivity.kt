package com.example.bank

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bank.api.BankBranchDto
import com.example.bank.api.CatalogItemDto
import com.example.bank.api.NavigationApiClient
import com.example.bank.api.RouteProposalDto
import com.example.bank.api.TransportMode
import com.example.bank.api.UtilitiesApiClient
import com.example.bank.api.UtilityBillDto
import com.example.bank.api.UtilityOrderDto
import com.example.bank.api.UtilityServiceDto
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {

    data class UtilityItem(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val iconBgRes: Int,
        val iconTint: Int,
        val serviceType: String? = null,
        val providerCode: String? = null
    )

    private var balanceVisible = true
    private var currentBalanceText = "186.240.500 ₫"
    private var accordionOpen = false
    private var speedDialOpen = false
    private var sheetOpen = false
    private var sheetFull = false
    private var activeTool = TOOL_LOAN
    private var years = 10

    private val utilityItems = mutableListOf<UtilityItem>()
    private val upcomingQueue = ArrayDeque<UtilityItem>()

    // Dữ liệu Utilities truy vấn từ Database thông qua BankServer API
    private val dbServices = mutableListOf<UtilityServiceDto>()
    private val dbBills = mutableListOf<UtilityBillDto>()
    private val dbUnpaidBills = mutableListOf<UtilityBillDto>()
    private val dbOrders = mutableListOf<UtilityOrderDto>()

    private lateinit var tvBalanceAmount: TextView
    private lateinit var utilityGridContainer: LinearLayout
    private lateinit var layoutExpandBody: LinearLayout
    private lateinit var ivExpandChevron: ImageView

    private lateinit var layoutSpeedMenu: LinearLayout
    private lateinit var btnFab: FrameLayout
    private lateinit var ivFabIcon: ImageView

    private lateinit var viewOverlay: View
    private lateinit var layoutBottomSheet: LinearLayout
    private lateinit var tvDragHint: TextView
    private lateinit var tvSheetTitle: TextView
    private lateinit var sheetToolTabsContainer: LinearLayout

    private lateinit var layoutLoanTool: LinearLayout
    private lateinit var layoutFlightTool: LinearLayout
    private lateinit var layoutNavigationTool: LinearLayout
    private lateinit var layoutToolReady: LinearLayout
    private lateinit var ivToolReadyIcon: ImageView
    private lateinit var tvToolReadyTitle: TextView

    private lateinit var etLoanAmount: EditText
    private lateinit var tvLoanYears: TextView
    private lateinit var seekBarYears: SeekBar
    private lateinit var tvLoanEstimate: TextView

    // Giao diện Bản đồ Chi nhánh & ATM (osmdroid + OSRM Route Service)
    private lateinit var osmMapView: MapView
    private lateinit var pbNavRouteLoading: ProgressBar
    private lateinit var tvNavLocationStatus: TextView
    private lateinit var btnNavMyLocation: TextView
    private lateinit var btnNavFilterAll: TextView
    private lateinit var btnNavFilterBranch: TextView
    private lateinit var btnNavFilterAtm: TextView
    private lateinit var btnNavModeWalking: TextView
    private lateinit var btnNavModeTwoWheeler: TextView
    private lateinit var btnNavModeDriving: TextView
    private lateinit var btnNavModeTransit: TextView
    private lateinit var tvNavSelectedBranchName: TextView
    private lateinit var tvNavSelectedBranchAddress: TextView
    private lateinit var tvNavRouteMetrics: TextView
    private lateinit var tvNavRouteSteps: TextView
    private lateinit var btnNavOpenGoogleMaps: TextView
    private lateinit var layoutNavBranchesList: LinearLayout

    private val nearbyBranches = mutableListOf<BankBranchDto>()
    private var selectedBranchForMap: BankBranchDto? = null
    private var currentNavRoute: RouteProposalDto? = null
    private var activeNavBranchTypeFilter: String? = null
    private var activeNavTransportMode: TransportMode = TransportMode.WALKING
    private var userNavLat: Double = 10.772100
    private var userNavLng: Double = 106.698300
    private var navMapLoadedOnce: Boolean = false

    // Giao diện Tìm kiếm chuyến bay (FLIGHT)
    private lateinit var actvFlightFrom: AutoCompleteTextView
    private lateinit var actvFlightTo: AutoCompleteTextView
    private lateinit var tvFlightFromCity: TextView
    private lateinit var tvFlightToCity: TextView
    private lateinit var tvFlightDate: TextView
    private lateinit var btnPickFlightDate: View
    private lateinit var btnSearchFlights: TextView
    private lateinit var layoutFlightTimeFilter: LinearLayout
    private lateinit var btnFlightFilterAll: TextView
    private lateinit var btnFlightFilterMorning: TextView
    private lateinit var btnFlightFilterAfternoon: TextView
    private lateinit var btnFlightFilterEvening: TextView
    private lateinit var layoutFlightLoading: LinearLayout
    private lateinit var tvFlightLoadingText: TextView
    private lateinit var tvFlightResultSummary: TextView
    private lateinit var tvFlightEmptyState: TextView
    private lateinit var rvFlightResults: RecyclerView
    private lateinit var flightCatalogAdapter: FlightCatalogAdapter

    private val allFetchedFlights = mutableListOf<CatalogItemDto>()
    private var activeFlightTimeFilter: String = TIME_FILTER_ALL
    private var selectedFlightDate: String = ""
    private var lastSearchedFromIata: String = "SGN"
    private var lastSearchedToIata: String = "HAN"
    private var lastSearchedFlightDate: String = ""

    data class IataAirport(val code: String, val city: String, val airportName: String) {
        val displayLabel: String get() = "$code - $city ($airportName)"
    }

    private val iataAirports = listOf(
        IataAirport("SGN", "TP. Hồ Chí Minh", "Tân Sơn Nhất"),
        IataAirport("HAN", "Hà Nội", "Nội Bài"),
        IataAirport("DAD", "Đà Nẵng", "Đà Nẵng"),
        IataAirport("CXR", "Nha Trang", "Cam Ranh"),
        IataAirport("PQC", "Phú Quốc", "Phú Quốc"),
        IataAirport("HPH", "Hải Phòng", "Cát Bi"),
        IataAirport("HUI", "Huế", "Phú Bài"),
        IataAirport("DLI", "Đà Lạt", "Liên Khương"),
        IataAirport("VII", "Vinh", "Vinh"),
        IataAirport("VCA", "Cần Thơ", "Cần Thơ"),
        IataAirport("UIH", "Quy Nhơn", "Phù Cát"),
        IataAirport("BMV", "Buôn Ma Thuột", "Buôn Ma Thuột"),
        IataAirport("THD", "Thanh Hóa", "Thọ Xuân"),
        IataAirport("VDH", "Đồng Hới", "Đồng Hới"),
        IataAirport("VCL", "Chu Lai", "Chu Lai"),
        IataAirport("VCS", "Côn Đảo", "Côn Đảo")
    )

    private val vndFormatter: DecimalFormat by lazy {
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = '.'
        }
        DecimalFormat("#,###", symbols)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cấu hình osmdroid trước khi inflate MapView trong activity_main.xml
        val osmConfig = Configuration.getInstance()
        val osmPrefs = getSharedPreferences("osmdroid_prefs_v3", MODE_PRIVATE)
        osmConfig.load(applicationContext, osmPrefs)
        osmConfig.userAgentValue = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 NEXABank/1.0"
        osmConfig.osmdroidBasePath = File(cacheDir, "osmdroid_v3").apply { mkdirs() }
        osmConfig.osmdroidTileCache = File(cacheDir, "osmdroid_v3/tiles").apply { mkdirs() }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val rootView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initUtilityData()
        bindViews()
        setupBalanceCard()
        setupAccordion()
        setupUtilitiesAndStickyBar()
        setupSpeedDial()
        setupBottomNav()
        setupBottomSheet()
        setupLoanCalculator()
        setupFlightSearchTool()
        setupNavigationMapTool()
        loadUtilitiesFromDatabase()
        handleDeepLinkIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheetOpen -> closeSheet()
                    speedDialOpen -> setSpeedDialState(false)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (::osmMapView.isInitialized) {
            osmMapView.onResume()
        }
    }

    override fun onPause() {
        if (::osmMapView.isInitialized) {
            osmMapView.onPause()
        }
        super.onPause()
    }

    private fun initUtilityData() {
        utilityItems.clear()
        utilityItems.addAll(
            listOf(
                UtilityItem(
                    title = TOOL_LOAN,
                    subtitle = "Ước tính trả góp",
                    iconRes = R.drawable.ic_calculator,
                    iconBgRes = R.drawable.bg_utility_icon_mint,
                    iconTint = Color.parseColor("#0B8E6E")
                ),
                UtilityItem(
                    title = TOOL_FX,
                    subtitle = "Tỷ giá trực tiếp",
                    iconRes = R.drawable.ic_chart,
                    iconBgRes = R.drawable.bg_utility_icon_blue,
                    iconTint = Color.parseColor("#347EA4")
                ),
                UtilityItem(
                    title = TOOL_BILL,
                    subtitle = "3 lịch sắp tới",
                    iconRes = R.drawable.ic_bill,
                    iconBgRes = R.drawable.bg_utility_icon_amber,
                    iconTint = Color.parseColor("#AB7628"),
                    serviceType = "ELECTRICITY",
                    providerCode = "EVN_HCM"
                ),
                UtilityItem(
                    title = TOOL_FLIGHT,
                    subtitle = "Vietnam Airlines, Vietjet",
                    iconRes = R.drawable.ic_send,
                    iconBgRes = R.drawable.bg_utility_icon_violet,
                    iconTint = Color.parseColor("#735BA3"),
                    serviceType = "FLIGHT",
                    providerCode = "VNA"
                ),
                UtilityItem(
                    title = TOOL_NOTE,
                    subtitle = "Lưu việc cần làm",
                    iconRes = R.drawable.ic_note,
                    iconBgRes = R.drawable.bg_utility_icon_violet,
                    iconTint = Color.parseColor("#735BA3")
                ),
                UtilityItem(
                    title = TOOL_NAVIGATION,
                    subtitle = "Tìm chi nhánh gần bạn",
                    iconRes = R.drawable.ic_home,
                    iconBgRes = R.drawable.bg_utility_icon_mint,
                    iconTint = Color.parseColor("#0B8E6E"),
                    serviceType = "NAVIGATION"
                )
            )
        )

        upcomingQueue.clear()
        upcomingQueue.addAll(
            listOf(
                UtilityItem(
                    title = "Thanh toán điện",
                    subtitle = "Hóa đơn EVNHCMC",
                    iconRes = R.drawable.ic_bill,
                    iconBgRes = R.drawable.bg_utility_icon_amber,
                    iconTint = Color.parseColor("#AB7628"),
                    serviceType = "ELECTRICITY",
                    providerCode = "EVN_HCM"
                ),
                UtilityItem(
                    title = "Thanh toán nước",
                    subtitle = "Cấp nước SAWACO",
                    iconRes = R.drawable.ic_bill,
                    iconBgRes = R.drawable.bg_utility_icon_blue,
                    iconTint = Color.parseColor("#347EA4"),
                    serviceType = "WATER",
                    providerCode = "SAWACO"
                ),
                UtilityItem(
                    title = "Nạp điện thoại",
                    subtitle = "Viettel, Vina, Mobi",
                    iconRes = R.drawable.ic_transfer,
                    iconBgRes = R.drawable.bg_utility_icon_mint,
                    iconTint = Color.parseColor("#0B8E6E"),
                    serviceType = "PHONE_TOPUP",
                    providerCode = "VIETTEL"
                ),
                UtilityItem(
                    title = "Vé xem phim CGV",
                    subtitle = "Chọn ghế trực tuyến",
                    iconRes = R.drawable.ic_sparkle,
                    iconBgRes = R.drawable.bg_utility_icon_amber,
                    iconTint = Color.parseColor("#AB7628"),
                    serviceType = "MOVIE",
                    providerCode = "CGV"
                ),
                UtilityItem(
                    title = "Đặt khách sạn",
                    subtitle = "Chuỗi Mường Thanh",
                    iconRes = R.drawable.ic_home,
                    iconBgRes = R.drawable.bg_utility_icon_blue,
                    iconTint = Color.parseColor("#347EA4"),
                    serviceType = "HOTEL",
                    providerCode = "MUONG_THANH"
                ),
                UtilityItem(
                    title = "Mua sắm TMĐT",
                    subtitle = "Shopee, Lazada, Amazon",
                    iconRes = R.drawable.ic_wallet,
                    iconBgRes = R.drawable.bg_utility_icon_mint,
                    iconTint = Color.parseColor("#0B8E6E"),
                    serviceType = "ECOMMERCE",
                    providerCode = "SHOPEE"
                )
            )
        )
    }

    private fun bindViews() {
        tvBalanceAmount = findViewById(R.id.tvBalanceAmount)
        utilityGridContainer = findViewById(R.id.utilityGridContainer)
        layoutExpandBody = findViewById(R.id.layoutExpandBody)
        ivExpandChevron = findViewById(R.id.ivExpandChevron)

        layoutSpeedMenu = findViewById(R.id.layoutSpeedMenu)
        btnFab = findViewById(R.id.btnFab)
        ivFabIcon = findViewById(R.id.ivFabIcon)

        viewOverlay = findViewById(R.id.viewOverlay)
        layoutBottomSheet = findViewById(R.id.layoutBottomSheet)
        tvDragHint = findViewById(R.id.tvDragHint)
        tvSheetTitle = findViewById(R.id.tvSheetTitle)
        sheetToolTabsContainer = findViewById(R.id.sheetToolTabsContainer)

        layoutLoanTool = findViewById(R.id.layoutLoanTool)
        layoutFlightTool = findViewById(R.id.layoutFlightTool)
        layoutNavigationTool = findViewById(R.id.layoutNavigationTool)
        layoutToolReady = findViewById(R.id.layoutToolReady)
        ivToolReadyIcon = findViewById(R.id.ivToolReadyIcon)
        tvToolReadyTitle = findViewById(R.id.tvToolReadyTitle)

        etLoanAmount = findViewById(R.id.etLoanAmount)
        tvLoanYears = findViewById(R.id.tvLoanYears)
        seekBarYears = findViewById(R.id.seekBarYears)
        tvLoanEstimate = findViewById(R.id.tvLoanEstimate)

        actvFlightFrom = findViewById(R.id.actvFlightFrom)
        actvFlightTo = findViewById(R.id.actvFlightTo)
        tvFlightFromCity = findViewById(R.id.tvFlightFromCity)
        tvFlightToCity = findViewById(R.id.tvFlightToCity)
        tvFlightDate = findViewById(R.id.tvFlightDate)
        btnPickFlightDate = findViewById(R.id.btnPickFlightDate)
        btnSearchFlights = findViewById(R.id.btnSearchFlights)
        layoutFlightTimeFilter = findViewById(R.id.layoutFlightTimeFilter)
        btnFlightFilterAll = findViewById(R.id.btnFlightFilterAll)
        btnFlightFilterMorning = findViewById(R.id.btnFlightFilterMorning)
        btnFlightFilterAfternoon = findViewById(R.id.btnFlightFilterAfternoon)
        btnFlightFilterEvening = findViewById(R.id.btnFlightFilterEvening)
        layoutFlightLoading = findViewById(R.id.layoutFlightLoading)
        tvFlightLoadingText = findViewById(R.id.tvFlightLoadingText)
        tvFlightResultSummary = findViewById(R.id.tvFlightResultSummary)
        tvFlightEmptyState = findViewById(R.id.tvFlightEmptyState)
        rvFlightResults = findViewById(R.id.rvFlightResults)

        osmMapView = findViewById(R.id.osmMapView)
        pbNavRouteLoading = findViewById(R.id.pbNavRouteLoading)
        tvNavLocationStatus = findViewById(R.id.tvNavLocationStatus)
        btnNavMyLocation = findViewById(R.id.btnNavMyLocation)
        btnNavFilterAll = findViewById(R.id.btnNavFilterAll)
        btnNavFilterBranch = findViewById(R.id.btnNavFilterBranch)
        btnNavFilterAtm = findViewById(R.id.btnNavFilterAtm)
        btnNavModeWalking = findViewById(R.id.btnNavModeWalking)
        btnNavModeTwoWheeler = findViewById(R.id.btnNavModeTwoWheeler)
        btnNavModeDriving = findViewById(R.id.btnNavModeDriving)
        btnNavModeTransit = findViewById(R.id.btnNavModeTransit)
        tvNavSelectedBranchName = findViewById(R.id.tvNavSelectedBranchName)
        tvNavSelectedBranchAddress = findViewById(R.id.tvNavSelectedBranchAddress)
        tvNavRouteMetrics = findViewById(R.id.tvNavRouteMetrics)
        tvNavRouteSteps = findViewById(R.id.tvNavRouteSteps)
        btnNavOpenGoogleMaps = findViewById(R.id.btnNavOpenGoogleMaps)
        layoutNavBranchesList = findViewById(R.id.layoutNavBranchesList)
    }

    private fun setupBalanceCard() {
        findViewById<View>(R.id.btnToggleBalance).setOnClickListener {
            balanceVisible = !balanceVisible
            tvBalanceAmount.text = if (balanceVisible) currentBalanceText else "••••••••••"
        }
    }

    private fun setupAccordion() {
        findViewById<View>(R.id.btnExpandTrigger).setOnClickListener {
            accordionOpen = !accordionOpen
            layoutExpandBody.visibility = if (accordionOpen) View.VISIBLE else View.GONE
            ivExpandChevron.animate()
                .rotation(if (accordionOpen) 180f else 0f)
                .setDuration(250)
                .start()
        }

        findViewById<View>(R.id.btnAdjustPlan).setOnClickListener {
            openSheet(TOOL_LOAN)
        }
    }

    private fun setupUtilitiesAndStickyBar() {
        findViewById<View>(R.id.btnViewAllUtilities).setOnClickListener { openSheet() }

        renderUtilityGrid()
        renderSheetToolTabs()

        findViewById<View>(R.id.btnStickyLoan).setOnClickListener { openSheet(TOOL_LOAN) }
        findViewById<View>(R.id.btnStickyFx).setOnClickListener { openSheet(TOOL_FX) }
        findViewById<View>(R.id.btnStickyBill).setOnClickListener { openSheet(TOOL_BILL) }
        findViewById<View>(R.id.btnStickyAll).setOnClickListener { openSheet() }
    }

    /**
     * Thêm một tiện ích mới vào danh sách và tự động đẩy ô
     * "Tiện ích mới sắp được cập nhật" sang vị trí tiếp theo trong lưới 2 cột.
     */
    fun addUtilityItem(item: UtilityItem) {
        utilityItems.add(item)
        renderUtilityGrid()
        renderSheetToolTabs()
    }

    private fun renderUtilityGrid() {
        utilityGridContainer.removeAllViews()

        // Tổng số ô cần hiển thị = danh sách tiện ích hiện có + 1 ô trống "Tiện ích mới sắp được cập nhật" ở cuối
        val totalSlots = utilityItems.size + 1
        val rowCount = (totalSlots + 1) / 2

        for (rowIndex in 0 until rowCount) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIndex > 0) {
                        topMargin = dp(10)
                    }
                }
            }

            for (colIndex in 0..1) {
                val itemIndex = rowIndex * 2 + colIndex
                val isLeftCol = colIndex == 0

                val cellView: View = when {
                    itemIndex < utilityItems.size -> {
                        createUtilityCardView(utilityItems[itemIndex], isLeftCol)
                    }
                    itemIndex == utilityItems.size -> {
                        createUpcomingPlaceholderCardView(isLeftCol)
                    }
                    else -> {
                        Space(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(126), 1f).apply {
                                marginStart = dp(5)
                            }
                        }
                    }
                }
                rowLayout.addView(cellView)
            }

            utilityGridContainer.addView(rowLayout)
        }
    }

    private fun createUtilityCardView(item: UtilityItem, isLeftCol: Boolean): View {
        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(126), 1f).apply {
                if (isLeftCol) marginEnd = dp(5) else marginStart = dp(5)
            }
            setBackgroundResource(R.drawable.bg_utility_card)
            isClickable = true
            isFocusable = true
            foreground = getSelectableItemDrawable()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { openSheet(item.title) }
        }

        val contentCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val iconBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(37), dp(37))
            setBackgroundResource(item.iconBgRes)
        }

        val iconView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            setImageResource(item.iconRes)
            setColorFilter(item.iconTint)
        }
        iconBox.addView(iconView)

        val titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            text = item.title
            setTextColor(Color.parseColor("#294740"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val subtitleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
            text = item.subtitle
            setTextColor(Color.parseColor("#8B9A97"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        contentCol.addView(iconBox)
        contentCol.addView(titleView)
        contentCol.addView(subtitleView)

        val arrowView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            )
            text = "→"
            setTextColor(Color.parseColor("#91A09D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }

        card.addView(contentCol)
        card.addView(arrowView)
        return card
    }

    private fun createUpcomingPlaceholderCardView(isLeftCol: Boolean): View {
        val card = FrameLayout(this).apply {
            id = R.id.cardUtilUpcoming
            layoutParams = LinearLayout.LayoutParams(0, dp(126), 1f).apply {
                if (isLeftCol) marginEnd = dp(5) else marginStart = dp(5)
            }
            setBackgroundResource(R.drawable.bg_utility_card_placeholder)
            isClickable = true
            isFocusable = true
            foreground = getSelectableItemDrawable()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { showAddUtilityDialog() }
        }

        val contentCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val iconBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(37), dp(37))
            setBackgroundResource(R.drawable.bg_utility_icon_placeholder)
        }

        val iconView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            setImageResource(R.drawable.ic_plus)
            setColorFilter(Color.parseColor("#5E7D75"))
        }
        iconBox.addView(iconView)

        val titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            text = "Tiện ích mới sắp được cập nhật"
            setTextColor(Color.parseColor("#527068"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val subtitleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
            text = "Chạm để thêm tiện ích"
            setTextColor(Color.parseColor("#8B9A97"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 1
        }

        contentCol.addView(iconBox)
        contentCol.addView(titleView)
        contentCol.addView(subtitleView)
        card.addView(contentCol)
        return card
    }

    private fun showAddUtilityDialog() {
        val nextPreset = upcomingQueue.firstOrNull()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val etTitle = EditText(this).apply {
            hint = "Tên tiện ích (VD: Thanh toán điện)"
            setText(nextPreset?.title ?: "Tiện ích #${utilityItems.size + 1}")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val etSubtitle = EditText(this).apply {
            hint = "Mô tả ngắn (VD: Hóa đơn EVN tự động)"
            setText(nextPreset?.subtitle ?: "Công cụ mới cập nhật")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        container.addView(etTitle)
        container.addView(etSubtitle)

        AlertDialog.Builder(this)
            .setTitle("Thêm tiện ích mới")
            .setMessage("Tiện ích mới sẽ được thêm vào danh sách và tự động đẩy ô \"Tiện ích mới sắp được cập nhật\" sang vị trí tiếp theo.")
            .setView(container)
            .setPositiveButton("Thêm tiện ích") { _, _ ->
                val customTitle = etTitle.text?.toString()?.trim().orEmpty()
                val customSubtitle = etSubtitle.text?.toString()?.trim().orEmpty()

                val newItem = if (nextPreset != null && customTitle == nextPreset.title) {
                    upcomingQueue.removeFirst()
                } else {
                    val tones = listOf(
                        Triple(R.drawable.ic_calculator, R.drawable.bg_utility_icon_mint, Color.parseColor("#0B8E6E")),
                        Triple(R.drawable.ic_chart, R.drawable.bg_utility_icon_blue, Color.parseColor("#347EA4")),
                        Triple(R.drawable.ic_bill, R.drawable.bg_utility_icon_amber, Color.parseColor("#AB7628")),
                        Triple(R.drawable.ic_sparkle, R.drawable.bg_utility_icon_violet, Color.parseColor("#735BA3"))
                    )
                    val (iconRes, bgRes, tint) = tones[utilityItems.size % tones.size]
                    UtilityItem(
                        title = customTitle.ifEmpty { "Tiện ích #${utilityItems.size + 1}" },
                        subtitle = customSubtitle.ifEmpty { "Công cụ mới cập nhật" },
                        iconRes = iconRes,
                        iconBgRes = bgRes,
                        iconTint = tint
                    )
                }

                addUtilityItem(newItem)
                // Đồng bộ tiện ích mới lên bảng dbo.utility_services trên API Server nếu có kết nối
                val providerCode = newItem.providerCode
                    ?: "CUSTOM_${utilityItems.size}_${System.currentTimeMillis() % 1000}"
                val srvType = newItem.serviceType ?: "ELECTRICITY"
                UtilitiesApiClient.createUtilityService(
                    providerCode = providerCode,
                    serviceName = newItem.title,
                    serviceType = srvType
                ) { res ->
                    res.onSuccess { createdSrv ->
                        if (dbServices.none { it.serviceId == createdSrv.serviceId }) {
                            dbServices.add(createdSrv)
                        }
                    }
                }
                Toast.makeText(this, "Đã thêm: ${newItem.title}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Tải từ DB Server") { _, _ ->
                loadUtilitiesFromDatabase(showToast = true)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun renderSheetToolTabs() {
        sheetToolTabsContainer.removeAllViews()
        sheetToolTabsContainer.weightSum = utilityItems.size.coerceAtMost(4).toFloat()

        val activeColor = Color.parseColor("#08765F")
        val inactiveColor = Color.parseColor("#788985")

        // Nếu có nhiều hơn 4 tiện ích, chia thành các hàng 4 cột gọn gàng trong Bottom Sheet
        sheetToolTabsContainer.orientation = LinearLayout.VERTICAL
        val rowCount = (utilityItems.size + 3) / 4

        for (rowIdx in 0 until rowCount) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIdx > 0) topMargin = dp(6)
                }
            }

            for (colIdx in 0 until 4) {
                val index = rowIdx * 4 + colIdx
                if (index < utilityItems.size) {
                    val item = utilityItems[index]
                    val isSelected = item.title == activeTool
                    val tab = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                            marginStart = if (colIdx == 0) 0 else dp(3)
                            marginEnd = if (colIdx == 3) 0 else dp(3)
                        }
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        setBackgroundResource(
                            if (isSelected) R.drawable.bg_sheet_tab_active else R.drawable.bg_sheet_tab_inactive
                        )
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { selectTool(item.title) }
                    }

                    val icon = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        setImageResource(item.iconRes)
                        setColorFilter(if (isSelected) activeColor else inactiveColor)
                    }

                    val label = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(5)
                        }
                        text = item.title
                        setTextColor(if (isSelected) activeColor else inactiveColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                        setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }

                    tab.addView(icon)
                    tab.addView(label)
                    row.addView(tab)
                } else {
                    row.addView(
                        Space(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                                marginStart = dp(3)
                                marginEnd = if (colIdx == 3) 0 else dp(3)
                            }
                        }
                    )
                }
            }

            sheetToolTabsContainer.addView(row)
        }
    }

    private fun setupSpeedDial() {
        findViewById<View>(R.id.btnAiChatFab).setOnClickListener {
            setSpeedDialState(false)
            startActivity(Intent(this, ChatActivity::class.java))
        }
        btnFab.setOnClickListener {
            setSpeedDialState(!speedDialOpen)
        }
        findViewById<View>(R.id.btnSpeedNote).setOnClickListener { openSheet(TOOL_NOTE) }
        findViewById<View>(R.id.btnSpeedFx).setOnClickListener { openSheet(TOOL_FX) }
        findViewById<View>(R.id.btnSpeedLoan).setOnClickListener { openSheet(TOOL_LOAN) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ChatActivity.EXTRA_OPEN_TOOL)?.let { targetTool ->
            openSheet(targetTool)
        }
        handleDeepLinkIntent(intent)
    }


    private fun setSpeedDialState(open: Boolean) {
        speedDialOpen = open
        if (open) {
            layoutSpeedMenu.visibility = View.VISIBLE
            layoutSpeedMenu.alpha = 0f
            layoutSpeedMenu.translationY = 24f
            layoutSpeedMenu.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
            btnFab.setBackgroundResource(R.drawable.bg_fab_active)
            ivFabIcon.setImageResource(R.drawable.ic_close)
            btnFab.animate().rotation(90f).setDuration(200).start()
        } else {
            layoutSpeedMenu.visibility = View.GONE
            btnFab.setBackgroundResource(R.drawable.bg_fab_closed)
            ivFabIcon.setImageResource(R.drawable.ic_plus)
            btnFab.animate().rotation(0f).setDuration(200).start()
        }
    }

    private fun setupBottomNav() {
        val navItems = listOf(
            NavTab(
                container = findViewById(R.id.navHome),
                indicator = findViewById(R.id.indNavHome),
                icon = findViewById(R.id.ivNavHome),
                label = findViewById(R.id.tvNavHome),
                name = "Trang chủ"
            ),
            NavTab(
                container = findViewById(R.id.navTransactions),
                indicator = findViewById(R.id.indNavTransactions),
                icon = findViewById(R.id.ivNavTransactions),
                label = findViewById(R.id.tvNavTransactions),
                name = "Giao dịch"
            ),
            NavTab(
                container = findViewById(R.id.navFinance),
                indicator = findViewById(R.id.indNavFinance),
                icon = findViewById(R.id.ivNavFinance),
                label = findViewById(R.id.tvNavFinance),
                name = "Tài chính"
            ),
            NavTab(
                container = findViewById(R.id.navUtilities),
                indicator = findViewById(R.id.indNavUtilities),
                icon = findViewById(R.id.ivNavUtilities),
                label = findViewById(R.id.tvNavUtilities),
                name = "Tiện ích"
            )
        )

        val activeColor = Color.parseColor("#08765F")
        val inactiveColor = Color.parseColor("#82928E")

        navItems.forEach { selected ->
            selected.container.setOnClickListener {
                navItems.forEach { item ->
                    val isActive = item == selected
                    item.indicator.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
                    item.icon.setColorFilter(if (isActive) activeColor else inactiveColor)
                    item.label.setTextColor(if (isActive) activeColor else inactiveColor)
                    item.label.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                }
                if (selected.name == "Tiện ích") {
                    openSheet()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBottomSheet() {
        viewOverlay.setOnClickListener { closeSheet() }
        findViewById<View>(R.id.btnCloseSheet).setOnClickListener { closeSheet() }

        val dragArea = findViewById<View>(R.id.layoutDragArea)
        findViewById<View>(R.id.btnStartToolNow).setOnClickListener {
            openUtilityDatabaseActionDialog(activeTool)
        }
        var dragStartY = 0f
        dragArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val distancePx = event.rawY - dragStartY
                    val distanceDp = distancePx / resources.displayMetrics.density
                    when {
                        distanceDp < -45f -> setSheetFullMode(true)
                        distanceDp > 55f && sheetFull -> setSheetFullMode(false)
                        distanceDp > 90f && !sheetFull -> closeSheet()
                        kotlin.math.abs(distanceDp) < 10f -> setSheetFullMode(!sheetFull)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openSheet(tool: String? = null) {
        if (tool != null) {
            selectTool(tool)
        } else {
            selectTool(activeTool)
        }
        setSpeedDialState(false)
        sheetOpen = true
        setSheetFullMode(isFlightTool(activeTool) || isNavigationTool(activeTool))

        viewOverlay.visibility = View.VISIBLE
        viewOverlay.alpha = 0f
        viewOverlay.animate().alpha(1f).setDuration(250).start()

        layoutBottomSheet.visibility = View.VISIBLE
        layoutBottomSheet.post {
            layoutBottomSheet.translationY = layoutBottomSheet.height.toFloat()
            layoutBottomSheet.animate()
                .translationY(0f)
                .setDuration(300)
                .start()
        }
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        sheetFull = false

        viewOverlay.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction { viewOverlay.visibility = View.GONE }
            .start()

        val slideDist = if (layoutBottomSheet.height > 0) {
            layoutBottomSheet.height.toFloat()
        } else {
            600f * resources.displayMetrics.density
        }
        layoutBottomSheet.animate()
            .translationY(slideDist)
            .setDuration(250)
            .withEndAction {
                layoutBottomSheet.visibility = View.GONE
                layoutBottomSheet.translationY = 0f
            }
            .start()
    }

    private fun setSheetFullMode(full: Boolean) {
        sheetFull = full
        tvDragHint.text = if (full) {
            "Vuốt xuống để thu gọn"
        } else {
            "Vuốt lên để mở toàn màn hình"
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val lp = layoutBottomSheet.layoutParams
        lp.height = if (full) {
            (screenHeight * 0.88f).toInt()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        layoutBottomSheet.layoutParams = lp
    }

    private fun isFlightTool(tool: String): Boolean {
        val matchedItem = utilityItems.find { it.title.equals(tool, ignoreCase = true) }
        val srvType = matchedItem?.serviceType ?: inferServiceTypeByTitle(tool)
        return srvType.equals("FLIGHT", ignoreCase = true)
    }

    private fun isNavigationTool(tool: String): Boolean {
        val matchedItem = utilityItems.find { it.title.equals(tool, ignoreCase = true) }
        val srvType = matchedItem?.serviceType ?: inferServiceTypeByTitle(tool)
        return srvType.equals("NAVIGATION", ignoreCase = true)
    }

    private fun selectTool(tool: String) {
        activeTool = tool
        tvSheetTitle.text = tool
        renderSheetToolTabs()

        val isFlight = isFlightTool(tool)
        val isNavigation = isNavigationTool(tool)
        when {
            tool == TOOL_LOAN -> {
                layoutLoanTool.visibility = View.VISIBLE
                layoutFlightTool.visibility = View.GONE
                layoutNavigationTool.visibility = View.GONE
                layoutToolReady.visibility = View.GONE
            }
            isFlight -> {
                layoutLoanTool.visibility = View.GONE
                layoutFlightTool.visibility = View.VISIBLE
                layoutNavigationTool.visibility = View.GONE
                layoutToolReady.visibility = View.GONE
                if (sheetOpen && !sheetFull) {
                    setSheetFullMode(true)
                }
            }
            isNavigation -> {
                layoutLoanTool.visibility = View.GONE
                layoutFlightTool.visibility = View.GONE
                layoutNavigationTool.visibility = View.VISIBLE
                layoutToolReady.visibility = View.GONE
                if (sheetOpen && !sheetFull) {
                    setSheetFullMode(true)
                }
                if (!navMapLoadedOnce) {
                    loadNearbyBranchesOntoMap()
                }
            }
            else -> {
                layoutLoanTool.visibility = View.GONE
                layoutFlightTool.visibility = View.GONE
                layoutNavigationTool.visibility = View.GONE
                layoutToolReady.visibility = View.VISIBLE
                val matchedItem = utilityItems.find { it.title == tool }
                val srvType = matchedItem?.serviceType ?: inferServiceTypeByTitle(tool)
                val matchedDbServices = if (srvType != null) {
                    dbServices.filter { it.serviceType.equals(srvType, ignoreCase = true) }
                } else {
                    emptyList()
                }

                tvToolReadyTitle.text = when {
                    tool == TOOL_BILL && dbUnpaidBills.isNotEmpty() ->
                        "$tool (${dbUnpaidBills.size} hóa đơn chưa TT từ DB)"
                    matchedDbServices.isNotEmpty() ->
                        "$tool • ${matchedDbServices.joinToString(", ") { it.providerCode }}"
                    else ->
                        "$tool đã sẵn sàng"
                }
                ivToolReadyIcon.setImageResource(matchedItem?.iconRes ?: R.drawable.ic_tools)
            }
        }
    }

    /**
     * Gọi API tới BankServer (`GET /v1/utilities/dashboard`) để truy vấn dữ liệu Utilities
     * từ các bảng `dbo.utility_services`, `dbo.utility_orders`, `dbo.accounts` trong SQL Server.
     */
    private fun loadUtilitiesFromDatabase(showToast: Boolean = false) {
        UtilitiesApiClient.fetchDashboard { result ->
            result.onSuccess { dashboard ->
                dbServices.clear()
                dbServices.addAll(dashboard.services)

                dbBills.clear()
                dbBills.addAll(dashboard.bills)

                dbUnpaidBills.clear()
                dbUnpaidBills.addAll(dashboard.unpaidBills)

                dbOrders.clear()
                dbOrders.addAll(dashboard.orders)

                // Cập nhật số dư khả dụng từ tài khoản CHECKING trong Database
                val checkingAcc = dashboard.accounts.find { it.accountType.equals("CHECKING", ignoreCase = true) }
                if (checkingAcc != null && checkingAcc.balance > 0) {
                    currentBalanceText = "${vndFormatter.format(checkingAcc.balance.roundToLong())} ₫"
                    if (balanceVisible) {
                        tvBalanceAmount.text = currentBalanceText
                    }
                }

                // Cập nhật subtitle của thẻ Nhắc hóa đơn / Điện / Nước theo dữ liệu thật từ Database
                syncUtilityCardsWithDatabase()
                renderUtilityGrid()
                renderSheetToolTabs()

                if (showToast) {
                    Toast.makeText(
                        this,
                        "Đã đồng bộ ${dbServices.size} dịch vụ & ${dbUnpaidBills.size} hóa đơn từ Database!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { err ->
                if (showToast) {
                    Toast.makeText(
                        this,
                        "Không kết nối được BankServer (${UtilitiesApiClient.baseUrl}): ${err.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun syncUtilityCardsWithDatabase() {
        if (dbServices.isNotEmpty()) {
            // 1. Tự động đưa các nhóm tiện ích có trong dbo.utility_services từ upcomingQueue lên lưới UI
            val dbServiceTypes = dbServices.map { it.serviceType.uppercase(Locale.ROOT) }.toSet()
            val iterator = upcomingQueue.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val type = candidate.serviceType?.uppercase(Locale.ROOT)
                if (type != null && type in dbServiceTypes) {
                    val alreadyAdded = utilityItems.any {
                        it.title.equals(candidate.title, ignoreCase = true) ||
                            (it.serviceType != null && it.serviceType.equals(type, ignoreCase = true))
                    }
                    if (!alreadyAdded) {
                        utilityItems.add(candidate)
                    }
                    iterator.remove()
                }
            }

            // 2. Nếu trong dbo.utility_services có thêm dịch vụ tùy chỉnh mới chưa có trên UI thì thêm luôn vào lưới
            val standardProviderCodes = setOf(
                "EVN_HCM", "SAWACO", "VIETTEL", "VINAPHONE", "MOBIFONE",
                "VNA", "VIETJET", "CGV", "MUONG_THANH", "SHOPEE", "LAZADA", "AMAZON"
            )
            val tones = listOf(
                Triple(R.drawable.ic_calculator, R.drawable.bg_utility_icon_mint, Color.parseColor("#0B8E6E")),
                Triple(R.drawable.ic_chart, R.drawable.bg_utility_icon_blue, Color.parseColor("#347EA4")),
                Triple(R.drawable.ic_bill, R.drawable.bg_utility_icon_amber, Color.parseColor("#AB7628")),
                Triple(R.drawable.ic_sparkle, R.drawable.bg_utility_icon_violet, Color.parseColor("#735BA3"))
            )
            for (srv in dbServices) {
                if (srv.providerCode.uppercase(Locale.ROOT) !in standardProviderCodes) {
                    val exists = utilityItems.any {
                        it.providerCode.equals(srv.providerCode, ignoreCase = true) ||
                            it.title.equals(srv.serviceName, ignoreCase = true)
                    }
                    if (!exists) {
                        val (iconRes, bgRes, tint) = tones[utilityItems.size % tones.size]
                        utilityItems.add(
                            UtilityItem(
                                title = srv.serviceName,
                                subtitle = "${srv.serviceType} • ${srv.aggregatorGateway}",
                                iconRes = iconRes,
                                iconBgRes = bgRes,
                                iconTint = tint,
                                serviceType = srv.serviceType,
                                providerCode = srv.providerCode
                            )
                        )
                    }
                }
            }
        }

        // 3. Cập nhật subtitle trực tiếp từ dữ liệu hóa đơn & đối tác trong Database
        for (i in utilityItems.indices) {
            val item = utilityItems[i]
            val srvType = item.serviceType ?: inferServiceTypeByTitle(item.title)
            if (item.title == TOOL_BILL && dbUnpaidBills.isNotEmpty()) {
                val totalUnpaid = dbUnpaidBills.sumOf { it.amount }.roundToLong()
                utilityItems[i] = item.copy(
                    subtitle = "${dbUnpaidBills.size} hóa đơn • ${vndFormatter.format(totalUnpaid)} ₫"
                )
            } else if (srvType != null && dbServices.isNotEmpty()) {
                val providers = dbServices.filter { it.serviceType.equals(srvType, ignoreCase = true) }
                if (providers.isNotEmpty()) {
                    val unpaidForType = dbUnpaidBills.find { it.billType.equals(srvType, ignoreCase = true) }
                    val newSub = if (unpaidForType != null) {
                        "${unpaidForType.providerCustomerId}: ${vndFormatter.format(unpaidForType.amount.roundToLong())} ₫"
                    } else {
                        providers.joinToString(", ") { it.serviceName.substringBefore(" (") }
                    }
                    utilityItems[i] = item.copy(subtitle = newSub)
                }
            }
        }
    }

    private fun inferServiceTypeByTitle(title: String): String? {
        val lower = title.lowercase(Locale.ROOT)
        return when {
            lower.contains("điện thoại") || lower.contains("nạp") -> "PHONE_TOPUP"
            lower.contains("điện") -> "ELECTRICITY"
            lower.contains("nước") -> "WATER"
            lower.contains("máy bay") || lower.contains("bay") -> "FLIGHT"
            lower.contains("phim") || lower.contains("cgv") -> "MOVIE"
            lower.contains("khách sạn") || lower.contains("phòng") -> "HOTEL"
            lower.contains("mua sắm") || lower.contains("tmđt") || lower.contains("shopee") -> "ECOMMERCE"
            lower.contains("hóa đơn") -> "ELECTRICITY"
            lower.contains("chi nhánh") || lower.contains("atm") || lower.contains("bản đồ") -> "NAVIGATION"
            else -> null
        }
    }





    private fun handleDeepLinkIntent(intent: Intent?) {
        val openTool = intent?.getStringExtra(ChatActivity.EXTRA_OPEN_TOOL)
        val deepLink = intent?.getStringExtra(ChatActivity.EXTRA_DEEP_LINK)
        if (openTool.isNullOrBlank() && deepLink.isNullOrBlank()) return

        val isNavigationLink = (deepLink != null && deepLink.lowercase(Locale.ROOT).contains("navigation"))
            || openTool == TOOL_NAVIGATION

        if (isNavigationLink) {
            val uri = deepLink?.let { Uri.parse(it) }
            val modeParam = uri?.getQueryParameter("mode")
            val typeParam = uri?.getQueryParameter("type")
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it == "ATM" || it == "BRANCH" }

            activeNavTransportMode = TransportMode.fromApiValue(modeParam)
            updateNavModeTabUI()
            openSheet(TOOL_NAVIGATION)
            setNavBranchTypeFilter(typeParam)
        } else if (!openTool.isNullOrBlank()) {
            openSheet(openTool)
        }
    }

    /**
     * Mở hộp thoại tương tác trực tiếp với API Server & Database cho tiện ích đang chọn.
     */
    private fun openUtilityDatabaseActionDialog(toolTitle: String) {
        val serviceType = utilityItems.find { it.title == toolTitle }?.serviceType
            ?: inferServiceTypeByTitle(toolTitle)

        when (serviceType) {
            "ELECTRICITY", "WATER" -> showBillLookupAndPaymentDialog(toolTitle, serviceType)
            "PHONE_TOPUP" -> showPhoneTopupDialog()
            "FLIGHT" -> {
                if (!sheetOpen) {
                    openSheet(toolTitle)
                } else {
                    selectTool(toolTitle)
                }
                performFlightSearch()
            }
            "MOVIE", "HOTEL", "ECOMMERCE" -> showCatalogFromApiDialog(toolTitle, serviceType)
            "NAVIGATION" -> {
                if (!sheetOpen) {
                    openSheet(TOOL_NAVIGATION)
                } else {
                    selectTool(TOOL_NAVIGATION)
                }
                loadNearbyBranchesOntoMap(defaultMode = activeNavTransportMode)
            }
            else -> showAllDbServicesDialog(toolTitle)
        }
    }

    // ======================= OSMDROID MAP & DIRECT OSRM ROUTING =======================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupNavigationMapTool() {
        val esriTileSource = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
            "EsriWorldStreetMap",
            0,
            19,
            256,
            "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl +
                        org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" +
                        org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "/" +
                        org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            }
        }
        osmMapView.setTileSource(esriTileSource)
        osmMapView.setUseDataConnection(true)
        osmMapView.setMultiTouchControls(true)
        osmMapView.isHorizontalMapRepetitionEnabled = false
        osmMapView.isVerticalMapRepetitionEnabled = false
        osmMapView.minZoomLevel = 4.0
        osmMapView.maxZoomLevel = 18.5
        osmMapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        osmMapView.controller.setZoom(15.5)
        osmMapView.controller.setCenter(GeoPoint(userNavLat, userNavLng))

        // Cho phép kéo/phóng to bản đồ osmdroid mượt mà bên trong NestedScrollView của BottomSheet
        osmMapView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        // Lọc theo loại điểm: Tất cả / Chi nhánh / ATM
        btnNavFilterAll.setOnClickListener {
            setNavBranchTypeFilter(null)
        }
        btnNavFilterBranch.setOnClickListener {
            setNavBranchTypeFilter("BRANCH")
        }
        btnNavFilterAtm.setOnClickListener {
            setNavBranchTypeFilter("ATM")
        }

        // Đổi phương tiện dẫn đường: Đi bộ / Xe máy / Ô tô / Xe buýt (gọi thẳng OSRM Route Service)
        btnNavModeWalking.setOnClickListener {
            switchNavTransportMode(TransportMode.WALKING)
        }
        btnNavModeTwoWheeler.setOnClickListener {
            switchNavTransportMode(TransportMode.TWO_WHEELER)
        }
        btnNavModeDriving.setOnClickListener {
            switchNavTransportMode(TransportMode.DRIVING)
        }
        btnNavModeTransit.setOnClickListener {
            switchNavTransportMode(TransportMode.TRANSIT)
        }

        // Nút lấy vị trí hiện tại hoặc chọn khu vực
        btnNavMyLocation.setOnClickListener {
            resolveDeviceLocationOrPickCity()
        }

        // Mở chỉ đường trên Google Maps bên ngoài nếu người dùng muốn
        btnNavOpenGoogleMaps.setOnClickListener {
            val targetBranch = selectedBranchForMap
            val uriStr = currentNavRoute?.googleMapsIntentUri ?: targetBranch?.let {
                String.format(
                    Locale.US,
                    "https://www.google.com/maps/dir/?api=1&origin=%.6f,%.6f&destination=%.6f,%.6f",
                    userNavLat,
                    userNavLng,
                    it.latitude,
                    it.longitude
                )
            }
            if (!uriStr.isNullOrBlank()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)))
                } catch (_: Exception) {
                    Toast.makeText(this, "Không tìm thấy ứng dụng bản đồ ngoài.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setNavBranchTypeFilter(branchType: String?) {
        activeNavBranchTypeFilter = branchType
        val activeColor = Color.parseColor("#08765F")
        val inactiveColor = Color.parseColor("#788985")
        val filterTabs = listOf(
            btnNavFilterAll to null,
            btnNavFilterBranch to "BRANCH",
            btnNavFilterAtm to "ATM"
        )
        for ((btn, typeVal) in filterTabs) {
            val selected = typeVal == activeNavBranchTypeFilter
            btn.setBackgroundResource(
                if (selected) R.drawable.bg_sheet_tab_active else R.drawable.bg_sheet_tab_inactive
            )
            btn.setTextColor(if (selected) activeColor else inactiveColor)
            btn.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        loadNearbyBranchesOntoMap(
            lat = userNavLat,
            lng = userNavLng,
            branchType = activeNavBranchTypeFilter,
            defaultMode = activeNavTransportMode
        )
    }

    private fun switchNavTransportMode(mode: TransportMode) {
        activeNavTransportMode = mode
        updateNavModeTabUI()
        val targetBranch = selectedBranchForMap ?: nearbyBranches.firstOrNull()
        if (targetBranch != null) {
            requestAndDrawOsrmRoute(targetBranch, mode)
        }
    }

    private fun updateNavModeTabUI() {
        val activeColor = Color.parseColor("#08765F")
        val inactiveColor = Color.parseColor("#788985")
        val modeButtons = listOf(
            btnNavModeWalking to TransportMode.WALKING,
            btnNavModeTwoWheeler to TransportMode.TWO_WHEELER,
            btnNavModeDriving to TransportMode.DRIVING,
            btnNavModeTransit to TransportMode.TRANSIT
        )
        for ((btn, m) in modeButtons) {
            val selected = m == activeNavTransportMode
            btn.setBackgroundResource(
                if (selected) R.drawable.bg_sheet_tab_active else R.drawable.bg_sheet_tab_inactive
            )
            btn.setTextColor(if (selected) activeColor else inactiveColor)
            btn.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun resolveDeviceLocationOrPickCity() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION_PERMISSION
            )
        } else {
            val locManager = getSystemService(LOCATION_SERVICE) as? LocationManager
            val gpsLoc = runCatching {
                locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()

            // Nếu GPS nằm trong lãnh thổ Việt Nam thì sử dụng trực tiếp
            if (gpsLoc != null && gpsLoc.latitude in 8.0..24.0 && gpsLoc.longitude in 102.0..110.5) {
                userNavLat = gpsLoc.latitude
                userNavLng = gpsLoc.longitude
                tvNavLocationStatus.text = String.format(
                    Locale.US,
                    "GPS của bạn: %.4f, %.4f • OSRM Route",
                    userNavLat,
                    userNavLng
                )
                loadNearbyBranchesOntoMap(
                    lat = userNavLat,
                    lng = userNavLng,
                    branchType = activeNavBranchTypeFilter,
                    defaultMode = activeNavTransportMode
                )
                return
            }
        }

        val options = arrayOf(
            "Chợ Bến Thành, Quận 1, TP. Hồ Chí Minh (10.7721, 106.6983)",
            "Hồ Hoàn Kiếm, Quận Hoàn Kiếm, Hà Nội (21.0285, 105.8501)"
        )
        AlertDialog.Builder(this)
            .setTitle("Chọn vị trí xuất phát của bạn")
            .setItems(options) { _, which ->
                if (which == 0) {
                    userNavLat = 10.772100
                    userNavLng = 106.698300
                    tvNavLocationStatus.text = "Vị trí: Chợ Bến Thành, Quận 1, TP.HCM • OSRM Route"
                } else {
                    userNavLat = 21.028511
                    userNavLng = 105.850100
                    tvNavLocationStatus.text = "Vị trí: Hồ Hoàn Kiếm, Hà Nội • OSRM Route"
                }
                loadNearbyBranchesOntoMap(
                    lat = userNavLat,
                    lng = userNavLng,
                    branchType = activeNavBranchTypeFilter,
                    defaultMode = activeNavTransportMode
                )
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    /**
     * Tải danh sách chi nhánh/ATM gần nhất, gắn Marker lên `osmMapView`
     * và tự gọi thẳng **OSRM Route Service** để vẽ đường đi tới chi nhánh gần nhất.
     */
    private fun loadNearbyBranchesOntoMap(
        lat: Double? = userNavLat,
        lng: Double? = userNavLng,
        fallbackCity: String = "HCM",
        branchType: String? = activeNavBranchTypeFilter,
        defaultMode: TransportMode = activeNavTransportMode
    ) {
        pbNavRouteLoading.visibility = View.VISIBLE
        NavigationApiClient.fetchNearbyBranches(
            lat = lat,
            lng = lng,
            fallbackCity = fallbackCity,
            radiusKm = 10.0,
            limit = 8,
            type = branchType
        ) { result ->
            pbNavRouteLoading.visibility = View.GONE
            result.onSuccess { response ->
                navMapLoadedOnce = true
                var resolvedUserLat = response.userLocation.latitude
                var resolvedUserLng = response.userLocation.longitude

                // Nếu tọa độ user trùng khít (< 30m) với chi nhánh đầu tiên do fallback mặc định của server,
                // dịch nhẹ điểm xuất phát về khu vực Chợ Bến Thành (Q.1) để hiển thị rõ tuyến đường OSRM trên bản đồ
                val firstBranch = response.nearestBranch ?: response.items.firstOrNull()
                if (firstBranch != null &&
                    firstBranch.calculateDistanceFrom(resolvedUserLat, resolvedUserLng) < 0.03
                ) {
                    if (resolvedUserLat < 16.0) {
                        resolvedUserLat = 10.772100
                        resolvedUserLng = 106.698300
                    } else {
                        resolvedUserLat = 21.028511
                        resolvedUserLng = 105.850100
                    }
                }
                userNavLat = resolvedUserLat
                userNavLng = resolvedUserLng

                val recalculatedItems = response.items.map { br ->
                    val distKm = br.calculateDistanceFrom(userNavLat, userNavLng)
                    val distMeters = (distKm * 1000.0).roundToInt()
                    val walkMins = (distMeters / 80.0).roundToInt().coerceAtLeast(1)
                    br.copy(
                        distanceKm = (distKm * 100.0).roundToInt() / 100.0,
                        distanceMeters = distMeters,
                        estimatedWalkingMinutes = walkMins
                    )
                }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
                    .mapIndexed { idx, br -> br.copy(isNearest = idx == 0) }

                nearbyBranches.clear()
                nearbyBranches.addAll(recalculatedItems)

                val nearest = nearbyBranches.firstOrNull()
                selectedBranchForMap = nearest

                renderNearbyBranchesCards()

                if (nearest != null) {
                    requestAndDrawOsrmRoute(nearest, defaultMode)
                } else {
                    tvNavSelectedBranchName.text = "Không tìm thấy chi nhánh / ATM phù hợp"
                    tvNavSelectedBranchAddress.text = "Hãy thử đổi bộ lọc hoặc khu vực tìm kiếm"
                    tvNavRouteMetrics.text = "-- km • -- phút"
                    tvNavRouteSteps.visibility = View.GONE
                    renderMapMarkersAndOsrmPolyline(null, null)
                }
            }.onFailure { err ->
                Toast.makeText(
                    this,
                    "Lỗi tải chi nhánh: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Tự gọi thẳng **OSRM Route Service** qua [NavigationApiClient.fetchDirectionsDebounced]
     * và vẽ `Polyline` đường đi thực tế lên bản đồ `osmdroid`.
     */
    private fun requestAndDrawOsrmRoute(branch: BankBranchDto, mode: TransportMode) {
        selectedBranchForMap = branch
        renderNearbyBranchesCards()
        pbNavRouteLoading.visibility = View.VISIBLE
        tvNavSelectedBranchName.text = "${if (branch.isNearest) "★ Gần nhất • " else ""}${branch.name} [${branch.branchType}]"
        tvNavSelectedBranchAddress.text = "${branch.address} • Giờ mở cửa: ${branch.openingHours ?: "08:00 - 17:00"}"
        tvNavRouteMetrics.text = "Đang lấy đường đi từ OSRM Route Service..."

        NavigationApiClient.fetchDirectionsDebounced(
            originLat = userNavLat,
            originLng = userNavLng,
            branchId = branch.branchId,
            destLat = branch.latitude,
            destLng = branch.longitude,
            mode = mode,
            debounceMs = 200L
        ) { result ->
            pbNavRouteLoading.visibility = View.GONE
            result.onSuccess { route ->
                currentNavRoute = route
                updateNavSummaryCard(branch, route, mode)
                renderMapMarkersAndOsrmPolyline(branch, route)
            }.onFailure { err ->
                tvNavRouteMetrics.text = "Không lấy được đường đi OSRM: ${err.message}"
                renderMapMarkersAndOsrmPolyline(branch, null)
            }
        }
    }

    private fun updateNavSummaryCard(
        branch: BankBranchDto,
        route: RouteProposalDto,
        mode: TransportMode
    ) {
        val nearestPrefix = if (branch.isNearest) "★ Gần nhất • " else ""
        tvNavSelectedBranchName.text = "$nearestPrefix${branch.name} [${branch.branchType}]"
        tvNavSelectedBranchAddress.text =
            "${branch.address}\nGiờ mở cửa: ${branch.openingHours ?: "08:00 - 17:00"} (${branch.status})"

        val sum = route.summary
        if (sum != null) {
            val modeText = route.modeLabel ?: "${mode.labelVi} (OSRM)"
            tvNavRouteMetrics.text = "${sum.distanceText} • ${sum.durationText} • $modeText"
        } else {
            val distText = branch.distanceKm?.let { "$it km" } ?: "-- km"
            tvNavRouteMetrics.text = "$distText • ${mode.labelVi}"
        }

        if (route.steps.isNotEmpty()) {
            tvNavRouteSteps.visibility = View.VISIBLE
            tvNavRouteSteps.text = buildString {
                appendLine("Lộ trình chi tiết (OSRM):")
                route.steps.take(6).forEach { step ->
                    appendLine("${step.stepNumber}. ${step.instruction} (${step.distanceText}, ${step.durationText})")
                }
            }.trim()
        } else {
            tvNavRouteSteps.visibility = View.GONE
        }
    }

    /**
     * Vẽ Markers (Vị trí người dùng + tất cả Chi nhánh/ATM) và đường `Polyline` từ OSRM Route Service
     * lên `org.osmdroid.views.MapView`.
     */
    private fun renderMapMarkersAndOsrmPolyline(
        activeBranch: BankBranchDto?,
        route: RouteProposalDto?
    ) {
        osmMapView.overlays.clear()

        val allBoundPoints = mutableListOf<GeoPoint>()
        val userGeoPoint = GeoPoint(userNavLat, userNavLng)
        allBoundPoints.add(userGeoPoint)

        // 1. Vẽ đường đi OSRM Polyline nếu có
        val pathPoints = route?.decodedPath?.map { GeoPoint(it.latitude, it.longitude) }.orEmpty()
        if (pathPoints.size >= 2) {
            allBoundPoints.addAll(pathPoints)

            // Đường viền trắng làm nổi bật lộ trình trên nền bản đồ OpenStreetMap
            val haloPolyline = Polyline(osmMapView).apply {
                setPoints(pathPoints)
                outlinePaint.color = Color.parseColor("#CCFFFFFF")
                outlinePaint.strokeWidth = dp(7).toFloat()
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            osmMapView.overlays.add(haloPolyline)

            // Đường lộ trình chính theo màu & kiểu (DOTTED cho đi bộ, SOLID cho xe máy/ô tô)
            val routeColor = try {
                Color.parseColor(route?.summary?.polylineColor ?: "#08755F")
            } catch (_: Exception) {
                Color.parseColor("#08755F")
            }
            val isDotted = route?.summary?.polylineStyle.equals("DOTTED", ignoreCase = true)

            val mainPolyline = Polyline(osmMapView).apply {
                setPoints(pathPoints)
                outlinePaint.color = routeColor
                outlinePaint.strokeWidth = dp(4).toFloat()
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                if (isDotted) {
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(22f, 16f), 0f)
                }
                title = route?.summary?.let { "${it.distanceText} • ${it.durationText}" } ?: "Lộ trình OSRM"
            }
            osmMapView.overlays.add(mainPolyline)
        } else if (activeBranch != null) {
            val branchPt = GeoPoint(activeBranch.latitude, activeBranch.longitude)
            allBoundPoints.add(branchPt)
            val fallbackLine = Polyline(osmMapView).apply {
                setPoints(listOf(userGeoPoint, branchPt))
                outlinePaint.color = Color.parseColor("#08755F")
                outlinePaint.strokeWidth = dp(4).toFloat()
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
            }
            osmMapView.overlays.add(fallbackLine)
        }

        // 2. Vẽ Marker vị trí của người dùng
        val userMarker = Marker(osmMapView).apply {
            position = userGeoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Vị trí của bạn"
            snippet = String.format(Locale.US, "%.5f, %.5f", userNavLat, userNavLng)
            icon = createCircleMarkerDrawable(
                fillColor = Color.parseColor("#2563EB"),
                label = "BẠN",
                sizeDp = 30
            )
        }
        osmMapView.overlays.add(userMarker)

        // 3. Vẽ Markers cho danh sách Chi nhánh & ATM
        for (branch in nearbyBranches) {
            val isSelected = activeBranch?.branchId == branch.branchId
            val branchPoint = GeoPoint(branch.latitude, branch.longitude)
            if (isSelected) {
                allBoundPoints.add(branchPoint)
            }

            val badgeText = when {
                isSelected -> "★"
                branch.branchType.equals("ATM", ignoreCase = true) -> "ATM"
                else -> "NH"
            }
            val pinColor = when {
                isSelected -> Color.parseColor("#E11D48")
                branch.branchType.equals("ATM", ignoreCase = true) -> Color.parseColor("#D97706")
                else -> Color.parseColor("#08755F")
            }

            val marker = Marker(osmMapView).apply {
                position = branchPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = branch.name
                snippet = "${branch.address} (${branch.distanceKm ?: "--"} km)"
                icon = createCircleMarkerDrawable(
                    fillColor = pinColor,
                    label = badgeText,
                    sizeDp = if (isSelected) 34 else 28
                )
                setOnMarkerClickListener { m, _ ->
                    m.showInfoWindow()
                    requestAndDrawOsrmRoute(branch, activeNavTransportMode)
                    true
                }
            }
            osmMapView.overlays.add(marker)
        }

        osmMapView.invalidate()

        // 4. Căn khung nhìn bản đồ an toàn (đảm bảo span tối thiểu để không bao giờ zoom quá mức gây lưới xám)
        osmMapView.post {
            try {
                var minLat = allBoundPoints.minOf { it.latitude }
                var maxLat = allBoundPoints.maxOf { it.latitude }
                var minLng = allBoundPoints.minOf { it.longitude }
                var maxLng = allBoundPoints.maxOf { it.longitude }

                val minSpanDeg = 0.005 // ~550m tối thiểu
                if (maxLat - minLat < minSpanDeg) {
                    val midLat = (minLat + maxLat) / 2.0
                    minLat = midLat - minSpanDeg / 2.0
                    maxLat = midLat + minSpanDeg / 2.0
                }
                if (maxLng - minLng < minSpanDeg) {
                    val midLng = (minLng + maxLng) / 2.0
                    minLng = midLng - minSpanDeg / 2.0
                    maxLng = midLng + minSpanDeg / 2.0
                }

                val latPad = (maxLat - minLat) * 0.22
                val lngPad = (maxLng - minLng) * 0.22
                val safeBbox = BoundingBox(
                    maxLat + latPad,
                    maxLng + lngPad,
                    minLat - latPad,
                    minLng - lngPad
                )
                osmMapView.zoomToBoundingBox(safeBbox, true, dp(24), 17.5, 400L)
            } catch (_: Exception) {
                osmMapView.controller.setZoom(15.5)
                osmMapView.controller.animateTo(userGeoPoint)
            }
        }
    }

    private fun createCircleMarkerDrawable(
        fillColor: Int,
        label: String,
        sizeDp: Int
    ): BitmapDrawable {
        val sizePx = dp(sizeDp).coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val radius = sizePx / 2f - dp(2)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius + dp(1), borderPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius, fillPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (label.length >= 3) dp(8).toFloat() else dp(10).toFloat()
        }
        val textOffset = (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, cy - textOffset, textPaint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun renderNearbyBranchesCards() {
        layoutNavBranchesList.removeAllViews()
        for (branch in nearbyBranches) {
            val isSelected = selectedBranchForMap?.branchId == branch.branchId
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(
                    if (isSelected) R.drawable.bg_sheet_tab_active else R.drawable.bg_utility_card
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    requestAndDrawOsrmRoute(branch, activeNavTransportMode)
                }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = dp(8)
                }
            }

            val nearestTag = if (branch.isNearest) "★ Gần nhất • " else ""
            val titleTv = TextView(this).apply {
                text = "$nearestTag${branch.name} [${branch.branchType}]"
                setTextColor(Color.parseColor(if (isSelected) "#08755F" else "#18352F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
            }

            val distStr = branch.distanceKm?.let { "$it km" } ?: ""
            val walkStr = branch.estimatedWalkingMinutes?.let { " (~${it}p đi bộ)" } ?: ""
            val subTv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                }
                text = "${branch.address}\n${distStr}${walkStr} • ${branch.openingHours ?: "08:00 - 17:00"}"
                setTextColor(Color.parseColor("#647773"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            }

            infoCol.addView(titleTv)
            infoCol.addView(subTv)

            val actionBtn = TextView(this).apply {
                text = if (isSelected) "Đang dẫn" else "Chỉ đường"
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#08755F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(
                    if (isSelected) R.drawable.bg_primary_button else R.drawable.bg_estimate_box
                )
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }

            card.addView(infoCol)
            card.addView(actionBtn)
            layoutNavBranchesList.addView(card)
        }
    }

    private fun setupFlightSearchTool() {
        flightCatalogAdapter = FlightCatalogAdapter { selectedFlight ->
            holdAndPayCatalogItem(selectedFlight)
        }
        rvFlightResults.layoutManager = LinearLayoutManager(this)
        rvFlightResults.adapter = flightCatalogAdapter

        // Khởi tạo ngày bay mặc định (hôm nay theo format YYYY-MM-DD)
        val cal = Calendar.getInstance()
        selectedFlightDate = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        tvFlightDate.text = selectedFlightDate

        // Thiết lập AutoCompleteTextView & hộp chọn sân bay IATA cho Điểm đi & Điểm đến
        val airportLabels = iataAirports.map { it.displayLabel }
        val fromAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, airportLabels)
        val toAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, airportLabels)

        actvFlightFrom.setAdapter(fromAdapter)
        actvFlightTo.setAdapter(toAdapter)

        actvFlightFrom.setOnItemClickListener { _, _, position, _ ->
            val selectedLabel = fromAdapter.getItem(position).orEmpty()
            val code = parseIataCode(selectedLabel)
            actvFlightFrom.setText(code, false)
            actvFlightFrom.setSelection(code.length)
            updateAirportCitySubtitle(code, tvFlightFromCity)
        }

        actvFlightTo.setOnItemClickListener { _, _, position, _ ->
            val selectedLabel = toAdapter.getItem(position).orEmpty()
            val code = parseIataCode(selectedLabel)
            actvFlightTo.setText(code, false)
            actvFlightTo.setSelection(code.length)
            updateAirportCitySubtitle(code, tvFlightToCity)
        }

        actvFlightFrom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAirportCitySubtitle(parseIataCode(s?.toString()), tvFlightFromCity)
            }
        })

        actvFlightTo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAirportCitySubtitle(parseIataCode(s?.toString()), tvFlightToCity)
            }
        })

        findViewById<View>(R.id.btnPickFromAirport).setOnClickListener {
            showAirportPickerDialog("Chọn sân bay đi (IATA)") { airport ->
                actvFlightFrom.setText(airport.code, false)
                updateAirportCitySubtitle(airport.code, tvFlightFromCity)
            }
        }
        tvFlightFromCity.setOnClickListener {
            showAirportPickerDialog("Chọn sân bay đi (IATA)") { airport ->
                actvFlightFrom.setText(airport.code, false)
                updateAirportCitySubtitle(airport.code, tvFlightFromCity)
            }
        }

        findViewById<View>(R.id.btnPickToAirport).setOnClickListener {
            showAirportPickerDialog("Chọn sân bay đến (IATA)") { airport ->
                actvFlightTo.setText(airport.code, false)
                updateAirportCitySubtitle(airport.code, tvFlightToCity)
            }
        }
        tvFlightToCity.setOnClickListener {
            showAirportPickerDialog("Chọn sân bay đến (IATA)") { airport ->
                actvFlightTo.setText(airport.code, false)
                updateAirportCitySubtitle(airport.code, tvFlightToCity)
            }
        }

        // Nút đảo chiều Điểm đi <-> Điểm đến
        findViewById<View>(R.id.btnSwapFlightRoute).setOnClickListener {
            val currentFrom = parseIataCode(actvFlightFrom.text?.toString())
            val currentTo = parseIataCode(actvFlightTo.text?.toString())
            actvFlightFrom.setText(currentTo, false)
            actvFlightTo.setText(currentFrom, false)
            updateAirportCitySubtitle(currentTo, tvFlightFromCity)
            updateAirportCitySubtitle(currentFrom, tvFlightToCity)
        }

        // Chọn ngày bay bằng DatePicker (format YYYY-MM-DD)
        btnPickFlightDate.setOnClickListener {
            showFlightDatePicker()
        }
        tvFlightDate.setOnClickListener {
            showFlightDatePicker()
        }

        // Nút Tìm chuyến bay
        btnSearchFlights.setOnClickListener {
            performFlightSearch()
        }

        // Bộ lọc khung giờ bay: Tất cả / Sáng / Chiều / Tối
        btnFlightFilterAll.setOnClickListener {
            setFlightTimeFilter(TIME_FILTER_ALL)
        }
        btnFlightFilterMorning.setOnClickListener {
            setFlightTimeFilter(TIME_FILTER_MORNING)
        }
        btnFlightFilterAfternoon.setOnClickListener {
            setFlightTimeFilter(TIME_FILTER_AFTERNOON)
        }
        btnFlightFilterEvening.setOnClickListener {
            setFlightTimeFilter(TIME_FILTER_EVENING)
        }
    }

    private fun parseIataCode(rawInput: String?): String {
        val cleaned = rawInput?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (cleaned.isEmpty()) return ""
        val token = cleaned.substringBefore(" ").substringBefore("-").trim()
        return token.take(3)
    }

    private fun updateAirportCitySubtitle(iataCode: String, targetView: TextView) {
        val matched = iataAirports.find { it.code.equals(iataCode, ignoreCase = true) }
        targetView.text = when {
            matched != null -> matched.city
            iataCode.length == 3 -> "Mã IATA: $iataCode"
            else -> "Chọn mã IATA"
        }
    }

    private fun showAirportPickerDialog(title: String, onPicked: (IataAirport) -> Unit) {
        val labels = iataAirports.map { it.displayLabel }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                onPicked(iataAirports[which])
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showFlightDatePicker() {
        val parts = selectedFlightDate.split("-")
        val cal = Calendar.getInstance()
        val initYear = parts.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.YEAR)
        val initMonth = (parts.getOrNull(1)?.toIntOrNull() ?: (cal.get(Calendar.MONTH) + 1)) - 1
        val initDay = parts.getOrNull(2)?.toIntOrNull() ?: cal.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(
            this,
            { _, year, monthOfYear, dayOfMonth ->
                selectedFlightDate = String.format(
                    Locale.US,
                    "%04d-%02d-%02d",
                    year,
                    monthOfYear + 1,
                    dayOfMonth
                )
                tvFlightDate.text = selectedFlightDate
            },
            initYear,
            initMonth,
            initDay
        ).show()
    }

    private fun performFlightSearch() {
        val fromCode = parseIataCode(actvFlightFrom.text?.toString()).ifBlank { "SGN" }
        val toCode = parseIataCode(actvFlightTo.text?.toString()).ifBlank { "HAN" }
        val dateStr = tvFlightDate.text?.toString()?.trim().orEmpty().ifBlank { selectedFlightDate }

        actvFlightFrom.setText(fromCode, false)
        actvFlightTo.setText(toCode, false)
        updateAirportCitySubtitle(fromCode, tvFlightFromCity)
        updateAirportCitySubtitle(toCode, tvFlightToCity)

        if (fromCode.length < 3 || toCode.length < 3) {
            Toast.makeText(this, "Vui lòng chọn mã sân bay IATA hợp lệ (VD: SGN, HAN, DAD)", Toast.LENGTH_SHORT).show()
            return
        }
        if (fromCode == toCode) {
            Toast.makeText(this, "Điểm đi và điểm đến không được trùng nhau ($fromCode)", Toast.LENGTH_SHORT).show()
            return
        }

        if (sheetOpen && !sheetFull) {
            setSheetFullMode(true)
        }

        // Hiển thị trạng thái loading trong lúc chờ kết quả từ Server
        layoutFlightLoading.visibility = View.VISIBLE
        tvFlightLoadingText.text = "Đang tìm chuyến bay $fromCode → $toCode ($dateStr)..."
        btnSearchFlights.isEnabled = false
        btnSearchFlights.alpha = 0.65f
        btnSearchFlights.text = "Đang tìm chuyến bay..."
        tvFlightEmptyState.visibility = View.GONE
        tvFlightResultSummary.visibility = View.GONE
        rvFlightResults.visibility = View.GONE

        UtilitiesApiClient.fetchCatalog(
            serviceType = "FLIGHT",
            from = fromCode,
            to = toCode,
            date = dateStr
        ) { result ->
            layoutFlightLoading.visibility = View.GONE
            btnSearchFlights.isEnabled = true
            btnSearchFlights.alpha = 1f
            btnSearchFlights.text = "Tìm chuyến bay"

            result.onSuccess { items ->
                lastSearchedFromIata = fromCode
                lastSearchedToIata = toCode
                lastSearchedFlightDate = dateStr

                allFetchedFlights.clear()
                allFetchedFlights.addAll(items)

                activeFlightTimeFilter = TIME_FILTER_ALL
                layoutFlightTimeFilter.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
                applyFlightTimeFilter()
            }.onFailure { err ->
                allFetchedFlights.clear()
                layoutFlightTimeFilter.visibility = View.GONE
                rvFlightResults.visibility = View.GONE
                tvFlightResultSummary.visibility = View.GONE
                tvFlightEmptyState.visibility = View.VISIBLE
                tvFlightEmptyState.text = "Lỗi tìm chuyến bay ($fromCode → $toCode): ${err.message}"
                Toast.makeText(
                    this,
                    "Không lấy được dữ liệu chuyến bay: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setFlightTimeFilter(filter: String) {
        activeFlightTimeFilter = filter
        applyFlightTimeFilter()
    }

    private fun applyFlightTimeFilter() {
        val activeColor = Color.parseColor("#08765F")
        val inactiveColor = Color.parseColor("#788985")

        val morningCount = allFetchedFlights.count { FlightCatalogAdapter.extractDepartureHour(it) in 0..11 }
        val afternoonCount = allFetchedFlights.count { FlightCatalogAdapter.extractDepartureHour(it) in 12..17 }
        val eveningCount = allFetchedFlights.count { FlightCatalogAdapter.extractDepartureHour(it) in 18..23 }

        btnFlightFilterAll.text = "Tất cả (${allFetchedFlights.size})"
        btnFlightFilterMorning.text = "Sáng ($morningCount)"
        btnFlightFilterAfternoon.text = "Chiều ($afternoonCount)"
        btnFlightFilterEvening.text = "Tối ($eveningCount)"

        val filterButtons = listOf(
            btnFlightFilterAll to TIME_FILTER_ALL,
            btnFlightFilterMorning to TIME_FILTER_MORNING,
            btnFlightFilterAfternoon to TIME_FILTER_AFTERNOON,
            btnFlightFilterEvening to TIME_FILTER_EVENING
        )
        for ((btn, mode) in filterButtons) {
            val selected = mode == activeFlightTimeFilter
            btn.setBackgroundResource(
                if (selected) R.drawable.bg_sheet_tab_active else R.drawable.bg_sheet_tab_inactive
            )
            btn.setTextColor(if (selected) activeColor else inactiveColor)
            btn.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        val filteredList = when (activeFlightTimeFilter) {
            TIME_FILTER_MORNING -> allFetchedFlights.filter {
                FlightCatalogAdapter.extractDepartureHour(it) in 0..11
            }
            TIME_FILTER_AFTERNOON -> allFetchedFlights.filter {
                FlightCatalogAdapter.extractDepartureHour(it) in 12..17
            }
            TIME_FILTER_EVENING -> allFetchedFlights.filter {
                FlightCatalogAdapter.extractDepartureHour(it) in 18..23
            }
            else -> allFetchedFlights.toList()
        }

        flightCatalogAdapter.submitList(
            newItems = filteredList,
            fromIata = lastSearchedFromIata,
            toIata = lastSearchedToIata,
            date = lastSearchedFlightDate
        )

        if (filteredList.isEmpty()) {
            rvFlightResults.visibility = View.GONE
            tvFlightResultSummary.visibility = View.GONE
            tvFlightEmptyState.visibility = View.VISIBLE
            tvFlightEmptyState.text = if (allFetchedFlights.isEmpty()) {
                "Không tìm thấy chuyến bay nào cho chặng $lastSearchedFromIata → $lastSearchedToIata ngày $lastSearchedFlightDate."
            } else {
                "Không có chuyến bay nào trong khung giờ đã chọn."
            }
        } else {
            tvFlightEmptyState.visibility = View.GONE
            tvFlightResultSummary.visibility = View.VISIBLE
            tvFlightResultSummary.text =
                "Tìm thấy ${filteredList.size} chuyến bay • $lastSearchedFromIata → $lastSearchedToIata ($lastSearchedFlightDate)"
            rvFlightResults.visibility = View.VISIBLE
        }
    }

    /**
     * Tra cứu hóa đơn Điện / Nước từ bảng `dbo.utility_orders` & `dbo.utility_services` qua API Server
     * và hỗ trợ thanh toán (khởi tạo OTP -> xác nhận trừ tiền).
     */
    private fun showBillLookupAndPaymentDialog(title: String, billType: String) {
        val defaultCustomerCode = if (billType == "WATER") "SW987654" else "PE123456"
        val defaultProvider = if (billType == "WATER") "SAWACO" else "EVN_HCM"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val unpaidSummary = dbUnpaidBills.filter {
            title == TOOL_BILL || it.billType.equals(billType, ignoreCase = true)
        }
        val tvDbInfo = TextView(this).apply {
            text = if (unpaidSummary.isNotEmpty()) {
                "Hóa đơn chưa thanh toán trong Database:\n" + unpaidSummary.joinToString("\n") {
                    "• [${it.providerCode}] Mã KH: ${it.providerCustomerId} — ${vndFormatter.format(it.amount.roundToLong())} ₫ (${it.billingPeriod})"
                }
            } else {
                "Nhập mã khách hàng (VD: PE123456 hoặc SW987654) để truy vấn từ API Server:"
            }
            setTextColor(Color.parseColor("#294740"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }

        val etCustomerCode = EditText(this).apply {
            hint = "Mã khách hàng (VD: $defaultCustomerCode)"
            setText(unpaidSummary.firstOrNull()?.providerCustomerId ?: defaultCustomerCode)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }

        container.addView(tvDbInfo)
        container.addView(etCustomerCode)

        AlertDialog.Builder(this)
            .setTitle("$title (API Database)")
            .setView(container)
            .setPositiveButton("Tra cứu & Thanh toán") { _, _ ->
                val customerCode = etCustomerCode.text?.toString()?.trim().orEmpty().ifEmpty { defaultCustomerCode }
                val resolvedType = if (customerCode.uppercase().startsWith("SW")) "WATER" else billType
                val resolvedProvider = if (resolvedType == "WATER") "SAWACO" else defaultProvider

                UtilitiesApiClient.lookupBill(
                    billType = resolvedType,
                    providerCustomerId = customerCode,
                    providerCode = resolvedProvider
                ) { result ->
                    result.onSuccess { bill ->
                        showConfirmPayBillDialog(bill)
                    }.onFailure { err ->
                        Toast.makeText(this, "Lỗi tra cứu hóa đơn: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("DS Dịch vụ DB") { _, _ ->
                showAllDbServicesDialog(title)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showConfirmPayBillDialog(bill: UtilityBillDto) {
        val message = buildString {
            appendLine("Mã hóa đơn (order_id): ${bill.orderId}")
            appendLine("Nhà cung cấp: ${bill.providerCode}")
            appendLine("Mã khách hàng: ${bill.providerCustomerId}")
            appendLine("Khách hàng: ${bill.customerName}")
            appendLine("Địa chỉ: ${bill.customerAddress}")
            appendLine("Kỳ cước: ${bill.billingPeriod}")
            appendLine("Số tiền: ${vndFormatter.format(bill.amount.roundToLong())} ₫")
            append("Trạng thái: ${bill.status}")
        }

        AlertDialog.Builder(this)
            .setTitle("Kết quả truy vấn Hóa đơn từ DB")
            .setMessage(message)
            .setPositiveButton("Thanh toán ngay") { _, _ ->
                UtilitiesApiClient.initiatePayment(
                    txnType = "BILL",
                    billId = bill.billId,
                    orderId = bill.orderId
                ) { initRes ->
                    initRes.onSuccess { paymentInit ->
                        showOtpConfirmDialog(
                            txnCode = paymentInit.txnCode,
                            amount = paymentInit.amount,
                            otpHint = paymentInit.devOtpHint
                        )
                    }.onFailure { err ->
                        Toast.makeText(this, "Không thể khởi tạo thanh toán: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showPhoneTopupDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val etPhone = EditText(this).apply {
            hint = "Số điện thoại (VD: 0987654321)"
            setText("0987654321")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val etAmount = EditText(this).apply {
            hint = "Mệnh giá nạp (VD: 100000)"
            setText("100000")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        container.addView(etPhone)
        container.addView(etAmount)

        AlertDialog.Builder(this)
            .setTitle("Nạp tiền điện thoại (API Server)")
            .setMessage("Tự động nhận diện nhà mạng (Viettel, VinaPhone, MobiFone) và tạo đơn nạp tiền trong bảng utility_orders:")
            .setView(container)
            .setPositiveButton("Kiểm tra & Nạp") { _, _ ->
                val phone = etPhone.text?.toString()?.trim().orEmpty().ifEmpty { "0987654321" }
                val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull() ?: 100000.0
                UtilitiesApiClient.detectCarrier(phone) { carrierRes ->
                    carrierRes.onSuccess { carrier ->
                        UtilitiesApiClient.lookupBill(
                            billType = "PHONE_TOPUP",
                            providerCustomerId = carrier.phoneNumber,
                            providerCode = carrier.carrier,
                            topupAmount = amount
                        ) { lookupRes ->
                            lookupRes.onSuccess { bill ->
                                showConfirmPayBillDialog(bill)
                            }.onFailure { err ->
                                Toast.makeText(this, "Lỗi tạo đơn nạp: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.onFailure { err ->
                        Toast.makeText(this, "Lỗi nhận diện nhà mạng: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showCatalogFromApiDialog(title: String, serviceType: String) {
        UtilitiesApiClient.fetchCatalog(serviceType = serviceType) { result ->
            result.onSuccess { items ->
                if (items.isEmpty()) {
                    Toast.makeText(this, "Không có dữ liệu danh mục cho $serviceType", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                val labels = items.map { item ->
                    "${item.title}\n[${item.providerCode}] Giá: ${vndFormatter.format(item.price.roundToLong())} ${item.currency}"
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("$title — Danh mục từ Server")
                    .setItems(labels) { _, which ->
                        val chosen: CatalogItemDto = items[which]
                        holdAndPayCatalogItem(chosen)
                    }
                    .setNeutralButton("Xem DB Services") { _, _ ->
                        showAllDbServicesDialog(title)
                    }
                    .setNegativeButton("Đóng", null)
                    .show()
            }.onFailure { err ->
                Toast.makeText(this, "Lỗi tải danh mục $serviceType: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun holdAndPayCatalogItem(item: CatalogItemDto) {
        val bookingDetails = JSONObject().apply {
            if (item.serviceType.equals("MOVIE", ignoreCase = true)) {
                put("showtime", "19:30")
                put("seats", org.json.JSONArray().put("F1").put("F2"))
            } else if (item.serviceType.equals("FLIGHT", ignoreCase = true)) {
                put("airline", FlightCatalogAdapter.extractAirlineName(item))
                put("flight_number", FlightCatalogAdapter.extractFlightCode(item))
                put("from", FlightCatalogAdapter.extractFromIata(item, lastSearchedFromIata))
                put("to", FlightCatalogAdapter.extractToIata(item, lastSearchedToIata))
                put("date", FlightCatalogAdapter.extractDate(item, lastSearchedFlightDate))
                put("departure_time", FlightCatalogAdapter.extractDepartureTime(item))
                put("arrival_time", FlightCatalogAdapter.extractArrivalTime(item))
            }
        }
        UtilitiesApiClient.holdUtilityOrder(
            serviceType = item.serviceType,
            catalogItemId = item.catalogItemId,
            quantity = 1,
            bookingDetails = bookingDetails
        ) { holdRes ->
            holdRes.onSuccess { held ->
                AlertDialog.Builder(this)
                    .setTitle("Đã giữ chỗ trong utility_orders")
                    .setMessage(
                        "Mã đơn (order_id): ${held.orderId}\n" +
                            "Dịch vụ: ${held.title}\n" +
                            "Tổng tiền: ${vndFormatter.format(held.totalAmount.roundToLong())} ₫\n" +
                            "Trạng thái: ${held.status}\n" +
                            "Giữ chỗ đến: ${held.holdExpiresAt ?: "15 phút"}"
                    )
                    .setPositiveButton("Thanh toán") { _, _ ->
                        val bookingPayload = JSONObject().apply {
                            put("service_type", item.serviceType)
                            put("catalog_item_id", item.catalogItemId)
                            put("total_amount", held.totalAmount)
                            put("booking_details", bookingDetails)
                        }
                        UtilitiesApiClient.initiatePayment(
                            txnType = "UTILITY",
                            orderId = held.orderId,
                            bookingPayload = bookingPayload
                        ) { payRes ->
                            payRes.onSuccess { initData ->
                                showOtpConfirmDialog(
                                    txnCode = initData.txnCode,
                                    amount = initData.amount,
                                    otpHint = initData.devOtpHint
                                )
                            }.onFailure { err ->
                                Toast.makeText(this, "Lỗi tạo thanh toán: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Đóng") { _, _ ->
                        loadUtilitiesFromDatabase()
                    }
                    .show()
            }.onFailure { err ->
                Toast.makeText(this, "Lỗi giữ chỗ: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showOtpConfirmDialog(txnCode: String, amount: Double, otpHint: String?) {
        val etOtp = EditText(this).apply {
            hint = "Nhập mã OTP 6 số"
            setText(otpHint.orEmpty())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(etOtp)
        }

        AlertDialog.Builder(this)
            .setTitle("Xác thực OTP Giao dịch")
            .setMessage(
                "Mã giao dịch: $txnCode\n" +
                    "Số tiền: ${vndFormatter.format(amount.roundToLong())} ₫\n" +
                    (if (!otpHint.isNullOrBlank()) "(Mã OTP dev từ Server: $otpHint)" else "")
            )
            .setView(container)
            .setPositiveButton("Xác nhận") { _, _ ->
                val otp = etOtp.text?.toString()?.trim().orEmpty()
                UtilitiesApiClient.confirmPayment(
                    txnCode = txnCode,
                    otpCode = otp,
                    biometricToken = "BIO_VERIFIED_TOKEN"
                ) { confirmRes ->
                    confirmRes.onSuccess { res ->
                        loadUtilitiesFromDatabase()
                        AlertDialog.Builder(this)
                            .setTitle("Thanh toán thành công!")
                            .setMessage(
                                "Mã GD: ${res.txnCode}\n" +
                                    "Trạng thái: ${res.status}\n" +
                                    "Số tiền: ${vndFormatter.format(res.amount.roundToLong())} ₫\n" +
                                    (res.balanceAfter?.let { "Số dư mới: ${vndFormatter.format(it.roundToLong())} ₫" } ?: "")
                            )
                            .setPositiveButton("Hoàn tất", null)
                            .show()
                    }.onFailure { err ->
                        Toast.makeText(this, "Xác nhận OTP thất bại: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showAllDbServicesDialog(title: String) {
        UtilitiesApiClient.fetchUtilityServices { result ->
            result.onSuccess { services ->
                dbServices.clear()
                dbServices.addAll(services)
                val msg = buildString {
                    appendLine("Bảng dbo.utility_services (${services.size} dịch vụ):")
                    services.forEachIndexed { idx, s ->
                        appendLine("${idx + 1}. [${s.serviceType}] ${s.serviceName} (${s.providerCode}) - Cổng: ${s.aggregatorGateway} - ${s.status}")
                    }
                    if (dbOrders.isNotEmpty()) {
                        appendLine("\nBảng dbo.utility_orders (${dbOrders.size} đơn hàng):")
                        dbOrders.take(5).forEach { o ->
                            appendLine("• ${o.providerCustomerId}: ${vndFormatter.format(o.totalAmount.roundToLong())} ₫ [${o.status}]")
                        }
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("Dữ liệu Utilities từ Database")
                    .setMessage(msg)
                    .setPositiveButton("Làm mới DB") { _, _ ->
                        loadUtilitiesFromDatabase(showToast = true)
                    }
                    .setNegativeButton("Đóng", null)
                    .show()
            }.onFailure { err ->
                Toast.makeText(
                    this,
                    "Chưa kết nối được API Server (${UtilitiesApiClient.baseUrl}): ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupLoanCalculator() {
        updateLoanEstimate()

        seekBarYears.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                years = (progress + 1).coerceIn(1, 25)
                tvLoanYears.text = "$years năm"
                updateLoanEstimate()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        etLoanAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateLoanEstimate()
            }
        })
    }

    private fun updateLoanEstimate() {
        val rawDigits = etLoanAmount.text?.toString()?.replace(Regex("[^0-9]"), "") ?: ""
        val principal = rawDigits.toDoubleOrNull()?.takeIf { it > 0 } ?: 500_000_000.0
        val monthly = ((principal / (years * 12.0)) * 1.46).roundToLong()
        tvLoanEstimate.text = "${vndFormatter.format(monthly)} ₫"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun getSelectableItemDrawable() = TypedValue().let { outValue ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        ContextCompat.getDrawable(this, outValue.resourceId)
    }

    private data class NavTab(
        val container: View,
        val indicator: View,
        val icon: ImageView,
        val label: TextView,
        val name: String
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        ) {
            resolveDeviceLocationOrPickCity()
        }
    }

    companion object {
        private const val TOOL_LOAN = "Tính khoản vay"
        private const val TOOL_FX = "Đổi ngoại tệ"
        private const val TOOL_BILL = "Nhắc hóa đơn"
        private const val TOOL_NOTE = "Ghi chú nhanh"
        private const val TOOL_FLIGHT = "Đặt vé máy bay"
        private const val TOOL_NAVIGATION = "Chi nhánh & ATM"

        private const val TIME_FILTER_ALL = "ALL"
        private const val TIME_FILTER_MORNING = "MORNING"
        private const val TIME_FILTER_AFTERNOON = "AFTERNOON"
        private const val TIME_FILTER_EVENING = "EVENING"

        private const val REQ_LOCATION_PERMISSION = 1001
    }
}
