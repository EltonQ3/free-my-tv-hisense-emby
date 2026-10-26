# Hisense TV CN MT9655 / Emby 1.8.5

面向中國大陸版 MediaTek MT9655 Hisense TV；目前僅海信 UXS 有實測經驗。其他型號、晶片、區域版及韌體未驗證。

Date: 2026-10-26. Tag: `hisense-emby-v1.8.5`. Branch: `hisense-emby`.
Android-specific prerelease. Package `io.freemytv`, versionCode `38`, versionName `1.8.5-uxs.20261026-public`.

## Scope

一個脫敏桌面 APK 支援可選、可共存的官方 Emby Android 通用版與 Android TV 版。保留本 fork 原有個人海報／進度、原色頭像、單 Home、聚好看按需啟用、調試按鈕、原廠 HDMI、六主題、橫向 App 卡片、股票指數及可調屏保。圖庫的既有實機圖片標註 1.8.1；新增的播放器圖是邏輯示意，不是假冒新截圖。

通用版保留已用的直接續播與可見介面帳號切換。TV 版加入可選 DUMP 任務識別、原任務喚回、精確進度及正常 Back；無授權或完整退出後有舊程序殘留時，先選原生續播按鈕。它不會在不確定的 PlayNow 回應後改路重試。新套件／版本來源標記在派送前再核對，避免把 TV 的 Android 名稱傳輸當成另一版 App。

## Completed local checks

- 上游 `tools/check.sh`：Android 編譯、252 鍵翻譯一致性、通用 webOS／Tizen 回歸封裝、ES5 語法與 API 檢查，均通過。未把 web 封裝宣稱為 Emby 版發行。
- 共 **724 項 Java 斷言**：調試 118、聚好看 14、單 Home 20、帳號 UI 59；播放解析 59、帳號隔離 17、基本 session 33、HTTP 8、通用版交接 6；TV 返回／傳輸 35、TV 帳號／session 82、雙版選擇 33、實際雙版 HTTP 14；任務身份 41、海信 UID 校準 113、返回時序 72。
- **6 項安裝流程測試**：分別接受／拒絕 DUMP 與 WRITE_SECURE_SETTINGS、可選授權失敗、簽名／版本安裝失敗；使用假 adb，不操作電視，不卸載或清資料。
- 兩個 App 同時啟用、同 IP／裝置 ID、不同用戶、TV SDK 別名、切換預設播放器、版本改變及相同版本歧義：本機策略與真正本機 HTTP 請求測試。確認只送所選 session，歧義不送出，失敗不重試。
- 安裝 Skill 驗證、套件／版本、穩定 fork 簽名、完整來源／APK 一致性、內外部 SHA-256、ZIP CRC、巢狀來源及 APK 的脫敏檢查。

CI 延用只讀 `contents: read`，不提供私人金鑰。網站 workflow 維持停用；CI 使用拋棄式簽名，發行 APK 使用本 fork 的穩定簽名。

## Hardware evidence and limits

參考機為 **中國大陸版 Hisense UXS／MT9655／Android 14**。已有 M 模式調試；擁有者在 2026-10-25 確認整天使用 U 模式。此敘述不等於每個功能都在每個模式完整測試。

- 官方通用版 `com.mb.android` **3.5.35**：先前個人版已驗證正常帳號 UI 的雙向切換，並由使用者確認從觀看進度播放。
- 官方 TV 版 `tv.emby.embyatv` **2.5.03g**：相同核心的個人版 **1.8.4** 在 2026-10-26 實測新程序冷啟動、既有任務續播、播放中 Home 後再續播、兩個已儲存帳號來回切換、實際起播 ticks、僅一個播放器以及 Back 返回 FullDetails 而不重播。
- 同次測試關閉開發者模式與 USB 調試，保留已批准的一次性 DUMP 授權，仍直接續播；停止後回片頁，測試自動恢復調試。完整退出 TV App 留有舊程序時，原生按鈕備援也通過；最終擁有者確認螢幕已關閉。

**本次 1.8.5 新增的雙版同時安裝、來源隔離及缺少 DUMP 的回退有程式／編譯／模擬驗證，未重新在電視部署驗收。** 不把以前「關閉調試但仍有 DUMP」的測試當成「未授予 DUMP」實測，也不把兩版分別的經驗當成同時安裝的實測。未為發布喚醒電視、重裝通用版或更動私人設定。

**此 Public APK 未完成乾淨電視首次安裝驗證。** 其他韌體、全部帳號／播放格式、待機喚醒、斷電開機、恢復原廠情境未全面覆蓋。遇密碼／PIN 需本人輸入；無法唯一識別版本或 session 時停止交接。它不是電視韌體、DRM 修復或全機備份。

## Public artifacts and identity

APK、Public ZIP、完整修改來源、安裝工具、獨立首次安裝 Skill、MIT 與素材聲明，以及公開檔案專用 SHA256SUMS。源碼 docs 目錄保留已公開且經擁有者允許的片庫、進度與頭像示例，名稱已替換；它們不作為 App 安裝預設。不附 Emby 等第三方 APK、私人恢復包、預置片庫／帳號／城市、API 金鑰、配對資料或簽名私鑰。覆蓋更新保留目標電視本來的私有資料。

- Public APK SHA-256: `339c71437517bf48c1518ccfe99c65089a9ce1798222799819c5c2b38f13b01d`
- Fork signing certificate SHA-256: `e9271d5ef1ea9a85bcb9b1d25a6029d74d0c68679bf8d3da7a6c5d66a90ea03e`
- Upstream baseline: `4d739516379976e4cc256e4106f3680d6bb94da8`

此簽名不是上游簽名。從 1.8.1 本 fork 可同簽名覆蓋更新；自行重建需保留自己的金鑰，不能覆蓋另一簽名。以 Release 的同版校驗清單驗證下載內容。
