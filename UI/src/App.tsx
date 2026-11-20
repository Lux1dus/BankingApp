import {
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from "react";

type IconName =
  | "arrow-down"
  | "arrow-up"
  | "bell"
  | "bill"
  | "calculator"
  | "chart"
  | "check"
  | "chevron"
  | "close"
  | "eye"
  | "grid"
  | "home"
  | "more"
  | "note"
  | "plus"
  | "scan"
  | "send"
  | "shield"
  | "sparkle"
  | "tools"
  | "transfer"
  | "wallet";

const paths: Record<IconName, ReactNode> = {
  "arrow-down": (
    <>
      <path d="M12 4v15M6.5 13.5 12 19l5.5-5.5" />
    </>
  ),
  "arrow-up": (
    <>
      <path d="M12 20V5M6.5 10.5 12 5l5.5 5.5" />
    </>
  ),
  bell: (
    <>
      <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
      <path d="M10 21h4" />
    </>
  ),
  bill: (
    <>
      <path d="M7 3h10a2 2 0 0 1 2 2v16l-3-2-4 2-4-2-3 2V5a2 2 0 0 1 2-2Z" />
      <path d="M9 8h6M9 12h6" />
    </>
  ),
  calculator: (
    <>
      <rect x="4" y="2" width="16" height="20" rx="3" />
      <path d="M8 6h8v3H8zM8 13h.01M12 13h.01M16 13h.01M8 17h.01M12 17h.01M16 17h.01" />
    </>
  ),
  chart: (
    <>
      <path d="M4 19V9M10 19V5M16 19v-7M22 19H2" />
    </>
  ),
  check: <path d="m5 12 4 4L19 6" />,
  chevron: <path d="m8 10 4 4 4-4" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  eye: (
    <>
      <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z" />
      <circle cx="12" cy="12" r="2.5" />
    </>
  ),
  grid: (
    <>
      <rect x="3" y="3" width="7" height="7" rx="2" />
      <rect x="14" y="3" width="7" height="7" rx="2" />
      <rect x="3" y="14" width="7" height="7" rx="2" />
      <rect x="14" y="14" width="7" height="7" rx="2" />
    </>
  ),
  home: (
    <>
      <path d="m3 11 9-8 9 8" />
      <path d="M5 10v10h14V10M9 20v-6h6v6" />
    </>
  ),
  more: (
    <>
      <circle cx="5" cy="12" r="1" />
      <circle cx="12" cy="12" r="1" />
      <circle cx="19" cy="12" r="1" />
    </>
  ),
  note: (
    <>
      <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z" />
      <path d="M14 3v6h6M8 13h8M8 17h6" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  scan: (
    <>
      <path d="M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3" />
      <path d="M7 12h10" />
    </>
  ),
  send: (
    <>
      <path d="m22 2-7 20-4-9-9-4Z" />
      <path d="M22 2 11 13" />
    </>
  ),
  shield: (
    <>
      <path d="M12 22s8-3 8-10V5l-8-3-8 3v7c0 7 8 10 8 10Z" />
      <path d="m9 12 2 2 4-4" />
    </>
  ),
  sparkle: (
    <>
      <path d="m12 3 1.3 3.7L17 8l-3.7 1.3L12 13l-1.3-3.7L7 8l3.7-1.3L12 3Z" />
      <path d="m19 14 .8 2.2L22 17l-2.2.8L19 20l-.8-2.2L16 17l2.2-.8L19 14Z" />
    </>
  ),
  tools: (
    <>
      <path d="M14.7 6.3a4 4 0 0 0-5-5L12 3.6 9.6 6 7.3 3.7a4 4 0 0 0 5 5l7 7a2.1 2.1 0 0 1-3 3l-7-7" />
      <path d="m5 13-3 3 4 4 3-3" />
    </>
  ),
  transfer: (
    <>
      <path d="M4 8h13l-3-3M20 16H7l3 3" />
    </>
  ),
  wallet: (
    <>
      <path d="M4 5h14a2 2 0 0 1 2 2v13H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12" />
      <path d="M20 10h-5a2 2 0 0 0 0 4h5M15 12h.01" />
    </>
  ),
};

function Icon({
  name,
  size = 20,
  className = "",
}: {
  name: IconName;
  size?: number;
  className?: string;
}) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name]}
    </svg>
  );
}

type UtilityItem = { icon: IconName; title: string; subtitle: string; tone: string };

const initialUtilityItems: UtilityItem[] = [
  { icon: "calculator", title: "Tính khoản vay", subtitle: "Ước tính trả góp", tone: "mint" },
  { icon: "chart", title: "Đổi ngoại tệ", subtitle: "Tỷ giá trực tiếp", tone: "blue" },
  { icon: "bill", title: "Nhắc hóa đơn", subtitle: "3 lịch sắp tới", tone: "amber" },
  { icon: "note", title: "Ghi chú nhanh", subtitle: "Lưu việc cần làm", tone: "violet" },
];

const upcomingUtilityPool: UtilityItem[] = [
  { icon: "bill", title: "Thanh toán điện", subtitle: "Hóa đơn EVN tự động", tone: "amber" },
  { icon: "bill", title: "Thanh toán nước", subtitle: "Cấp nước SAWACO", tone: "blue" },
  { icon: "transfer", title: "Nạp điện thoại", subtitle: "Viettel, Vina, Mobi", tone: "mint" },
  { icon: "send", title: "Mua vé máy bay", subtitle: "Giữ chỗ nhanh 15p", tone: "violet" },
  { icon: "sparkle", title: "Vé xem phim CGV", subtitle: "Chọn ghế trực tuyến", tone: "amber" },
  { icon: "home", title: "Đặt khách sạn", subtitle: "Ưu đãi Mường Thanh", tone: "blue" },
];

function App() {
  const [utilityItems, setUtilityItems] = useState<UtilityItem[]>(initialUtilityItems);
  const [balanceVisible, setBalanceVisible] = useState(true);
  const [accordionOpen, setAccordionOpen] = useState(false);
  const [speedDialOpen, setSpeedDialOpen] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [sheetFull, setSheetFull] = useState(false);
  const [activeTool, setActiveTool] = useState("Tính khoản vay");
  const [years, setYears] = useState(10);
  const [activeNav, setActiveNav] = useState("Trang chủ");
  const dragStart = useRef(0);

  const addNewUtility = () => {
    setUtilityItems((prev) => {
      const nextIndex = prev.length - initialUtilityItems.length;
      const nextItem =
        upcomingUtilityPool[nextIndex] ?? {
          icon: "sparkle",
          title: `Tiện ích #${prev.length + 1}`,
          subtitle: "Công cụ mới cập nhật",
          tone: ["mint", "blue", "amber", "violet"][prev.length % 4],
        };
      return [...prev, nextItem];
    });
  };

  const openSheet = (tool?: string) => {
    if (tool) setActiveTool(tool);
    setSheetOpen(true);
    setSpeedDialOpen(false);
  };

  const startDrag = (event: ReactPointerEvent) => {
    dragStart.current = event.clientY;
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const endDrag = (event: ReactPointerEvent) => {
    const distance = event.clientY - dragStart.current;
    if (distance < -55) setSheetFull(true);
    if (distance > 70 && sheetFull) setSheetFull(false);
    if (distance > 110 && !sheetFull) setSheetOpen(false);
  };

  useEffect(() => {
    document.body.style.overflow = sheetOpen ? "hidden" : "";
    return () => {
      document.body.style.overflow = "";
    };
  }, [sheetOpen]);

  return (
    <div className="stage">
      <div className="phone-app">
        <header className="app-header">
          <a className="brand" href="#" aria-label="Nexa Bank">
            <span className="brand-mark">
              <i />
              <i />
              <i />
            </span>
            <strong>NEXA</strong>
          </a>
          <div className="header-actions">
            <button aria-label="Thông báo" className="notification">
              <Icon name="bell" size={21} />
              <i />
            </button>
            <button className="avatar" aria-label="Hồ sơ Minh Anh">
              MA
            </button>
          </div>
        </header>

        <main>
          <section className="greeting">
            <span>THỨ TƯ, 28 THÁNG 5</span>
            <h1>Chào buổi sáng, Minh Anh</h1>
            <p>Tài chính của bạn đang ở trạng thái tốt.</p>
          </section>

          <section className="balance-card">
            <div className="balance-top">
              <span>
                <Icon name="wallet" size={18} /> Tài khoản thanh toán
              </span>
              <span className="protected">
                <Icon name="shield" size={13} /> Bảo mật
              </span>
            </div>
            <div className="balance-label">
              Số dư khả dụng{" "}
              <button
                onClick={() => setBalanceVisible(!balanceVisible)}
                aria-label="Ẩn hoặc hiện số dư"
              >
                <Icon name="eye" size={17} />
              </button>
            </div>
            <h2>{balanceVisible ? "186.240.500 ₫" : "••••••••••"}</h2>
            <div className="balance-meta">
              <span>
                <Icon name="arrow-up" size={12} /> 8,4%
              </span>{" "}
              so với tháng trước
            </div>
            <div className="quick-actions">
              <button>
                <span>
                  <Icon name="transfer" size={20} />
                </span>
                Chuyển tiền
              </button>
              <button>
                <span>
                  <Icon name="arrow-down" size={20} />
                </span>
                Nạp tiền
              </button>
              <button>
                <span>
                  <Icon name="scan" size={20} />
                </span>
                Quét QR
              </button>
              <button>
                <span>
                  <Icon name="more" size={20} />
                </span>
                Thêm
              </button>
            </div>
          </section>

          <section className="insight-strip">
            <span>
              <Icon name="sparkle" size={17} />
            </span>
            <div>
              <strong>Chi tiêu tuần này giảm 12%</strong>
              <small>Bạn đang kiểm soát tài chính rất tốt</small>
            </div>
            <Icon name="chevron" className="right" />
          </section>

          <section className="spending-card card">
            <div className="section-title">
              <div>
                <span>TỔNG QUAN THÁNG 5</span>
                <h3>Chi tiêu</h3>
              </div>
              <button>
                Tháng này <Icon name="chevron" size={14} />
              </button>
            </div>
            <div className="spending-value">
              <strong>24.850.000 ₫</strong>
              <span>của 35 triệu</span>
            </div>
            <div className="chart">
              <svg viewBox="0 0 380 100" preserveAspectRatio="none">
                <defs>
                  <linearGradient id="area" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" stopColor="#20a983" stopOpacity=".23" />
                    <stop offset="1" stopColor="#20a983" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <path className="gridline" d="M0 20H380M0 52H380M0 84H380" />
                <path
                  className="area"
                  d="M0 78C30 74 49 51 78 59s42 23 72 2 57-17 78-9 46-21 72-15 45-19 80-22v85H0Z"
                />
                <path
                  className="line"
                  d="M0 78C30 74 49 51 78 59s42 23 72 2 57-17 78-9 46-21 72-15 45-19 80-22"
                />
                <circle cx="300" cy="37" r="4" />
              </svg>
            </div>
            <div className="chart-labels">
              <span>01</span>
              <span>05</span>
              <span>10</span>
              <span>15</span>
              <span>20</span>
              <span>25</span>
              <span>28</span>
            </div>
          </section>

          <section className="utilities-section">
            <div className="section-title">
              <div>
                <span>TRONG TẦM TAY</span>
                <h3>Tiện ích của bạn</h3>
              </div>
              <button className="link-button" onClick={() => openSheet()}>
                Xem tất cả
              </button>
            </div>
            <div className="utility-grid">
              {utilityItems.map((item) => (
                <button
                  key={item.title}
                  className="utility-card"
                  onClick={() => openSheet(item.title)}
                >
                  <span className={`utility-icon ${item.tone}`}>
                    <Icon name={item.icon} size={20} />
                  </span>
                  <strong>{item.title}</strong>
                  <small>{item.subtitle}</small>
                  <span className="open-arrow">→</span>
                </button>
              ))}
              <button
                type="button"
                className="utility-card placeholder"
                onClick={addNewUtility}
              >
                <span className="utility-icon placeholder-icon">
                  <Icon name="plus" size={20} />
                </span>
                <strong>Tiện ích mới sắp được cập nhật</strong>
                <small>Chạm để thêm tiện ích</small>
              </button>
            </div>
          </section>

          <section className={`expand-card ${accordionOpen ? "open" : ""}`}>
            <button
              className="expand-trigger"
              onClick={() => setAccordionOpen(!accordionOpen)}
              aria-expanded={accordionOpen}
            >
              <span className="utility-icon mint">
                <Icon name="tools" size={19} />
              </span>
              <span>
                <strong>Lập kế hoạch tài chính</strong>
                <small>Công cụ nâng cao dành cho bạn</small>
              </span>
              <Icon name="chevron" className="expand-chevron" />
            </button>
            <div className="expand-body">
              <div>
                <div className="goal-head">
                  <span>Mục tiêu quỹ dự phòng</span>
                  <strong>72%</strong>
                </div>
                <div className="progress">
                  <i />
                </div>
                <p>Bạn cần thêm 16.800.000 ₫ để hoàn thành mục tiêu.</p>
                <button onClick={() => openSheet("Tính khoản vay")}>Điều chỉnh kế hoạch</button>
              </div>
            </div>
          </section>

          <section className="transactions card">
            <div className="section-title">
              <div>
                <span>HOẠT ĐỘNG</span>
                <h3>Giao dịch gần đây</h3>
              </div>
              <button className="link-button">Xem tất cả</button>
            </div>
            {[
              ["wallet", "Lương tháng 5", "Hôm nay, 08:30", "+ 42.500.000 ₫", "income"],
              ["bill", "EVN Hà Nội", "Hôm qua, 19:42", "- 1.284.000 ₫", ""],
              ["send", "Nguyễn Hoàng Nam", "25/05, 14:05", "- 2.500.000 ₫", ""],
            ].map(([icon, title, meta, amount, type]) => (
              <div className="transaction" key={title}>
                <span className={type}>
                  <Icon name={icon as IconName} size={18} />
                </span>
                <div>
                  <strong>{title}</strong>
                  <small>{meta}</small>
                </div>
                <b className={type}>{amount}</b>
              </div>
            ))}
          </section>
        </main>

        <div className="speed-dial">
          <div className={`speed-menu ${speedDialOpen ? "open" : ""}`}>
            <button onClick={() => openSheet("Ghi chú nhanh")}>
              <span>Ghi chú nhanh</span>
              <i>
                <Icon name="note" size={19} />
              </i>
            </button>
            <button onClick={() => openSheet("Đổi ngoại tệ")}>
              <span>Đổi ngoại tệ</span>
              <i>
                <Icon name="chart" size={19} />
              </i>
            </button>
            <button onClick={() => openSheet("Tính khoản vay")}>
              <span>Tính khoản vay</span>
              <i>
                <Icon name="calculator" size={19} />
              </i>
            </button>
          </div>
          <button
            className={speedDialOpen ? "fab active" : "fab"}
            onClick={() => setSpeedDialOpen(!speedDialOpen)}
            aria-label="Mở menu tiện ích"
          >
            <Icon name={speedDialOpen ? "close" : "plus"} size={24} />
          </button>
        </div>

        <div className="sticky-tools">
          <button onClick={() => openSheet("Tính khoản vay")}>
            <Icon name="calculator" size={18} />
            <span>Khoản vay</span>
          </button>
          <button onClick={() => openSheet("Đổi ngoại tệ")}>
            <Icon name="chart" size={18} />
            <span>Tỷ giá</span>
          </button>
          <button onClick={() => openSheet("Nhắc hóa đơn")}>
            <Icon name="bill" size={18} />
            <span>Hóa đơn</span>
          </button>
          <button onClick={() => openSheet()}>
            <Icon name="grid" size={18} />
            <span>Tất cả</span>
          </button>
        </div>

        <nav className="bottom-nav">
          {[
            ["Trang chủ", "home"],
            ["Giao dịch", "transfer"],
            ["Tài chính", "chart"],
            ["Tiện ích", "grid"],
          ].map(([label, icon]) => (
            <button
              key={label}
              className={activeNav === label ? "active" : ""}
              onClick={() => {
                setActiveNav(label);
                if (label === "Tiện ích") openSheet();
              }}
            >
              <Icon name={icon as IconName} size={21} />
              <span>{label}</span>
            </button>
          ))}
        </nav>

        <div
          className={`overlay ${sheetOpen ? "visible" : ""}`}
          onClick={() => setSheetOpen(false)}
        />
        <aside
          className={`bottom-sheet ${sheetOpen ? "open" : ""} ${sheetFull ? "full" : "peek"}`}
          aria-hidden={!sheetOpen}
        >
          <div
            className="drag-area"
            onPointerDown={startDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
          >
            <i />
            <span>{sheetFull ? "Vuốt xuống để thu gọn" : "Vuốt lên để mở toàn màn hình"}</span>
          </div>
          <div className="sheet-header">
            <div>
              <span>TRUNG TÂM CÔNG CỤ</span>
              <h2>{activeTool}</h2>
            </div>
            <button onClick={() => setSheetOpen(false)} aria-label="Đóng">
              <Icon name="close" size={20} />
            </button>
          </div>
          <div className="sheet-content">
            <div className="sheet-tool-tabs">
              {utilityItems.map((item) => (
                <button
                  key={item.title}
                  className={activeTool === item.title ? "active" : ""}
                  onClick={() => setActiveTool(item.title)}
                >
                  <Icon name={item.icon} size={18} />
                  <span>{item.title}</span>
                </button>
              ))}
            </div>
            {activeTool === "Tính khoản vay" ? (
              <div className="loan-tool">
                <label>Số tiền cần vay</label>
                <div className="money-input">
                  <input defaultValue="500.000.000" />
                  <span>VND</span>
                </div>
                <div className="range-head">
                  <span>Thời hạn vay</span>
                  <strong>{years} năm</strong>
                </div>
                <input
                  className="range"
                  type="range"
                  min="1"
                  max="25"
                  value={years}
                  onChange={(e) => setYears(Number(e.target.value))}
                  style={{ "--range": `${((years - 1) / 24) * 100}%` } as React.CSSProperties}
                />
                <div className="estimate">
                  <span>
                    <small>Thanh toán ước tính</small>
                    <strong>
                      {Math.round((500000000 / (years * 12)) * 1.46).toLocaleString("vi-VN")} ₫
                      <em>/tháng</em>
                    </strong>
                  </span>
                  <i>
                    <Icon name="sparkle" size={18} />
                  </i>
                </div>
                <button className="primary-button">Xem kế hoạch chi tiết</button>
              </div>
            ) : (
              <div className="tool-ready">
                <span className="utility-icon mint">
                  <Icon
                    name={utilityItems.find((item) => item.title === activeTool)?.icon ?? "tools"}
                    size={22}
                  />
                </span>
                <h3>{activeTool} đã sẵn sàng</h3>
                <p>Thiết lập công cụ theo nhu cầu tài chính của bạn.</p>
                <button className="primary-button">Bắt đầu ngay</button>
              </div>
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}

export default App;
