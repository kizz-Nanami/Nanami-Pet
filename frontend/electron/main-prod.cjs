const { app, BrowserWindow, ipcMain, globalShortcut, Tray, Menu, nativeImage, desktopCapturer, screen, shell, dialog } = require("electron")
const fs = require("fs")
const path = require("path")
const { exec, spawn } = require("child_process")

// ★ 单实例锁：双击两次/多开时，第二个实例直接退出并唤起已有实例，
//   避免两个实例共享同一 userData 互写配置（EPERM 弹窗）与重复拉起后端抢 8888 端口
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on("second-instance", () => {
    // 用户再次双击 exe：把已有桌宠窗口带到前台（穿透状态下也保证可见性）
    try {
      const wins = BrowserWindow.getAllWindows()
      for (const w of wins) { if (!w.isDestroyed()) { w.show(); w.focus() } }
    } catch {}
  })
}

// ★ 渲染层诊断日志转发：renderer 的 console 输出打到主进程终端（宠物窗口无键盘焦点开不了 DevTools，
//   转发后直接看 npm run dev 终端即可）。只转发 [TTS-D] 诊断与 error 级别，避免刷屏
app.on("web-contents-created", (_e, wc) => {
  wc.on("console-message", (_ev, level, message) => {
    if (typeof message === "string" && (message.includes("[TTS-D]") || level >= 3)) {
      console.log(`[wc${wc.id}] ${message}`)
    }
  })
})

// Config file for user preferences
const configPath = path.join(app.getPath("userData"), "cyberpet-config.json")
function loadConfig() {
  try { return JSON.parse(fs.readFileSync(configPath, "utf8")) } catch { return {} }
}
// ★ 配置持久化加固：临时文件+原子替换，EPERM（杀软/索引/双实例短暂锁文件）时重试而非崩溃弹窗
function saveConfig(cfg) {
  const data = JSON.stringify(cfg, null, 2)
  const tmp = configPath + ".tmp"
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      fs.writeFileSync(tmp, data)
      fs.renameSync(tmp, configPath) // Windows 下目标被读时 rename 也可能 EPERM，统一走重试
      return
    } catch (e) {
      if (attempt === 2) {
        console.warn("[saveConfig] 写入失败(已重试3次):", e.code || e.message)
        try { fs.unlinkSync(tmp) } catch {}
        return // 保存失败不致命：下次触发保存时再写，绝不弹窗中断用户
      }
      const wait = Date.now() + 150 * (attempt + 1)
      while (Date.now() < wait) {} // 同步短等待后重试
    }
  }
}

// Shared conversation store (pet window writes, settings window reads)
// ★ 对话持久化：单独存 conversation.json（与 config 隔离），重启后恢复；最多保留 200 条
const convPath = path.join(app.getPath("userData"), "conversation.json")
const CONV_MAX = 200
function loadConversation() {
  try { return JSON.parse(fs.readFileSync(convPath, "utf8")) } catch { return [] }
}
function saveConversation(msgs) {
  try {
    const trimmed = msgs.length > CONV_MAX ? msgs.slice(-CONV_MAX) : msgs
    fs.writeFileSync(convPath, JSON.stringify(trimmed))
  } catch { }
}
let conversation = loadConversation()

// ★ 对话落盘防抖：conv-update 高频到达（每句 SSE 切片都触发），只写内存、800ms 无更新后一次性落盘
let convSaveTimer = null
function scheduleConvSave() {
  if (convSaveTimer) clearTimeout(convSaveTimer)
  convSaveTimer = setTimeout(() => {
    convSaveTimer = null
    saveConversation(conversation)
  }, 800)
}
// 退出前把未落盘的对话强制写入（同步写，保证不丢）
function flushConvSave() {
  if (convSaveTimer) { clearTimeout(convSaveTimer); convSaveTimer = null }
  saveConversation(conversation)
}

let mainWin = null, settingsWin = null, tray = null, currentShortcut = null, currentScreenShortcut = null
let posLocked = false // ★ 位置锁定（内存缓存：拖动高频路径避免读盘）
let fullPass = false  // ★ 完全穿透（挂件模式）：true = 桌宠整体不响应鼠标

const isDev = process.env.NODE_ENV === "dev" || process.argv.includes("--dev")

// ★ 生产模式自动拉起内嵌后端：resources/jre 运行 resources/app.jar，
//   工作目录设为 userData/backend（记忆等运行时文件落在这里，升级覆盖安装不丢失），
//   后端 stdout/stderr 追加写入 backend.log 供测试者反馈排障
let backendProc = null
function startBackend() {
  const jarPath = path.join(process.resourcesPath, "app.jar")
  const javaPath = path.join(process.resourcesPath, "jre", "bin", "java.exe")
  if (!fs.existsSync(jarPath) || !fs.existsSync(javaPath)) {
    console.error("[backend] 内嵌后端文件缺失:", jarPath, javaPath)
    return
  }
  const backendHome = path.join(app.getPath("userData"), "backend")
  fs.mkdirSync(backendHome, { recursive: true })
  try {
    backendProc = spawn(javaPath, ["-Dfile.encoding=UTF-8", "-jar", jarPath], {
      cwd: backendHome, stdio: ["ignore", "pipe", "pipe"], windowsHide: true
    })
    const log = fs.createWriteStream(path.join(backendHome, "backend.log"), { flags: "a" })
    log.write(`\n===== 启动 ${new Date().toLocaleString()} =====\n`)
    backendProc.stdout.pipe(log, { end: false })
    backendProc.stderr.pipe(log, { end: false })
    backendProc.on("exit", (code) => console.log("[backend] exited:", code))
  } catch (e) {
    console.error("[backend] 启动失败:", e)
  }
}
// 轮询 8888 端口直到后端就绪（超时也放行，让窗口先起来显示状态）
function waitForBackend(timeoutMs = 45000) {
  const t0 = Date.now()
  return new Promise((resolve) => {
    const ping = async () => {
      try { await fetch("http://localhost:8888/"); resolve(true); return } catch { }
      if (Date.now() - t0 > timeoutMs) { resolve(false); return }
      setTimeout(ping, 500)
    }
    ping()
  })
}
function stopBackend() {
  if (backendProc && backendProc.pid) {
    try { exec(`taskkill /pid ${backendProc.pid} /T /F`, () => { }) } catch { }
    backendProc = null
  }
}

// ★ 截屏视觉感知：抓主显示器 → 缩放（≤1280px 宽）压缩 JPEG → base64（不广播）
async function captureBase64() {
  try {
    const display = screen.getPrimaryDisplay()
    const { width, height } = display.size
    const sf = display.scaleFactor || 1
    const sources = await desktopCapturer.getSources({
      types: ["screen"],
      thumbnailSize: { width: Math.round(width * sf), height: Math.round(height * sf) }
    })
    const img = sources[0]?.thumbnail
    if (!img || img.isEmpty()) return null
    // 缩放到最大宽 1280，控制 base64 体积（约 100~300KB）
    const w = img.getSize().width
    const scale = Math.min(1, 1280 / w)
    const resized = scale < 1 ? img.resize({ width: Math.round(w * scale) }) : img
    return resized.toJPEG(80).toString("base64")
  } catch (e) {
    console.error("capture-screen failed:", e)
    return null
  }
}

async function doCapture() {
  const base64 = await captureBase64()
  if (!base64) return
  const payload = { base64, at: Date.now() }
  if (settingsWin && !settingsWin.isDestroyed()) settingsWin.webContents.send("screen-captured", payload)
  if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send("screen-captured", payload)
}

// ★ 视觉记忆：定时静默截屏 → 后端 VL 生成一句话描述 → 存档长期记忆。
//   配置存 cyberpet-config.json 的 visualMemory: { enabled, intervalMin }，默认关闭
let visualMemTimer = null
function getVisualMemCfg() {
  const cfg = loadConfig().visualMemory || {}
  return { enabled: !!cfg.enabled, intervalMin: cfg.intervalMin > 0 ? cfg.intervalMin : 30 }
}
async function visualMemoryTick() {
  const cfg = getVisualMemCfg()
  if (!cfg.enabled) return
  try {
    const base64 = await captureBase64()
    if (!base64) return
    const r = await fetch("http://localhost:8888/api/vision-caption", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ image: base64 })
    })
    if (!r.ok) return
    const { text, error } = await r.json()
    if (!text) { console.log("[visual-memory] caption empty:", error); return }
    await fetch("http://localhost:8888/api/memory/visual", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text })
    })
    console.log("[visual-memory] 已存档:", text)
  } catch (e) {
    console.log("[visual-memory] failed:", e.message)
  }
}
function scheduleVisualMemory() {
  if (visualMemTimer) { clearInterval(visualMemTimer); visualMemTimer = null }
  const cfg = getVisualMemCfg()
  if (!cfg.enabled) return
  const ms = Math.max(5, cfg.intervalMin) * 60_000
  visualMemTimer = setInterval(visualMemoryTick, ms)
  // 启动后 20s 先观察一次（便于验证链路是否打通）
  setTimeout(visualMemoryTick, 20_000)
  console.log("[visual-memory] 已启用，间隔", cfg.intervalMin, "分钟")
}

function registerShortcut(shortcut, screenShortcut) {
  if (currentShortcut) globalShortcut.unregister(currentShortcut)
  if (currentScreenShortcut) globalShortcut.unregister(currentScreenShortcut)
  try {
    globalShortcut.register(shortcut, () => {
      if (mainWin) mainWin.webContents.send("toggle-recording")
    })
    currentShortcut = shortcut
    console.log("Shortcut registered:", shortcut)
  } catch (e) {
    console.error("Failed to register shortcut:", shortcut, e.message)
  }
  // ★ 截屏快捷键：截屏 + 宠物窗口自动开始聆听（用户直接说出想问的问题）
  try {
    globalShortcut.register(screenShortcut, () => {
      void doCapture()
      if (mainWin) mainWin.webContents.send("toggle-recording")
    })
    currentScreenShortcut = screenShortcut
    console.log("Screen shortcut registered:", screenShortcut)
  } catch (e) {
    console.error("Failed to register screen shortcut:", screenShortcut, e.message)
  }
}

function loadPage(win, hash) {
  const url = isDev ? "http://localhost:5173" : `file://${path.join(__dirname, "..", "dist", "index.html")}`
  win.loadURL(url + (hash || ""))
}

function openSettings() {
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.show()
    settingsWin.focus()
    return
  }
  settingsWin = new BrowserWindow({
    width: 420, height: 580,
    icon: path.join(__dirname, "whale-girl.png"),
    frame: false,
    transparent: true,   // ★ 纯透明窗口：不用系统 acrylic（其灰 tint 在白色桌面下发灰），玻璃感由 CSS 半透明白实现，任何背景色下表现一致
    autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, "preload.cjs"), contextIsolation: true, nodeIntegration: false }
  })
  settingsWin.setMenu(null)
  loadPage(settingsWin, "#settings")
  settingsWin.on("close", (e) => {
    // ★ app.quit() 触发的 close 直接放行（否则退出流程被 preventDefault 取消：
    //   后端已在 before-quit 被杀、进程却退不出去，表现为托盘图标假死，只能任务管理器强杀）
    if (!app.isQuitting) {
      e.preventDefault()
      settingsWin.hide()
    }
  })
  settingsWin.on("closed", () => {
    settingsWin = null
  })
}

// ★ 显示桌宠：窗口被隐藏/意外不可见时恢复（进程还在就不用重启前端）
function showPet() {
  if (!mainWin || mainWin.isDestroyed()) { createWindow(); return }
  if (mainWin.isMinimized()) mainWin.restore()
  mainWin.show()
  mainWin.setAlwaysOnTop(true, "screen-saver")
}

// ★ 清除聊天记录：清空内存 + 落盘 + 广播两窗口同步刷新
function clearConversation() {
  conversation = []
  saveConversation(conversation)
  if (settingsWin && !settingsWin.isDestroyed()) settingsWin.webContents.send("conv-update", conversation)
  if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send("conv-update", conversation)
}

// ★ 恢复初见：彻底清除聊天记录 + 全部记忆（长期记忆/向量/视觉记忆/快照），不可恢复。
//   先弹确认框（破坏性操作），再清聊天记录并通知后端清空记忆
async function resetAllMemory() {
  const r = await dialog.showMessageBox({
    type: "warning",
    title: "清除全部记忆与聊天记录",
    message: "将彻底清除聊天记录和全部记忆（长期记忆、视觉记忆），她将忘记与你的一切过往，此操作不可恢复。",
    buttons: ["取消", "确定清除"],
    defaultId: 0,
    cancelId: 0
  })
  if (r.response !== 1) return
  clearConversation()
  try {
    await fetch("http://localhost:8888/api/memory/clear", { method: "POST" })
    console.log("[memory] 全部记忆已清除")
  } catch (e) {
    console.error("[memory] 记忆清除失败（后端未启动？）:", e.message)
  }
}

// ★ 隐藏桌宠：隐藏宠物窗口（进程保持运行，可用"显示桌宠"召回）
function hidePet() {
  if (mainWin && !mainWin.isDestroyed()) mainWin.hide()
}

function createTray() {
  const icon = nativeImage.createFromPath(path.join(__dirname, "whale-girl.png"))
  tray = new Tray(icon)
  tray.setContextMenu(buildTrayMenu())
  tray.setToolTip("CyberPet")
  tray.on("double-click", openSettings)
}

// ★ 托盘菜单（可勾选的"鼠标穿透"项：全穿透后桌宠点不到，这里是唯一可靠的关闭入口）
function buildTrayMenu() {
  return Menu.buildFromTemplate([
    { label: "打开设置", click: openSettings },
    { label: "显示桌宠", click: showPet },
    { label: "隐藏桌宠", click: hidePet },
    { label: "清除聊天记录", click: clearConversation },
    { label: "恢复初见", click: resetAllMemory },
    { label: "配置", click: openAiConfig },
    { type: "separator" },
    { label: "挂件模式", type: "checkbox", checked: fullPass,
      click: (item) => { setFullPass(item.checked) } },
    { type: "separator" },
    { label: "退出", click: () => quitApp() }
  ])
}

// ★ AI 服务配置：打开设置窗口并直接进入"配置"面板（独立视图，不占 Tab 栏）
function openAiConfig() {
  openSettings()
  if (!settingsWin || settingsWin.isDestroyed()) return
  // 已加载完成则立即导航；冷启动还在加载则等加载完再导航（避免消息早于监听注册而丢失）
  if (settingsWin.webContents.isLoading()) {
    settingsWin.webContents.once("did-finish-load", () => {
      settingsWin && !settingsWin.isDestroyed() && settingsWin.webContents.send("open-ai-config")
    })
  } else {
    settingsWin.webContents.send("open-ai-config")
  }
}

// ★ 位置记忆/锁定：窗口至少 30% 落在某显示器工作区内才视为可见，
// 否则视为原位置失效（显示器被拔/分辨率变化），回默认居中
function isPositionVisible(x, y, w, h) {
  return screen.getAllDisplays().some((d) => {
    const a = d.workArea
    const ox = Math.min(x + w, a.x + a.width) - Math.max(x, a.x)
    const oy = Math.min(y + h, a.y + a.height) - Math.max(y, a.y)
    return ox > w * 0.3 && oy > h * 0.3
  })
}

function createWindow() {
  const cfg = loadConfig()
  const winOpts = {
    width: 280, height: 280,
    icon: path.join(__dirname, "whale-girl.png"),
    transparent: true, frame: false, thickFrame: false,
    alwaysOnTop: true, resizable: false,
    skipTaskbar: true, title: "",
    type: "toolbar",
    focusable: false,
    titleBarStyle: "hidden",
    acceptFirstMouse: true,
    hasShadow: false,
    backgroundColor: "#00000000",
    webPreferences: { preload: path.join(__dirname, "preload.cjs"), contextIsolation: true, nodeIntegration: false }
  }
  posLocked = !!cfg.posLocked
  mainWin = new BrowserWindow(winOpts)

  // ★ 位置记忆（预扣补偿法）：启动窗口固定 280×280，模型加载 fit 后会带补偿位移 (dx,dy)
  // 挪动窗口（每次启动该位移确定性相同）。恢复时预先扣掉 fitDelta，fit 后窗口恰好回到 petPos。
  // petPos 保存的是 fit 后的窗口位置；fitDelta 记录最近一次 fit 的补偿量。
  if (cfg.petPos && Number.isFinite(cfg.petPos.x) && Number.isFinite(cfg.petPos.y)) {
    let rx = cfg.petPos.x
    let ry = cfg.petPos.y
    const fd = cfg.fitDelta
    if (fd && Number.isFinite(fd.dx) && Number.isFinite(fd.dy)) {
      rx -= fd.dx
      ry -= fd.dy
    }
    if (isPositionVisible(rx, ry, 280, 280)) {
      mainWin.setPosition(Math.round(rx), Math.round(ry))
    }
  }

  loadPage(mainWin, "")



  mainWin.setAlwaysOnTop(true, "screen-saver")
  mainWin.setVisibleOnAllWorkspaces(true)
  mainWin.setBackgroundColor("#00000000")
  mainWin.setMenu(null)

  mainWin.on("focus", () => {
    if (process.platform === "win32") {
      mainWin.setBackgroundColor("#00000000")
    }
  })
  mainWin.on("blur", () => {
    if (process.platform === "win32") {
      mainWin.setBackgroundColor("#00000000")
        }
  })
  mainWin.on("show", () => {
    if (process.platform === "win32") {
      mainWin.setBackgroundColor("#00000000")
    }
  })
  mainWin.on("resize", () => {
    if (process.platform === "win32") {
      mainWin.setBackgroundColor("#00000000")
    }
  })
  mainWin.on("page-title-updated", (e) => e.preventDefault())

  // Load saved shortcut or use default
  const sc = cfg.shortcut || "Ctrl+Alt+L"
  const ssc = cfg.screenShortcut || "Ctrl+Alt+K"
  registerShortcut(sc, ssc)

  // ★ 位置记忆：窗口关闭时把最终位置落盘
  mainWin.on("close", savePetPos)

  createTray()
}

// ★ 调试支持：所有窗口按 F12 切换控制台（宠物窗口收不到键盘焦点，请用托盘菜单打开）
app.on("web-contents-created", (_e, wc) => {
  wc.on("before-input-event", (_ev, input) => {
    if (input.type === "keyDown" && input.key === "F12") {
      wc.toggleDevTools()
    }
  })
})

app.whenReady().then(async () => {
  if (!isDev) { startBackend(); await waitForBackend() } // ★ 打包版先拉起后端等就绪
  createWindow()
})
app.whenReady().then(scheduleVisualMemory)

// ★ 视觉记忆配置：设置窗读写（invoke 返回配置；send 更新并重新调度定时任务）
ipcMain.handle("get-visual-mem-config", () => getVisualMemCfg())
ipcMain.on("set-visual-mem-config", (_e, cfg) => {
  const all = loadConfig()
  all.visualMemory = {
    enabled: !!(cfg && cfg.enabled),
    intervalMin: cfg && cfg.intervalMin > 0 ? cfg.intervalMin : 30
  }
  saveConfig(all)
  scheduleVisualMemory()
})

ipcMain.on("close-window", () => quitApp())

ipcMain.on("resize-pet-window", (event, w, h, dx = 0, dy = 0) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (win) {
    // dx/dy：画布缩小时补偿窗口位置，保持桌宠屏幕位置不动
    const [wx, wy] = win.getPosition()
    win.setBounds({ x: wx + dx, y: wy + dy, width: w, height: h })
    // ★ 记录本次 fit 补偿量（每次启动模型自适应的位移确定性相同，供下次恢复位置预扣）
    if (dx !== 0 || dy !== 0) {
      const c = loadConfig()
      c.fitDelta = { dx, dy }
      saveConfig(c)
    }
  }
})

// ★ 位置记忆：拖动结束 400ms 后防抖落盘（避免高频 IO）。petPos = fit 后的窗口位置
let posSaveTimer = null
function savePetPos() {
  if (!mainWin || mainWin.isDestroyed()) return
  const b = mainWin.getBounds()
  const c = loadConfig()
  c.petPos = { x: b.x, y: b.y }
  saveConfig(c)
}

ipcMain.on("move-window", (event, x, y) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (!win) return
  if (posLocked) return // ★ 位置锁定：忽略拖动（渲染层仍可点击互动）
  // 只移动位置，不修改尺寸，避免窗口被压缩导致人物显示不全
  const [wx, wy] = win.getPosition()
  win.setPosition(Math.round(wx + x), Math.round(wy + y))
  clearTimeout(posSaveTimer)
  posSaveTimer = setTimeout(savePetPos, 400)
})

// ★ 位置锁定开关：设置窗口切换 → 持久化 + 同步另一窗口
ipcMain.on("set-pos-locked", (event, locked) => {
  posLocked = !!locked
  const c = loadConfig()
  c.posLocked = posLocked
  saveConfig(c)
  ;[mainWin, settingsWin].forEach((w) => {
    if (w && !w.isDestroyed() && w.webContents !== event.sender) {
      w.webContents.send("pos-locked-changed", posLocked)
    }
  })
})
ipcMain.on("get-pos-locked", (event) => { event.returnValue = posLocked })

// 鼠标穿透：桌宠本体之外的透明区域放行点击（forward 保持事件流，指针回到桌宠上可恢复）
ipcMain.on("set-ignore-mouse", (event, ignore) => {
  if (fullPass) return // ★ 全穿透模式下忽略渲染层的自动判定，保持整体穿透
  const win = BrowserWindow.fromWebContents(event.sender)
  if (win) win.setIgnoreMouseEvents(ignore, { forward: true })
})

// ★ 完全穿透开关：开启后桌宠整体不响应鼠标（纯观赏挂件），点击全部落到下层窗口；
//   只能从托盘菜单或设置窗口关回。持久化到 config，双窗口同步
function applyFullPass() {
  if (mainWin && !mainWin.isDestroyed()) {
    mainWin.setIgnoreMouseEvents(fullPass, { forward: !fullPass })
  }
}
function setFullPass(on, skipConfig) {
  fullPass = !!on
  if (!skipConfig) {
    const c = loadConfig()
    c.fullPass = fullPass
    saveConfig(c)
  }
  applyFullPass()
  ;[mainWin, settingsWin].forEach((w) => {
    if (w && !w.isDestroyed()) w.webContents.send("full-pass-changed", fullPass)
  })
  if (tray) tray.setContextMenu(buildTrayMenu()) // 托盘勾选态同步
}
ipcMain.on("set-full-pass", (event, on) => {
  setFullPass(on)
})
ipcMain.on("get-full-pass", (event) => { event.returnValue = fullPass })

// ★ 全局鼠标追踪：穿透 forward 只在窗口矩形内转发 mousemove，指针移出窗口后渲染层收不到事件，
//   导致桌宠无法追看窗口外的鼠标（如模型头顶方向）。主进程轮询全局光标，换算成窗口客户区坐标推送
let lastCursorX = NaN, lastCursorY = NaN
setInterval(() => {
  if (!mainWin || mainWin.isDestroyed() || !mainWin.isVisible()) return
  const pt = screen.getCursorScreenPoint()
  if (pt.x === lastCursorX && pt.y === lastCursorY) return
  lastCursorX = pt.x
  lastCursorY = pt.y
  const b = mainWin.getContentBounds()
  mainWin.webContents.send("global-cursor", { x: pt.x - b.x, y: pt.y - b.y })
}, 50)

// Shortcut IPC
ipcMain.on("get-shortcut", (event) => {
  const cfg = loadConfig()
  event.returnValue = cfg.shortcut || "Ctrl+Alt+L"
})

ipcMain.on("set-shortcut", (event, newShortcut) => {
  const cfg = loadConfig()
  cfg.shortcut = newShortcut
  saveConfig(cfg)
  registerShortcut(newShortcut, cfg.screenShortcut || "Ctrl+Alt+K")
})

// ★ 截屏快捷键读取/设置（设置窗口独立配置截屏键）
ipcMain.on("get-screen-shortcut", (event) => {
  event.returnValue = loadConfig().screenShortcut || "Ctrl+Alt+K"
})

ipcMain.on("set-screen-shortcut", (event, newShortcut) => {
  const cfg = loadConfig()
  cfg.screenShortcut = newShortcut
  saveConfig(cfg)
  registerShortcut(cfg.shortcut || "Ctrl+Alt+L", newShortcut)
})

ipcMain.on("capture-screen", () => { void doCapture() })

// ★ Edge-TTS 引擎（微软神经网络音色，免费无额度）：主进程合成，渲染层经 IPC 调用
const { EdgeTTS } = require("edge-tts-universal")
ipcMain.handle("edge-tts", async (_e, payload) => {
  const text = String(payload?.text || "").trim()
  if (!text) throw new Error("empty text")
  const voice = String(payload?.voice || "") || "zh-CN-XiaoxiaoNeural"
  const tts = new EdgeTTS(text, voice)
  const result = await tts.synthesize()
  const buf = Buffer.from(await result.audio.arrayBuffer())
  if (!buf.length) throw new Error("no audio")
  return buf
})

// ★ AI 配置保存后广播各窗口刷新 TTS 引擎缓存
ipcMain.on("ai-config-changed", () => {
  if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send("ai-config-changed", null)
  if (settingsWin && !settingsWin.isDestroyed()) settingsWin.webContents.send("ai-config-changed", null)
})

// ★ AI 工具桥（/ws/electron → 渲染进程 → 此处执行）：
// open_website 打开网页；open_application 用 cmd start 打开本地程序（名字已过白名单校验）
ipcMain.handle("tool-execute", async (_e, action, args) => {
  try {
    if (action === "open_website") {
      const url = String(args?.url || "")
      if (!/^https?:\/\/[\w.-]+/.test(url)) return "网址不合法：" + url
      await shell.openExternal(url)
      return "已在浏览器打开：" + url
    }
    if (action === "open_application") {
      const name = String(args?.name || "").trim()
      if (!/^[\w\u4e00-\u9fff .:\\/-]+$/.test(name)) return "应用名包含非法字符：" + name
      return await new Promise((resolve) => {
        exec(`start "" "${name}"`, { shell: "cmd.exe" }, (err) => {
          resolve(err ? `打开应用失败：${name}` : `已打开应用：${name}`)
        })
      })
    }
    return "未知操作：" + action
  } catch (e) {
    return "执行出错：" + (e?.message || e)
  }
})

// Conversation IPC - broadcast to other window
ipcMain.on("conv-update", (_event, msgs) => {
  conversation = msgs
  scheduleConvSave() // ★ 防抖落盘：高频更新只写内存，停顿后一次性写盘
  // Forward to settings window if pet sent it
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.webContents.send("conv-update", conversation)
  }
  // Forward to pet window if settings sent it
  if (mainWin && !mainWin.isDestroyed()) {
    mainWin.webContents.send("conv-update", conversation)
  }
})

ipcMain.on("get-conversation", (event) => {
  event.returnValue = conversation
})

// ★ 情绪广播：SSE 在发起聊天的窗口解析，但 Live2D 模型在宠物窗口 → 转发给另一窗口（排除发送方防重复）
ipcMain.on("pet-emotion", (event, emotion) => {
  if (settingsWin && !settingsWin.isDestroyed() && settingsWin.webContents !== event.sender) {
    settingsWin.webContents.send("pet-emotion", emotion)
  }
  if (mainWin && !mainWin.isDestroyed() && mainWin.webContents !== event.sender) {
    mainWin.webContents.send("pet-emotion", emotion)
  }
})

// ★ 语音播报状态广播：发声窗口广播"正在播报/已停止"，另一窗口据此显示停止按钮
ipcMain.on("speech-state", (event, speaking) => {
  if (settingsWin && !settingsWin.isDestroyed() && settingsWin.webContents !== event.sender) {
    settingsWin.webContents.send("speech-state", speaking)
  }
  if (mainWin && !mainWin.isDestroyed() && mainWin.webContents !== event.sender) {
    mainWin.webContents.send("speech-state", speaking)
  }
})

// ★ 停止语音请求：点击停止按钮的窗口转发给发声窗口，令其中断播报
ipcMain.on("speech-stop-req", (event) => {
  if (settingsWin && !settingsWin.isDestroyed() && settingsWin.webContents !== event.sender) {
    settingsWin.webContents.send("speech-stop-req", true)
  }
  if (mainWin && !mainWin.isDestroyed() && mainWin.webContents !== event.sender) {
    mainWin.webContents.send("speech-stop-req", true)
  }
})

// ★ TTS 配置 IPC：语音开关/语速/音色持久化 + 广播（宠物窗口是唯一发声源）
ipcMain.on("set-tts-config", (_e, cfg) => {
  const c = loadConfig()
  c.tts = { ...(c.tts || {}), ...cfg }
  saveConfig(c)
  // 广播给两个窗口（发送者窗口自己的本地实例是静音的，收到也无妨）
  if (settingsWin && !settingsWin.isDestroyed()) settingsWin.webContents.send("tts-config-changed", c.tts)
  if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send("tts-config-changed", c.tts)
})

ipcMain.on("get-tts-config", (event) => {
  event.returnValue = loadConfig().tts || {}
})

// ★ Persona IPC：角色人设持久化（设置窗口编辑保存，宠物窗口发送消息时随请求携带）
ipcMain.on("get-persona", (event) => {
  event.returnValue = loadConfig().persona || ""
})

// ★ 模型管理：枚举 live2d 目录（dev 下 public/，打包后 dist/），解析每个 model3.json 的表情列表
function listModels() {
  const candidates = [
    path.join(__dirname, "..", "public", "live2d"),
    path.join(__dirname, "..", "dist", "live2d")
  ]
  const baseDir = candidates.find((p) => fs.existsSync(p)) || candidates[0]
  const models = []
  try {
    for (const folder of fs.readdirSync(baseDir)) {
      const dir = path.join(baseDir, folder)
      if (!fs.statSync(dir).isDirectory()) continue
      for (const file of fs.readdirSync(dir)) {
        if (!file.endsWith(".model3.json")) continue
        let expressions = []
        try {
          const def = JSON.parse(fs.readFileSync(path.join(dir, file), "utf8"))
          expressions = (def.FileReferences?.Expressions || []).map((e) => e.Name)
        } catch { }
        models.push({ folder, file, expressions })
      }
    }
  } catch (e) {
    console.error("listModels failed:", e)
  }
  return models
}

ipcMain.on("list-models", (event) => {
  event.returnValue = listModels()
})

ipcMain.on("get-model-config", (event) => {
  event.returnValue = loadConfig().model || null
})

// ★ 切换模型：持久化并广播给两个窗口（宠物窗口收到后重建模型）
ipcMain.on("set-model-config", (_e, model) => {
  const cfg = loadConfig()
  cfg.model = model
  saveConfig(cfg)
  for (const win of [settingsWin, mainWin]) {
    if (win && !win.isDestroyed()) win.webContents.send("model-changed", model)
  }
})

ipcMain.on("set-persona", (_e, persona) => {
  const cfg = loadConfig()
  cfg.persona = persona
  saveConfig(cfg)
})

// ★ ASR IPC：宠物窗口（录音识别方）把识别文字转发给设置窗口（输入框所在）
ipcMain.on("asr-result", (_e, text) => {
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.webContents.send("asr-result", text)
  }
})

// ★ 桌宠气泡一键发送：通知设置窗口清空输入框，防止重复发送
ipcMain.on("asr-sent", (_e, text) => {
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.webContents.send("asr-sent", text)
  }
})

app.on("will-quit", () => { globalShortcut.unregisterAll() })
app.on("before-quit", () => {
  app.isQuitting = true // ★ 置退出标志：放行设置窗口的 close，quit 流程不再被拦截
  try { flushConvSave(); stopBackend() } catch (e) { console.error("[quit] 清理异常:", e) }
})
// ★ 统一退出入口：同步清理后 app.exit 强制结束（不走窗口关闭流程，
//   杜绝任何窗口拦截/异常导致"窗口关了进程却退不出去"的假死）
function quitApp() {
  app.isQuitting = true
  try { flushConvSave() } catch (e) { console.error("[quit] 对话落盘失败:", e) }
  try { stopBackend() } catch (e) { console.error("[quit] 停止后端失败:", e) }
  try { globalShortcut.unregisterAll() } catch { }
  try { tray && tray.destroy() } catch { } // 主动清托盘图标，不靠进程退出回收
  app.exit(0)
}
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit() })