# Free My TV · Hisense TV CN MT9655 / Emby Edition

針對中國大陸版、搭載 **MediaTek MT9655** 的 Hisense TV 改造的 **Emby 個人片庫桌面**。基於 [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv)，保留原版設計基礎、原廠首頁還原與崩潰保護，加入按用戶續播、聚好看按需開啟及六種設定頁主題。

**適配範圍：本改版面向中國大陸版、搭載 MediaTek MT9655 的 Hisense TV；目前僅測試海信 UXS 可用性。其他 MT9655 型號、其他晶片、其他地區版本及韌體未驗證，不能視為所有海信電視均已適配。**

**Unofficial Android fork · 非官方 Android 專用測試版。** 與 Free My TV 上游、海信、Emby、Apple 及任何串流服務無隸屬或背書關係。這是桌面應用，不是韌體；新增原生功能未移植至 LG webOS／Samsung Tizen。

[下載 1.8.5 公開包](https://github.com/EltonQ3/free-my-tv-hisense-emby/releases/tag/hisense-emby-v1.8.5) · [首次安裝 Skill](https://github.com/EltonQ3/free-my-tv-hisense-emby/blob/hisense-emby/skills/hisense-uxs-freemytv-first-install/SKILL.md) · [圖文導覽](docs/FEATURES.md) · [測試與限制](docs/HISENSE_RELEASE.md) · [上游發布要求](UPSTREAM.md) · [MIT](LICENSE)

![1.8.1 Emby 繼續播放、海報牆、頭像與適配應用卡片](docs/assets/hisense/home-emby-181.webp)

*1.8.1 實機外觀示意，展示本版保留的海報牆與應用卡片；片庫、進度與頭像經擁有者允許展示，帳號名稱和連線資料已移除。*

## 相對原版的 Features

這是此 fork 的首次公開發行，以下介紹海信／Emby 改版的完整功能。

- **Emby 個人推薦與續播**：繼續播放、隨機推薦、新到電影、新上劇四個分頁。點海報重新查詢所選用戶進度，明確傳入續播位置；劇集依該用戶觀看進度選擇接續集數。
- **首頁換 Emby 帳號**：原色頭像、同風格下拉選單與展開動畫。選用「Emby 账号遥控」無障礙服務，可操作官方播放器的正常介面同步帳號；密碼／PIN 仍手動輸入，核對實際用戶後才交接播放。
- **連續海報牆**：每類最多 10 項，首張橫圖，後續豎海報可向右逐項滑動。海報優先於劇內截圖，深色背景跟隨焦點海報的橫圖。右上時鐘只在海報模式顯示。
- **桌面自行更新**：首頁停留每 30 分鐘同步，返回首頁更新進度；焦點離開海報且 10 秒無操作，依序切換四個分頁。毋須電腦常駐。
- **單首頁與聚好看按需開啟**：Home 從 App、聚好看或 HDMI 回 Free My TV。可選擇平常封鎖聚好看，主動開啟時放出，回桌面後重新停用／強停；原廠套件與資料保留。
- **首頁雙向調試按鈕**：頂部可選按鈕與下方快捷方式均可開／關，僅內部深綠顯示已開啟。關閉時點聚好看會出現同風格提示窗，啟用調試成功後自動收起並繼續。參考韌體只需開發者模式＋USB 調試，不操作獨立無線調試開關。
- **推薦海報獨立設定**：切換 Emby／TMDB 來源；管理自己的伺服器、帳號、金鑰、更新偏好及 TMDB 地區。長金鑰可從電腦的一次性本機頁輸入；TMDB 預設香港 HK，無預置金鑰。
- **六種設定頁主題**：Chiikawa、經典、Fate 誓約、Fate 暮光、Lucy 夜之城、魔法使之夜。每個插畫主題配有自己的面板色彩；通知縮成可展開摘要，性能資訊另設一級入口。
- **海信訊號源適配**：整合原廠 HDMI 入口，回首頁時交接至 Android 訊號源；保留 CEC、HDR、音畫及校準服務。
- **統一的應用橫向卡片**：媒體中心、Clash、聚好看等卡片沿用原標誌元素，以統一比例呈現；普通與選中狀態均使用預先取樣、像素對齊的圖像。
- **首頁小工具與聲音**：納斯達克綜合指數 IXIC、恆生科技、滬深 300，使用免費騰訊 HTTPS 行情與來源時間；移動／確認／返回三種原創合成音效。屏保預設 1 分鐘，可選 1／2／5／10／15／30 分鐘。
- **可分享的脫敏發行**：不預置私人伺服器、帳號、API 金鑰、城市、位置、頭像、片庫、登入、配對或簽名私鑰。天氣預設關閉；Emby 尚未連接。公開包不附第三方 App APK。

![魔法使之夜的推薦設定入口與淺色面板](docs/assets/hisense/settings-mahoyo.webp)

*1.8.1 設定頁實機畫面裁切；展示獨立推薦海報設定與主題面板。設定頁美術與首頁明暗是兩個選項。更多主題與功能見[圖文導覽](docs/FEATURES.md)。*

## 1.8.5：通用版與 Android TV 版 Emby

![兩種 Emby 播放器與桌面交接示意；不是實機截圖](docs/assets/hisense/emby-players.svg)

- **同一個 APK，兩版可共存**：支援官方 `com.mb.android`（Emby Android 通用版）和 `tv.emby.embyatv`（Emby Android TV）。在 **推薦海報設定 → Emby → 播放應用** 選擇；首頁 Emby 卡片、海報點擊及帳號同步都跟隨選擇。自動選擇時優先 Android TV。
- **各走相符的帳號介面**：沿用通用版流程，新增 TV 版可見選單的帳號切換。密碼／PIN 仍由本人在官方播放器輸入，確認所選用戶後才查詢進度。
- **TV 直接續播與正常 Back**：有可選任務讀取授權時喚回原有 TV 頁面，不重複初始化啟動頁；從最新進度只發一次播放要求。退出播放後回到對應片頁，不補發播放指令。完全退出後若舊程序仍在，預先選用原生續播按鈕；未授予任務讀取時也使用原生續播。
- **避免控制錯誤的版本**：按套件、已安裝版本、裝置、地址與登入用戶分別識別連線，並為兩版保留獨立裝置配對。TV 的 Android 名稱傳輸不會被當成通用版。無法分辨的同版本／多連線情況停止自動交接，不要求卸載另一版。

參考播放器版本為通用版 **3.5.35**、Android TV **2.5.03g**，不是「所有最新版皆已實測」的承諾。既有 UXS 實測涵蓋兩版分別使用、TV 版直接續播／Back／切帳號及關閉調試；新增的兩版同時安裝與跨播放器阻擋有本機策略和 HTTP 模擬驗證，**尚未在雙版同裝實機完成本次驗收**。公開 APK 的乾淨首次安裝亦未實測，詳見[測試紀錄](docs/HISENSE_RELEASE.md)。

## 下載與第一次安裝

版本 **1.8.5-uxs.20261026-public**，package `io.freemytv`，versionCode **38**。最低 Android 8／API 26；實際參考機為 **海信 UXS／MT9655／Android 14**。已在 M 模式調試，並經使用者確認在 **U 模式日常使用**；其他海信型號與韌體未實測。

1. 從 [Release](https://github.com/EltonQ3/free-my-tv-hisense-emby/releases/tag/hisense-emby-v1.8.5) 下載 `FreeMyTV-UXS-1.8.5-20261026-Public.zip`、ZIP 外的 `Public-First-Install-SKILL.md` 與 `Public-SHA256SUMS.txt`，核對雜湊後完整解壓。
2. 電視與電腦在可互通的區域網路。手動開啟開發者模式及 USB 調試；Mac 執行「安裝桌面.command」，在電視允許調試。需要 Python 3.9+；現成 APK 不需重新編譯。
3. 工具會另問是否授予首頁開關所需的 `WRITE_SECURE_SETTINGS`。此權限定義較廣，本版按鈕只寫開發者模式與 USB 調試；拒絕也能手動從系統管理調試。
4. 在電視 Free My TV 設定選「主屏幕 → Free My TV」，允許應用自身的本機調試，開啟「Free My TV」無障礙服務，再用實體 Home 驗證。
5. 需要 Emby 時，另裝官方 [Emby Android 通用版](https://emby.media/emby-for-android.html) 或 [Emby Android TV](https://emby.media/emby-for-android-tv.html)，正常登入並在「播放應用」選定。兩版可同時保留。設定 → 推荐海报设置 → Emby，填自己的伺服器與金鑰並選帳號；長金鑰可用「電腦設定Emby.command」。TV 版海報續播與兩版的自動切帳號需開「Emby 账号遥控」；通用版同帳號續播可不開。
6. 需要聚好看按需封鎖時，設定 → 主屏幕设置 → 聚好看 → 按需启用。全新安裝需明確選擇，並非安裝後自動凍結。

Windows／Linux 自備官方 Android platform-tools，執行 `python3 tools/restore.py`。完整首次安裝、TMDB、主題、聚好看、排錯與恢復流程見 [SKILL.md](https://github.com/EltonQ3/free-my-tv-hisense-emby/blob/hisense-emby/skills/hisense-uxs-freemytv-first-install/SKILL.md)。

**退回原版或卸載前**：先開調試，在設定 → 主屏幕 → 原版完成還原，確認聚好看可用，再考慮卸載。只點聚好看卡片不等於完整還原。覆蓋安裝會保留目標電視既有資料，脫敏包不會替你清除它。

## 權限、隱私與相容性

Emby 海報與帳號來自你自己的伺服器。官方播放器仍需正常登入並具備播放權限；參考帳號介面為通用版 **3.5.35** 與 Android TV **2.5.03g**，不代表目前最新版本。自動切換只在明確操作後處理該播放器可見介面，最多 10 個動作／60 秒；遇密碼、PIN、歧義或驗證失敗即停止，不讀取播放器私人憑證。實際連線與用戶確認後才傳送續播位置，查詢失敗不退回從頭播放。

Android TV 的直接續播可選擇授予 `android.permission.DUMP`（系統狀態讀取）。這是較廣的系統權限；程式只解析 Emby／自身的任務和程序身份，不儲存或上傳完整傾印。不授權仍使用原生續播，通用版不需 DUMP；關閉開發者／USB 調試不等於撤銷這項一次性授權。系統狀態判讀與原生可見畫面都經核對後才交接。具體授權與回退步驟見首次安裝 Skill。

區域網路 Emby HTTP 僅接受驗證的私有／回環地址；外網需有效 HTTPS。憑證留在電視應用私有儲存，TMDB 金鑰使用 Android Keystore 加密；不略過 TLS 驗證、不跟隨認證重導。電腦輸入頁只監聽 `127.0.0.1`，隨機一次性網址、15 分鐘失效，不把金鑰寫進電腦檔案或命令列參數。

按需啟用聚好看只管理 `com.jamdeo.tv.vod`，開啟與重新封鎖時需要可用本機調試。`WRITE_SECURE_SETTINGS` 與無障礙均由安裝者選擇授予；不更改工廠 M／U 模式。選用功能會連線你設定的 Emby、TMDB／Watchmode、Open-Meteo 或騰訊行情。行情時效與可用性依資料源，並非交易級實時保證。

同套件更新需相同簽名。此 fork 發行 APK 使用本分支穩定簽名，**不是上游官方簽名**；從上游或自行編譯切換時先核對，不能直接覆蓋不同簽名。私人簽名金鑰不公開。角色插畫、商標與海報不因程式採 MIT 而變成 MIT 素材，請保留[素材來源與權利說明](THIRD_PARTY_NOTICES.md)。

## 原始碼與驗證

```sh
git clone --branch hisense-emby https://github.com/EltonQ3/free-my-tv-hisense-emby.git
cd free-my-tv-hisense-emby
cp config.example.properties config.properties
bash tools/check.sh
bash tools/check-debug.sh
bash tools/check-stock-home.sh
bash tools/check-hisense-home.sh
bash tools/check-emby-account.sh
bash tools/check-emby-playback.sh
bash tools/check-emby-tasks.sh
bash tools/check-emby-process.sh
bash tools/check-emby-return.sh
```

需要 JDK 17、Android build-tools 34／android-34、Python 3 與 Node.js。沿用上游無 Gradle、只用 Android SDK 的 Java 8 建置。`config.properties`、金鑰與輸出不提交；`emby_host=localhost` 是啟用 Emby 版的通用編譯標記，不是可用片庫。

`tools/check.sh` 保留 Android、翻譯、webOS／Tizen 通用封裝及 ES5 檢查。額外策略檢查覆蓋調試 118、聚好看 14、Home 20、帳號 59 項斷言，另有播放解析、隔離、傳輸及交接模擬測試。CI 僅讀取原始碼、使用臨時簽名，不提供私人金鑰；網站部署停用。

同功能個人版已有 UXS 的 M 模式調試與使用者確認的 U 模式日常使用經驗；TV 核心 1.8.4 已實測直接續播與正常返回。1.8.5 新雙版同裝邏輯本次以策略與 HTTP 模擬驗證，尚未重新雙裝實機驗收。**公開 APK 尚未完成乾淨電視首次安裝驗證**；所有帳號、播放格式、冷啟動與恢復原廠情境未全面驗證。詳細範圍見[測試紀錄](docs/HISENSE_RELEASE.md)。

## 上游、授權與致謝

Free My TV 由 [Free My TV contributors](https://github.com/simbasjungles/free-my-tv) 開發。保留完整 [MIT LICENSE](LICENSE)、原始歷史及[基線 README](docs/UPSTREAM_README.md)；本版修改基線為 `4d739516379976e4cc256e4106f3680d6bb94da8`。實際 fork 關係由 GitHub 保留，`main` 保持上游快照，`hisense-emby` 是專用分支。上游要求與本 Android 發布範圍的差異見 [UPSTREAM.md](UPSTREAM.md)。

- 上游以 [Claude Code](https://claude.com/claude-code) 開發；本 fork 的設備適配與整理使用 Codex。
- LG 研究：[danielthejoker18/FreeMyTvWebOS](https://github.com/danielthejoker18/FreeMyTvWebOS)（MIT）；Samsung 安裝：[reisxd/TizenBrewInstaller](https://github.com/reisxd/TizenBrewInstaller)。
- Inter（OFL）、Material Symbols（Apache 2.0）、保留的 cryptocurrency-icons／Simple Icons（CC0），完整聲明見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- 天氣 [Open-Meteo](https://open-meteo.com/)（CC BY 4.0）；指數為騰訊公開行情；選用連結為 Watchmode。本產品使用 TMDB API，但不受 TMDB 背書或認證。
- 三種聲音為原創合成，不含 Apple TV 原音或手機外錄。角色、插畫、App 名稱及商標歸各權利人；主題是依使用者提供參考所作的 AI 擴圖／改編，非官方授權主題。

**English:** An unofficial Free My TV Android fork for mainland-China Hisense TVs with MediaTek MT9655 and self-managed Emby libraries. Only the UXS model has been tested; other MT9655 models, chipsets, regional editions and firmware remain unverified. Supports selectable, coexisting official Emby Android and Android TV clients, per-user recommendations, exact resume positions, TV task reuse and normal Back with a native-resume fallback, optional visible-UI player account switching, focus-based artwork, a single Home destination with opt-in stock-launcher gating, debug toggles, native HDMI handling, six settings themes, adaptive app cards, stock-index widgets and an adjustable home screensaver. Reference hardware is UXS / Android 14, with M-mode development checks and owner-confirmed daily use in U mode. A clean-TV installation of the public APK remains unverified. It is not an official Hisense, Emby or upstream release.
