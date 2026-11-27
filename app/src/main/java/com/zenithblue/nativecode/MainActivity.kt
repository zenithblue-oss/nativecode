package com.zenithblue.nativecode

import android.animation.ObjectAnimator
import android.system.Os
import android.system.OsConstants
import java.io.InputStream
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.Typeface
import java.util.Locale
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.zenithblue.nativecode.ui.BrutalistToggle as CustomBrutalistToggle
import com.zenithblue.nativecode.onboarding.LegacyProotDataMigrator
import com.zenithblue.nativecode.billing.BillingRepository
import com.zenithblue.nativecode.entitlements.EntitlementStore
import com.zenithblue.nativecode.entitlements.GuestGate
import com.zenithblue.nativecode.entitlements.FeatureGate
import com.zenithblue.nativecode.entitlements.GateResult
import com.zenithblue.nativecode.entitlements.ProFeature
import com.zenithblue.nativecode.desktop.DesktopController
import com.zenithblue.nativecode.desktop.DesktopPill
import com.zenithblue.nativecode.quick.QuickActions
import com.zenithblue.nativecode.workspace.FileViewers
import com.zenithblue.nativecode.workspace.FolderPicker
import com.zenithblue.nativecode.workspace.StatStrip
import com.zenithblue.nativecode.workspace.StatsEditor
import com.zenithblue.nativecode.workspace.ToolRail
import com.zenithblue.nativecode.workspace.WorkspaceTopBar
import com.zenithblue.nativecode.telemetry.ConsentSheet
import com.zenithblue.nativecode.telemetry.Telemetry
import com.zenithblue.nativecode.onboarding.AndroidDevPackManager
import com.zenithblue.nativecode.onboarding.RuntimeUpdatePack
import com.zenithblue.nativecode.onboarding.PlayAssetDeliveryManager
import android.view.ContextMenu
import android.view.MenuItem
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import com.zenithblue.nativecode.terminal.*
import com.zenithblue.nativecode.ui.LanguagePicker
import com.zenithblue.nativecode.terminal.ui.*
import com.zenithblue.nativecode.terminal.keys.ExtraKeysBar
import com.zenithblue.nativecode.terminal.keys.KeyLayouts
import com.zenithblue.nativecode.terminal.keys.KeyPreset
import com.zenithblue.nativecode.marketplace.*
import com.zenithblue.nativecode.github.*
import com.zenithblue.nativecode.bridge.NativeCodeBridge
import com.zenithblue.nativecode.media.MediaCodecSelector
import com.zenithblue.nativecode.media.MediaCodecDecodeBenchmark
import com.zenithblue.nativecode.media.MediaCodecEncodeBenchmark
import com.zenithblue.nativecode.agent.AgentBridgeServer
import com.zenithblue.nativecode.agent.AgentBridgeProtocol
import com.zenithblue.nativecode.agent.chat.AgentChats
import com.zenithblue.nativecode.agent.chat.ChatGate
import com.zenithblue.nativecode.agent.chat.ChatNotifications
import com.zenithblue.nativecode.agent.chat.ui.AgentChatView
import com.zenithblue.nativecode.agent.chat.ui.ChatHost
import com.zenithblue.nativecode.agent.chat.ui.ChatLogic
import com.zenithblue.nativecode.agent.chat.ui.StoreBackend
import com.zenithblue.nativecode.agent.chat.ui.TermTab
import org.json.JSONObject
import com.zenithblue.nativecode.media.MediaBridgeServer
import com.zenithblue.nativecode.cliauth.*
import com.zenithblue.nativecode.git.*
import java.io.FileOutputStream
import java.util.concurrent.Executors

typealias ModifierState = TerminalModifierState

class MainActivity : AppCompatActivity(), AgentBridgeServer.UiDelegate {

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var rootLayout: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var projectBottomNavigation: BottomNavigationView
    private lateinit var mainContentLayout: LinearLayout
    private lateinit var sideNavContainer: FrameLayout
    private lateinit var bottomNavContainer: FrameLayout
    private lateinit var globalNavRail: com.google.android.material.navigationrail.NavigationRailView
    private lateinit var projectNavRail: com.google.android.material.navigationrail.NavigationRailView


    // Persistent Header (Unified across all pages)
    private lateinit var unifiedHeader: LinearLayout

    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    /** TERMINAL page tab strip (replaces the old terminal-list drawer; item 12 M1). */
    private var termTabStrip: TermTabStrip? = null
    // Tab state lives in TerminalStore so a recreate (language change) keeps the shells running.
    private val termTabTitles get() = TerminalStore.titles
    private val cliIconCache = HashMap<String, android.graphics.Bitmap?>()

    private lateinit var addTerminalBtn: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var scriptInstallBackBtn: ImageView
    private lateinit var scriptInstallTitleTv: TextView

    private val sessionsList get() = TerminalStore.sessions
    private val terminalSessionTypes get() = TerminalStore.sessionTypes
    private lateinit var terminalToolSelectorScrollView: ScrollView
    private var keepAwake = false
    private var skillsPicker: com.zenithblue.nativecode.skills.SkillsPickerSheet? = null
    private var activeSessionIndex by TerminalStore::activeIndex

    private var termFontSize = 40
    private var workspaceFontSize = 40
    private var scriptFontSize = 40
    private var showExtraKeys = true
    private val MIN_FONT_SIZE = 10
    private val MAX_FONT_SIZE = 72

    private lateinit var viewClient: TerminalViewClient
    private lateinit var sessionClient: TerminalSessionClient

    // Layout panels (pages)
    private lateinit var homeScrollView: ScrollView
    private lateinit var homeLayout: LinearLayout

    private lateinit var fileExplorerScrollView: ScrollView
    private lateinit var fileExplorerLayout: LinearLayout

    private lateinit var terminalWorkspaceLayout: LinearLayout
    private lateinit var terminalViewContainer: FrameLayout
    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null

    private lateinit var gitOperationsScrollView: ScrollView
    private lateinit var gitOperationsLayout: LinearLayout

    private lateinit var settingsHubScrollView: ScrollView
    private lateinit var settingsHubLayout: LinearLayout

    // Sub-pages (pushed on stack)
    /** In-memory project icons — avoids re-decode blink when rebuilding cards / re-entering workspace. */
    private var cachedProjectIconPath: String? = null
    private var cachedProjectIconBitmap: android.graphics.Bitmap? = null
    private val projectIconCache =
        object : android.util.LruCache<String, android.graphics.Bitmap>(48) {}
    private lateinit var fileViewerRootContainer: LinearLayout
    private lateinit var fileViewerTopBar: WorkspaceTopBar
    private lateinit var projectDirTreeTopBar: WorkspaceTopBar
    private lateinit var projectSettingsTopBar: WorkspaceTopBar
    private lateinit var projectGitDiffTopBar: WorkspaceTopBar
    /** Sticky FILE VIEWER title + path (outside scroll). */
    private lateinit var fileViewerStickyHeader: LinearLayout
    private var fileViewerPathTv: TextView? = null
    /** Body host under sticky chrome (weight=1); content scrolls inside. */
    private lateinit var fileViewerContentHost: FrameLayout
    private lateinit var fileViewerScrollView: ScrollView
    private lateinit var fileViewerContainer: LinearLayout
    private lateinit var diffViewerRootContainer: LinearLayout
    private lateinit var diffViewerTopBar: WorkspaceTopBar
    private lateinit var diffViewerScrollView: ScrollView
    private lateinit var diffViewerContainer: LinearLayout
    private lateinit var scriptsScrollView: ScrollView
    private lateinit var scriptsLayout: LinearLayout
    /** AI CLI browser login page (Settings Hub → AI CLI LOGIN). */
    private lateinit var cliAuthScrollView: ScrollView
    private var cliAuthMethodBadge: TextView? = null
    /** AI CLI sign-in popup ([CliLoginSheet]) shown for this tool id; null = closed. */
    private val cliLoginId = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The CLI update popup (too old for chat / sign-in): what it is about, and why the last update failed. [done] true = updated. */
    private class CliUpdateReq(val r: CliVersionCheck.Result, val need: CliNeed, val done: (Boolean) -> Unit)
    private val cliUpdateReq = androidx.compose.runtime.mutableStateOf<CliUpdateReq?>(null)
    private val cliUpdateErr = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Settings → AI Safety & Report (C5 / B16). */
    private lateinit var aiSafetyScrollView: ScrollView
    private var aiSafetyVendorList: LinearLayout? = null
    private var aiSafetyMethodBadge: TextView? = null
    /** Settings → Privacy Policy & Open Source Licenses. */
    private lateinit var privacyPolicyScrollView: ScrollView
    /** Settings → Play-delivered runtime and SDK components. */
    private lateinit var componentsScrollView: ScrollView
    /** Settings → Hardware & VPU Diagnostics. */
    private lateinit var hardwareScrollView: ScrollView
    /** Settings → native X11 Settings page (modular x11 package). */
    private lateinit var x11SettingsScrollView: ScrollView
    /** Settings → agent skills (inbuilt hardware docs + empty project/install). */
    private lateinit var skillsScrollView: FrameLayout
    private var skillsPage: com.zenithblue.nativecode.skills.SkillsPage? = null
    private var x11SettingsPage: com.zenithblue.nativecode.x11.X11SettingsPage? = null
    /** PRoot Home file navigation page. */
    private lateinit var prootFilesScrollView: ScrollView
    private var prootFilesPage: com.zenithblue.nativecode.proot.ProotFilesPage? = null
    private lateinit var vpuStatusBadge: TextView
    private lateinit var vpuSocDetailTv: TextView
    private lateinit var gpuStatusBadge: TextView
    private lateinit var gpuDetailTv: TextView
    private lateinit var venusServerBadge: TextView
    private lateinit var venusServerDetailTv: TextView
    private lateinit var venusHostBlock: LinearLayout
    private lateinit var hostServersBlurbTv: TextView
    private lateinit var pulseServerBadge: TextView
    private lateinit var pulseServerDetailTv: TextView
    private lateinit var vpuDecodersTv: TextView
    private lateinit var vpuEncodersTv: TextView
    private lateinit var bridgeStatusTv: TextView
    private lateinit var bridgeLogTv: TextView
    private lateinit var hwDriverBadge: TextView
    private lateinit var hwDriverTv: TextView
    private lateinit var hwDriverBtn: View
    private lateinit var hwFeaturesTv: TextView
    /** Repairs page: host | guest | chroot */
    private var repairsSelectedTab: String = "host"
    private var repairsRootOk: Boolean? = null
    private var repairsListContainer: LinearLayout? = null
    private var repairsTabHostBtn: TextView? = null
    private var repairsTabGuestBtn: TextView? = null
    private var repairsTabChrootBtn: TextView? = null
    private var repairsRootBadge: TextView? = null
    /** Settings hub → CHROOT SETTINGS row (gated when no root). */
    private var chrootSettingsHubCard: LinearLayout? = null
    private var chrootSettingsHubSub: TextView? = null
    private var chrootSettingsHubBadge: TextView? = null
    private var chrootSettingsHubArrow: ImageView? = null
    private var settingsHubRootOk: Boolean? = null
    private lateinit var prootSettingsScrollView: ScrollView
    private lateinit var chrootSettingsScrollView: ScrollView
    private lateinit var softwareManagerScrollView: ScrollView
    private lateinit var softwareManagerBody: LinearLayout
    private lateinit var marketplaceScrollView: ScrollView
    private lateinit var marketplaceBody: LinearLayout
    private var smSearchQuery: String = ""
    private var smFilter: String = "all" // all | mp | system
    private var smPackages: List<AptPackage> = emptyList()
    /** Env (`proot`|`chroot`) that [smPackages] was scanned under. Badge/status must match. */
    private var smScanEnv: String? = null
    private var smScanning: Boolean = false
    /** Category titles currently expanded in Software Manager list. */
    private val smExpandedCategories: MutableSet<String> = mutableSetOf()
    private var smStatusTv: TextView? = null
    private var smListContainer: LinearLayout? = null
    private var smEnvBadge: TextView? = null
    private var smRefreshBtn: TextView? = null
    private var mpCatalog: MpCatalog? = null
    private var mpCategoryFilter: String = "all"
    private var mpSearchQuery: String = ""
    private var mpBusy: Boolean = false
    private var mpLoading: Boolean = false
    private var mpStatusTv: TextView? = null
    private var mpListContainer: LinearLayout? = null
    private var mpLogTv: TextView? = null
    private var mpEnvBadge: TextView? = null
    private var mpSyncTv: TextView? = null

    // Apps page — installed kind=app only
    private lateinit var appsScrollView: ScrollView
    private lateinit var appsBody: LinearLayout
    private var appsSearchQuery: String = ""
    private var appsItems: List<AppListItem> = emptyList()
    private var appsLoading: Boolean = false
    private var appsStatusTv: TextView? = null
    private var appsListContainer: LinearLayout? = null
    private var appsEnvBadge: TextView? = null
    private var appsRefreshBtn: TextView? = null

    /** Dedicated marketplace install/uninstall page (not generic script runner). */
    private lateinit var mpInstallerLayout: LinearLayout
    private lateinit var mpInstallerBackBtn: ImageView
    private lateinit var mpInstallerTitleTv: TextView
    private lateinit var mpInstallerIcon: ImageView
    private lateinit var mpInstallerNameTv: TextView
    private lateinit var mpInstallerMetaTv: TextView
    private lateinit var mpInstallerStatusTv: TextView
    private lateinit var mpInstallerPlanTv: TextView
    private lateinit var mpInstallerTerminalView: TerminalView
    private lateinit var mpInstallerFooter: LinearLayout
    private lateinit var mpInstallerDoneBtn: TextView
    private lateinit var mpInstallerHintTv: TextView
    private var mpInstallerSession: TerminalSession? = null
    private var mpInstallerFinishedOk: Boolean? = null

    private lateinit var scriptInstallLayout: LinearLayout
    private lateinit var scriptInstallViewContainer: FrameLayout
    private lateinit var scriptInstallTerminalView: TerminalView
    private lateinit var scriptInstallCopyBtn: TextView
    private var scriptInstallSession: TerminalSession? = null

    private lateinit var projectCreateContainer: LinearLayout
    private lateinit var projectCreateScrollView: ScrollView
    private lateinit var projectCreateLayout: LinearLayout
    private var projectCreateSelectedMethod: String = "proot"
    private var projectCreateProotChip: TextView? = null
    private var projectCreateChrootChip: TextView? = null
    /** null = not probed yet this session; seeded from prefs then live RootShell.probe */
    private var createChrootRootOk: Boolean? = null
    // GitHub Connect (Create + Settings)
    private var ghCreateSubtitle: TextView? = null
    private var ghCreateActionBtn: TextView? = null
    private var ghRepoCard: LinearLayout? = null
    private var ghRepoTriggerLabel: TextView? = null
    private var ghRepoPanel: LinearLayout? = null
    private var ghRepoSearchInput: EditText? = null
    private var ghRepoListLayout: LinearLayout? = null
    private var ghRepoCountTv: TextView? = null
    private var ghRepoPanelOpen: Boolean = false
    private var ghReposLoading: Boolean = false
    private var ghReposForMethod: List<GhRepo> = emptyList()
    private var ghSelectedRepo: GhRepo? = null
    private var ghAuthSession: GhAuthSession? = null
    private var ghAuthOverlay: View? = null
    private var settingsGhCard: View? = null
    private var settingsGhSubtitle: TextView? = null
    private var settingsGhActionBtn: TextView? = null
    private var settingsGhLogoutBtn: TextView? = null
    private var settingsGhBadge: TextView? = null
    private lateinit var projectsListContainer: FrameLayout
    private lateinit var projectsListScrollView: ScrollView
    private lateinit var projectsListLayout: LinearLayout

    // Home dashboard widgets
    private lateinit var homeStatusDot: View
    private lateinit var homeStatusLabel: TextView
    private lateinit var homeContainerLabel: TextView
    private lateinit var startGuiBtn: TextView
    private lateinit var stopGuiBtn: TextView

    private val composeCpuState = androidx.compose.runtime.mutableIntStateOf(34)
    private val composeMemState = androidx.compose.runtime.mutableIntStateOf(82)
    private val composeRamUsedState = androidx.compose.runtime.mutableLongStateOf(0L)
    private val composeRamTotalState = androidx.compose.runtime.mutableLongStateOf(0L)
    private val composeSwapUsedState = androidx.compose.runtime.mutableLongStateOf(0L)
    private val composeSwapTotalState = androidx.compose.runtime.mutableLongStateOf(0L)
    private val composeDiskState = androidx.compose.runtime.mutableIntStateOf(0)

    // Proot Settings page — size-only (app storage rootfs)
    private var prootSizeValueTv: TextView? = null
    private var prootSizeUnitTv: TextView? = null
    private var prootSizeHintTv: TextView? = null
    private var prootRefreshBtn: TextView? = null
    private var prootLoadingRow: LinearLayout? = null
    private var prootProgressBar: ProgressBar? = null
    private var prootPathTv: TextView? = null
    private var prootMeasuring = false

    // Chroot Settings page — live labels for async refresh
    private var chrootStatusBadge: TextView? = null
    private var chrootRootBadge: TextView? = null
    private var chrootRootRowView: View? = null
    private var chrootSizePanelView: View? = null
    private var chrootPathRowView: View? = null
    private var chrootSizeValueTv: TextView? = null
    private var chrootSizeUnitTv: TextView? = null
    private var chrootSizeHintTv: TextView? = null
    private var chrootUninstallBtn: TextView? = null
    private var chrootInstallBtn: TextView? = null
    private var chrootRefreshBtn: TextView? = null
    private var chrootLoadingRow: LinearLayout? = null
    private var chrootProgressBar: ProgressBar? = null
    private var chrootMeasuring = false
    private var pendingChrootUninstall = false
    /** Marketplace install/uninstall finished via script-runner terminal. */
    private var pendingMarketplaceRun: PackageInstallRunner.PreparedRun? = null

    // Chroot Settings — Processes card (detect / kill / verify)
    private var chrootProcCountTv: TextView? = null
    private var chrootProcUnitTv: TextView? = null
    private var chrootProcHintTv: TextView? = null
    private var chrootProcSampleBox: LinearLayout? = null
    private var chrootProcScanBtn: TextView? = null
    private var chrootProcKillBtn: TextView? = null
    private var chrootProcLoadingRow: LinearLayout? = null
    private var chrootProcStatusTv: TextView? = null
    private var chrootProcMeasuring = false
    private var chrootProcKilling = false
    private var chrootProcLastCount = -1

    companion object {
        private const val PREF_PROOT_BYTES = "proot_size_bytes"
        private const val PREF_PROOT_DIR = "proot_dir_present"
        private const val PREF_PROOT_LAST_MS = "proot_last_check_ms"
        private const val PREF_CHROOT_INSTALLED = "chroot_installed"
        private const val PREF_CHROOT_DIR = "chroot_dir_present"
        private const val PREF_CHROOT_BYTES = "chroot_size_bytes"
        private const val PREF_CHROOT_ROOT_OK = "chroot_root_ok"
        private const val PREF_CHROOT_SIZE_VIA_ROOT = "chroot_size_via_root"
        private const val PREF_CHROOT_LAST_MS = "chroot_last_check_ms"
        private const val PREF_CHROOT_PROC_COUNT = "chroot_proc_count"
        private const val PREF_CHROOT_PROC_LAST_MS = "chroot_proc_last_ms"
        /** B12 outbound share stage under filesDir (and optional cache twin). */
        private const val SHARE_EXPORT_REL = "share/export"
        private const val SHARE_MAX_BYTES = 200L * 1024 * 1024
        private const val SHARE_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val ID_HOME     = 1
    private val ID_FILES    = 2
    private val ID_TERMINAL = 3
    private val ID_GIT      = 4
    private val ID_SETTINGS = 5
    private val ID_SCRIPTS  = 6
    private val ID_SCRIPT_INSTALL = 7
    private val ID_PROJECT_CREATE = 8
    private val ID_PROJECTS_LIST = 9
    private val ID_PROJECT_WORKSPACE = 10
    private val ID_PROJECT_SETTINGS = 11
    private val ID_PROJECT_DIR_TREE = 12
    private val ID_PROJECT_GIT_DIFF = 13
    private val ID_CHROOT_SETTINGS = 14
    private val ID_PROOT_SETTINGS = 15
    private val ID_SOFTWARE_MANAGER = 16
    private val ID_MARKETPLACE = 17
    private val ID_MP_INSTALLER = 18
    private val ID_CLI_AUTH = 19
    private val ID_AI_SAFETY = 20
    private val ID_APPS = 21
    private val ID_PRIVACY_POLICY = 22
    private val ID_COMPONENTS = 23
    private val ID_HARDWARE = 24
    private val ID_X11_SETTINGS = 25
    private val ID_PROOT_FILES = 26
    private val ID_SKILLS = 27
    private val ID_TOPBAR = 28
    private val ID_BACKUP = 29
    private val ID_SSH = 30
    private val ID_USAGE = 31
    private var usagePage: com.zenithblue.nativecode.usage.ui.UsagePage? = null
    private val usageHost = object : com.zenithblue.nativecode.usage.ui.UsageHostApi {
        override fun allowed(agent: String) =
            FeatureGate.canLaunchAiTool(isPro(), com.zenithblue.nativecode.usage.UsageModel.toolId(agent)) is GateResult.Allow
        override fun upgrade() { gateAllows(FeatureGate.canLaunchAiTool(isPro(), "claude-code")) }
        override fun openLogin(agent: String) {
            val tool = com.zenithblue.nativecode.usage.UsageModel.toolId(agent)
            CliToolCatalog.ALL.find { it.terminalType == tool }?.let { showCliLogin(it.id) }
        }
        override fun openComponents() = openComponentsPage()
    }
    private fun openComponentsPage() {
        if (pageStack.isEmpty() || pageStack.peek() != ID_COMPONENTS) pageStack.push(ID_COMPONENTS)
        navigateToPage(ID_COMPONENTS)
    }
    /** Settings sub-pages built by [buildExtraPage] (SSH, AI usage): shown/hidden/back-handled generically. */
    private val extraPages = LinkedHashMap<Int, ScrollView>()
    private val extraPageOnShow = HashMap<Int, () -> Unit>()
    private var sshPage: com.zenithblue.nativecode.ssh.ui.SshPage? = null
    /** One-shot `ssh ...` command and tab title for the next app terminal tab of type "ssh". */
    private var sshTabCommand: String? = null
    private var sshTabTitle: String? = null
    private val sshHost = object : com.zenithblue.nativecode.ssh.ui.SshHostApi {
        override fun serverAllowed() = gateAllows(FeatureGate.canUseSshServer(isPro()))
        override fun isPro() = this@MainActivity.isPro()
        override fun connect(title: String, command: String) = openSshTab(title, command)
        override fun openDocument(onResult: (Uri?) -> Unit) { docResult = onResult; openDocLauncher.launch(arrayOf("*/*")) }
        override fun requestNotifications() = NativeCodeFgs.ensurePostNotificationsPermission(this@MainActivity)
        override fun openComponents() = openComponentsPage()
    }
    private lateinit var topBarScrollView: ScrollView
    private lateinit var backupScrollView: ScrollView
    private var backupPage: com.zenithblue.nativecode.backup.ui.BackupPage? = null
    private var docResult: ((Uri?) -> Unit)? = null
    private val createDocLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        docResult.also { docResult = null }?.invoke(uri)
    }
    private val openDocLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        docResult.also { docResult = null }?.invoke(uri)
    }
    private val backupHost = object : com.zenithblue.nativecode.backup.ui.BackupHost {
        override fun createDocument(name: String, onResult: (Uri?) -> Unit) { docResult = onResult; createDocLauncher.launch(name) }
        override fun openDocument(onResult: (Uri?) -> Unit) { docResult = onResult; openDocLauncher.launch(arrayOf("*/*")) }
        override fun aiSessionsOpen() = aiSessionCount() > 0
        override fun keepAwake(on: Boolean) {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else applyKeepAwake()
        }
    }

    private var fileViewerBackPage = ID_FILES
    private var diffViewerBackPage = ID_GIT

    private var isScriptRunning = false
    private var resourceMonitorRunnable: Runnable? = null
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L

    private var activeProjectName = "MyAndroidApp"
    private var activeProjectPathBacking = "/home/flux/repos/my-android-app"
    private var projectGeneration = 0
    private var activeProjectPath: String
        get() = activeProjectPathBacking
        set(value) {
            if (activeProjectPathBacking != value) projectGeneration++
            activeProjectPathBacking = value
        }
    private var diffRequestGen = 0
    private var diffRequestName: String? = null
    /** Isolation method for [activeProjectPath] (proot|chroot). SSOT for path resolve. */
    private var activeProjectMethodBacking: String = "proot"
    private var activeProjectMethod: String
        get() = activeProjectMethodBacking
        set(value) {
            if (activeProjectMethodBacking != value) projectGeneration++
            activeProjectMethodBacking = value
        }
    private var terminalLifecycleGeneration = 0
    private var terminalLaunchSeq = 0L
    private var appLaunchWatermark = 0L
    private var workspaceLaunchWatermark = 0L
    private var pendingAppTerminalSlots = 0
    private var pendingWorkspaceTerminalSlots = 0
    private var pendingAiTerminalLaunches = 0
    /** Project whose tabs are in [workspaceSessions] right now ("" = none loaded yet). */
    private var workspaceLoadedPath = ""
    private val chatsChanged: () -> Unit = { mainHandler.post { if (!isDestroyed) syncTerminalServices() } }
    private val bootstrapLock = Any()
    @Volatile private var bootstrapReady = false
    @Volatile private var bootstrapInFlight = false
    private lateinit var fileExplorerTitleTv: TextView
    private lateinit var projectIconInput: EditText
    private lateinit var projectNameInput: EditText
    private lateinit var projectPathInput: EditText
    private lateinit var projectGithubInput: EditText
    private lateinit var projectCreateBtn: TextView
    private lateinit var recentProjectsContainer: LinearLayout

    private var activeIconInput: EditText? = null
    private var activeIconPreviewIv: ImageView? = null
    private var activeIconPreviewTv: TextView? = null

    private fun updateIconPreview(input: String, previewIv: ImageView, previewTv: TextView) {
        val str = input.trim()
        if (str.isEmpty()) {
            previewIv.setImageResource(R.drawable.ic_folder)
            previewIv.setColorFilter(NC.PRIMARY)
            previewIv.visibility = View.VISIBLE
            previewTv.visibility = View.GONE
            return
        }
        val file = File(str)
        if (file.exists() && file.isFile) {
            try {
                val bm = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bm != null) {
                    previewIv.setImageBitmap(bm)
                    previewIv.clearColorFilter()
                    previewIv.visibility = View.VISIBLE
                    previewTv.visibility = View.GONE
                    return
                }
            } catch (e: Exception) {}
        }
        if (str.startsWith("http://") || str.startsWith("https://") || str.startsWith("content://") || str.startsWith("file://")) {
            try {
                val uri = android.net.Uri.parse(str)
                previewIv.setImageURI(uri)
                previewIv.clearColorFilter()
                previewIv.visibility = View.VISIBLE
                previewTv.visibility = View.GONE
                return
            } catch (e: Exception) {}
        }
        previewTv.text = str
        previewTv.visibility = View.VISIBLE
        previewIv.visibility = View.GONE
    }

    private val projectIconPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            try {
                val filename = "icon_" + System.currentTimeMillis() + ".png"
                val destFile = File(filesDir, filename)
                contentResolver.openInputStream(selectedUri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                activeIconInput?.setText(destFile.absolutePath)
                activeIconPreviewIv?.let { iv ->
                    activeIconPreviewTv?.let { tv ->
                        updateIconPreview(destFile.absolutePath, iv, tv)
                    }
                }
            } catch (e: Exception) {
                // Try taking persistable permission as fallback
                try {
                    contentResolver.takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (pe: Exception) {}
                activeIconInput?.setText(selectedUri.toString())
            }
        }
    }

    private lateinit var projectSettingsContainer: FrameLayout
    private lateinit var projectSettingsScrollView: ScrollView
    private lateinit var projectSettingsLayout: LinearLayout
    private lateinit var configIconInput: EditText

    private lateinit var projectDirTreeContainer: FrameLayout
    private lateinit var directoryPage: com.zenithblue.nativecode.workspace.DirectoryPage
    /** Sticky DIRECTORY title + search (outside ScrollView). */
    private lateinit var projectGitDiffContainer: FrameLayout
    private lateinit var projectGitDiffScrollView: ScrollView
    private lateinit var projectGitDiffLayout: LinearLayout
    private lateinit var projectGitHistoryView: com.zenithblue.nativecode.git.ui.GitHistoryView
    private var projectGitTab = 0 // 0 = CHANGES, 1 = HISTORY
    private var projectGitTabBtns: List<TextView> = emptyList()
    /** Non-null while the diff viewer shows one file of a commit (from the history page). */
    private var diffViewerCommit: String? = null


    private lateinit var projectWorkspaceContainer: FrameLayout
    private lateinit var projectWorkspaceLayout: LinearLayout
    private lateinit var workspaceTerminalView: TerminalView
    /** Shared tab strip (same component as the TERMINAL page, [termTabStrip]). */
    private var wsTabStrip: TermTabStrip? = null
    private lateinit var workspaceHubLayout: View
    /** Host for workspace hub AI tool cards; rebuilt on linux_method change. */
    private var workspaceHubToolsHost: LinearLayout? = null
    private lateinit var projectGitChangesView: com.zenithblue.nativecode.git.ui.GitChangesView
    private lateinit var workspaceProjectNameTv: TextView
    private lateinit var workspaceProjectIconIv: ImageView

    private val workspaceSessions = ArrayList<TerminalSession>()
    private val workspaceTabNames = ArrayList<String>()
    private var activeWorkspaceTabIndex = -1

    private lateinit var workspaceTerminalContainer: LinearLayout
    private lateinit var workspaceKeyboardToolbar: ExtraKeysBar
    private lateinit var terminalKeyboardToolbar: ExtraKeysBar

    private val termModState = ModifierState()
    private val wsModState   = ModifierState()

    private val termImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> val cb = chatPick.also { chatPick = null }; uri?.let { if (cb != null) cb(it) else handleImageAttachment(it, isWorkspace = false) } }

    private val wsImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { handleImageAttachment(it, isWorkspace = true) } }

    private val termDocumentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> val cb = chatPick.also { chatPick = null }; uri?.let { if (cb != null) cb(it) else handleFileAttachment(it, isWorkspace = false) } }

    /** One-shot: the chat composer's pick goes to the chat (ChatAttachments), not into the terminal. */
    private var chatPick: ((Uri) -> Unit)? = null

    private val wsDocumentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleFileAttachment(it, isWorkspace = true) } }

    private val projectSessionsMap get() = TerminalStore.projectSessions
    private val projectTabNamesMap get() = TerminalStore.projectTabNames
    private val projectActiveTabIndexMap get() = TerminalStore.projectActiveTab

    // History tracking for back button support
    private val pageStack = java.util.Stack<Int>()
    /** Actually visible page — SSOT for nav chrome (stack can lag after bad backs). */
    private var currentPageId: Int = 1

    // ── Linux isolation method ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safe if Splash already ran; covers deep-link / skip-splash entry
        AppUpgrade.runIfNeeded(this)
        com.zenithblue.nativecode.bridge.NativeCodeBridge.start(this)
        // Pro: connect Play Billing (queries products + purchases)
        BillingRepository.init(this)
        AgentChats.onChanged = chatsChanged
        AgentChats.gate = chatGate // chat sends pass the same aiLaunchAllowed / FeatureGate as terminal tabs
        com.zenithblue.nativecode.agent.chat.tools.NcMcp.install(this) // M7: NC tools MCP hooks (server binds on first chat spawn)
        if (AgentChats.liveCount() + AgentChats.startingCount() > 0) chatsChanged() // chats outlived a recreate

        // Enable fullscreen/immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.statusBarColor = NC.BG
        window.navigationBarColor = NC.BG

        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        val setupCompleteFile = File(filesDir, "setup_complete")
        val isSetupComplete = setupCompleteFile.exists() || prefs.getBoolean("onboarding_completed", false)
        if (!isSetupComplete) {
            val intent = Intent(this, OnboardingActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }
        termFontSize = prefs.getInt("pref_terminal_zoom", 40)
        workspaceFontSize = termFontSize
        scriptFontSize = termFontSize
        showExtraKeys = prefs.getBoolean("pref_show_extra_keys", true)
        keepAwake = prefs.getBoolean("pref_keep_awake", false)
        val storedMethod = prefs.getString("linux_method", "proot") ?: "proot"
        LinuxCommandBuilder.currentMethod = if (com.zenithblue.nativecode.ProotOnly.ON) "proot" else storedMethod
        if (com.zenithblue.nativecode.ProotOnly.ON && storedMethod != "proot") {
            prefs.edit()
                .putString("linux_method", "proot")
                .remove("ai_cli_tools_provisioned_chroot")
                .apply()
        }
        activeProjectName = prefs.getString("active_project_name", activeProjectName) ?: activeProjectName
        activeProjectPath = prefs.getString("active_project_path", activeProjectPath) ?: activeProjectPath
        activeProjectMethod = prefs.getString("active_project_method", null)
            ?: getProjects().find { it.path == activeProjectPath }?.linuxMethod
            ?: LinuxCommandBuilder.currentMethod

        executor.execute { LegacyProotDataMigrator.migrateIfNeeded(this) }
        executor.execute { HostServerController.ensureServers(this) }
        if (BuildConfig.PLAY_STORE_BUILD &&
            !AndroidDevPackManager.isApplied(this) &&
            AndroidDevPackManager.resolveArchive(this) != null
        ) {
            executor.execute {
                val result = AndroidDevPackManager.applyAvailablePack(this)
                Log.i("AndroidDevPack", result.message)
            }
        }
        // 1.7.x rootfs -> 1.8.0 runtime: small Play pack, applied by Kotlin file I/O (no guest scripts).
        // PLAY_STORE_BUILD is hard-coded false since the play flavor was removed, so it cannot gate this.
        // Not downloaded yet: no dialog, the Components card + home banner offer it (refreshComponentUpdates).
        RuntimeUpdatePack.applyIfDownloaded(applicationContext)
        // 1.7.1 kept the 1.3 GB nativecode_runtime pack after extracting it; Play would patch it on every update.
        RuntimeUpdatePack.dropInstalledRuntimePack(applicationContext)

        buildRootLayout()
        setContentView(drawerLayout)
        adoptTerminals()
        applyKeepAwake()
        ensurePostNotificationsPermission()
        Telemetry.apply(this)
        ConsentSheet.showOnceIfUnasked(this)

        // Apply Insets
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            unifiedHeader.setPadding(dp(16), dp(12), dp(16), dp(12))
            
            if (::projectWorkspaceLayout.isInitialized) {
                val workspaceTopBar = projectWorkspaceLayout.getChildAt(0) as? LinearLayout
                workspaceTopBar?.setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            if (::terminalWorkspaceLayout.isInitialized) {
                val terminalTopBar = terminalWorkspaceLayout.getChildAt(0) as? LinearLayout
                terminalTopBar?.setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            if (::scriptInstallLayout.isInitialized) {
                val installTopBar = scriptInstallLayout.getChildAt(0) as? LinearLayout
                installTopBar?.setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            if (::mpInstallerLayout.isInitialized) {
                val mpTop = mpInstallerLayout.getChildAt(0) as? LinearLayout
                mpTop?.setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            if (::projectCreateScrollView.isInitialized) {
                projectCreateScrollView.setPadding(0, 0, 0, 0)
            }
            if (::projectSettingsLayout.isInitialized) {
                val settingsTopBar = projectSettingsLayout.getChildAt(0) as? LinearLayout
                settingsTopBar?.setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            if (::projectGitDiffLayout.isInitialized) {
                val gitDiffTopBar = projectGitDiffLayout.getChildAt(0) as? LinearLayout
                gitDiffTopBar?.setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            
            val currentPage = currentPageId
            val showGlobalNav = (currentPage == ID_HOME || currentPage == ID_PROJECTS_LIST || currentPage == ID_TERMINAL || currentPage == ID_SETTINGS)
            val showProjectNav = (currentPage == ID_PROJECT_WORKSPACE || currentPage == ID_PROJECT_SETTINGS || currentPage == ID_PROJECT_DIR_TREE || currentPage == ID_PROJECT_GIT_DIFF)
            val isLandscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
            val isKeyboardOpen = ime.bottom > 0

            // System bars are hidden in immersive mode. Never retain a stale
            // pre-hide top inset; that produced a temporary blank strip above
            // the first page header until navigation forced another layout.
            mainContentLayout.setPadding(0, 0, if (isLandscape) bars.right else 0, 0)
            sideNavContainer.setPadding(if (isLandscape) bars.left else 0, 0, 0, bars.bottom)

            if (::bottomNavContainer.isInitialized) {
                if (!isLandscape && (showGlobalNav || showProjectNav) && !isKeyboardOpen) {
                    bottomNavContainer.visibility = View.VISIBLE
                    bottomNavContainer.setPadding(bars.left, 0, bars.right, bars.bottom)
                    contentFrame.setPadding(0, 0, 0, 0)
                } else {
                    bottomNavContainer.visibility = View.GONE
                    bottomNavContainer.setPadding(0, 0, 0, 0)
                    contentFrame.setPadding(0, 0, 0, if (isKeyboardOpen) ime.bottom else bars.bottom)
                }
            }
            // Chat position: the IME padding above resizes the composer; the hidden TerminalView is left alone.
            if (currentPage == ID_TERMINAL && !isChatMode(ws = false)) {
                forceTerminalResize(if (::terminalView.isInitialized) terminalView else null)
            } else if (currentPage == ID_PROJECT_WORKSPACE && !isChatMode(ws = true)) {
                forceTerminalResize(if (::workspaceTerminalView.isInitialized) workspaceTerminalView else null)
            }
            insets
        }
        ViewCompat.requestApplyInsets(drawerLayout)

        // Setup back callback for predictive/system back
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onBackPressed()
            }
        })

        setupBottomNavigationListener()
        setupProjectBottomNavigationListener()
        setupGlobalNavRailListener()
        setupProjectNavRailListener()

        val targetPage = intent.getIntExtra("target_page", ID_HOME)
        if (targetPage != ID_HOME) {
            pageStack.push(targetPage)
            navigateToPage(targetPage)
        } else {
            showHome()
        }
        TerminalStore.restorePage.also { TerminalStore.restorePage = 0 }
            .takeIf { it == ID_SETTINGS || it == ID_TERMINAL }
            ?.let { pageStack.push(it); navigateToPage(it) }
        onSetupComplete()
        handleIntent(intent)
        InAppReportService.retryQueued(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        chatResumed = true
        updateChatShown()
        NativeCodeBridge.agentUiDelegate = this
        applyKeepAwake()
        // Login may have finished in the browser or terminal: re-read the auth files.
        if (::cliAuthScrollView.isInitialized && cliAuthScrollView.visibility == View.VISIBLE) paintCliAuth()
        if (desktop.isRunning) desktop.syncUi() // desktop started by a previous (recreated) Activity
        // Files may have changed in a terminal or another app: refresh the visible CHANGES tab.
        if (::projectGitDiffContainer.isInitialized && projectGitDiffContainer.visibility == View.VISIBLE && projectGitTab == 0) refreshGitDiffTree()
        if (lastProState == null) lastProState = isPro() // state the onCreate-built views reflect
        if (!componentUpdatesChecked && ::componentsScrollView.isInitialized) {
            componentUpdatesChecked = true
            refreshComponentUpdates()
        }
        BillingRepository.onChanged = { onEntitlementChanged() }
        BillingRepository.refresh()
    }

    override fun onPause() {
        super.onPause()
        chatResumed = false
        updateChatShown()
        if (NativeCodeBridge.agentUiDelegate === this) {
            NativeCodeBridge.agentUiDelegate = null
        }
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
            .putString("active_project_name", activeProjectName)
            .putString("active_project_path", activeProjectPath)
            .putString("active_project_method", activeProjectMethod)
            .apply()
    }

    /** Host dir for active project using project method (not ambient global alone). */
    private fun activeProjectHostDir(): File =
        ProjectPathResolver.resolve(this, activeProjectPath, activeProjectMethod)

    /**
     * Apply isolation method for a project: switch global, persist, repair if clone
     * lives only under the opposite rootfs.
     * @return effective method used after optional recovery
     */
    private fun applyProjectIsolation(path: String, preferredMethod: String): String {
        var method = if (com.zenithblue.nativecode.ProotOnly.ON) "proot" else preferredMethod
        val found = ProjectManager.detectRepoMethod(this, path, method)
        if (found != null && found != preferredMethod) {
            method = found
            val list = getProjects().toMutableList()
            val idx = list.indexOfFirst { it.path == path }
            if (idx >= 0) {
                val p = list[idx]
                list[idx] = p.copy(linuxMethod = method)
                saveProjects(list)
            }
            Toast.makeText(
                this,
                "Repo found under ${ProjectPathResolver.methodLabel(method)}; using that method",
                Toast.LENGTH_LONG
            ).show()
        }
        activeProjectMethod = method
        LinuxCommandBuilder.currentMethod = method
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
            .edit()
            .putString("linux_method", method)
            .putString("active_project_method", method)
            .apply()
        refreshToolCardsForMethod()
        return method
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            // Desktop notification "Stop": through the controller like every other entry point.
            if (it.action == BackgroundService.ACTION_DESKTOP_STOP) {
                it.action = null // one-shot: not replayed if this Activity is recreated
                desktop.stop()
            }
            // Tile / widget / shortcuts: one fixed action, validated, one-shot (quick/QuickActions).
            QuickActions.parse(it)?.let { a -> it.removeExtra(QuickActions.EXTRA); handleQuickAction(a) }
            // Chroot uninstall script deep-link: nativecode://callback?result=success&name=distro_uninstall_...
            val data = it.data
            if (!com.zenithblue.nativecode.ProotOnly.ON && data != null && data.scheme == "nativecode" && data.host == "callback") {
                val name = data.getQueryParameter("name").orEmpty()
                val result = data.getQueryParameter("result").orEmpty()
                if (name.contains("uninstall", ignoreCase = true) && result == "success") {
                    onChrootUninstalled(fromCallback = true)
                }
            }
            // Project notification tap: gate first so a locked project gets no side effects.
            val projBlocked = it.getStringExtra("PROJECT_PATH") != null && !projectGateAllows()
            val projPath = if (projBlocked) null else it.getStringExtra("PROJECT_PATH")
            if (projPath != null) {
                activeProjectPath = projPath
                val preferred = getProjects().find { p -> p.path == projPath }?.linuxMethod
                    ?: LinuxCommandBuilder.currentMethod
                applyProjectIsolation(projPath, preferred)
            }
            val projName = if (projBlocked) null else it.getStringExtra("PROJECT_NAME")
            if (projName != null) {
                activeProjectName = projName
            }
            val targetPage = if (projBlocked) -1 else if (it.hasExtra("EXTRA_TARGET_PAGE")) {
                it.getIntExtra("EXTRA_TARGET_PAGE", -1)
            } else {
                it.getIntExtra("target_page", -1)
            }
            if (targetPage != -1) {
                navigateToPage(targetPage)
            }
            // Chat notification: open the thread in the Chat position (one-shot, not replayed on recreate).
            it.getStringExtra(ChatNotifications.EXTRA_THREAD_ID)?.let { threadId ->
                it.removeExtra(ChatNotifications.EXTRA_THREAD_ID)
                if (!projBlocked) openChatThreadFromIntent(threadId, if (targetPage == ID_PROJECT_WORKSPACE) ID_PROJECT_WORKSPACE else ID_TERMINAL)
            }
            val createTerm = it.getStringExtra("CREATE_TERM")
            if (createTerm != null) {
                createNewTerminalSession(createTerm)
                navigateToPage(ID_TERMINAL)
            }
            val agentApkPath = it.getStringExtra("EXTRA_AGENT_INSTALL_APK")
            if (!agentApkPath.isNullOrBlank()) {
                val apkFile = File(agentApkPath)
                val title = it.getStringExtra("EXTRA_AGENT_TITLE")
                val message = it.getStringExtra("EXTRA_AGENT_MESSAGE")
                showAgentInstallApkDialog(apkFile, title, message)
            }
            // Token, not a path: MainActivity is exported, so a path extra would let any app share our private files.
            it.getStringExtra("EXTRA_AGENT_SHARE_TOKEN")?.let { t -> AgentBridgeServer.takeShare(t) }?.let { f -> shareApkFromProject(f, "Share file") }
            val agentOpenUrl = it.getStringExtra("EXTRA_AGENT_OPEN_LINK_URL")
            if (!agentOpenUrl.isNullOrBlank()) {
                val title = it.getStringExtra("EXTRA_AGENT_TITLE")
                val message = it.getStringExtra("EXTRA_AGENT_MESSAGE")
                showAgentOpenLinkDialog(agentOpenUrl, title, message)
            }
        }
    }

    /** Every action goes through the usual gates (desktop budget, AI tool/session, workspace). */
    private fun handleQuickAction(action: String) {
        when (action) {
            QuickActions.START_DESKTOP -> desktop.start()
            QuickActions.STOP_DESKTOP -> desktop.stop()
            QuickActions.NEW_TERMINAL -> { createNewTerminalSession("shell"); navigateToPage(ID_TERMINAL) }
            QuickActions.LAST_PROJECT -> openLastProject(null)
            // Free: opencode in the app terminal (workspace is Pro). Pro: last-used tool in the last project.
            QuickActions.AGENT -> {
                val tool = QuickActions.agentTool(this)
                if (isPro()) openLastProject(tool) else { createNewTerminalSession(tool); navigateToPage(ID_TERMINAL) }
            }
        }
    }

    /** Most recently opened project; none yet → the project picker. */
    private fun openLastProject(tool: String?) {
        val p = getProjects().maxByOrNull { it.lastOpened }
        if (p == null) { navigateToPage(ID_PROJECTS_LIST); return }
        openProjectFromList(p)
        if (tool != null && FeatureGate.canUse(isPro(), ProFeature.WORKSPACE) is GateResult.Allow) createWorkspaceTerminalTab(tool)
    }

    private fun ensurePostNotificationsPermission() {
        NativeCodeFgs.ensurePostNotificationsPermission(this)
    }

    private fun startNativeCodeFgs(intent: Intent) {
        NativeCodeFgs.start(this, intent)
    }

    private fun updateAppTerminalService() {
        syncTerminalServices()
    }

    private fun updateProjectTerminalService() {
        syncTerminalServices()
    }

    private fun syncTerminalServices() {
        ensurePostNotificationsPermission()
        // Agent chats count like tabs: chat-only use must keep the FGS too (R4 B4).
        val appCount = sessionsList.count { it.isRunning } + AgentChats.countFor(null)
        saveActiveProjectSessions()

        val projectList = ArrayList<TerminalService.ProjectState>()
        val allProjects = getProjects()
        for (path in projectSessionsMap.keys + AgentChats.projectPaths()) {
            val running = (projectSessionsMap[path]?.count { it.isRunning } ?: 0) + AgentChats.countFor(path)
            if (running > 0) {
                val name = allProjects.find { it.path == path }?.name
                    ?: if (path == activeProjectPath && activeProjectName.isNotEmpty()) activeProjectName else File(path).name
                val tabs = (projectTabNamesMap[path] ?: emptyList()) + AgentChats.toolsFor(path)
                projectList.add(
                    TerminalService.ProjectState(
                        name = name,
                        path = path,
                        sessionCount = running,
                        tabNames = tabs
                    )
                )
            }
        }

        TerminalService.syncState(this, appCount = appCount, projects = projectList)
    }

    // ── Unified Navigation & Switcher ────────────────────────────────────────

    private fun setupBottomNavigationListener() {
        if (!::bottomNavigation.isInitialized) return
        bottomNavigation.setOnItemSelectedListener { item ->
            val pageId = item.itemId
            if (::globalNavRail.isInitialized) {
                globalNavRail.setOnItemSelectedListener(null)
                globalNavRail.selectedItemId = pageId
                setupGlobalNavRailListener()
            }
            if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                pageStack.push(pageId)
            }
            navigateToPage(pageId)
            true
        }
    }
    
    private fun setupGlobalNavRailListener() {
        if (!::globalNavRail.isInitialized) return
        globalNavRail.setOnItemSelectedListener { item ->
            val pageId = item.itemId
            if (::bottomNavigation.isInitialized) {
                bottomNavigation.setOnItemSelectedListener(null)
                bottomNavigation.selectedItemId = pageId
                setupBottomNavigationListener()
            }
            if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                pageStack.push(pageId)
            }
            navigateToPage(pageId)
            true
        }
    }

    private fun setupProjectBottomNavigationListener() {
        if (!::projectBottomNavigation.isInitialized) return
        projectBottomNavigation.setOnItemSelectedListener { item ->
            val pageId = item.itemId
            if (::projectNavRail.isInitialized) {
                projectNavRail.setOnItemSelectedListener(null)
                projectNavRail.selectedItemId = pageId
                setupProjectNavRailListener()
            }
            if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                pageStack.push(pageId)
            }
            navigateToPage(pageId)
            true
        }
    }
    
    private fun setupProjectNavRailListener() {
        if (!::projectNavRail.isInitialized) return
        projectNavRail.setOnItemSelectedListener { item ->
            val pageId = item.itemId
            if (::projectBottomNavigation.isInitialized) {
                projectBottomNavigation.setOnItemSelectedListener(null)
                projectBottomNavigation.selectedItemId = pageId
                setupProjectBottomNavigationListener()
            }
            if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                pageStack.push(pageId)
            }
            navigateToPage(pageId)
            true
        }
    }

    private fun navigateToPage(id: Int) {
        navigateToPage(id, true)
    }

    /** Project open gate (free = 0 projects). Deny → Upgrade sheet. */
    private fun projectGateAllows(): Boolean {
        if (FeatureGate.canUse(isPro(), ProFeature.WORKSPACE) is GateResult.Allow) return true
        showUpgradeSheet(tr(R.string.main_unlock_your_projects_with_nativecode))
        return false
    }

    private fun navigateToPage(id: Int, pushToStack: Boolean) {
        // Every project page (workspace, settings, dir tree, git diff) is Pro; all open paths route here.
        val projectPage = id == ID_PROJECT_WORKSPACE || id == ID_PROJECT_SETTINGS || id == ID_PROJECT_DIR_TREE || id == ID_PROJECT_GIT_DIFF
        if (projectPage && !projectGateAllows()) {
            if (pageStack.isNotEmpty() && pageStack.peek() == id) pageStack.pop()
            return
        }
        if (pushToStack) {
            if (pageStack.isEmpty() || pageStack.peek() != id) {
                pageStack.push(id)
            }
        }
        currentPageId = id
        updateChatShown()

        updateNavigationVisibility(id)
        updateCustomBottomNavSelection(id)

        // Hide keyboard when switching pages
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(drawerLayout.windowToken, 0)
        window.decorView.clearFocus()

        if (::terminalView.isInitialized) {
            terminalView.isFocusable = false
            terminalView.isFocusableInTouchMode = false
            terminalView.clearFocus()
        }
        if (::workspaceTerminalView.isInitialized) {
            workspaceTerminalView.isFocusable = false
            workspaceTerminalView.isFocusableInTouchMode = false
            workspaceTerminalView.clearFocus()
        }

        // Hide all screens
        homeScrollView.visibility = View.GONE
        fileExplorerScrollView.visibility = View.GONE
        terminalWorkspaceLayout.visibility = View.GONE
        gitOperationsScrollView.visibility = View.GONE
        settingsHubScrollView.visibility = View.GONE
        if (::fileViewerRootContainer.isInitialized) {
            fileViewerRootContainer.visibility = View.GONE
        }
        if (::diffViewerRootContainer.isInitialized) {
            diffViewerRootContainer.visibility = View.GONE
        }
        if (::scriptsScrollView.isInitialized) {
            scriptsScrollView.visibility = View.GONE
        }
        if (::cliAuthScrollView.isInitialized) {
            cliAuthScrollView.visibility = View.GONE
        }
        if (::aiSafetyScrollView.isInitialized) {
            aiSafetyScrollView.visibility = View.GONE
        }
        if (::privacyPolicyScrollView.isInitialized) {
            privacyPolicyScrollView.visibility = View.GONE
        }
        if (::componentsScrollView.isInitialized) {
            componentsScrollView.visibility = View.GONE
        }
        if (::topBarScrollView.isInitialized) {
            topBarScrollView.visibility = View.GONE
        }
        if (::backupScrollView.isInitialized) {
            backupScrollView.visibility = View.GONE
        }
        extraPages.values.forEach { it.visibility = View.GONE }
        if (::hardwareScrollView.isInitialized) {
            hardwareScrollView.visibility = View.GONE
        }
        if (::x11SettingsScrollView.isInitialized) {
            x11SettingsScrollView.visibility = View.GONE
        }
        if (::skillsScrollView.isInitialized) {
            skillsScrollView.visibility = View.GONE
        }
        if (::prootSettingsScrollView.isInitialized) {
            prootSettingsScrollView.visibility = View.GONE
        }
        if (::prootFilesScrollView.isInitialized) {
            prootFilesScrollView.visibility = View.GONE
        }
        if (::chrootSettingsScrollView.isInitialized) {
            chrootSettingsScrollView.visibility = View.GONE
        }
        if (::softwareManagerScrollView.isInitialized) {
            softwareManagerScrollView.visibility = View.GONE
        }
        if (::marketplaceScrollView.isInitialized) {
            marketplaceScrollView.visibility = View.GONE
        }
        if (::appsScrollView.isInitialized) {
            appsScrollView.visibility = View.GONE
        }
        if (::mpInstallerLayout.isInitialized) {
            mpInstallerLayout.visibility = View.GONE
        }
        if (::scriptInstallLayout.isInitialized) {
            scriptInstallLayout.visibility = View.GONE
        }
        if (::projectCreateContainer.isInitialized) {
            // Only toggle container — scroll child must stay VISIBLE or form is blank
            projectCreateContainer.visibility = View.GONE
        }
        if (::projectsListContainer.isInitialized) {
            projectsListContainer.visibility = View.GONE
        }
        if (::projectWorkspaceContainer.isInitialized) {
            projectWorkspaceContainer.visibility = View.GONE
        }
        if (::projectSettingsContainer.isInitialized) {
            projectSettingsContainer.visibility = View.GONE
        }
        if (::projectDirTreeContainer.isInitialized) {
            projectDirTreeContainer.visibility = View.GONE
        }
        if (::projectGitDiffContainer.isInitialized) {
            projectGitDiffContainer.visibility = View.GONE
        }

        if (id == ID_TERMINAL) {
            unifiedHeader.visibility = View.GONE
            if (::drawerLayout.isInitialized) drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else if (id == ID_SCRIPT_INSTALL || id == ID_MP_INSTALLER) {
            unifiedHeader.visibility = View.GONE
            if (::bottomNavigation.isInitialized) bottomNavigation.menu.findItem(bottomNavigation.selectedItemId)?.isChecked = false
            if (::drawerLayout.isInitialized) drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else if (id == ID_PROJECT_CREATE) {
            // App title bar (logo + telemetry) stays visible on create/import
            unifiedHeader.visibility = View.VISIBLE
            if (::drawerLayout.isInitialized) drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else if (id == ID_PROJECT_WORKSPACE || id == ID_PROJECT_SETTINGS || id == ID_PROJECT_DIR_TREE || id == ID_PROJECT_GIT_DIFF) {
            unifiedHeader.visibility = View.GONE
            if (::drawerLayout.isInitialized) drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        } else {
            unifiedHeader.visibility = View.VISIBLE
            if (::drawerLayout.isInitialized) drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        }
        
        // Hide back button on top level pages if stack is 1 or empty
        if (::backBtn.isInitialized) {
            backBtn.visibility = if (pageStack.size > 1) View.VISIBLE else View.GONE
        }
        
        ViewCompat.requestApplyInsets(drawerLayout)

        when (id) {
            ID_HOME -> {
                homeScrollView.visibility = View.VISIBLE

        homeScrollView.visibility = View.VISIBLE
                homeScrollView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                populateRecentProjects()
                refreshHomeSsh()
                refreshHomeUsage()
                val valTv = homeLayout.findViewWithTag<TextView>("APP_STORAGE_VAL")
                val subTv = homeLayout.findViewWithTag<TextView>("APP_STORAGE_SUB")
                val barFill = homeLayout.findViewWithTag<View>("APP_STORAGE_BAR")
                if (valTv != null && subTv != null && barFill != null) {
                    updateAppStorageUsage(valTv, subTv, barFill, null)
                }
                animateHomeLayoutEntrance()
            }
            ID_FILES -> {
                fileExplorerScrollView.visibility = View.VISIBLE
                if (::fileExplorerTitleTv.isInitialized) {
                    fileExplorerTitleTv.text = activeProjectName
                }
            }
            ID_TERMINAL -> {
                terminalWorkspaceLayout.visibility = View.VISIBLE
                if (sessionsList.isNotEmpty() && activeSessionIndex >= 0 && activeSessionIndex < sessionsList.size) {
                    if (::terminalToolSelectorScrollView.isInitialized) terminalToolSelectorScrollView.visibility = View.GONE
                    if (::terminalViewContainer.isInitialized) terminalViewContainer.visibility = View.VISIBLE
                    if (::terminalKeyboardToolbar.isInitialized) terminalKeyboardToolbar.visibility = if (showExtraKeys) View.VISIBLE else View.GONE
                    terminalView.isFocusable = true
                    terminalView.isFocusableInTouchMode = true
                    terminalView.requestFocus()
                } else {
                    if (::terminalViewContainer.isInitialized) terminalViewContainer.visibility = View.GONE
                    if (::terminalKeyboardToolbar.isInitialized) terminalKeyboardToolbar.visibility = View.GONE
                    if (::terminalToolSelectorScrollView.isInitialized) {
                        refreshTerminalToolSelector()
                        terminalToolSelectorScrollView.visibility = View.VISIBLE
                        terminalToolSelectorScrollView.post {
                            terminalToolSelectorScrollView.requestLayout()
                            terminalToolSelectorScrollView.invalidate()
                        }
                    }
                }
            }
            ID_GIT -> {
                gitOperationsScrollView.visibility = View.VISIBLE
            }
            ID_SETTINGS -> {
                settingsHubScrollView.visibility = View.VISIBLE
                if (!com.zenithblue.nativecode.ProotOnly.ON) refreshChrootSettingsHubRootGate(force = false)
                refreshSettingsGithubCard()
            }
            ID_PROOT_SETTINGS -> {
                if (::prootSettingsScrollView.isInitialized) {
                    prootSettingsScrollView.visibility = View.VISIBLE
                }
                applyCachedProotInfo()
                refreshProotSettingsCard(force = false)
            }
            ID_PROOT_FILES -> {
                if (::prootFilesScrollView.isInitialized) {
                    prootFilesScrollView.visibility = View.VISIBLE
                    prootFilesScrollView.scrollTo(0, 0)
                }
                prootFilesPage?.refresh()
            }
            ID_CHROOT_SETTINGS -> if (!com.zenithblue.nativecode.ProotOnly.ON) {
                if (::chrootSettingsScrollView.isInitialized) {
                    chrootSettingsScrollView.visibility = View.VISIBLE
                }
                applyInstantChrootStatus()
                applyCachedChrootInfo()
                applyCachedChrootProcInfo()
                // Sequential root work: size then processes (no concurrent su race)
                refreshChrootSettingsPage(force = false)
            }
            ID_SOFTWARE_MANAGER -> {
                if (::softwareManagerScrollView.isInitialized) {
                    softwareManagerScrollView.visibility = View.VISIBLE
                }
                refreshSoftwareManagerPage(force = false)
            }
            ID_MARKETPLACE -> if (!com.zenithblue.nativecode.ProotOnly.ON) {
                if (::marketplaceScrollView.isInitialized) {
                    marketplaceScrollView.visibility = View.VISIBLE
                }
                refreshMarketplacePage(force = false)
            }
            ID_APPS -> {
                if (::appsScrollView.isInitialized) {
                    appsScrollView.visibility = View.VISIBLE
                }
                refreshAppsPage(force = false)
            }
            ID_MP_INSTALLER -> {
                if (::mpInstallerLayout.isInitialized) {
                    mpInstallerLayout.visibility = View.VISIBLE
                }
            }
            ID_SCRIPTS -> {
                if (::scriptsScrollView.isInitialized) {
                    scriptsScrollView.visibility = View.VISIBLE
                    refreshRepairsPage()
                }
            }
            ID_CLI_AUTH -> {
                if (::cliAuthScrollView.isInitialized) {
                    cliAuthScrollView.visibility = View.VISIBLE
                    paintCliAuth()
                }
            }
            ID_AI_SAFETY -> {
                if (::aiSafetyScrollView.isInitialized) {
                    aiSafetyScrollView.visibility = View.VISIBLE
                    refreshAiSafetyPage()
                }
            }
            ID_TOPBAR -> {
                if (::topBarScrollView.isInitialized) {
                    topBarScrollView.visibility = View.VISIBLE
                    topBarScrollView.scrollTo(0, 0)
                }
            }
            ID_BACKUP -> {
                if (::backupScrollView.isInitialized) {
                    backupScrollView.visibility = View.VISIBLE
                    backupScrollView.scrollTo(0, 0)
                }
                backupPage?.refresh()
            }
            in extraPages.keys -> {
                extraPages[id]?.let { it.visibility = View.VISIBLE; it.scrollTo(0, 0) }
                extraPageOnShow[id]?.invoke()
            }
            ID_COMPONENTS -> {
                if (::componentsScrollView.isInitialized) {
                    componentsScrollView.visibility = View.VISIBLE
                    refreshAndroidSdkPackCard()
                    refreshComponentUpdates()
                }
            }
            ID_PRIVACY_POLICY -> {
                if (::privacyPolicyScrollView.isInitialized) {
                    privacyPolicyScrollView.visibility = View.VISIBLE
                    privacyPolicyScrollView.scrollTo(0, 0)
                }
            }
            ID_HARDWARE -> {
                if (::hardwareScrollView.isInitialized) {
                    hardwareScrollView.visibility = View.VISIBLE
                    hardwareScrollView.scrollTo(0, 0)
                }
                refreshHardwarePage()
            }
            ID_X11_SETTINGS -> {
                if (::x11SettingsScrollView.isInitialized) {
                    x11SettingsScrollView.visibility = View.VISIBLE
                    x11SettingsScrollView.scrollTo(0, 0)
                }
                refreshX11SettingsPage()
            }
            ID_SKILLS -> {
                if (::skillsScrollView.isInitialized) {
                    skillsScrollView.visibility = View.VISIBLE
                    skillsScrollView.scrollTo(0, 0)
                }
                skillsPage?.refresh()
            }
            ID_SCRIPT_INSTALL -> {
                if (::scriptInstallLayout.isInitialized) {
                    scriptInstallLayout.visibility = View.VISIBLE
                }
            }
            ID_PROJECT_CREATE -> {
                if (::projectCreateContainer.isInitialized) {
                    projectCreateContainer.visibility = View.VISIBLE
                    if (::projectCreateScrollView.isInitialized) {
                        projectCreateScrollView.visibility = View.VISIBLE
                    }
                    // Live root probe (Settings Chroot gate) + paint chips
                    probeCreateChrootRoot()
                    refreshProjectCreateMethodChips()
                    refreshCreateGithubUi()
                }
            }
            ID_PROJECTS_LIST -> {
                if (::projectsListContainer.isInitialized) {
                    projectsListContainer.visibility = View.VISIBLE
                    populateProjectsList()
                }
            }
            ID_PROJECT_WORKSPACE -> {
                if (::projectWorkspaceContainer.isInitialized) {
                    projectWorkspaceContainer.visibility = View.VISIBLE
                    workspaceTerminalView.isFocusable = true
                    workspaceTerminalView.isFocusableInTouchMode = true
                    openProjectWorkspace()
                }
            }
            ID_PROJECT_SETTINGS -> {
                if (::projectSettingsContainer.isInitialized) {
                    projectSettingsContainer.visibility = View.VISIBLE
                    openProjectSettings()
                }
            }
            ID_PROJECT_DIR_TREE -> {
                if (::projectDirTreeContainer.isInitialized) {
                    projectDirTreeContainer.visibility = View.VISIBLE
                    openProjectDirTree()
                }
            }
            ID_PROJECT_GIT_DIFF -> {
                if (::projectGitDiffContainer.isInitialized) {
                    projectGitDiffContainer.visibility = View.VISIBLE
                    openProjectGitDiff()
                }
            }
        }
        selectBottomNavItem(id)
        applyKeepAwake()
    }

    private fun selectBottomNavItem(id: Int) {
        if (::bottomNavigation.isInitialized) {
            val hasItem = try {
                bottomNavigation.menu.findItem(id) != null
            } catch (e: Exception) {
                false
            }
            if (hasItem) {
                bottomNavigation.setOnItemSelectedListener(null)
                bottomNavigation.selectedItemId = id
                bottomNavigation.setOnItemSelectedListener { item ->
                    val pageId = item.itemId
                    if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                        pageStack.push(pageId)
                    }
                    navigateToPage(pageId)
                    true
                }
            }
        }
        if (::projectBottomNavigation.isInitialized) {
            val hasItem = try {
                projectBottomNavigation.menu.findItem(id) != null
            } catch (e: Exception) {
                false
            }
            if (hasItem) {
                projectBottomNavigation.setOnItemSelectedListener(null)
                projectBottomNavigation.selectedItemId = id
                projectBottomNavigation.setOnItemSelectedListener { item ->
                    val pageId = item.itemId
                    if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                        pageStack.push(pageId)
                    }
                    navigateToPage(pageId)
                    true
                }
            }
        }
    }

    @android.annotation.SuppressLint("MissingSuperCall", "GestureBackNavigation")
    override fun onBackPressed() {
        android.util.Log.e("NativeCodeNav", "onBackPressed called! currentPageId=$currentPageId, stack=$pageStack, privacyPolicyScrollVis=${if (::privacyPolicyScrollView.isInitialized) privacyPolicyScrollView.visibility else -1}")
        if (skillsPicker != null) {
            skillsPicker?.dismiss()
            return
        }

        // Priority 1: Settings subpages (MUST check before any project/workspace checks)
        if (::privacyPolicyScrollView.isInitialized && privacyPolicyScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_PRIVACY_POLICY, fallback = ID_SETTINGS)
            return
        }
        if (::hardwareScrollView.isInitialized && hardwareScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_HARDWARE, fallback = ID_SETTINGS)
            return
        }
        if (::x11SettingsScrollView.isInitialized && x11SettingsScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_X11_SETTINGS, fallback = ID_SETTINGS)
            return
        }
        if (::skillsScrollView.isInitialized && skillsScrollView.visibility == View.VISIBLE) {
            if (skillsPage?.handleBack() == true) return
            navigateBackFromSubpage(ID_SKILLS, fallback = ID_SETTINGS)
            return
        }
        if (::componentsScrollView.isInitialized && componentsScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_COMPONENTS, fallback = ID_SETTINGS)
            return
        }
        if (::topBarScrollView.isInitialized && topBarScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_TOPBAR, fallback = ID_SETTINGS)
            return
        }
        if (::backupScrollView.isInitialized && backupScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_BACKUP, fallback = ID_SETTINGS)
            return
        }
        extraPages.entries.firstOrNull { it.value.visibility == View.VISIBLE }?.let {
            navigateBackFromSubpage(it.key, fallback = ID_SETTINGS)
            return
        }
        if (::aiSafetyScrollView.isInitialized && aiSafetyScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_AI_SAFETY, fallback = ID_SETTINGS)
            return
        }
        if (::cliAuthScrollView.isInitialized && cliAuthScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_CLI_AUTH)
            return
        }
        if (::scriptsScrollView.isInitialized && scriptsScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_SCRIPTS, fallback = ID_SETTINGS)
            return
        }
        if (::prootFilesScrollView.isInitialized && prootFilesScrollView.visibility == View.VISIBLE) {
            if (prootFilesPage?.handleBack() == true) {
                return
            }
            navigateBackFromSubpage(ID_PROOT_FILES, fallback = ID_PROOT_SETTINGS)
            return
        }
        if (::prootSettingsScrollView.isInitialized && prootSettingsScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_PROOT_SETTINGS, fallback = ID_SETTINGS)
            return
        }
        if (::chrootSettingsScrollView.isInitialized && chrootSettingsScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_CHROOT_SETTINGS, fallback = ID_SETTINGS)
            return
        }
        if (::softwareManagerScrollView.isInitialized && softwareManagerScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_SOFTWARE_MANAGER, fallback = ID_SETTINGS)
            return
        }
        if (::marketplaceScrollView.isInitialized && marketplaceScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_MARKETPLACE, fallback = ID_SETTINGS)
            return
        }
        if (::appsScrollView.isInitialized && appsScrollView.visibility == View.VISIBLE) {
            navigateBackFromSubpage(ID_APPS, fallback = ID_SETTINGS)
            return
        }

        // File / diff overlays first — never fall through to workspace → projects list
        if (::fileViewerRootContainer.isInitialized && fileViewerRootContainer.visibility == View.VISIBLE) {
            dismissFileViewer()
            return
        }
        if (::diffViewerRootContainer.isInitialized && diffViewerRootContainer.visibility == View.VISIBLE) {
            dismissDiffViewer()
            return
        }

        if (::projectSettingsContainer.isInitialized && projectSettingsContainer.visibility == View.VISIBLE) {
            navigateToPage(ID_PROJECT_WORKSPACE, false)
            return
        }

        if (::projectDirTreeContainer.isInitialized && projectDirTreeContainer.visibility == View.VISIBLE) {
            if (::directoryPage.isInitialized && directoryPage.handleBack()) return
            navigateToPage(ID_PROJECT_WORKSPACE, false)
            return
        }

        if (::projectGitDiffContainer.isInitialized && projectGitDiffContainer.visibility == View.VISIBLE) {
            navigateToPage(ID_PROJECT_WORKSPACE, false)
            return
        }

        if (::projectWorkspaceContainer.isInitialized && projectWorkspaceContainer.visibility == View.VISIBLE) {
            navigateToPage(ID_PROJECTS_LIST, false)
            return
        }

        if ((::projectCreateContainer.isInitialized && projectCreateContainer.visibility == View.VISIBLE) ||
            (::projectCreateScrollView.isInitialized && projectCreateScrollView.visibility == View.VISIBLE)
        ) {
            navigateToPage(ID_PROJECTS_LIST, false)
            return
        }

        if (::mpInstallerLayout.isInitialized && mpInstallerLayout.visibility == View.VISIBLE) {
            // Block back while install still running
            if (isScriptRunning && mpInstallerFinishedOk == null) return
            leaveMarketplaceInstaller()
            return
        }

        if (::scriptInstallLayout.isInitialized && scriptInstallLayout.visibility == View.VISIBLE) {
            if (isScriptRunning) {
                return
            }
            // Return to launcher page (Chroot Settings after uninstall, Scripts hub, etc.)
            if (pageStack.size > 1 && pageStack.peek() == ID_SCRIPT_INSTALL) {
                pageStack.pop()
                navigateToPage(pageStack.peek(), false)
            } else {
                navigateToPage(ID_SCRIPTS, false)
            }
            return
        }

        if (pageStack.size > 1) {
            pageStack.pop() // remove current
            val prev = pageStack.peek()
            navigateToPage(prev, false)
        } else {
            showExitConfirmDialog()
        }
    }


    private fun updateNavigationVisibility(id: Int) {
        val isLandscape = (::rootLayout.isInitialized && rootLayout.isLaidOut && rootLayout.width > rootLayout.height) ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && windowManager.currentWindowMetrics.bounds.width() > windowManager.currentWindowMetrics.bounds.height()) ||
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

        if (::rootLayout.isInitialized) {
            rootLayout.orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        if (::mainContentLayout.isInitialized) {
            mainContentLayout.layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(0, MATCH, 1f)
            } else {
                LinearLayout.LayoutParams(MATCH, MATCH)
            }
        }

        var showGlobalNav = false
        var showProjectNav = false

        if (id == ID_HOME || id == ID_PROJECTS_LIST || id == ID_TERMINAL || id == ID_SETTINGS) {
            showGlobalNav = true
        } else if (id == ID_PROJECT_WORKSPACE || id == ID_PROJECT_SETTINGS || id == ID_PROJECT_DIR_TREE || id == ID_PROJECT_GIT_DIFF) {
            showProjectNav = true
        }
        
        if (id == ID_SCRIPTS || id == ID_SCRIPT_INSTALL || id == ID_PROJECT_CREATE ||
            id == ID_CHROOT_SETTINGS || id == ID_PROOT_SETTINGS ||
            id == ID_SOFTWARE_MANAGER || id == ID_MARKETPLACE || id == ID_APPS || id == ID_MP_INSTALLER ||
            id == ID_CLI_AUTH || id == ID_AI_SAFETY || id == ID_PRIVACY_POLICY || id == ID_COMPONENTS ||
            id == ID_HARDWARE || id == ID_X11_SETTINGS || id == ID_SKILLS || id == ID_TOPBAR || id == ID_BACKUP ||
            extraPages.containsKey(id)
        ) {
            showGlobalNav = false
            showProjectNav = false
        }

        if (::sideNavContainer.isInitialized) {
            sideNavContainer.visibility = if (isLandscape && (showGlobalNav || showProjectNav)) android.view.View.VISIBLE else android.view.View.GONE
        }
        if (::bottomNavContainer.isInitialized) {
            val isKeyboardOpen = drawerLayout.rootWindowInsets?.let {
                androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it).getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom > 0
            } ?: false
            bottomNavContainer.visibility = if (!isLandscape && !isKeyboardOpen && (showGlobalNav || showProjectNav)) android.view.View.VISIBLE else android.view.View.GONE
        }
        
        // Material rails hidden - using custom cyber-brutalist side rails (portrait bottom nav design)
        if (::globalNavRail.isInitialized) {
            globalNavRail.visibility = android.view.View.GONE
        }
        if (::projectNavRail.isInitialized) {
            projectNavRail.visibility = android.view.View.GONE
        }
        if (::customSideNav.isInitialized) {
            customSideNav.visibility = if (showGlobalNav) android.view.View.VISIBLE else android.view.View.GONE
        }
        if (::customSideProjectNav.isInitialized) {
            customSideProjectNav.visibility = if (showProjectNav) android.view.View.VISIBLE else android.view.View.GONE
        }
        if (::bottomNavigation.isInitialized) {
            bottomNavigation.visibility = android.view.View.GONE
        }
        if (::projectBottomNavigation.isInitialized) {
            projectBottomNavigation.visibility = android.view.View.GONE
        }
        
        // Insets are owned by the window-insets listener. Keep this path free
        // of cached top insets, which can be stale while immersive mode starts.
        if (isLandscape && ::mainContentLayout.isInitialized) {
            rootLayout.rootWindowInsets?.let { insets ->
                val bars = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                mainContentLayout.setPadding(0, 0, bars.right, 0)
                bottomNavContainer.setPadding(0, 0, 0, 0)
                sideNavContainer.setPadding(bars.left, 0, 0, bars.bottom)
            }
        } else if (::mainContentLayout.isInitialized) {
            rootLayout.rootWindowInsets?.let { insets ->
                val bars = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                mainContentLayout.setPadding(0, 0, 0, 0)
                bottomNavContainer.setPadding(bars.left, 0, bars.right, bars.bottom)
                sideNavContainer.setPadding(0, 0, 0, 0)
            }
        }

        // Use visible page, not stack peek (stack can lag after nested subpage backs).
        val currentPage = currentPageId

        // Side rail selection handled via updateCustomSideNavSelection (custom view, centered per ui_design.md)
        // Rails hidden - no selectedItemId needed
        if (!isLandscape && showGlobalNav && ::bottomNavigation.isInitialized) {
            bottomNavigation.setOnItemSelectedListener(null)
            if (bottomNavigation.menu.findItem(currentPage) != null) {
                bottomNavigation.selectedItemId = currentPage
            }
            setupBottomNavigationListener()
        }
        if (!isLandscape && showProjectNav && ::projectBottomNavigation.isInitialized) {
            projectBottomNavigation.setOnItemSelectedListener(null)
            if (projectBottomNavigation.menu.findItem(currentPage) != null) {
                projectBottomNavigation.selectedItemId = currentPage
            }
            setupProjectBottomNavigationListener()
        }
    }

    /**
     * Pop [pageId] off the stack and show the previous page (or [fallback]).
     * Keeps stack aligned with visible UI so bottom nav / insets stay correct.
     */
    private fun navigateBackFromSubpage(pageId: Int, fallback: Int = ID_SETTINGS) {
        android.util.Log.d("NativeCodeNav", "navigateBackFromSubpage called: pageId=$pageId, fallback=$fallback, currentStack=$pageStack")
        while (pageStack.isNotEmpty() && pageStack.peek() == pageId) {
            pageStack.pop()
        }
        val target = if (pageStack.isNotEmpty()) {
            pageStack.peek()
        } else {
            fallback
        }
        android.util.Log.d("NativeCodeNav", "navigateBackFromSubpage resolved target=$target, stackAfter=$pageStack")
        if (pageStack.isEmpty() || pageStack.peek() != target) {
            pageStack.push(target)
        }
        navigateToPage(target, false)
    }

    private fun showExitConfirmDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr(R.string.main_exit_nativecode))
            .setMessage(tr(R.string.main_terminal_sessions_will_keep_running))
            .setPositiveButton(tr(R.string.main_exit)) { _, _ ->
                super.onBackPressed()
            }
            .setNegativeButton(tr(R.string.git_cancel), null)
            .show()
    }

    // ── Layout construction ───────────────────────────────────────────────────

    private fun buildRootLayout() {
        drawerLayout = androidx.drawerlayout.widget.DrawerLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(NC.BG)
        }

        // Detect initial orientation so we don't start with wrong layout params
        val initLandscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

        rootLayout = LinearLayout(this).apply {
            orientation = if (initLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            // Baseline alignment measures weighted children with an UNSPECIFIED width (Compose horizontalScroll throws).
            isBaselineAligned = false
            setBackgroundColor(NC.BG)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val oldWidth = oldRight - oldLeft
                val oldHeight = oldBottom - oldTop
                val newWidth = right - left
                val newHeight = bottom - top
                if (newWidth != oldWidth || newHeight != oldHeight) {
                    updateNavigationVisibility(currentPageId)
                }
            }
        }
        drawerLayout.addView(rootLayout)

        sideNavContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, MATCH)
            setBackgroundColor(NC.SURFACE_LOWEST)
            visibility = android.view.View.GONE
        }
        rootLayout.addView(sideNavContainer)

        mainContentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NC.BG)
            layoutParams = if (initLandscape) {
                LinearLayout.LayoutParams(0, MATCH, 1f)
            } else {
                LinearLayout.LayoutParams(MATCH, MATCH)
            }
        }
        rootLayout.addView(mainContentLayout)

        // ── Unified Top Bar ──────────────────────────────────────────────────
        unifiedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), Color.parseColor("#3360F99E"))
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val logoContainer = FrameLayout(this).apply {
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_HIGH,
                strokeColor = Color.parseColor("#8060F99E"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 2
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.logo_highres)
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        logoContainer.addView(logoView)

        backBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.ON_SURFACE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                onBackPressed()
            }
        }
        // Phone stats (user-ordered, drop-to-fit): workspace/StatStrip
        val headerStats = StatStrip(this).apply {
            alignEnd = true
            onCustomize = { openTopBarPage() }
            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f).apply { leftMargin = dp(12) }
        }

        unifiedHeader.addView(logoContainer)
        unifiedHeader.addView(headerStats)
        // Desktop start / view / stop from every page: desktop/DesktopPill
        unifiedHeader.addView(DesktopPill(this, desktop).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        })
        mainContentLayout.addView(unifiedHeader)

        startResourceMonitoring()

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            background = TerminalScanlineDrawable()
            // Empty until a sign-in popup opens (its sheet lives in its own window).
            addView(androidx.compose.ui.platform.ComposeView(this@MainActivity).apply {
                setContent {
                    cliLoginId.value?.let { id ->
                        com.zenithblue.nativecode.agent.chat.ui.ChatTheme {
                            com.zenithblue.nativecode.agent.chat.ui.CliLoginSheet(id, ::paintCliAuth, ::openCliTerminal) { cliLoginId.value = null }
                        }
                    }
                    cliUpdateReq.value?.let { q ->
                        com.zenithblue.nativecode.agent.chat.ui.ChatTheme {
                            com.zenithblue.nativecode.agent.chat.ui.CliUpdateDialog(q.r, q.need, cliTools.busy[q.r.tool], cliUpdateErr.value, ::runCliUpdate, ::closeCliUpdate) {
                                CliToolCatalog.byId(q.r.tool)?.let(::openCliTerminal)
                                closeCliUpdate()
                            }
                        }
                    }
                }
            })
        }

        val navStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val navColors = intArrayOf(
            NC.SURFACE_LOWEST,
            NC.ON_SURF_VAR
        )
        val navTintList = android.content.res.ColorStateList(navStates, navColors)

        bottomNavigation = BottomNavigationView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
            setBackgroundColor(NC.SURFACE_LOWEST)
            itemIconTintList = navTintList
            itemTextColor = navTintList
            itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            menu.add(Menu.NONE, ID_HOME,          Menu.NONE, tr(R.string.main_home).uppercase()).setIcon(R.drawable.ic_home)
            menu.add(Menu.NONE, ID_PROJECTS_LIST, Menu.NONE, tr(R.string.main_project)).setIcon(R.drawable.ic_folder)
            menu.add(Menu.NONE, ID_TERMINAL,      Menu.NONE, tr(R.string.main_terminal)).setIcon(R.drawable.ic_terminal)
            menu.add(Menu.NONE, ID_SETTINGS,      Menu.NONE, tr(R.string.main_settings)).setIcon(R.drawable.ic_settings)
        }

        projectBottomNavigation = BottomNavigationView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
            setBackgroundColor(NC.SURFACE_LOWEST)
            itemIconTintList = navTintList
            itemTextColor = navTintList
            itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            menu.add(Menu.NONE, ID_PROJECT_WORKSPACE, Menu.NONE, tr(R.string.onb_workspace)).setIcon(R.drawable.ic_home)
            menu.add(Menu.NONE, ID_PROJECT_DIR_TREE, Menu.NONE, tr(R.string.main_directory)).setIcon(R.drawable.ic_folder)
            menu.add(Menu.NONE, ID_PROJECT_GIT_DIFF, Menu.NONE, tr(R.string.main_diff)).setIcon(R.drawable.ic_git)
            menu.add(Menu.NONE, ID_PROJECT_SETTINGS, Menu.NONE, tr(R.string.main_config)).setIcon(R.drawable.ic_project_config)
        }

        mainContentLayout.addView(contentFrame)
        
        bottomNavContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), Color.parseColor("#1AFFFFFF"))
            }
        }
        mainContentLayout.addView(bottomNavContainer)

        bottomNavContainer.addView(buildCustomBottomNav())
        bottomNavContainer.addView(buildCustomProjectBottomNav())

        globalNavRail = com.google.android.material.navigationrail.NavigationRailView(this).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP, MATCH)
            setBackgroundColor(NC.SURFACE_LOWEST)
            itemIconTintList = navTintList
            itemTextColor = navTintList
            labelVisibilityMode = com.google.android.material.navigationrail.NavigationRailView.LABEL_VISIBILITY_LABELED
            menuGravity = 1 // MENU_GRAVITY_CENTER - vertically center items per ui_design.md
            menu.add(android.view.Menu.NONE, ID_HOME,          android.view.Menu.NONE, tr(R.string.main_home)).setIcon(R.drawable.ic_home)
            menu.add(android.view.Menu.NONE, ID_PROJECTS_LIST, android.view.Menu.NONE, tr(R.string.main_projects)).setIcon(R.drawable.ic_folder)
            menu.add(android.view.Menu.NONE, ID_TERMINAL,      android.view.Menu.NONE, "Terminal").setIcon(R.drawable.ic_terminal)
            menu.add(android.view.Menu.NONE, ID_SETTINGS,      android.view.Menu.NONE, tr(R.string.main_settings_2)).setIcon(R.drawable.ic_settings)
            
            setOnItemSelectedListener { item ->
                val pageId = item.itemId
                if (::bottomNavigation.isInitialized) {
                    bottomNavigation.setOnItemSelectedListener(null)
                    bottomNavigation.selectedItemId = pageId
                    setupBottomNavigationListener()
                }
                if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                    pageStack.push(pageId)
                }
                navigateToPage(pageId)
                true
            }
        }

        projectNavRail = com.google.android.material.navigationrail.NavigationRailView(this).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP, MATCH)
            setBackgroundColor(NC.SURFACE_LOWEST)
            itemIconTintList = navTintList
            itemTextColor = navTintList
            labelVisibilityMode = com.google.android.material.navigationrail.NavigationRailView.LABEL_VISIBILITY_LABELED
            menuGravity = 1 // MENU_GRAVITY_CENTER
            menu.add(android.view.Menu.NONE, ID_PROJECT_WORKSPACE, android.view.Menu.NONE, tr(R.string.chat_workspace)).setIcon(R.drawable.ic_home)
            menu.add(android.view.Menu.NONE, ID_PROJECT_DIR_TREE, android.view.Menu.NONE, tr(R.string.main_directory_2)).setIcon(R.drawable.ic_folder)
            menu.add(android.view.Menu.NONE, ID_PROJECT_GIT_DIFF, android.view.Menu.NONE, tr(R.string.main_diff_2)).setIcon(R.drawable.ic_git)
            menu.add(android.view.Menu.NONE, ID_PROJECT_SETTINGS, android.view.Menu.NONE, tr(R.string.main_config_2)).setIcon(R.drawable.ic_project_config)

            setOnItemSelectedListener { item ->
                val pageId = item.itemId
                if (::projectBottomNavigation.isInitialized) {
                    projectBottomNavigation.setOnItemSelectedListener(null)
                    projectBottomNavigation.selectedItemId = pageId
                    setupProjectBottomNavigationListener()
                }
                if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                    pageStack.push(pageId)
                }
                navigateToPage(pageId)
                true
            }
        }
        
        sideNavContainer.addView(globalNavRail)
        sideNavContainer.addView(projectNavRail)
        // Custom cyber-brutalist side rail (portrait bottom nav design, vertical, centered)
        sideNavContainer.addView(buildCustomSideNav())
        sideNavContainer.addView(buildCustomSideProjectNav())


        // Initialize all layout panels
        buildHomeLayout()
        buildFileExplorerLayout()
        buildTerminalLayout()
        buildGitOperationsLayout()
        buildSettingsHubLayout()
        buildFileViewerLayout()
        buildDiffViewerLayout()
        buildScriptsLayout()
        buildCliAuthPage()
        buildAiSafetyPage()
        buildComponentsPage()
        buildTopBarPage()
        buildBackupPage()
        buildSshPage()
        buildUsagePage()
        buildPrivacyPolicyPage()
        buildHardwarePage()
        buildProotSettingsPage()
        buildProotFilesPage()
        buildX11SettingsPage()
        buildSkillsPage()
        if (!com.zenithblue.nativecode.ProotOnly.ON) buildChrootSettingsPage()
        buildSoftwareManagerPage()
        if (!com.zenithblue.nativecode.ProotOnly.ON) buildMarketplacePage()
        buildAppsPage()
        if (!com.zenithblue.nativecode.ProotOnly.ON) buildMarketplaceInstallerLayout()
        buildScriptInstallLayout()
        buildProjectCreateLayout()
        buildProjectsListLayout()
        buildProjectWorkspaceLayout()
        buildProjectSettingsLayout()
        buildProjectDirTreeLayout()
        buildProjectGitDiffLayout()

        // Add all layouts to contentFrame
        contentFrame.addView(homeScrollView)
        contentFrame.addView(fileExplorerScrollView)
        contentFrame.addView(terminalWorkspaceLayout)
        contentFrame.addView(gitOperationsScrollView)
        contentFrame.addView(settingsHubScrollView)
        contentFrame.addView(fileViewerRootContainer)
        contentFrame.addView(diffViewerRootContainer)
        contentFrame.addView(scriptsScrollView)
        contentFrame.addView(cliAuthScrollView)
        contentFrame.addView(aiSafetyScrollView)
        contentFrame.addView(componentsScrollView)
        contentFrame.addView(topBarScrollView)
        contentFrame.addView(backupScrollView)
        extraPages.values.forEach { contentFrame.addView(it) }
        contentFrame.addView(privacyPolicyScrollView)
        contentFrame.addView(hardwareScrollView)
        contentFrame.addView(x11SettingsScrollView)
        contentFrame.addView(skillsScrollView)
        contentFrame.addView(prootSettingsScrollView)
        contentFrame.addView(prootFilesScrollView)
        if (!com.zenithblue.nativecode.ProotOnly.ON) contentFrame.addView(chrootSettingsScrollView)
        contentFrame.addView(softwareManagerScrollView)
        if (!com.zenithblue.nativecode.ProotOnly.ON) contentFrame.addView(marketplaceScrollView)
        contentFrame.addView(appsScrollView)
        if (!com.zenithblue.nativecode.ProotOnly.ON) contentFrame.addView(mpInstallerLayout)
        contentFrame.addView(scriptInstallLayout)
        contentFrame.addView(projectCreateContainer)
        contentFrame.addView(projectsListContainer)
        contentFrame.addView(projectWorkspaceContainer)
        contentFrame.addView(projectSettingsContainer)
        contentFrame.addView(projectDirTreeContainer)
        contentFrame.addView(projectGitDiffContainer)

        pageStack.push(ID_HOME)
    }

    private fun extractTarStream(inputStream: InputStream, targetDir: File) {
        val buffer = ByteArray(512)
        while (true) {
            var bytesRead = 0
            while (bytesRead < 512) {
                val r = inputStream.read(buffer, bytesRead, 512 - bytesRead)
                if (r == -1) break
                bytesRead += r
            }
            if (bytesRead < 512) break

            var allZero = true
            for (b in buffer) {
                if (b != 0.toByte()) { allZero = false; break }
            }
            if (allZero) break

            fun parseString(offset: Int, length: Int): String {
                var len = 0
                while (len < length && buffer[offset + len] != 0.toByte()) { len++ }
                return String(buffer, offset, len, Charsets.UTF_8)
            }

            var name = parseString(0, 100)
            val prefix = parseString(345, 155)
            if (prefix.isNotEmpty()) name = "$prefix/$name"

            val sizeStr = parseString(124, 12).trim()
            val size = try { sizeStr.toLong(8) } catch (e: Exception) { 0L }
            val type = buffer[156].toInt().toChar()
            val linkName = parseString(157, 100)

            val relPath = name.replace("^data/data/${Regex.escape(com.zenithblue.nativecode.terminal.TermuxHostPaths.PACKAGE)}/files/".toRegex(), "").trimStart('/')
            if (relPath.isEmpty()) {
                val dataBlocks = Math.ceil(size.toDouble() / 512.0).toLong()
                skipBytes(inputStream, dataBlocks * 512L)
                continue
            }

            val outFile = File(targetDir, relPath)

            if (type == '5') {
                outFile.mkdirs()
            } else if (type == '2') {
                outFile.parentFile?.mkdirs()
                try {
                    Os.symlink(linkName, outFile.absolutePath)
                } catch (e: Exception) {
                    try {
                        val linkTarget = File(outFile.parentFile, linkName)
                        if (linkTarget.exists() && linkTarget.isFile) {
                            linkTarget.copyTo(outFile, overwrite = true)
                        }
                    } catch (_: Exception) {}
                }
            } else if (type == '0' || type == '\u0000') {
                outFile.parentFile?.mkdirs()
                java.io.FileOutputStream(outFile).use { fos ->
                    var remaining = size
                    val copyBuf = ByteArray(8192)
                    while (remaining > 0) {
                        val toRead = Math.min(copyBuf.size.toLong(), remaining).toInt()
                        val r = inputStream.read(copyBuf, 0, toRead)
                        if (r == -1) break
                        fos.write(copyBuf, 0, r)
                        remaining -= r
                    }
                }
                val padding = ((512 - (size % 512)) % 512).toInt()
                if (padding > 0) {
                    skipBytes(inputStream, padding.toLong())
                }
                val modeStr = parseString(100, 8).trim()
                try {
                    val mode = modeStr.toInt(8)
                    if ((mode and 73) != 0) {
                        outFile.setExecutable(true, false)
                    }
                } catch (_: Exception) {}
            } else {
                val dataBlocks = Math.ceil(size.toDouble() / 512.0).toLong()
                skipBytes(inputStream, dataBlocks * 512L)
            }
        }
    }

    private fun skipBytes(inputStream: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = Math.min(buf.size.toLong(), remaining).toInt()
            val r = inputStream.read(buf, 0, toRead)
            if (r == -1) break
            remaining -= r
        }
    }

    /** Cold extract + path rewrite. One in-flight extraction; result visible only after it finishes. */
    private fun ensureBootstrapExtracted() {
        if (bootstrapReady) return
        synchronized(bootstrapLock) {
            if (bootstrapReady) return
            while (bootstrapInFlight) {
                try {
                    (bootstrapLock as Object).wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            if (bootstrapReady) return
            bootstrapInFlight = true
        }
        var ok = false
        try {
            ok = extractBootstrapIfNeeded()
        } finally {
            synchronized(bootstrapLock) {
                bootstrapInFlight = false
                if (ok) bootstrapReady = true
                (bootstrapLock as Object).notifyAll()
            }
        }
    }

    private fun extractBootstrapIfNeeded(): Boolean {
        val termuxExecFile = File(filesDir, "usr/lib/libtermux-exec.so")
        if (termuxExecFile.exists()) {
            val marker = File(filesDir, "usr/etc/.paths_rewritten")
            if (!marker.exists()) {
                TermuxHostPaths.applyPackageToExtractedPrefix(filesDir)
            }
            return true
        }
        return try {
            val usrDir = File(filesDir, "usr")
            File(usrDir, "tmp").mkdirs()
            File(usrDir, "etc").mkdirs()
            File(filesDir, "home").mkdirs()
            assets.open("bootstrap.tar").use { input ->
                extractTarStream(input, filesDir)
            }
            TermuxHostPaths.applyPackageToExtractedPrefix(filesDir, force = true)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


    private fun createNewTerminalSession(type: String = "shell") {
        if (sessionsList.size + pendingAppTerminalSlots >= 10) {
            Toast.makeText(this, tr(R.string.main_maximum_10_tabs_allowed), Toast.LENGTH_SHORT).show()
            return
        }
        if (type != "ssh" && !aiLaunchAllowed(type)) return // the ssh client tab is Free (plan 17)
        if (ToolLauncherCatalog.isAiToolType(type) &&
            !AiCliProvisionState.shouldShowAiToolLaunchers(this)
        ) {
            Toast.makeText(
                this,
                tr(R.string.main_install_ai_cli_tools_in),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!com.zenithblue.nativecode.ProotOnly.ON && type == "codex" && LinuxCommandBuilder.currentMethod == "chroot") {
            Toast.makeText(this, tr(R.string.main_codex_unavailable_in_chroot), Toast.LENGTH_SHORT).show()
            return
        }
        if (!com.zenithblue.nativecode.ProotOnly.ON && type == "shell-root" && LinuxCommandBuilder.currentMethod == "chroot") {
            executor.execute {
                val rootOk = RootShell.isRootAvailable()
                mainHandler.post {
                    if (!rootOk) {
                        Toast.makeText(
                            this,
                            tr(R.string.main_root_required_for_rooted_chroot),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        startNewTerminalSession(type)
                    }
                }
            }
            return
        }
        startNewTerminalSession(type)
    }

    private fun startNewTerminalSession(type: String) {
        if (!reserveTerminalSlot(app = true)) {
            Toast.makeText(this, tr(R.string.main_maximum_10_tabs_allowed), Toast.LENGTH_SHORT).show()
            return
        }
        setTerminalMode(ws = false, chat = false) // every new tab (card, +, intent, login, Open in terminal) shows the Terminal
        maybeToastHeavyToolLaunch(type)
        val ticket = captureTerminalLaunch(app = true, type)
        val workDir = chatTermCwd.also { chatTermCwd = null } // chat "Open terminal here" / "Open in terminal": thread cwd
        val sshCmd = if (type == "ssh") sshTabCommand else null
        val sshTitle = if (type == "ssh") sshTabTitle else null
        sshTabCommand = null; sshTabTitle = null
        val user = LinuxCommandBuilder.sessionUserForType(type)
        // Warm RootShell su cache so ChrootCommandBuilder.shellRootCommand uses discovered path
        if (!com.zenithblue.nativecode.ProotOnly.ON && ticket.method == "chroot") {
            executor.execute { try { RootShell.isRootAvailable() } catch (_: Exception) {} }
        }
        executor.execute {
            val prepared = try {
                ensureBootstrapExtracted()
                if (!bootstrapReady) error("Failed to extract bootstrap")
                terminalLaunchSpec(type, user, workDir = workDir, ticket.method, sshCmd)
            } catch (e: Exception) {
                mainHandler.post {
                    if (!releaseTerminalLaunch(ticket)) return@post
                    Toast.makeText(
                        this,
                        e.message?.takeIf { it.isNotBlank() } ?: tr(R.string.main_failed_to_prepare_terminal),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@execute
            }
            mainHandler.post {
                if (!releaseTerminalLaunch(ticket)) return@post
                if (!terminalLaunchStillCurrent(ticket)) return@post
                val (args, envMap) = prepared
                attachTerminalSession(type, args, envMap, ticket.method, sshTitle)
            }
        }
    }

    private data class TerminalLaunchTicket(
        val app: Boolean,
        val seq: Long,
        val lifecycle: Int,
        val project: String,
        val projectGen: Int,
        val method: String,
        val methodGen: Int,
        val rootfs: String,
        val ai: Boolean
    )

    private fun captureTerminalLaunch(app: Boolean, type: String): TerminalLaunchTicket {
        val seq = ++terminalLaunchSeq
        if (app) appLaunchWatermark = seq else workspaceLaunchWatermark = seq
        val method = if (app) LinuxCommandBuilder.currentMethod else projectGitMethod()
        val ai = ToolLauncherCatalog.isAiToolType(type)
        if (ai) pendingAiTerminalLaunches++ // counted by aiLaunchAllowed until the tab exists or the launch fails
        return TerminalLaunchTicket(
            ai = ai,
            app = app,
            seq = seq,
            lifecycle = terminalLifecycleGeneration,
            project = activeProjectPath,
            projectGen = projectGeneration,
            method = method,
            methodGen = LinuxCommandBuilder.environmentGeneration,
            rootfs = ProjectPathResolver.prootRootfsDir(this).absolutePath
        )
    }

    /** Drop reservation. False when a newer request of the same kind already owns the slot. */
    private fun releaseTerminalLaunch(ticket: TerminalLaunchTicket): Boolean {
        if (ticket.app) pendingAppTerminalSlots-- else pendingWorkspaceTerminalSlots--
        if (ticket.ai) pendingAiTerminalLaunches--
        val watermark = if (ticket.app) appLaunchWatermark else workspaceLaunchWatermark
        return ticket.seq == watermark
    }

    private fun terminalLaunchStillCurrent(ticket: TerminalLaunchTicket): Boolean {
        if (isFinishing || isDestroyed || ticket.lifecycle != terminalLifecycleGeneration) return false
        if (ticket.methodGen != LinuxCommandBuilder.environmentGeneration) return false
        if (ProjectPathResolver.prootRootfsDir(this).absolutePath != ticket.rootfs) return false
        if (!ticket.app &&
            (ticket.project != activeProjectPath || ticket.projectGen != projectGeneration)
        ) {
            return false
        }
        val methodNow = if (ticket.app) LinuxCommandBuilder.currentMethod else projectGitMethod()
        return ticket.method == methodNow
    }

    private fun reserveTerminalSlot(app: Boolean): Boolean {
        val used = if (app) {
            sessionsList.size + pendingAppTerminalSlots
        } else {
            workspaceSessions.size + pendingWorkspaceTerminalSlots
        }
        if (used >= 10) return false
        if (app) pendingAppTerminalSlots++ else pendingWorkspaceTerminalSlots++
        return true
    }

    private fun terminalLaunchSpec(
        type: String,
        user: String,
        workDir: String?,
        method: String,
        shellCmd: String? = null
    ): Pair<Array<String>, HashMap<String, String>> =
        if (type == "ssh" && shellCmd != null) {
            LinuxCommandBuilder.build(this, shellCmd, user = user, method = method)
        } else if (type == "shell" || type == "shell-root") {
            LinuxCommandBuilder.buildInteractive(this, user = user, workDir = workDir, method = method)
        } else if (method != "chroot") {
            LinuxCommandBuilder.buildToolDirect(
                this, listOf(ChrootCommandBuilder.toolBinaryName(type)), user = user, workDir = workDir
            )
        } else {
            val shellCmd = ChrootCommandBuilder.buildToolShellCommand(this, type, workDir)
            LinuxCommandBuilder.build(this, shellCmd, user = user, method = method)
        }

    private fun attachTerminalSession(
        type: String,
        args: Array<String>,
        envMap: HashMap<String, String>,
        method: String,
        title: String? = null
    ) {
        val cwd = File(filesDir, "home").absolutePath
        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        if (!::sessionClient.isInitialized) {
            initTerminalView()
        }
        val sessionExec = LinuxCommandBuilder.sessionExec(this, args, method = method)
        val sessionArgs = LinuxCommandBuilder.sessionArgs(this, args, method = method)
        val session = TerminalSession(sessionExec, cwd, sessionArgs, env, 10000, sessionClient)
        sessionsList.add(session)
        terminalSessionTypes.add(type)
        if (title != null) termTabTitles[session] = title
        switchTerminalSession(sessionsList.size - 1)
        updateAppTerminalService()
    }


    private fun switchTerminalSession(index: Int) {
        if (index < 0 || index >= sessionsList.size) return
        activeSessionIndex = index
        val session = sessionsList[index]
        terminalSession = session

        mainHandler.post {
            if (::terminalToolSelectorScrollView.isInitialized) {
                terminalToolSelectorScrollView.visibility = View.GONE
            }
            if (::terminalViewContainer.isInitialized) {
                terminalViewContainer.visibility = View.VISIBLE
            }
            if (::terminalKeyboardToolbar.isInitialized) {
                terminalKeyboardToolbar.visibility = if (showExtraKeys) View.VISIBLE else View.GONE
            }
            terminalView.attachSession(session)
            terminalView.onScreenUpdated()
            terminalView.isFocusable = true
            terminalView.isFocusableInTouchMode = true
            terminalView.requestFocus()
            forceTerminalResize(terminalView)
            refreshTermTabStrip()
        }
    }

    /**
     * Kill this session only. session.pid is the setsid group leader (libproot.so for
     * interactive shells). Guest bash is a proot tracee, reaped by --kill-on-exit when
     * the tracer dies — not a PPid child. No comm-name or PPID-1 sweep.
     */
    private fun terminateSessionProcesses(session: TerminalSession) {
        val pid = session.pid
        if (pid <= 1) return
        val descendants = mutableListOf<Int>()
        val tracees = mutableListOf<Int>()
        collectDescendants(pid, descendants, 0)
        collectTracees(pid, tracees)
        signalPid(-pid, android.system.OsConstants.SIGTERM)
        signalPid(-pid, android.system.OsConstants.SIGHUP)
        signalPid(-pid, android.system.OsConstants.SIGKILL)
        for (child in descendants) signalPid(child, android.system.OsConstants.SIGKILL)
        signalPid(pid, android.system.OsConstants.SIGKILL)
        for (tracee in tracees) signalPid(tracee, android.system.OsConstants.SIGKILL)
    }

    private fun signalPid(pid: Int, sig: Int) {
        try {
            android.system.Os.kill(pid, sig)
        } catch (_: Exception) {}
    }

    /** PPid children of [root], host-side only. Guest tracees are not in this tree. */
    private fun collectDescendants(root: Int, out: MutableList<Int>, depth: Int) {
        if (depth > 32) return
        val procDir = File("/proc")
        val files = procDir.listFiles() ?: return
        val children = ArrayList<Int>()
        for (f in files) {
            val name = f.name
            if (!name.all { it.isDigit() }) continue
            val pid = name.toIntOrNull() ?: continue
            if (pid <= 1) continue
            val ppid = readPpid(f) ?: continue
            if (ppid == root) children.add(pid)
        }
        for (child in children) {
            out.add(child)
            collectDescendants(child, out, depth + 1)
        }
    }

    /** Tasks whose TracerPid is [tracer]. Snapshot before the tracer is signalled. */
    private fun collectTracees(tracer: Int, out: MutableList<Int>) {
        val procDir = File("/proc")
        val files = procDir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            if (!name.all { it.isDigit() }) continue
            val pid = name.toIntOrNull() ?: continue
            if (pid <= 1 || pid == tracer) continue
            val status = File(f, "status")
            if (!status.canRead()) continue
            try {
                status.useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("TracerPid:")) {
                            val tp = line.substringAfter(':').trim().toIntOrNull()
                            if (tp == tracer) out.add(pid)
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun readPpid(procEntry: File): Int? {
        val statFile = File(procEntry, "stat")
        if (!statFile.canRead()) return null
        return try {
            val statText = statFile.readText()
            val lastParen = statText.lastIndexOf(')')
            if (lastParen == -1 || lastParen + 2 >= statText.length) return null
            val rest = statText.substring(lastParen + 2).trim().split(" ")
            rest.getOrNull(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun closeTerminalSession(index: Int) {
        if (index < 0 || index >= sessionsList.size) return
        val session = sessionsList[index]
        terminateSessionProcesses(session)
        session.finishIfRunning()
        sessionsList.removeAt(index)
        termTabTitles.remove(session)
        if (index < terminalSessionTypes.size) {
            terminalSessionTypes.removeAt(index)
        }
        // Closing a tab left of the active one shifts it (strip × on a background tab).
        if (index < activeSessionIndex) activeSessionIndex--

        if (sessionsList.isEmpty()) {
            activeSessionIndex = -1
            terminalSession = null
            if (::terminalViewContainer.isInitialized) {
                terminalViewContainer.visibility = View.GONE
            }
            if (::terminalKeyboardToolbar.isInitialized) {
                terminalKeyboardToolbar.visibility = View.GONE
            }
            if (::terminalToolSelectorScrollView.isInitialized) {
                terminalToolSelectorScrollView.visibility = View.VISIBLE
            }
        } else {
            if (activeSessionIndex >= sessionsList.size) {
                activeSessionIndex = sessionsList.size - 1
            }
            switchTerminalSession(activeSessionIndex)
        }
        refreshTermTabStrip()
        updateAppTerminalService()
    }

    /**
     * Tab strip contents (no layout rebuild; [TermTabStrip.submit] no-ops when nothing changed).
     * Raw terminal page only (chat has its own thread list): a leading CHAT cell (back to chat; beta on), then one
     * tab per app terminal session. Dot = output in the last 2 s.
     */
    private fun refreshTermTabStrip() {
        val strip = termTabStrip ?: return
        // Called from onCreate before the first terminal session creates sessionClient.
        val out = (if (::sessionClient.isInitialized) sessionClient as? NativeCodeTerminalSessionClient else null)?.lastOutputAt
        val now = android.os.SystemClock.uptimeMillis()
        val chat = if (chatBetaOn()) listOf(TermTabStrip.Tab("", tr(R.string.main_chat), null, live = false, selected = false, home = true)) else emptyList()
        strip.submit(chat + sessionsList.mapIndexed { i, s ->
            val type = terminalSessionTypes.getOrNull(i) ?: "shell"
            val name = termTabTitles[s] ?: termTabLabel(type)
            TermTabStrip.Tab(i.toString(), name, cliIcon(type), live = now - (out?.get(s) ?: 0L) < 2000, selected = i == activeSessionIndex)
        })
    }

    private fun termTabLabel(type: String) = when (type) { "shell" -> "shell"; "shell-root" -> "root shell"; else -> type }

    /** Workspace page: same strip, same tabs model as [refreshTermTabStrip], over [workspaceSessions]. */
    private fun refreshWsTabStrip() {
        val strip = wsTabStrip ?: return
        val out = NativeCodeTerminalSessionClient.SHARED_LAST_OUTPUT
        val now = android.os.SystemClock.uptimeMillis()
        strip.submit(workspaceSessions.mapIndexed { i, s ->
            val type = workspaceTabNames.getOrNull(i) ?: "shell"
            TermTabStrip.Tab(i.toString(), termTabTitles[s] ?: termTabLabel(type), cliIcon(type), live = now - (out[s] ?: 0L) < 2000, selected = i == activeWorkspaceTabIndex)
        })
    }

    private fun buildWsTabStrip() = TermTabStrip(this) { anchor -> showNewTerminalDropdown(anchor) }.apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        onTick = { refreshWsTabStrip() }
        onTap = { t -> t.key.toIntOrNull()?.let { setTerminalMode(ws = true, chat = false); switchWorkspaceTab(it) } }
        onClose = { t -> t.key.toIntOrNull()?.let { closeWorkspaceTab(it) } }
        onRename = { t, name -> t.key.toIntOrNull()?.let { workspaceSessions.getOrNull(it) }?.let { termTabTitles[it] = name; refreshWsTabStrip() } }
        onCloseOthers = { t ->
            t.key.toIntOrNull()?.let { workspaceSessions.getOrNull(it) }?.let { keep ->
                for (j in workspaceSessions.indices.reversed()) if (workspaceSessions[j] !== keep) closeWorkspaceTab(j)
                switchWorkspaceTab(workspaceSessions.indexOf(keep))
            }
        }
    }

    private fun cliIcon(type: String) = cliIconCache.getOrPut(type) {
        runCatching { assets.open("images/cli/${cliToolAssetFilename(type)}").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    private fun buildTermTabStrip() = TermTabStrip(this) { anchor ->
        showNewTerminalDropdown(anchor) { type -> createNewTerminalSession(type) }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        onTick = { refreshTermTabStrip() }
        onTap = { t ->
            if (t.home) setTerminalMode(ws = false, chat = true)
            else t.key.toIntOrNull()?.let { if (it != activeSessionIndex) switchTerminalSession(it) }
        }
        onClose = { t -> t.key.toIntOrNull()?.let { closeTerminalSession(it) } }
        onRename = { t, name -> t.key.toIntOrNull()?.let { sessionsList.getOrNull(it) }?.let { termTabTitles[it] = name; refreshTermTabStrip() } }
        onCloseOthers = { t ->
            t.key.toIntOrNull()?.let { sessionsList.getOrNull(it) }?.let { keep ->
                for (j in sessionsList.indices.reversed()) if (sessionsList[j] !== keep) closeTerminalSession(j)
                switchTerminalSession(sessionsList.indexOf(keep))
            }
        }
    }

    private fun buildTerminalToolSelectorView(): ScrollView {
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(NC.BG)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        }

        data class TermToolDef(val type: String, val label: String, val desc: String)

        fun fromCatalog(list: List<LauncherToolDef>) =
            list.map { TermToolDef(it.type, it.label, it.desc) }

        val showAi = AiCliProvisionState.shouldShowAiToolLaunchers(this)
        val method = LinuxCommandBuilder.currentMethod
        val shellTools = fromCatalog(ToolLauncherCatalog.shellTools())
        val freeTools = fromCatalog(ToolLauncherCatalog.freeTools())
        val paidTools = fromCatalog(ToolLauncherCatalog.paidTools(method))

        if (!showAi) {
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cyberBrutalistBg(
                    fillColor = NC.SURFACE_CONTAINER,
                    strokeColor = NC.OUTLINE_VAR,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0
                )
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(12)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (pageStack.isEmpty() || pageStack.peek() != ID_CLI_AUTH) {
                        pageStack.push(ID_CLI_AUTH)
                    }
                    navigateToPage(ID_CLI_AUTH)
                }
            }
            banner.addView(TextView(this).apply {
                text = tr(R.string.main_ai_cli_tools_not_installed)
                textSize = 13f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
            })
            banner.addView(TextView(this).apply {
                text = tr(R.string.main_tap_settings_ai_cli_tools)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, 0)
            })
            container.addView(banner)
        }

        fun makeToolCard(tool: TermToolDef): LinearLayout {
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = cyberBrutalistBg(
                    fillColor = NC.SURFACE_LOW,
                    strokeColor = NC.OUTLINE_VAR,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0
                )
                setPadding(dp(16), dp(20), dp(16), dp(18))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnClickListener { createNewTerminalSession(tool.type) }
            }

            val iconSize = dp(64)
            val iconView = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { bottomMargin = dp(10) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val filename = cliToolAssetFilename(tool.type)
            try {
                assets.open("images/cli/$filename").use {
                    val bmp = android.graphics.BitmapFactory.decodeStream(it)
                    if (bmp != null) iconView.setImageBitmap(bmp)
                }
            } catch (_: Exception) {
                iconView.setImageResource(R.drawable.ic_terminal_thick)
                iconView.setColorFilter(NC.PRIMARY)
            }

            card.addView(iconView)

            val nameTv = TextView(this@MainActivity).apply {
                text = tool.label
                textSize = 14f
                setTextColor(NC.ON_SURFACE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                if (aiToolLocked(tool.type)) {
                    text = "${tool.label} · PRO"
                    markProLocked()
                    card.contentDescription = "${tool.label}, Pro required"
                }
            }
            card.addView(nameTv)

            val descTv = TextView(this@MainActivity).apply {
                text = tool.desc
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }
            card.addView(descTv)
            CliToolCatalog.ALL.find { it.terminalType == tool.type }?.let { addCliLoginChip(card, it.id) }

            return card
        }

        fun addSection(title: String, subtitle: String, tools: List<TermToolDef>) {
            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(4))
            }
            val titleTv = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val subTitleTv = TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 10f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
            }
            headerRow.addView(titleTv)
            headerRow.addView(subTitleTv)

            val divider = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(8); topMargin = dp(2) }
                setBackgroundColor(NC.OUTLINE_VAR)
            }

            container.addView(headerRow)
            container.addView(divider)

            tools.chunked(2).forEach { rowTools ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        bottomMargin = dp(4)
                    }
                }
                val card1 = makeToolCard(rowTools[0])
                row.addView(card1)
                if (rowTools.size > 1) {
                    val card2 = makeToolCard(rowTools[1])
                    row.addView(card2)
                } else {
                    val dummySpacer = View(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(140), 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                    }
                    row.addView(dummySpacer)
                }
                container.addView(row)
            }
        }

        // C6: AI Free/Paid only when suite provisioned for current method
        if (showAi) {
            if (BuildConfig.MONETISATION_ENFORCE) {
                addSection(tr(R.string.main_free_cli_tools), "// OPEN SOURCE / FREE", freeTools)
                addSection(tr(R.string.main_paid_cli_tools), "// PRO / SUBSCRIPTION", paidTools)
            } else addSection(tr(R.string.fd_cli_tools), "// AI CLIS", freeTools + paidTools) // fdroid: no free/paid split
        }
        addSection(tr(R.string.main_linux_shell), "// SYSTEM SHELL", shellTools)

        scrollView.addView(container)
        scrollView.post {
            scrollView.requestLayout()
            container.requestLayout()
            scrollView.invalidate()
        }
        return scrollView
    }

    /** Rebuild terminal tool selector so chroot/proot codex visibility stays in sync. */
    private fun refreshTerminalToolSelector() {
        if (!::terminalToolSelectorScrollView.isInitialized) return
        if (!::terminalWorkspaceLayout.isInitialized) return
        val oldVis = terminalToolSelectorScrollView.visibility
        val parent = (terminalToolSelectorScrollView.parent as? ViewGroup) ?: terminalWorkspaceLayout
        val idx = parent.indexOfChild(terminalToolSelectorScrollView)
        parent.removeView(terminalToolSelectorScrollView)
        terminalToolSelectorScrollView = buildTerminalToolSelectorView().apply {
            visibility = oldVis
        }
        if (idx >= 0) {
            parent.addView(terminalToolSelectorScrollView, idx)
        } else {
            parent.addView(terminalToolSelectorScrollView)
        }
    }

    /** Rebuild workspace hub tool cards after linux_method change. */
    private fun refreshWorkspaceHubTools() {
        val host = workspaceHubToolsHost ?: return
        host.removeAllViews()
        populateWorkspaceHubTools(host)
    }

    private data class WorkspaceAiToolDef(
        val type: String,
        val label: String,
        val desc: String
    )

    private fun populateWorkspaceHubTools(host: LinearLayout) {
        // Same categories + order as Terminal tool selector (ToolLauncherCatalog SSOT)
        fun fromCatalog(list: List<LauncherToolDef>) =
            list.map { WorkspaceAiToolDef(it.type, it.label, it.desc) }

        // Project isolation — not ambient global currentMethod
        val method = projectGitMethod()
        val showAi = AiCliProvisionState.shouldShowAiToolLaunchers(this, method)
        val freeTools = fromCatalog(ToolLauncherCatalog.freeTools())
        val paidTools = fromCatalog(ToolLauncherCatalog.paidTools(method))
        val shellTools = fromCatalog(ToolLauncherCatalog.shellTools())

        if (!showAi) {
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cyberBrutalistBg(
                    fillColor = NC.SURFACE_CONTAINER,
                    strokeColor = NC.OUTLINE_VAR,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0
                )
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(12)
                }
                setOnClickListener {
                    // Install target = this project’s isolation (proot≠chroot slots)
                    alignGlobalMethodToProject()
                    if (pageStack.isEmpty() || pageStack.peek() != ID_CLI_AUTH) {
                        pageStack.push(ID_CLI_AUTH)
                    }
                    navigateToPage(ID_CLI_AUTH)
                }
            }
            banner.addView(TextView(this).apply {
                text = "AI CLI tools not installed for ${method.uppercase()}"
                textSize = 13f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
            })
            banner.addView(TextView(this).apply {
                text = tr(R.string.main_tap_install_suite_in_guest, method)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, 0)
            })
            host.addView(banner)
        }

        fun makeToolCard(tool: WorkspaceAiToolDef): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = cyberBrutalistBg(
                    fillColor = NC.SURFACE_LOW,
                    strokeColor = NC.OUTLINE_VAR,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0
                )
                setPadding(dp(16), dp(20), dp(16), dp(18))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnClickListener { createWorkspaceTerminalTab(tool.type) }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.translationX = dp(2).toFloat()
                            v.translationY = dp(2).toFloat()
                            v.background = cyberBrutalistBg(
                                fillColor = NC.SURFACE_CONTAINER,
                                strokeColor = NC.PRIMARY,
                                shadowColor = NC.SHADOW_DARK,
                                offsetDp = 2,
                                cornerRadiusDp = 0
                            )
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.translationX = 0f
                            v.translationY = 0f
                            v.background = cyberBrutalistBg(
                                fillColor = NC.SURFACE_LOW,
                                strokeColor = NC.OUTLINE_VAR,
                                shadowColor = NC.SHADOW_DARK,
                                offsetDp = 4,
                                cornerRadiusDp = 0
                            )
                        }
                    }
                    false
                }
            }

            val iconSize = dp(64)
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { bottomMargin = dp(10) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val filename = cliToolAssetFilename(tool.type)
            try {
                assets.open("images/cli/$filename").use {
                    val bmp = android.graphics.BitmapFactory.decodeStream(it)
                    if (bmp != null) iconView.setImageBitmap(bmp)
                }
            } catch (_: Exception) {
                iconView.setImageResource(R.drawable.ic_extension)
                iconView.setColorFilter(NC.PRIMARY)
            }

            card.addView(iconView)

            val nameTv = TextView(this).apply {
                text = tool.label
                textSize = 14f
                setTextColor(NC.ON_SURFACE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                if (aiToolLocked(tool.type)) {
                    text = "${tool.label} · PRO"
                    markProLocked()
                    card.contentDescription = "${tool.label}, Pro required"
                }
            }
            card.addView(nameTv)

            val descTv = TextView(this).apply {
                text = tool.desc
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }
            card.addView(descTv)
            CliToolCatalog.ALL.find { it.terminalType == tool.type }?.let { addCliLoginChip(card, it.id) }

            return card
        }

        fun addSection(title: String, subtitle: String, tools: List<WorkspaceAiToolDef>) {
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(4))
            }
            val titleTv = TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val subTitleTv = TextView(this).apply {
                text = subtitle
                textSize = 10f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
            }
            headerRow.addView(titleTv)
            headerRow.addView(subTitleTv)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                    bottomMargin = dp(8)
                    topMargin = dp(2)
                }
                setBackgroundColor(NC.OUTLINE_VAR)
            }

            host.addView(headerRow)
            host.addView(divider)

            tools.chunked(2).forEach { rowTools ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        bottomMargin = dp(4)
                    }
                }
                row.addView(makeToolCard(rowTools[0]))
                if (rowTools.size > 1) {
                    row.addView(makeToolCard(rowTools[1]))
                } else {
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(140), 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                    })
                }
                host.addView(row)
            }
        }

        if (showAi) {
            if (BuildConfig.MONETISATION_ENFORCE) {
                addSection(tr(R.string.main_free_cli_tools), "// OPEN SOURCE / FREE", freeTools)
                addSection(tr(R.string.main_paid_cli_tools), "// PRO / SUBSCRIPTION", paidTools)
            } else addSection(tr(R.string.fd_cli_tools), "// AI CLIS", freeTools + paidTools) // fdroid: no free/paid split
        }
        addSection(tr(R.string.main_linux_shell), "// SYSTEM SHELL", shellTools)
    }

    private fun refreshToolCardsForMethod() {
        refreshTerminalToolSelector()
        refreshWorkspaceHubTools()
    }

    // ── Custom Cyber-Brutalist Bottom Navigation ────────────────────────────

    private lateinit var customBottomNav: LinearLayout
    private val navTabViews = ArrayList<LinearLayout>()
    private lateinit var customProjectBottomNav: LinearLayout
    private val projectNavTabViews = ArrayList<LinearLayout>()

    private lateinit var customSideNav: LinearLayout
    private val sideNavTabViews = ArrayList<LinearLayout>()
    private lateinit var customSideProjectNav: LinearLayout
    private val sideProjectNavTabViews = ArrayList<LinearLayout>()

    private fun buildCustomBottomNav(): LinearLayout {
        customBottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(64))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
            }
        }

        val tabsData = listOf(
            Triple(ID_HOME, R.drawable.ic_home, tr(R.string.main_home).uppercase()),
            Triple(ID_PROJECTS_LIST, R.drawable.ic_folder, tr(R.string.main_project)),
            Triple(ID_TERMINAL, R.drawable.ic_terminal, tr(R.string.main_terminal)),
            Triple(ID_SETTINGS, R.drawable.ic_settings, tr(R.string.main_settings))
        )

        navTabViews.clear()
        for ((pageId, iconRes, label) in tabsData) {
            val tabLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                setOnClickListener {
                    if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                        pageStack.push(pageId)
                    }
                    navigateToPage(pageId)
                }
            }

            val iconIv = ImageView(this).apply {
                tag = "TAB_ICON"
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            }
            val labelTv = TextView(this).apply {
                tag = "TAB_LABEL"
                text = label
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(3)
                }
            }
            tabLayout.addView(iconIv)
            tabLayout.addView(labelTv)
            tabLayout.tag = pageId
            navTabViews.add(tabLayout)
            customBottomNav.addView(tabLayout)
        }

        updateCustomBottomNavSelection(ID_HOME)
        return customBottomNav
    }

    private fun updateCustomBottomNavSelection(activeId: Int) {
        val isProjectPage = activeId == ID_PROJECT_WORKSPACE ||
            activeId == ID_PROJECT_DIR_TREE ||
            activeId == ID_PROJECT_GIT_DIFF ||
            activeId == ID_PROJECT_SETTINGS

        // Toggle which nav is visible
        if (::customBottomNav.isInitialized) {
            customBottomNav.visibility = if (isProjectPage) View.GONE else View.VISIBLE
        }
        if (::customProjectBottomNav.isInitialized) {
            customProjectBottomNav.visibility = if (isProjectPage) View.VISIBLE else View.GONE
        }

        // Update global nav tab highlights
        if (::customBottomNav.isInitialized) {
            for (tab in navTabViews) {
                val pageId = tab.tag as? Int ?: continue
                val isSelected = when (pageId) {
                    ID_HOME -> activeId == ID_HOME
                    ID_PROJECTS_LIST -> activeId == ID_PROJECTS_LIST
                    ID_TERMINAL -> activeId == ID_TERMINAL
                    ID_SETTINGS -> activeId == ID_SETTINGS
                    else -> pageId == activeId
                }
                val iconIv = tab.findViewWithTag<ImageView>("TAB_ICON")
                val labelTv = tab.findViewWithTag<TextView>("TAB_LABEL")
                if (isSelected) {
                    tab.setBackgroundColor(NC.PRIMARY)
                    iconIv?.imageTintList = android.content.res.ColorStateList.valueOf(NC.SURFACE_LOWEST)
                    labelTv?.setTextColor(NC.SURFACE_LOWEST)
                    labelTv?.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                } else {
                    tab.setBackgroundColor(NC.SURFACE_LOWEST)
                    iconIv?.imageTintList = android.content.res.ColorStateList.valueOf(NC.ON_SURF_VAR)
                    labelTv?.setTextColor(NC.ON_SURF_VAR)
                    labelTv?.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                }
            }
        }

        // Update project nav tab highlights
        if (::customProjectBottomNav.isInitialized) {
            for (tab in projectNavTabViews) {
                val pageId = tab.tag as? Int ?: continue
                val isSelected = pageId == activeId
                val iconIv = tab.findViewWithTag<ImageView>("TAB_ICON")
                val labelTv = tab.findViewWithTag<TextView>("TAB_LABEL")
                if (isSelected) {
                    tab.setBackgroundColor(NC.PRIMARY)
                    iconIv?.imageTintList = android.content.res.ColorStateList.valueOf(NC.SURFACE_LOWEST)
                    labelTv?.setTextColor(NC.SURFACE_LOWEST)
                    labelTv?.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                } else {
                    tab.setBackgroundColor(NC.SURFACE_LOWEST)
                    iconIv?.imageTintList = android.content.res.ColorStateList.valueOf(NC.ON_SURF_VAR)
                    labelTv?.setTextColor(NC.ON_SURF_VAR)
                    labelTv?.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                }
            }
        }
        // Sync side rails (same cyber-brutalist design, vertically centered)
        if (::customSideNav.isInitialized || ::customSideProjectNav.isInitialized) {
            try { updateCustomSideNavSelection(activeId) } catch (_: Exception) {}
        }
    }

    private fun buildCustomProjectBottomNav(): LinearLayout {
        customProjectBottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(64))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
            }
            visibility = View.GONE // hidden by default; shown when in project pages
        }

        val tabsData = listOf(
            Triple(ID_PROJECT_WORKSPACE, R.drawable.ic_home, tr(R.string.onb_workspace)),
            Triple(ID_PROJECT_DIR_TREE, R.drawable.ic_folder, tr(R.string.main_directory)),
            Triple(ID_PROJECT_GIT_DIFF, R.drawable.ic_git, tr(R.string.main_diff)),
            Triple(ID_PROJECT_SETTINGS, R.drawable.ic_project_config, tr(R.string.main_config))
        )

        projectNavTabViews.clear()
        for ((pageId, iconRes, label) in tabsData) {
            val tabLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                setOnClickListener {
                    if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                        pageStack.push(pageId)
                    }
                    navigateToPage(pageId)
                }
            }
            val iconIv = ImageView(this).apply {
                tag = "TAB_ICON"
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            }
            val labelTv = TextView(this).apply {
                tag = "TAB_LABEL"
                text = label
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) }
            }
            tabLayout.addView(iconIv)
            tabLayout.addView(labelTv)
            tabLayout.tag = pageId
            projectNavTabViews.add(tabLayout)
            customProjectBottomNav.addView(tabLayout)
        }

        return customProjectBottomNav
    }

    private fun buildCustomSideNav(): LinearLayout {
        customSideNav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(80), MATCH)
            setBackgroundColor(NC.SURFACE_LOWEST)
        }
        val tabsData = listOf(
            Triple(ID_HOME, R.drawable.ic_home, tr(R.string.main_home)),
            Triple(ID_PROJECTS_LIST, R.drawable.ic_folder, tr(R.string.main_projects)),
            Triple(ID_TERMINAL, R.drawable.ic_terminal, "Terminal"),
            Triple(ID_SETTINGS, R.drawable.ic_settings, tr(R.string.main_settings_2))
        )
        sideNavTabViews.clear()
        for ((pageId, iconRes, label) in tabsData) {
            val tabLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(4); bottomMargin = dp(4)
                }
                setOnClickListener {
                    if (pageStack.isEmpty() || pageStack.peek() != pageId) pageStack.push(pageId)
                    navigateToPage(pageId)
                }
            }
            val iconIv = ImageView(this).apply {
                tag = "TAB_ICON"
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
            val labelTv = TextView(this).apply {
                tag = "TAB_LABEL"
                text = label
                textSize = 10f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
            }
            tabLayout.addView(iconIv)
            tabLayout.addView(labelTv)
            tabLayout.tag = pageId
            sideNavTabViews.add(tabLayout)
            customSideNav.addView(tabLayout)
        }
        updateCustomSideNavSelection(ID_HOME)
        return customSideNav
    }

    private fun buildCustomSideProjectNav(): LinearLayout {
        customSideProjectNav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(80), MATCH)
            setBackgroundColor(NC.SURFACE_LOWEST)
            visibility = View.GONE
        }
        val tabsData = listOf(
            Triple(ID_PROJECT_WORKSPACE, R.drawable.ic_home, tr(R.string.chat_workspace)),
            Triple(ID_PROJECT_DIR_TREE, R.drawable.ic_folder, tr(R.string.main_directory_2)),
            Triple(ID_PROJECT_GIT_DIFF, R.drawable.ic_git, tr(R.string.main_diff_2)),
            Triple(ID_PROJECT_SETTINGS, R.drawable.ic_project_config, tr(R.string.main_config_2))
        )
        sideProjectNavTabViews.clear()
        for ((pageId, iconRes, label) in tabsData) {
            val tabLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(4); bottomMargin = dp(4)
                }
                setOnClickListener {
                    if (pageStack.isEmpty() || pageStack.peek() != pageId) pageStack.push(pageId)
                    navigateToPage(pageId)
                }
            }
            val iconIv = ImageView(this).apply {
                tag = "TAB_ICON"
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
            val labelTv = TextView(this).apply {
                tag = "TAB_LABEL"
                text = label
                textSize = 10f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
            }
            tabLayout.addView(iconIv)
            tabLayout.addView(labelTv)
            tabLayout.tag = pageId
            sideProjectNavTabViews.add(tabLayout)
            customSideProjectNav.addView(tabLayout)
        }
        return customSideProjectNav
    }

    private fun updateCustomSideNavSelection(activeId: Int) {
        val isProjectPage = activeId == ID_PROJECT_WORKSPACE ||
            activeId == ID_PROJECT_DIR_TREE ||
            activeId == ID_PROJECT_GIT_DIFF ||
            activeId == ID_PROJECT_SETTINGS
        if (::customSideNav.isInitialized) {
            customSideNav.visibility = if (isProjectPage) View.GONE else View.VISIBLE
        }
        if (::customSideProjectNav.isInitialized) {
            customSideProjectNav.visibility = if (isProjectPage) View.VISIBLE else View.GONE
        }
        if (::customSideNav.isInitialized) {
            for (tab in sideNavTabViews) {
                val pageId = tab.tag as? Int ?: continue
                val isSelected = when (pageId) {
                    ID_HOME -> activeId == ID_HOME
                    ID_PROJECTS_LIST -> activeId == ID_PROJECTS_LIST
                    ID_TERMINAL -> activeId == ID_TERMINAL
                    ID_SETTINGS -> activeId == ID_SETTINGS
                    else -> pageId == activeId
                }
                val iconIv = tab.findViewWithTag<ImageView>("TAB_ICON")
                val labelTv = tab.findViewWithTag<TextView>("TAB_LABEL")
                if (isSelected) {
                    tab.setBackgroundColor(NC.PRIMARY)
                    iconIv?.imageTintList = android.content.res.ColorStateList.valueOf(NC.SURFACE_LOWEST)
                    labelTv?.setTextColor(NC.SURFACE_LOWEST)
                    labelTv?.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                } else {
                    tab.setBackgroundColor(NC.SURFACE_LOWEST)
                    iconIv?.imageTintList = android.content.res.ColorStateList.valueOf(NC.ON_SURF_VAR)
                    labelTv?.setTextColor(NC.ON_SURF_VAR)
                    labelTv?.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                }
            }
        }
        if (::customSideProjectNav.isInitialized) {
            for (tab in sideProjectNavTabViews) {
                val pageId = tab.tag as? Int ?: continue
                val isSelected = pageId == activeId
                val iconIv = tab.findViewWithTag<ImageView>("TAB_ICON")
                val labelTv = tab.findViewWithTag<TextView>("TAB_LABEL")
                if (isSelected) {
                    tab.setBackgroundColor(NC.PRIMARY)
                    iconIv?.imageTintList = android.content.res.ColorStateList.valueOf(NC.SURFACE_LOWEST)
                    labelTv?.setTextColor(NC.SURFACE_LOWEST)
                    labelTv?.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                } else {
                    tab.setBackgroundColor(NC.SURFACE_LOWEST)
                    iconIv?.imageTintList = android.content.res.ColorStateList.valueOf(NC.ON_SURF_VAR)
                    labelTv?.setTextColor(NC.ON_SURF_VAR)
                    labelTv?.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                }
            }
        }
    }

    // ── Screen Builders ──────────────────────────────────────────────────────

    private fun buildHomeLayout() {
        homeScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            background = TerminalScanlineDrawable()
        }
        homeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            background = TerminalScanlineDrawable()
        }
        homeScrollView.addView(homeLayout)

        // 1. Dashboard Banner & Quick Actions
        homeLayout.addView(buildHomeHeaderBanner())
        homeLayout.addView(spacer(14))
        (homeUpdateBannerHolder.parent as? ViewGroup)?.removeView(homeUpdateBannerHolder)
        homeLayout.addView(homeUpdateBannerHolder) // non-blocking "updates available" link to Components

        // 2. System Telemetry Cards (SYS_CPU & SYS_MEM)
        homeLayout.addView(buildSystemTelemetryCards())

        // 3. Package ops — Software Manager
        homeLayout.addView(buildHomePackageOpsSection())
        homeLayout.addView(spacer(8))

        // 4. Remote access + AI usage
        homeLayout.addView(buildHomeToolsSection())
        homeLayout.addView(spacer(8))

        // 5. Recent workspaces
        homeLayout.addView(buildRecentProjectsSection())
    }

    
    private fun cyberBrutalistBg(
        fillColor: Int,
        strokeColor: Int = Color.parseColor("#3c4a3f"),
        shadowColor: Int = Color.parseColor("#393939"),
        offsetDp: Int = 6,
        cornerRadiusDp: Int = 0,
        rightFaceColor: Int = Color.parseColor("#3c4a3f")
    ): LayerDrawable {
        val bottomShadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(shadowColor)
            if (cornerRadiusDp > 0) cornerRadius = dp(cornerRadiusDp).toFloat()
        }
        val rightShadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(rightFaceColor)
            if (cornerRadiusDp > 0) cornerRadius = dp(cornerRadiusDp).toFloat()
        }
        val frontDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
            if (cornerRadiusDp > 0) cornerRadius = dp(cornerRadiusDp).toFloat()
        }
        val layers = arrayOf<Drawable>(bottomShadow, rightShadow, frontDrawable)
        val off = dp(offsetDp)
        return LayerDrawable(layers).apply {
            setLayerInset(0, off, off, 0, 0)
            setLayerInset(1, off, 0, 0, off)
            setLayerInset(2, 0, 0, off, off)
        }
    }

    private fun buildSystemTelemetryCards(): View {
        return androidx.compose.ui.platform.ComposeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = -dp(16)
                rightMargin = -dp(16)
                bottomMargin = dp(16)
            }
            setContent {
                SystemTelemetryCards(
                    cpuPercentage = composeCpuState.intValue,
                    memPercentage = composeMemState.intValue,
                    ramUsedMb = composeRamUsedState.longValue,
                    ramTotalMb = composeRamTotalState.longValue,
                    swapUsedMb = composeSwapUsedState.longValue,
                    swapTotalMb = composeSwapTotalState.longValue,
                    diskPercentage = composeDiskState.intValue
                )
            }
        }
    }

    private fun buildHomeHeaderBanner(): View {
        val heroCard = FrameLayout(this).apply {
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 6
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val bgIv = object : androidx.appcompat.widget.AppCompatImageView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            }
        }.apply {
            setImageResource(R.drawable.hero_bg)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.5f
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        heroCard.addView(bgIv)

        val overlay = object : View(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#B00E0E0E"))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        heroCard.addView(overlay)

        val contentCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        }

        val titleTv = TextView(this).apply {
            text = tr(R.string.main_welcome_to_nativecode)
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

        val subtextTv = TextView(this).apply {
            text = tr(R.string.main_full_linux_development_environment_o)
            textSize = 11f
            setTextColor(Color.parseColor("#D0D0D0"))
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, dp(16))
            setLineSpacing(0f, 1.25f)
        }

        val createBtn = TextView(this).apply {
            text = "  " + tr(R.string.main_caps_create_new_project)
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            background = cyberBrutalistBg(
                fillColor = Color.parseColor("#CC121212"),
                strokeColor = Color.parseColor("#3DDC84"),
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 4
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener {
                if (pageStack.isEmpty() || pageStack.peek() != ID_PROJECT_CREATE) {
                    pageStack.push(ID_PROJECT_CREATE)
                }
                navigateToPage(ID_PROJECT_CREATE)
            }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().translationX(dp(2).toFloat()).translationY(dp(2).toFloat()).setDuration(60).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().translationX(0f).translationY(0f).setDuration(60).start()
                }
                false
            }
        }

        contentCol.addView(titleTv)
        contentCol.addView(subtextTv)
        createBtn.trackProLock()
        contentCol.addView(createBtn)
        heroCard.addView(contentCol)

        return heroCard
    }

    private fun animateHomeLayoutEntrance() {
        if (!::homeLayout.isInitialized) return
        homeLayout.post {
            for (i in 0 until homeLayout.childCount) {
                val child = homeLayout.getChildAt(i)
                child.alpha = 0f
                child.translationY = dp(24).toFloat()
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(350)
                    .setStartDelay((i * 50).toLong())
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun buildFileExplorerLayout() {
        fileExplorerScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
        }
        fileExplorerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        fileExplorerScrollView.addView(fileExplorerLayout)

        // Search Bar
        val searchFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }
        val searchEt = EditText(this).apply {
            hint = tr(R.string.main_search_files); setHintTextColor(NC.OUTLINE); textSize = 13f; setTextColor(NC.ON_SURFACE)
            setBackgroundColor(NC.SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(NC.SURFACE, NC.BORDER, dp(6))
        }
        searchFrame.addView(searchEt)
        fileExplorerLayout.addView(searchFrame)

        // Project Root Panel
        val rootPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(NC.SURFACE, NC.BORDER, dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(NC.SURFACE_VAR); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val title = TextView(this).apply {
            fileExplorerTitleTv = this
            text = tr(R.string.main_project_root); textSize = 15f; setTextColor(NC.ON_SURFACE); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val newFolder = TextView(this).apply { text = tr(R.string.main_folder); textSize = 16f; setTextColor(NC.ON_SURF_VAR); setPadding(dp(8), 0, dp(4), 0) }
        val newFile = TextView(this).apply { text = tr(R.string.main_file); textSize = 16f; setTextColor(NC.ON_SURF_VAR) }
        header.addView(title); header.addView(newFolder); header.addView(newFile)
        rootPanel.addView(header)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        for ((icon, name, isFolder) in listOf(
            Triple("", "app", true),
            Triple("", "gradle", true),
            Triple("", ".gitignore", false),
            Triple("", "build.gradle.kts", false),
            Triple("", "settings.gradle.kts", false)
        )) {
            val row = fileRow(icon, name, isFolder, modified = name == "build.gradle.kts")
            row.setOnClickListener {
                if (!isFolder) {
                    showFileViewer(name)
                }
            }
            list.addView(row)
        }
        rootPanel.addView(list)
        fileExplorerLayout.addView(rootPanel)

        // Git Changes Panel
        val gitPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(NC.SURFACE, NC.BORDER, dp(10))
        }
        val gitHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(NC.SURFACE_VAR); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val gitTitle = TextView(this).apply {
            text = tr(R.string.main_git_changes_2); textSize = 15f; setTextColor(NC.ON_SURFACE); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val commitBtn = TextView(this).apply { text = tr(R.string.main_commit); textSize = 13f; setTextColor(NC.PRIMARY); typeface = Typeface.DEFAULT_BOLD }
        gitHeader.addView(gitTitle); gitHeader.addView(commitBtn)
        gitPanel.addView(gitHeader)

        val gitList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        for (name in listOf("MainActivity.kt", "build.gradle.kts")) {
            val row = fileRow("", name, false, modified = true)
            row.setOnClickListener {
                showDiffViewer(name)
            }
            gitList.addView(row)
        }
        gitPanel.addView(gitList)
        fileExplorerLayout.addView(gitPanel)
    }

    // ── Key injection helper ───────────────────────────────────────────────────
    private val SPECIAL_KEY_CODES = mapOf(
        "ESC"   to KeyEvent.KEYCODE_ESCAPE,
        "TAB"   to KeyEvent.KEYCODE_TAB,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "BKSP"  to KeyEvent.KEYCODE_DEL,
        "DEL"   to KeyEvent.KEYCODE_FORWARD_DEL,
        "UP"    to KeyEvent.KEYCODE_DPAD_UP,
        tr(R.string.term_down)  to KeyEvent.KEYCODE_DPAD_DOWN,
        tr(R.string.term_left)  to KeyEvent.KEYCODE_DPAD_LEFT,
        tr(R.string.term_right) to KeyEvent.KEYCODE_DPAD_RIGHT,
        "HOME"  to KeyEvent.KEYCODE_MOVE_HOME,
        "END"   to KeyEvent.KEYCODE_MOVE_END,
        "PGUP"  to KeyEvent.KEYCODE_PAGE_UP,
        "PGDN"  to KeyEvent.KEYCODE_PAGE_DOWN,
        "INS"   to KeyEvent.KEYCODE_INSERT,
        "F1"    to KeyEvent.KEYCODE_F1,
        "F2"    to KeyEvent.KEYCODE_F2,
        "F3"    to KeyEvent.KEYCODE_F3,
        "F4"    to KeyEvent.KEYCODE_F4,
        "F5"    to KeyEvent.KEYCODE_F5,
        "F6"    to KeyEvent.KEYCODE_F6,
        "F7"    to KeyEvent.KEYCODE_F7,
        "F8"    to KeyEvent.KEYCODE_F8,
        "F9"    to KeyEvent.KEYCODE_F9,
        "F10"   to KeyEvent.KEYCODE_F10,
        "F11"   to KeyEvent.KEYCODE_F11,
        "F12"   to KeyEvent.KEYCODE_F12
    )

    private fun injectKey(tv: TerminalView?, key: String, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        tv ?: return
        val keyCode = SPECIAL_KEY_CODES[key]
        if (keyCode != null) {
            var meta = 0
            if (ctrl)  meta = meta or KeyEvent.META_CTRL_ON  or KeyEvent.META_CTRL_LEFT_ON
            if (alt)   meta = meta or KeyEvent.META_ALT_ON   or KeyEvent.META_ALT_LEFT_ON
            if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            val evDown = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
            tv.onKeyDown(keyCode, evDown)
            val evUp = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta)
            tv.onKeyUp(keyCode, evUp)
        } else {
            key.codePoints().forEach { cp -> tv.inputCodePoint(cp, ctrl, alt) }
        }
    }

    /** App terminal → global method; workspace → active project method. */
    private fun resolveAttachMethod(isWorkspace: Boolean): String {
        return if (isWorkspace) {
            activeProjectMethod.ifBlank { LinuxCommandBuilder.currentMethod }
        } else {
            LinuxCommandBuilder.currentMethod
        }
    }

    private fun mimeToImageExt(mime: String?): String {
        val m = mime?.lowercase(Locale.US) ?: return "jpg"
        return when {
            m.contains("png") -> "png"
            m.contains("webp") -> "webp"
            m.contains("gif") -> "gif"
            m.contains("jpeg") || m.contains("jpg") -> "jpg"
            m.startsWith("image/") -> m.substringAfterLast('/').substringBefore(';').ifBlank { "jpg" }
            else -> "jpg"
        }
    }

    private fun setAttachmentPathClipboard(guestPath: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("linux_attachment_path", guestPath))
    }

    private fun handleImageAttachment(uri: Uri, isWorkspace: Boolean) {
        handleAttachment(uri, isWorkspace, imageOnly = true)
    }

    private fun handleFileAttachment(uri: Uri, isWorkspace: Boolean) {
        handleAttachment(uri, isWorkspace, imageOnly = false)
    }

    private fun queryAttachmentName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun safeAttachmentName(uri: Uri, mime: String?, imageOnly: Boolean): String {
        val raw = queryAttachmentName(uri)
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            .orEmpty()
        val safe = raw
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(96)
        val fallback = if (imageOnly) "image.${mimeToImageExt(mime)}" else "file"
        return "attach_${System.currentTimeMillis()}_${safe.ifBlank { fallback }}"
    }

    /** Copy a picked Android document into the active Linux guest and copy its guest path. */
    private fun handleAttachment(uri: Uri, isWorkspace: Boolean, imageOnly: Boolean) {
        val method = resolveAttachMethod(isWorkspace)
        val mime = contentResolver.getType(uri)
        if (imageOnly && mime != null && !mime.startsWith("image/")) {
            Toast.makeText(this, tr(R.string.main_not_an_image), Toast.LENGTH_SHORT).show()
            return
        }
        val fname = safeAttachmentName(uri, mime, imageOnly)
        val guestPath = if (isWorkspace && activeProjectPath.isNotBlank()) {
            "${activeProjectPath.trimEnd('/')}/$fname"
        } else {
            "/home/flux/$fname"
        }

        Toast.makeText(this, tr(R.string.main_copying_into_linux), Toast.LENGTH_SHORT).show()

        executor.execute {
            var stageFile: File? = null
            try {
                val stageDir = ProjectPathResolver.stageAttachDir(this@MainActivity)
                stageFile = File(stageDir, fname)
                contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(stageFile).use { out -> inp.copyTo(out) }
                } ?: run {
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, tr(R.string.main_failed_to_read_selection), Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                if (!stageFile.isFile || (imageOnly && stageFile.length() <= 0L)) {
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, tr(R.string.main_selected_file_could_not_be), Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                val stagedSize = stageFile.length()

                val finalGuestPath: String
                if (method == "chroot") {
                    if (!RootShell.isRootAvailable()) {
                        finalGuestPath = ProjectPathResolver.stageAttachGuestPath(fname)
                        Log.w(
                            "FileAttach",
                            "chroot no root; bind fallback path=$finalGuestPath stage=${stageFile.absolutePath}"
                        )
                        mainHandler.post {
                            setAttachmentPathClipboard(finalGuestPath)
                            Toast.makeText(
                                this@MainActivity,
                                "Copied path: $finalGuestPath",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@execute
                    }
                    val result = RootShell.copyIntoChroot(
                        hostSrc = stageFile,
                        guestAbsPath = guestPath,
                        chrootPath = ChrootCommandBuilder.CHROOT_PATH
                    )
                    if (result.exitCode != 0) {
                        Log.e(
                            "FileAttach",
                            "chroot cp fail code=${result.exitCode} out=${result.stdout.take(200)}"
                        )
                        stageFile.delete()
                        mainHandler.post {
                            Toast.makeText(
                                this@MainActivity,
                                tr(R.string.main_chroot_copy_failed_exit, result.exitCode),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@execute
                    }
                    stageFile.delete()
                    finalGuestPath = guestPath
                } else {
                    val hostTarget = ProjectPathResolver.resolve(this@MainActivity, guestPath, method)
                    hostTarget.parentFile?.mkdirs()
                    stageFile.copyTo(hostTarget, overwrite = true)
                    if (!hostTarget.isFile || hostTarget.length() != stagedSize) {
                        mainHandler.post {
                            Toast.makeText(this@MainActivity, tr(R.string.main_proot_write_failed), Toast.LENGTH_SHORT).show()
                        }
                        return@execute
                    }
                    stageFile.delete()
                    finalGuestPath = guestPath
                }

                Log.i(
                    "FileAttach",
                    "ok method=$method workspace=$isWorkspace guest=$finalGuestPath"
                )
                mainHandler.post {
                    setAttachmentPathClipboard(finalGuestPath)
                    Toast.makeText(
                        this@MainActivity,
                        "Copied path: $finalGuestPath",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                stageFile?.delete()
                Log.e("FileAttach", "Failed to copy attachment", e)
                mainHandler.post {
                    Toast.makeText(
                        this@MainActivity,
                        "Attach failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showAttachmentTutorial(onContinue: (() -> Unit)? = null) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC0E0E0E"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.BORDER
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            isClickable = true
        }
        card.addView(TextView(this).apply {
            text = tr(R.string.main_attach_to_linux)
            textSize = 15f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(14))
        })
        fun step(icon: Int, title: String, body: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_CONTAINER)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    setColorFilter(NC.PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) }
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.MONOSPACE
                        paint.isFakeBoldText = true
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = body
                        textSize = 11f
                        setTextColor(NC.ON_SURF_VAR)
                        setPadding(0, dp(2), 0, 0)
                    })
                })
            }
        }
        card.addView(step(R.drawable.ic_attach_image, tr(R.string.main_image), tr(R.string.main_pick_a_picture_copied_into)))
        card.addView(step(R.drawable.ic_attach_file, tr(R.string.main_file_2), tr(R.string.main_pick_any_document_same_copy)))
        card.addView(TextView(this).apply {
            text = tr(R.string.main_nativecode_writes_the_file_into)
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(0, dp(4), 0, dp(16))
        })
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (onContinue != null) {
            btnRow.addView(TextView(this).apply {
                text = tr(R.string.x11_cancel)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                setTextColor(NC.ON_SURF_VAR)
                gravity = Gravity.CENTER
                background = cyberBrutalistBg(NC.SURFACE_LOW, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
                setOnClickListener { dialog.dismiss() }
            })
        }
        btnRow.addView(TextView(this).apply {
            text = if (onContinue == null) tr(R.string.main_caps_got_it) else tr(R.string.main_caps_continue)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#0A0A0A"))
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(NC.PRIMARY, NC.BORDER, NC.SHADOW_GREEN, 4, 0)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.3f)
            setOnClickListener {
                getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
                    .putBoolean("attachment_tutorial_seen", true)
                    .apply()
                dialog.dismiss()
                onContinue?.invoke()
            }
        })
        card.addView(btnRow)
        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.show()
    }

    private fun pickAttachmentWithTutorial(picker: () -> Unit) {
        val seen = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
            .getBoolean("attachment_tutorial_seen", false)
        if (seen) picker() else showAttachmentTutorial(picker)
    }

    /** Extra keys: terminal/keys/ExtraKeysBar (own design). This activity only routes keys and actions. */
    private fun buildExtraKeysBar(
        tvRef: () -> TerminalView?,
        sessionRef: () -> TerminalSession?,
        modState: ModifierState,
    ): ExtraKeysBar = ExtraKeysBar(this, modState, object : ExtraKeysBar.Host {
        override fun send(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean) {
            if (tvRef()?.isShown == false) return // Chat position: never inject into the hidden session
            injectKey(tvRef(), key, ctrl, alt, shift)
        }

        override fun action(id: String) {
            when (id) {
                KeyLayouts.ACTION_COPY -> TerminalScrollViewHelper.copyTranscript(this@MainActivity, sessionRef())
                KeyLayouts.ACTION_PASTE -> sessionRef()?.onPasteTextFromClipboard()
                KeyLayouts.ACTION_TRANSCRIPT -> {
                    val customTf = try { Typeface.createFromAsset(assets, "fonts/font.ttf") } catch (_: Exception) { null }
                    TerminalScrollViewHelper.showScrollViewDialog(this@MainActivity, sessionRef(), customTf)
                }
                KeyLayouts.ACTION_LIVE -> tvRef()?.setTopRow(0)
            }
        }
    }).apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP) }

    private fun buildTerminalLayout() {
        terminalWorkspaceLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
        }

        // Dedicated Terminal Top Bar
        val terminalTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), Color.parseColor("#3C4A3F"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        // Stretched strip: drops the chips that do not fit instead of pushing the rail out
        val termStats = StatStrip(this).apply {
            onCustomize = { openTopBarPage() }
            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f).apply {
                rightMargin = dp(8)
            }
        }
        terminalTopBar.addView(termStats)
        terminalTopBar.addView(ToolRail(this).also { fillToolRail(it, workspace = false) })
        terminalWorkspaceLayout.addView(terminalTopBar)
        applyKeepAwake()

        // Raw terminal page and the chat view (default): siblings, one GONE (item 12, §9.2.6 E).
        val termStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        // Tab strip heads the raw terminal page (replaces the drawer): CHAT cell, terminal tabs, "+".
        termTabStrip = buildTermTabStrip().also { termStack.addView(it) }
        appTermStack = termStack
        terminalWorkspaceLayout.addView(termStack)
        appChatView = newChatView(ws = false).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) }
        terminalWorkspaceLayout.addView(appChatView)

        terminalToolSelectorScrollView = buildTerminalToolSelectorView().apply {
            visibility = View.VISIBLE
        }
        termStack.addView(terminalToolSelectorScrollView)

        terminalViewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            visibility = View.GONE
        }
        terminalView = TerminalView(this, null).apply {
            isFocusable = false; isFocusableInTouchMode = false
        }
        registerForContextMenu(terminalView)
        terminalViewContainer.addView(terminalView)
        terminalViewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            forceTerminalResize(if (::terminalView.isInitialized) terminalView else null)
        }
        termStack.addView(terminalViewContainer)

        terminalKeyboardToolbar = buildExtraKeysBar(
            tvRef = { if (::terminalView.isInitialized) terminalView else null },
            sessionRef = { terminalSession },
            modState = termModState,
        ).apply {
            visibility = if (showExtraKeys) View.VISIBLE else View.GONE
        }
        termStack.addView(terminalKeyboardToolbar)
        applyTerminalMode(ws = false)
    }

    private fun toggleKeepAwake() {
        keepAwake = !keepAwake
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
            .putBoolean("pref_keep_awake", keepAwake)
            .apply()
        applyKeepAwake()
    }

    private fun showSkillsPicker(workspace: Boolean) {
        if (skillsPicker != null) {
            skillsPicker?.dismiss()
            return
        }
        val attach: ((com.zenithblue.nativecode.skills.Skill) -> Boolean)? = if (!isChatMode(workspace)) null else { sk ->
            (if (workspace) wsChatView else appChatView)?.attachSkill(sk.title, sk.body) == true
        }
        skillsPicker = com.zenithblue.nativecode.skills.SkillsPickerSheet(this, contentFrame, attach) {
            skillsPicker = null
        }
    }

    // ---------------- Agent chat (item 12): Chat | Terminal per page ----------------
    private var appChatView: AgentChatView? = null
    private var wsChatView: AgentChatView? = null
    private var appTermStack: View? = null
    private var wsTermArea: View? = null
    /** ws → raw terminal page showing. Chat is the default view; not persisted (no Chat/Terminal switch, §9.2.6 E). */
    private val termShown = HashMap<Boolean, Boolean>()
    /** Workspace "back to chat" rail button (the TERMINAL page uses the tab strip's CHAT cell). */
    private var wsBackToChat: View? = null
    private val chatVisible = HashMap<Boolean, String?>()
    /** Backgrounded = no thread is "on screen" (notifications must show). */
    private var chatResumed = false
    /** One-shot guest cwd for the next app terminal tab (chat "Open terminal here" / "Open in terminal"). */
    private var chatTermCwd: String? = null
    private val chatBackend by lazy { StoreBackend(this) }

    private fun chatPrefs() = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
    private fun chatBetaOn() = chatPrefs().getBoolean(ChatLogic.PREF_BETA, true)
    private fun isChatMode(ws: Boolean) = chatBetaOn() && termShown[ws] != true

    /** Show chat or the raw terminal page of a surface. Chat is refused while the beta switch is off. */
    private fun setTerminalMode(ws: Boolean, chat: Boolean) {
        if (chat && !chatBetaOn()) return
        termShown[ws] = !chat
        applyTerminalMode(ws)
    }

    private fun applyTerminalMode(ws: Boolean) {
        val chat = isChatMode(ws)
        val (term, view) = if (ws) wsTermArea to wsChatView else appTermStack to appChatView
        if (term == null || view == null) return
        val wasChat = view.visibility == View.VISIBLE
        term.visibility = if (chat) View.GONE else View.VISIBLE
        view.visibility = if (chat) View.VISIBLE else View.GONE
        if (ws) wsBackToChat?.visibility = if (!chat && chatBetaOn()) View.VISIBLE else View.GONE
        if (wasChat != chat) currentFocus?.let { f ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0)
        }
        if (!chat) forceTerminalResize(if (ws) (if (::workspaceTerminalView.isInitialized) workspaceTerminalView else null)
            else if (::terminalView.isInitialized) terminalView else null)
        updateChatShown()
        if (!ws) refreshTermTabStrip()
        toolRails.forEach { it.refresh() } // terminal-only rail tools follow chat/terminal
    }

    /** Page / position / scope → each chat view (visibleThreadId, Terminal-position snackbar). */
    private fun updateChatShown() {
        val resumed = chatResumed
        if (currentPageId == ID_PROJECT_WORKSPACE) wsChatView?.setScope(activeProjectPath.ifBlank { null })
        appChatView?.setShown(resumed && currentPageId == ID_TERMINAL, isChatMode(ws = false))
        wsChatView?.setShown(resumed && currentPageId == ID_PROJECT_WORKSPACE, isChatMode(ws = true))
    }

    /** Workspace terminal area "back to chat": one rail child, shown only while the raw terminal is (no layout rebuild). */
    private fun buildWsBackToChat(): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_tab_chat)
        setColorFilter(NC.ON_PRIMARY)
        setBackgroundColor(NC.PRIMARY)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        contentDescription = "Back to chat"
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(4) }
        visibility = if (!isChatMode(ws = true) && chatBetaOn()) View.VISIBLE else View.GONE
        setOnClickListener { setTerminalMode(ws = true, chat = true) }
        wsBackToChat = this
    }

    private fun newChatView(ws: Boolean) = AgentChatView(this, executor, ws, chatHost, chatBackend) { id ->
        chatVisible[ws] = id
        AgentChats.visibleThreadId = chatVisible[false] ?: chatVisible[true]
    }.apply { isClickable = true } // blank areas never fall through

    /** Same count as the gate: AI terminal tabs (all projects) + pending launches + live/starting chats. */
    private fun aiSessionCount(): Int {
        val others = projectTabNamesMap.filterKeys { it != workspaceLoadedPath }.values.flatten()
        val tabs = (terminalSessionTypes + workspaceTabNames + others).count { ToolLauncherCatalog.isAiToolType(it) }
        return tabs + pendingAiTerminalLaunches + AgentChats.liveCount() + AgentChats.startingCount()
    }

    private val chatGate = object : ChatGate {
        override fun allowLaunch(tool: String, projectPath: String?) = aiLaunchAllowed(tool, "chat")
        override fun canUseTool(tool: String) = FeatureGate.canLaunchAiTool(isPro(), tool) is GateResult.Allow
        override fun showUpsell(tool: String) { gateAllows(FeatureGate.canLaunchAiTool(isPro(), tool)) }
        override fun updateCli(tool: String, need: CliNeed, done: (Boolean) -> Unit) = showCliUpdate(tool, need, done)
    }

    private val chatHost = object : ChatHost {
        override fun isPro() = this@MainActivity.isPro()
        override fun canUseTool(tool: String) = !aiToolLocked(tool)
        override fun showUpgradeSheet(reason: String?) = this@MainActivity.showUpgradeSheet(reason)
        override fun showCliLogin(id: String) = this@MainActivity.showCliLogin(id)
        override fun showCliUpdate(id: String, need: CliNeed, done: (Boolean) -> Unit) = this@MainActivity.showCliUpdate(id, need, done)
        override fun openTerminal(tool: String, args: List<String>, cwd: String?, workspace: Boolean) {
            // ponytail: resume flags unverified for opencode/agy/grok → [args] unused, fresh start (plan 9.2.8).
            if (workspace) { createWorkspaceTerminalTab(tool); return }
            chatTermCwd = cwd
            createNewTerminalSession(tool)
            chatTermCwd = null
        }
        override fun openShellHere(cwd: String, workspace: Boolean) = openTerminal("shell", emptyList(), cwd, workspace) // shell gate = Pro upsell (Q1)
        override fun pickImage(cb: (Uri) -> Unit) { chatPick = cb; termImagePickerLauncher.launch("image/*") }
        override fun pickFile(cb: (Uri) -> Unit) { chatPick = cb; termDocumentPickerLauncher.launch(arrayOf("*/*")) }
        override fun showTerminal(workspace: Boolean) = setTerminalMode(workspace, chat = false)
        override fun showChat(workspace: Boolean) = setTerminalMode(workspace, chat = true)
        override fun terminalTabs(workspace: Boolean) =
            (if (workspace) workspaceTabNames else terminalSessionTypes).mapIndexed { i, n -> TermTab(i, "$n · ${i + 1}") }
        override fun openTerminalTab(index: Int, workspace: Boolean) {
            setTerminalMode(workspace, chat = false)
            if (workspace) switchWorkspaceTab(index) else switchTerminalSession(index)
        }
        override fun sessionUsage() = aiSessionCount() to
            if (isPro() || !BuildConfig.MONETISATION_ENFORCE) com.zenithblue.nativecode.entitlements.FeatureLimits.PRO_MAX_AI_SESSIONS
            else com.zenithblue.nativecode.entitlements.FeatureLimits.FREE_MAX_AI_SESSIONS
        override fun guestHome() = ProjectPathResolver.guestHomeDir(this@MainActivity)
        override fun aiToolsInstalled() = AiCliProvisionState.shouldShowAiToolLaunchers(this@MainActivity)
        override fun openInstallTools() {
            if (pageStack.isEmpty() || pageStack.peek() != ID_CLI_AUTH) pageStack.push(ID_CLI_AUTH)
            navigateToPage(ID_CLI_AUTH)
        }
        override fun projects() = if (proLocked()) emptyList() else getProjects().map { it.name to it.path }
        override fun approvalWhileInTerminal(threadTitle: String, open: () -> Unit) {
            com.google.android.material.snackbar.Snackbar.make(contentFrame, "Approval needed · $threadTitle", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("OPEN") { open() }.show()
        }
        override fun saveApk(hostPath: String) = exportApkToDownloads(File(hostPath))
        override fun shareFile(hostPath: String) = shareApkFromProject(File(hostPath), "Share file")
        override fun openPreview(url: String?) = com.zenithblue.nativecode.preview.PreviewActivity.open(this@MainActivity, url)
        override fun openLink(raw: String, cwd: String, workspace: Boolean) = this@MainActivity.openChatLink(raw, cwd, workspace)
        override fun resolveFile(raw: String, cwd: String) =
            com.zenithblue.nativecode.agent.chat.ui.ChatFiles.resolve(rootfsFor(LinuxCommandBuilder.currentMethod), raw, cwd)
        override fun openFile(hostPath: String, workspace: Boolean) = this@MainActivity.openChatFile(File(hostPath), workspace)
    }

    /** A file from chat (tapped link, preview card, bridge card) into the Directory file viewer; Back returns to the chat surface. */
    private fun openChatFile(f: File?, workspace: Boolean) {
        if (f == null || !f.isFile) {
            com.google.android.material.snackbar.Snackbar.make(contentFrame, tr(R.string.main_file_not_found), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        } else {
            showFileViewer(f.absolutePath, if (workspace) ID_PROJECT_WORKSPACE else ID_TERMINAL)
        }
    }

    /**
     * Single exit for every link tapped in assistant markdown (ChatLinks.classify). A file: URI is never handed to
     * startActivity (FileUriExposedException): guest files open in the in-app editor, anything else is guarded.
     */
    private fun openChatLink(raw: String, cwd: String, workspace: Boolean) {
        fun toast(msg: String) = com.google.android.material.snackbar.Snackbar.make(contentFrame, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        when (val link = com.zenithblue.nativecode.agent.chat.ui.ChatLinks.classify(raw, cwd)) {
            is com.zenithblue.nativecode.agent.chat.ui.ChatLink.Web -> com.zenithblue.nativecode.preview.PreviewActivity.openExternal(this, link.url)
            is com.zenithblue.nativecode.agent.chat.ui.ChatLink.Preview -> com.zenithblue.nativecode.preview.PreviewActivity.open(this, link.url)
            // Symlinks resolved; null = leaves the home or missing. Same resolution as the inline preview cards (ChatFiles).
            is com.zenithblue.nativecode.agent.chat.ui.ChatLink.GuestFile ->
                openChatFile(com.zenithblue.nativecode.agent.chat.ui.ChatFiles.resolveGuest(rootfsFor(LinuxCommandBuilder.currentMethod), link.path), workspace)
            is com.zenithblue.nativecode.agent.chat.ui.ChatLink.Other -> try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { // ActivityNotFoundException, SecurityException, FileUriExposedException
                (getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("link", link.url))
                toast(tr(R.string.chat_no_app_can_open_this) + " · " + tr(R.string.main_link_copied))
            }
            com.zenithblue.nativecode.agent.chat.ui.ChatLink.Rejected -> toast(tr(R.string.main_file_not_found))
        }
    }

    /** Terminal printed a loopback dev-server URL: a non-blocking "Preview :PORT" chip (snackbar) over the terminal. */
    private fun showPreviewChip(url: String) {
        if (isFinishing) return
        val port = com.zenithblue.nativecode.preview.PortProbe.portOf(url) ?: return
        com.google.android.material.snackbar.Snackbar.make(contentFrame, tr(R.string.main_dev_server_on, port), 8000)
            .setAction("PREVIEW") { com.zenithblue.nativecode.preview.PreviewActivity.open(this, url) }
            .setActionTextColor(NC.PRIMARY)
            .show()
    }

    /** Notification deep link: page 3, or 10 + PROJECT_PATH (already applied by handleIntent), then the thread in Chat. */
    private fun openChatThreadFromIntent(threadId: String, page: Int) {
        val ws = page == ID_PROJECT_WORKSPACE
        if (!chatBetaOn()) return
        setTerminalMode(ws, chat = true)
        (if (ws) wsChatView else appChatView)?.openThread(threadId)
    }

    /** Project removed: its chats stop (A: stopProject in stopProjectSessions) and its threads + checkpoint refs go. */
    private fun deleteProjectThreads(path: String) {
        if (path.isEmpty()) return
        val store = AgentChats.store(this)
        store.read({ threads(path) }) { list -> list.forEach { AgentChats.deleteThread(this, it.id) } }
    }

    /** Tool rails (terminal + project workspace top bars); same tools, all visible, no popup. */
    private val toolRails = ArrayList<ToolRail>()

    /** @param terminalTools false on workspace sub-pages: only keep-awake + desktop pill (page actions go after them). */
    private fun fillToolRail(rail: ToolRail, workspace: Boolean, terminalTools: Boolean = true) {
        // Terminal-only tools hide while the chat is shown (its composer has its own + sheet).
        val termOnly = { terminalTools && !isChatMode(workspace) }
        rail.add(
            icon = { R.drawable.ic_keyboard },
            desc = { if (showExtraKeys) tr(R.string.main_extra_keys_on) else tr(R.string.main_extra_keys_off) },
            active = { showExtraKeys },
            visible = termOnly,
        ) { setExtraKeysEnabled(!showExtraKeys) }
        // Attach lives here now (was two keys in the extra-keys row). Hold = how it works.
        rail.add(
            icon = { R.drawable.ic_lucide_image },
            desc = { tr(R.string.main_attach_image_to_linux) },
            onLongClick = { showAttachmentTutorial() },
            visible = termOnly,
        ) {
            pickAttachmentWithTutorial {
                (if (workspace) wsImagePickerLauncher else termImagePickerLauncher).launch("image/*")
            }
        }
        rail.add(
            icon = { R.drawable.ic_lucide_file_text },
            desc = { tr(R.string.main_attach_file_to_linux) },
            onLongClick = { showAttachmentTutorial() },
            visible = termOnly,
        ) {
            pickAttachmentWithTutorial {
                (if (workspace) wsDocumentPickerLauncher else termDocumentPickerLauncher).launch(arrayOf("*/*"))
            }
        }
        // Terminal page: copy a skill. Chat page: the same list adds the skill to the open chat's composer.
        rail.add(
            icon = { R.drawable.ic_lucide_bot },
            desc = { if (isChatMode(workspace)) tr(R.string.chat_skills) else tr(R.string.main_copy_skill) },
        ) { showSkillsPicker(workspace) }
        rail.add(
            icon = { if (keepAwake) R.drawable.ic_lucide_eye else R.drawable.ic_lucide_eye_off },
            desc = { if (keepAwake) tr(R.string.main_keep_awake_on) else tr(R.string.main_keep_awake_off) },
            active = { keepAwake },
        ) { toggleKeepAwake() }
        rail.addControl(DesktopPill(this, desktop))
        toolRails += rail
    }

    /** Play-safe keep-awake: FLAG_KEEP_SCREEN_ON only. No WAKE_LOCK. Off when user toggles or app backgrounds. */
    private fun applyKeepAwake() {
        if (keepAwake) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        toolRails.forEach { it.refresh() }
    }

    private fun buildGitOperationsLayout() {
        gitOperationsScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
        }
        gitOperationsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        gitOperationsScrollView.addView(gitOperationsLayout)

        // Action buttons row
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val cloneBtn = outlineBtn(" Clone Repository")
        cloneBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_download, 0, 0, 0)
        cloneBtn.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
        cloneBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        val pushBtn = primaryBtn(" Push")
        pushBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_upload, 0, 0, 0)
        pushBtn.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        pushBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        actionRow.addView(cloneBtn); actionRow.addView(pushBtn)
        gitOperationsLayout.addView(actionRow)

        // GitHub CLI Auth card
        gitOperationsLayout.addView(buildAuthCard())
        gitOperationsLayout.addView(spacer(12))

        // Branches card
        gitOperationsLayout.addView(buildBranchCard())
        gitOperationsLayout.addView(spacer(12))

        // Git input credential card
        gitOperationsLayout.addView(buildGitAuthInputCard())
    }

    private fun buildSettingsHubLayout() {
        settingsHubScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        settingsHubLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        settingsHubScrollView.addView(settingsHubLayout)

        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_tune_thick)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) }
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_settings_hub)
            textSize = 24f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }
        headerTitleRow.addView(headerIcon)
        headerTitleRow.addView(titleTv)
        val subTitleTv = TextView(this).apply {
            text = tr(R.string.main_caps_system_configuration_preferences)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(16) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        headerCol.addView(headerTitleRow)
        headerCol.addView(subTitleTv)
        headerCol.addView(divider)
        settingsHubLayout.addView(headerCol)

        // General: app language (drop-down; applies at once)
        settingsHubLayout.addView(sectionHeader(R.drawable.ic_tune_thick, tr(R.string.language_general), NC.PRIMARY))
        settingsHubLayout.addView(buildLanguageRow())
        settingsHubLayout.addView(spacer(16))

        // NativeCode Pro — status + Upgrade / Restore / Manage sheet
        if (BuildConfig.MONETISATION_ENFORCE) { // fdroid: Pro is always on, no plans / purchase UI
            settingsHubLayout.addView(buildProCard())
            settingsHubLayout.addView(spacer(16))
        }

        // Graphical Desktop card
        settingsHubLayout.addView(buildGuiLaunchCard())
        settingsHubLayout.addView(spacer(16))

        // Components card (Play-delivered / bundled Linux runtime & SDK)
        settingsHubLayout.addView(
            settingsHubNavRow(
                iconRes = R.drawable.ic_download,
                title = tr(R.string.main_components),
                subtitle = tr(R.string.main_linux_runtime_android_sdk_36),
                pageId = ID_COMPONENTS
            ).also { row ->
                // Update dot on the icon + subtitle, painted by refreshComponentUpdates().
                val details = (row as ViewGroup).getChildAt(1) as ViewGroup
                componentsNavSub = details.getChildAt(1) as TextView
                val icon = row.getChildAt(0)
                row.removeViewAt(0)
                row.addView(FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
                    icon.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24))
                    addView(icon)
                    componentsNavDot = View(this@MainActivity).apply {
                        visibility = View.GONE
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(NC.TERTIARY_CON) }
                        layoutParams = FrameLayout.LayoutParams(dp(9), dp(9), Gravity.TOP or Gravity.END)
                        contentDescription = "Update available"
                    }
                    addView(componentsNavDot)
                }, 0)
            }
        )
        settingsHubLayout.addView(spacer(16))

        // Terminal Settings card
        settingsHubLayout.addView(buildTerminalSettingsCard())
        settingsHubLayout.addView(spacer(16))

        // Top bar phone stats: own page (pick + reorder + live preview)
        settingsHubLayout.addView(
            settingsHubNavRow(
                iconRes = R.drawable.ic_tune_thick,
                title = tr(R.string.main_top_bar),
                subtitle = tr(R.string.main_phone_stats_order),
                pageId = ID_TOPBAR
            )
        )
        settingsHubLayout.addView(spacer(16))

        settingsHubLayout.addView(
            settingsHubNavRow(
                iconRes = R.drawable.ic_lucide_archive,
                title = tr(R.string.main_backup),
                subtitle = tr(R.string.main_back_up_and_restore_your),
                pageId = ID_BACKUP
            )
        )
        settingsHubLayout.addView(spacer(16))

        settingsHubLayout.addView(
            settingsHubNavRow(
                iconRes = R.drawable.ic_lucide_server,
                title = "SSH",
                subtitle = tr(if (BuildConfig.MONETISATION_ENFORCE) R.string.main_ssh_server_pro_remote_hosts else R.string.fd_ssh_subtitle),
                pageId = ID_SSH
            )
        )
        settingsHubLayout.addView(spacer(16))

        settingsHubLayout.addView(
            settingsHubNavRow(
                iconRes = R.drawable.ic_lucide_chart,
                title = tr(R.string.main_ai_usage),
                subtitle = tr(R.string.main_tokens_and_cost_per_ai),
                pageId = ID_USAGE
            )
        )
        settingsHubLayout.addView(spacer(16))

        // GitHub account
        settingsHubLayout.addView(buildSettingsGithubCard())
        settingsHubLayout.addView(spacer(16))

        // AI CLI browser / device-code login hub
        settingsHubLayout.addView(buildCliAuthSectionButton())
        settingsHubLayout.addView(spacer(16))

        // AI Safety & Report (C5 / B16) + C10 deletion pointers
        settingsHubLayout.addView(buildAiSafetySectionButton())
        settingsHubLayout.addView(spacer(16))

        // Linux Storage Settings — size-only (app storage rootfs)
        settingsHubLayout.addView(buildProotSettingsSectionButton())
        settingsHubLayout.addView(spacer(16))

        // Software Manager — apt inventory by category
        settingsHubLayout.addView(buildSoftwareManagerSectionButton())
        settingsHubLayout.addView(spacer(16))

        // X11 display settings (termux-x11 LoriePreferences)
        settingsHubLayout.addView(buildX11SettingsSectionButton())
        settingsHubLayout.addView(spacer(16))

        // Onboarding Setup
        settingsHubLayout.addView(buildOnboardingSectionButton())
        settingsHubLayout.addView(spacer(16))

        // Hardware Diagnostics & VPU Bridges
        settingsHubLayout.addView(buildHardwareSectionButton())
        settingsHubLayout.addView(spacer(16))

        // Agent skills — markdown prompts for CLI tools
        settingsHubLayout.addView(buildSkillsSectionButton())
        settingsHubLayout.addView(spacer(16))

        // Privacy Policy — opens public GitHub markdown
        settingsHubLayout.addView(buildPrivacyPolicyCard())
        settingsHubLayout.addView(spacer(16))
        // Opt-in crash reports / usage statistics (telemetry/ConsentSheet)
        if (BuildConfig.TELEMETRY_AVAILABLE) {
            settingsHubLayout.addView(secondaryButton(tr(R.string.main_crash_reports_usage_stats)) { ConsentSheet.show(this) })
            settingsHubLayout.addView(spacer(20))
        }

        // App version code + commit from gradle BuildConfig
        settingsHubLayout.addView(buildSettingsVersionFooter())
    }

    private fun buildLanguageRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cyberBrutalistBg(
            fillColor = NC.SURFACE_LOW, strokeColor = NC.OUTLINE_VAR, shadowColor = NC.SHADOW_DARK,
            offsetDp = 6, cornerRadiusDp = 0
        )
        setPadding(dp(16), dp(16), dp(16), dp(16))
        isClickable = true
        contentDescription = tr(R.string.language_title)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_globe)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) }
        })
        addView(TextView(this@MainActivity).apply {
            text = tr(R.string.language_title)
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        addView(TextView(this@MainActivity).apply {
            text = LanguagePicker.currentName(this@MainActivity)
            textSize = 13f
            setTextColor(NC.ON_SURF_VAR)
            maxLines = 1
        })
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lucide_chevron_down)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) }
        })
        setOnClickListener { LanguagePicker.showMenu(this@MainActivity, this) }
    }

    private var sdkStatusTv: TextView? = null
    private var sdkActionBtn: TextView? = null

    private fun refreshAndroidSdkPackCard() {
        val applied = AndroidDevPackManager.isApplied(this)
        val archive = AndroidDevPackManager.resolveArchive(this)
        sdkStatusTv?.text = when {
            applied -> tr(R.string.main_ready_platform_36_arm64_aapt2)
            archive != null -> "PACK READY · apply to Alpaquita"
            else -> tr(if (BuildConfig.FDROID) R.string.fd_sdk_pack_optional else R.string.main_optional_play_asset_pack_76)
        }
        sdkActionBtn?.text = when {
            applied -> tr(R.string.main_reapply_sdk)
            archive != null -> "APPLY SDK"
            else -> tr(R.string.main_download_sdk)
        }
        sdkActionBtn?.isEnabled = true
        sdkActionBtn?.alpha = 1f
    }

    private fun buildAndroidSdkPackCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(TextView(this).apply {
            text = tr(R.string.main_android_sdk_36)
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        val applied = AndroidDevPackManager.isApplied(this)
        val archive = AndroidDevPackManager.resolveArchive(this)
        val status = TextView(this).apply {
            text = when {
                applied -> tr(R.string.main_ready_platform_36_arm64_aapt2)
                archive != null -> "PACK READY · apply to Alpaquita"
                else -> tr(if (BuildConfig.FDROID) R.string.fd_sdk_pack_optional else R.string.main_optional_play_asset_pack_76)
            }
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(10))
        }
        sdkStatusTv = status
        card.addView(status)

        val action = primaryButton(
            when {
                applied -> tr(R.string.main_reapply_sdk)
                archive != null -> "APPLY SDK"
                else -> tr(R.string.main_download_sdk)
            }
        ) {}
        sdkActionBtn = action
        action.setOnClickListener {
            action.isEnabled = false
            action.alpha = 0.5f

            fun applyPack(force: Boolean) {
                status.text = if (force) tr(R.string.main_re_verifying_and_applying_sdk) else tr(R.string.main_verifying_and_applying_sdk_overlay)
                executor.execute {
                    val result = AndroidDevPackManager.applyAvailablePack(this@MainActivity, force = force)
                    runOnUiThread {
                        status.text = result.message
                        action.isEnabled = true
                        action.alpha = 1f
                        if (result.success) {
                            action.text = tr(R.string.main_reapply_sdk)
                        }
                    }
                }
            }

            if (AndroidDevPackManager.resolveArchive(this) != null) {
                applyPack(force = AndroidDevPackManager.isApplied(this))
            } else {
                status.text = tr(if (BuildConfig.FDROID) R.string.fd_requesting_github else R.string.onb_requesting_nativecode_android_dev_fr)
                var listener: Any? = null
                listener = PlayAssetDeliveryManager.requestDownload(
                    this,
                    PlayAssetDeliveryManager.PACK_ANDROID_DEV
                ) { state ->
                    when (state) {
                        is PlayAssetDeliveryManager.DownloadState.Pending ->
                            status.text = tr(R.string.main_sdk_download_pending)
                        is PlayAssetDeliveryManager.DownloadState.Downloading ->
                            status.text = tr(R.string.main_sdk_downloading, state.percentage)
                        is PlayAssetDeliveryManager.DownloadState.Transferring ->
                            status.text = tr(R.string.main_sdk_transferring, state.percentage)
                        is PlayAssetDeliveryManager.DownloadState.Completed -> {
                            listener?.let { PlayAssetDeliveryManager.unregisterListener(this, it) }
                            applyPack(force = true)
                        }
                        is PlayAssetDeliveryManager.DownloadState.WaitingForWifi ->
                            status.text = tr(R.string.main_waiting_for_wi_fi_approval)
                        is PlayAssetDeliveryManager.DownloadState.Failed -> {
                            listener?.let { PlayAssetDeliveryManager.unregisterListener(this, it) }
                            status.text = tr(R.string.main_sdk_download_failed, state.errorCode)
                            action.isEnabled = true
                            action.alpha = 1f
                        }
                        is PlayAssetDeliveryManager.DownloadState.Canceled -> {
                            listener?.let { PlayAssetDeliveryManager.unregisterListener(this, it) }
                            status.text = tr(R.string.main_sdk_download_canceled)
                            action.isEnabled = true
                            action.alpha = 1f
                        }
                        else -> Unit
                    }
                }
            }
        }
        card.addView(action)
        return card
    }

    private fun buildComponentsPage() {
        componentsScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        componentsScrollView.addView(page)
        page.addView(
            buildSubPageHeader(
                title = tr(R.string.main_components),
                subtitle = tr(if (BuildConfig.FDROID) R.string.fd_components_subtitle else R.string.main_caps_play_delivered_development_components),
                iconRes = R.drawable.ic_download,
                pageId = ID_COMPONENTS
            ) { badge -> badge.text = tr(if (BuildConfig.FDROID) R.string.fd_components_badge else R.string.main_play) }
        )

        val rootfs = ProjectPathResolver.prootRootfsDir(this)
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_alpaquita_runtime)
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                val phosh = File(rootfs, "usr/local/bin/phoc").exists() || File(rootfs, "usr/local/bin/nativecode-desktop").exists() || File(rootfs, "usr/bin/startxfce4").exists()
                text = if (phosh) tr(R.string.main_ready_proot_phosh_desktop) else tr(R.string.main_not_installed_complete_onboarding)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, 0)
            })
        })
        page.addView(spacer(16))
        page.addView(buildAndroidSdkPackCard())
        (componentUpdatesHolder.parent as? ViewGroup)?.removeView(componentUpdatesHolder)
        page.addView(componentUpdatesHolder)
    }

    // ── Component updates (existing users): cards on Components + a non-blocking home banner ──────────
    // Testing only: shows both update cards on an up-to-date install. Never commit as true.
    private val FORCE_COMPONENT_UPDATE_UI = false

    private val componentUpdatesHolder by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private val homeUpdateBannerHolder by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private var componentsNavSub: TextView? = null
    private var componentsNavDot: View? = null
    private var componentUpdatesChecked = false
    private val busyUpdates = mutableSetOf<String>()

    private class ComponentUpdate(val id: String, val iconRes: Int, val title: String, val fromTo: String,
                                  val size: String, val changes: String)

    /** Which components have an update (background thread: PanVK hashes guest files). */
    private fun pendingComponentUpdates(): List<ComponentUpdate> = buildList {
        if (FORCE_COMPONENT_UPDATE_UI || RuntimeUpdatePack.isNeeded(this@MainActivity)) add(ComponentUpdate(
            "runtime_pack", R.drawable.ic_terminal, RuntimeUpdatePack.TITLE, RuntimeUpdatePack.FROM_TO,
            RuntimeUpdatePack.SIZE, RuntimeUpdatePack.CHANGES))
        val panvk = FORCE_COMPONENT_UPDATE_UI || try { PanvkDriverUpdate.needsUpdate(this@MainActivity) } catch (_: Exception) { false }
        if (panvk) add(ComponentUpdate(
            "panvk", R.drawable.ic_display, tr(R.string.main_panvk_gpu_driver),
            "installed ${(PanvkDriverUpdate.installedSha(this@MainActivity) ?: "unknown").take(8)} to ${PanvkDriverUpdate.VERSION_LABEL} ${PanvkDriverUpdate.bundledSha(this@MainActivity).take(8)}",
            tr(if (BuildConfig.FDROID) R.string.fd_panvk_download else R.string.main_bundled_in_the_app_no),
            "${PanvkDriverUpdate.VERSION_LABEL}: new PanVK Vulkan driver for Mali GPUs and the matching GPU env scripts. Stop the desktop first; the old driver is kept as .so.bak."))
        val turnip = FORCE_COMPONENT_UPDATE_UI || try { TurnipDriverUpdate.needsUpdate(this@MainActivity) } catch (_: Exception) { false }
        if (turnip) add(ComponentUpdate(
            "turnip", R.drawable.ic_display, tr(R.string.main_turnip_gpu_driver),
            "installed ${(TurnipDriverUpdate.installedSha(this@MainActivity) ?: "unknown").take(8)} to ${TurnipDriverUpdate.VERSION_LABEL} ${TurnipDriverUpdate.bundledSha(this@MainActivity).take(8)}",
            tr(if (BuildConfig.FDROID) R.string.fd_turnip_download else R.string.main_bundled_in_the_app_no),
            "${TurnipDriverUpdate.VERSION_LABEL}: new Turnip Vulkan driver for Adreno GPUs. Stop the desktop first; the old driver is kept as .so.bak."))
    }

    /** Re-check and repaint the Components cards, the Settings row dot and the home banner. */
    private fun refreshComponentUpdates(cards: Boolean = true) {
        executor.execute {
            val list = pendingComponentUpdates()
            runOnUiThread {
                componentsNavDot?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                componentsNavSub?.text = if (list.isEmpty()) tr(R.string.main_linux_runtime_android_sdk_36) else "${list.size} UPDATE${if (list.size > 1) "S" else ""} AVAILABLE"
                componentsNavSub?.setTextColor(if (list.isEmpty()) NC.ON_SURF_VAR else NC.TERTIARY_CON)
                homeUpdateBannerHolder.removeAllViews()
                if (list.isNotEmpty()) homeUpdateBannerHolder.addView(buildHomeUpdateBanner(list.size))
                if (!cards || busyUpdates.isNotEmpty()) return@runOnUiThread // keep live progress on screen
                componentUpdatesHolder.removeAllViews()
                list.forEach { u ->
                    componentUpdatesHolder.addView(spacer(16))
                    componentUpdatesHolder.addView(buildComponentUpdateCard(u))
                }
            }
        }
    }

    private fun buildHomeUpdateBanner(count: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cyberBrutalistBg(NC.SURFACE_LOW, NC.TERTIARY_CON, NC.SHADOW_DARK, 4, 0)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        contentDescription = "Component updates available. Open Components."
        setOnClickListener { pageStack.push(ID_COMPONENTS); navigateToPage(ID_COMPONENTS) }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_download)
            setColorFilter(NC.TERTIARY_CON)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) }
        })
        addView(TextView(this@MainActivity).apply {
            text = if (count == 1) "1 component update available" else "$count component updates available"
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        addView(TextView(this@MainActivity).apply {
            text = tr(R.string.skills_view)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(NC.TERTIARY_CON)
        })
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(NC.TERTIARY_CON)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
    }

    private fun buildComponentUpdateCard(u: ComponentUpdate): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(NC.SURFACE_LOW, NC.OUTLINE_VAR, NC.SHADOW_DARK, 6, 0)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val chip = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        fun chipState(label: String, color: Int) {
            chip.text = label
            chip.setTextColor(color)
            chip.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), color) }
        }
        chipState(tr(R.string.main_update_available), NC.TERTIARY_CON)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(u.iconRes)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
            })
            addView(TextView(this@MainActivity).apply {
                text = u.title.uppercase()
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(chip)
        })
        fun mono(t: String, color: Int = NC.ON_SURF_VAR, top: Int = 6) = TextView(this).apply {
            text = t
            textSize = 11f
            setTextColor(color)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(top), 0, 0)
        }
        val fromToTv = mono(u.fromTo.uppercase(), NC.PRIMARY, 10)
        card.addView(fromToTv)
        card.addView(mono(u.size))
        card.addView(TextView(this).apply {
            text = u.changes
            textSize = 13f
            setTextColor(Color.parseColor("#D0D0D0"))
            setPadding(0, dp(10), 0, dp(10))
        })
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(6)).apply { bottomMargin = dp(6) }
        }
        card.addView(bar)
        val status = mono("", top = 0).apply { visibility = View.GONE; setPadding(0, 0, 0, dp(10)) }
        card.addView(status)
        val btn = primaryButton(tr(R.string.main_update)) {}
        card.addView(btn)

        fun running(text: String, pct: Int?) {
            chipState(tr(R.string.main_updating), NC.PRIMARY)
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = pct == null
            if (pct != null) bar.progress = pct
            status.visibility = View.VISIBLE
            status.setTextColor(NC.ON_SURF_VAR)
            status.text = text
            btn.visibility = View.GONE
        }
        fun finished(ok: Boolean, msg: String) {
            busyUpdates.remove(u.id)
            Telemetry.componentUpdate(this, u.id, ok)
            bar.visibility = View.GONE
            status.visibility = View.VISIBLE
            status.text = msg
            status.setTextColor(if (ok) NC.PRIMARY else NC.ERROR)
            if (ok) {
                chipState(tr(R.string.main_up_to_date), NC.PRIMARY)
                btn.visibility = View.GONE
            } else {
                chipState(tr(R.string.ssh_failed), NC.ERROR)
                btn.text = tr(R.string.preview_retry)
                btn.visibility = View.VISIBLE
            }
            refreshComponentUpdates(cards = false) // banner + dot only: this card keeps its done/failed state
            if (ok && (u.id == "panvk" || u.id == "turnip")) executor.execute {
                // The "installed <old sha> to ..." line is stale now: show what is installed.
                val turnip = u.id == "turnip"
                val sha = HardwareStatus.shortSha(if (turnip) TurnipDriverUpdate.installedSha(this) else PanvkDriverUpdate.installedSha(this))
                val label = if (turnip) TurnipDriverUpdate.VERSION_LABEL else PanvkDriverUpdate.VERSION_LABEL
                runOnUiThread {
                    fromToTv.text = tr(R.string.main_component_installed, label, sha).uppercase()
                    refreshHardwareProbes() // Hardware page driver badge/sha
                }
            }
        }
        btn.setOnClickListener {
            if (!busyUpdates.add(u.id)) return@setOnClickListener
            when (u.id) {
                "runtime_pack" -> RuntimeUpdatePack.update(this) { p ->
                    when (p) {
                        is RuntimeUpdatePack.Progress.Running -> running(p.text, p.percent)
                        is RuntimeUpdatePack.Progress.Finished -> finished(p.ok, p.message)
                    }
                }
                "panvk" -> {
                    running(tr(R.string.main_updating_the_panvk_driver), null)
                    executor.execute {
                        val (ok, msg) = PanvkDriverUpdate.update(this)
                        runOnUiThread { finished(ok, msg) }
                    }
                }
                "turnip" -> {
                    running(tr(R.string.main_updating_the_turnip_driver), null)
                    executor.execute {
                        val (ok, msg) = TurnipDriverUpdate.update(this)
                        runOnUiThread { finished(ok, msg) }
                    }
                }
            }
        }
        return card
    }

    private fun buildSkillsSectionButton(): View {
        return settingsHubNavRow(
            iconRes = R.drawable.ic_smart_toy,
            title = tr(R.string.skills_skills),
            subtitle = tr(R.string.main_agent_prompts_hardware_project_insta),
            pageId = ID_SKILLS
        )
    }

    private fun buildSkillsPage() {
        skillsPage = com.zenithblue.nativecode.skills.SkillsPage(this) {
            navigateBackFromSubpage(ID_SKILLS, fallback = ID_SETTINGS)
        }
        skillsScrollView = skillsPage!!.view
    }

    private fun buildX11SettingsSectionButton(): View {
        return settingsHubNavRow(
            iconRes = R.drawable.ic_display,
            title = tr(R.string.x11_x11_settings),
            subtitle = tr(R.string.main_display_pointer_keyboard_gestures),
            pageId = ID_X11_SETTINGS
        )
    }

    private fun buildX11SettingsPage() {
        x11SettingsPage = com.zenithblue.nativecode.x11.X11SettingsPage(this) {
            navigateBackFromSubpage(ID_X11_SETTINGS)
        }
        x11SettingsScrollView = x11SettingsPage!!.view
    }

    private fun refreshX11SettingsPage() {
        x11SettingsPage?.refresh()
    }

    private fun buildProotFilesPage() {
        prootFilesPage = com.zenithblue.nativecode.proot.ProotFilesPage(
            ctx = this,
            onBack = { navigateBackFromSubpage(ID_PROOT_FILES, fallback = ID_PROOT_SETTINGS) },
            onOpenFile = { file -> showFileViewer(file.absolutePath, ID_PROOT_FILES) }
        )
        prootFilesScrollView = prootFilesPage!!.view
    }

    private fun buildSoftwareManagerSectionButton(): View {
        return settingsHubNavRow(
            iconRes = R.drawable.ic_extension,
            title = tr(R.string.main_software_manager),
            subtitle = tr(R.string.main_installed_packages_by_category),
            pageId = ID_SOFTWARE_MANAGER
        )
    }

    private fun buildMarketplaceSectionButton(): View {
        return settingsHubNavRow(
            iconRes = R.drawable.ic_download,
            title = tr(R.string.main_marketplace),
            subtitle = tr(R.string.main_curated_components_x11_apps),
            pageId = ID_MARKETPLACE
        )
    }

    private fun settingsHubNavRow(iconRes: Int, title: String, subtitle: String, pageId: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                if (pageId == ID_MARKETPLACE) {
                    openMarketplace()
                    return@setOnClickListener
                }
                if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                    pageStack.push(pageId)
                }
                navigateToPage(pageId)
            }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
            }
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val name = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
            }
            details.addView(name)
            details.addView(sub)
            val arrow = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            addView(icon)
            addView(details)
            addView(arrow)
        }
    }

    private fun buildCliAuthSectionButton(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                if (pageStack.isEmpty() || pageStack.peek() != ID_CLI_AUTH) {
                    pageStack.push(ID_CLI_AUTH)
                }
                navigateToPage(ID_CLI_AUTH)
            }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_build)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
            }
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            details.addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_caps_ai_cli_tools)
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            details.addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_install_suite_browser_device_code)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
            })
            val arrow = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            addView(icon)
            addView(details)
            addView(arrow)
        }
    }

    private fun buildAiSafetySectionButton(): View {
        return settingsHubNavRow(
            iconRes = R.drawable.ic_shield_thick,
            title = tr(R.string.main_ai_safety_report),
            subtitle = tr(R.string.main_flag_output_vendor_tos_contact),
            pageId = ID_AI_SAFETY
        )
    }

    private fun buildScriptsSectionButton(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                if (pageStack.isEmpty() || pageStack.peek() != ID_SCRIPTS) {
                    pageStack.push(ID_SCRIPTS)
                }
                navigateToPage(ID_SCRIPTS)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }
            
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_build)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
            }
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val name = TextView(this@MainActivity).apply {
                text = tr(R.string.main_repairs)
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextView(this@MainActivity).apply {
                text = tr(R.string.main_re_run_host_and_guest)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
            }
            details.addView(name)
            details.addView(sub)
            val arrow = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            addView(icon)
            addView(details)
            addView(arrow)
        }
    }

    private fun buildProotSettingsSectionButton(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                if (pageStack.isEmpty() || pageStack.peek() != ID_PROOT_SETTINGS) {
                    pageStack.push(ID_PROOT_SETTINGS)
                }
                navigateToPage(ID_PROOT_SETTINGS)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }

            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_proot_debian)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_LOWEST)
                    setStroke(dp(1), Color.parseColor("#3360F99E"))
                }
            }
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val name = TextView(this@MainActivity).apply {
                text = tr(R.string.main_storage_settings)
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextView(this@MainActivity).apply {
                text = tr(R.string.main_alpaquita_rootfs_size_app_storage)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
            }
            details.addView(name)
            details.addView(sub)
            val arrow = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            addView(icon)
            addView(details)
            addView(arrow)
        }
    }

    private fun buildChrootSettingsSectionButton(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_chroot_debian)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), Color.parseColor("#33FFB175"))
            }
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = tr(R.string.main_chroot_settings)
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val badge = textBadge("…", NC.SURFACE_HIGHEST, NC.ON_SURF_VAR).apply {
            visibility = View.GONE
        }
        titleRow.addView(name)
        titleRow.addView(badge)
        val sub = TextView(this).apply {
            text = tr(R.string.main_rootfs_size_path_and_uninstall)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        }
        details.addView(titleRow)
        details.addView(sub)
        val arrow = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        card.addView(icon)
        card.addView(details)
        card.addView(arrow)

        chrootSettingsHubCard = card
        chrootSettingsHubSub = sub
        chrootSettingsHubBadge = badge
        chrootSettingsHubArrow = arrow

        // Cached prefs: instant paint, then live probe (same SSOT as chroot page)
        val cachedRoot = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
            .getBoolean(PREF_CHROOT_ROOT_OK, false)
        applyChrootSettingsHubRootGate(enabled = cachedRoot, probing = true)
        refreshChrootSettingsHubRootGate(force = false)

        return card
    }

    /**
     * Probe root via [RootShell.probeRootAvailable] and gate hub CHROOT SETTINGS card.
     * Same checker as chroot settings page ([runChrootSizeProbeOnBg]).
     */
    private fun refreshChrootSettingsHubRootGate(force: Boolean = false) {
        if (chrootSettingsHubCard == null) return
        // Instant paint from last known; re-probe so grant-after-launch unlocks the card
        applyChrootSettingsHubRootGate(
            enabled = settingsHubRootOk == true,
            probing = settingsHubRootOk == null
        )
        RootShell.probeRootAvailable(forceClearCache = force) { ok ->
            settingsHubRootOk = ok
            getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                .edit().putBoolean(PREF_CHROOT_ROOT_OK, ok).apply()
            applyChrootSettingsHubRootGate(enabled = ok, probing = false)
        }
    }

    /** Enable/disable settings hub CHROOT SETTINGS row when root missing. */
    private fun applyChrootSettingsHubRootGate(enabled: Boolean, probing: Boolean) {
        val card = chrootSettingsHubCard ?: return
        val sub = chrootSettingsHubSub
        val badge = chrootSettingsHubBadge
        val arrow = chrootSettingsHubArrow

        card.setOnTouchListener(null)
        card.setOnClickListener(null)

        when {
            probing -> {
                card.alpha = 0.75f
                card.isEnabled = false
                card.isClickable = false
                sub?.text = tr(R.string.onb_checking_root_access)
                sub?.setTextColor(NC.ON_SURF_VAR)
                badge?.visibility = View.VISIBLE
                badge?.text = "…"
                badge?.setTextColor(NC.ON_SURF_VAR)
                badge?.background = roundedBg(NC.SURFACE_HIGHEST, NC.OUTLINE_VAR, dp(4))
                arrow?.alpha = 0.4f
            }
            enabled -> {
                card.alpha = 1f
                card.isEnabled = true
                card.isClickable = true
                sub?.text = tr(R.string.main_rootfs_size_path_and_uninstall)
                sub?.setTextColor(NC.ON_SURF_VAR)
                badge?.visibility = View.GONE
                arrow?.alpha = 1f
                arrow?.setColorFilter(NC.PRIMARY)
                card.setOnClickListener {
                    if (pageStack.isEmpty() || pageStack.peek() != ID_CHROOT_SETTINGS) {
                        pageStack.push(ID_CHROOT_SETTINGS)
                    }
                    navigateToPage(ID_CHROOT_SETTINGS)
                }
                card.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.translationX = dp(4).toFloat()
                            v.translationY = dp(4).toFloat()
                            v.background = cyberBrutalistBg(
                                fillColor = NC.SURFACE_LOW,
                                strokeColor = NC.OUTLINE_VAR,
                                shadowColor = NC.SHADOW_DARK,
                                offsetDp = 2,
                                cornerRadiusDp = 0
                            )
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.translationX = 0f
                            v.translationY = 0f
                            v.background = cyberBrutalistBg(
                                fillColor = NC.SURFACE_LOW,
                                strokeColor = NC.OUTLINE_VAR,
                                shadowColor = NC.SHADOW_DARK,
                                offsetDp = 6,
                                cornerRadiusDp = 0
                            )
                        }
                    }
                    false
                }
            }
            else -> {
                card.alpha = 0.45f
                card.isEnabled = false
                card.isClickable = false
                sub?.text = tr(R.string.main_no_root_access)
                sub?.setTextColor(NC.ERROR)
                badge?.visibility = View.VISIBLE
                badge?.text = tr(R.string.main_no_root)
                badge?.setTextColor(Color.parseColor("#0A0A0A"))
                badge?.background = roundedBg(NC.ERROR, NC.ON_ERROR, dp(4))
                arrow?.alpha = 0.25f
                arrow?.setColorFilter(NC.ON_SURF_VAR)
                card.setOnClickListener {
                    Toast.makeText(
                        this,
                        tr(R.string.main_chroot_needs_root_access_grant),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                // Keep clickable only for toast explain
                card.isClickable = true
                card.isEnabled = true
            }
        }
    }

    private fun buildOnboardingSectionButton(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                val intent = Intent(this@MainActivity, OnboardingActivity::class.java).apply {
                    putExtra("force_onboarding", true)
                }
                startActivity(intent)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }
            
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_reset_thick)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
            }
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val name = TextView(this@MainActivity).apply {
                text = tr(R.string.main_re_run_onboarding)
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextView(this@MainActivity).apply {
                text = tr(R.string.main_relaunch_initial_setup_and_onboardin)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
            }
            details.addView(name)
            details.addView(sub)
            val arrow = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            addView(icon)
            addView(details)
            addView(arrow)
        }
    }

    /** Settings Hub → Privacy Policy (public GitHub docs markdown). */
    // ---- NativeCode Pro (docs/monetisation/plan.md) ----

    private fun isPro() = EntitlementStore.isPro(this)

    /** Free user under enforcement → Pro entry points show a lock. */
    private fun proLocked() = BuildConfig.MONETISATION_ENFORCE && !isPro()
    /** AI CLI tab (non-free) or interactive shell tab (user/root) locked for this user. */
    private fun aiToolLocked(type: String) =
        (if (ToolLauncherCatalog.isAiToolType(type)) FeatureGate.canLaunchAiTool(isPro(), type)
        else FeatureGate.canOpenShell(isPro())) is GateResult.Deny

    /** Built-once views (home banner/FAB, create page) re-marked when Pro flips. */
    private val proLockTexts = mutableListOf<TextView>()

    /** Trailing lock icon (tinted to the text, other slots kept) + "Pro required" for TalkBack. */
    private fun TextView.markProLocked(locked: Boolean = true) {
        val (start, top, _, bottom) = compoundDrawablesRelative
        if (!locked) {
            setCompoundDrawablesRelative(start, top, null, bottom)
            contentDescription = null
            return
        }
        val d = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_lock)?.mutate() ?: return
        d.setBounds(0, 0, dp(14), dp(14))
        d.setTint(currentTextColor)
        setCompoundDrawablesRelative(start, top, d, bottom)
        compoundDrawablePadding = dp(6)
        contentDescription = "${text.trim()}, Pro required"
    }

    /** Mark now and re-mark on entitlement change. */
    private fun TextView.trackProLock() {
        proLockTexts += this
        markProLocked(proLocked())
    }

    private var lastProState: Boolean? = null

    /** Billing hook: Pro card text always; rebuild the visible page only when Pro actually flips. */
    private fun onEntitlementChanged() {
        GuestGate.syncAsync(this) // guest shims read this state; keep it current before any CLI runs
        refreshProCard()
        paintGuiBudget() // also re-reads minutes on every resume
        val pro = isPro()
        if (!pro && BuildConfig.MONETISATION_ENFORCE &&
            com.zenithblue.nativecode.ssh.SshServerController.status.state != com.zenithblue.nativecode.ssh.SshState.STOPPED
        ) com.zenithblue.nativecode.ssh.SshServerController.stop(this) // Pro ended: the server goes with it
        if (lastProState != null && lastProState != pro) {
            proLockTexts.forEach { it.markProLocked(proLocked()) }
            navigateToPage(currentPageId, false)
        }
        lastProState = pro
    }

    /** Deny → Upgrade sheet. Returns true when the action may proceed. */
    private fun gateAllows(result: GateResult): Boolean {
        if (result is GateResult.Deny) {
            Log.i("NCBilling", "gate deny ${result.upsell}: ${result.reason}")
            Telemetry.log(this, Telemetry.Event.FREE_LIMIT_HIT, Telemetry.Param.LIMIT to result.upsell.name)
            showUpgradeSheet(result.reason)
        }
        return result is GateResult.Allow
    }

    /** AI tool + concurrent AI session gates (app tabs + all project workspaces). */
    private fun aiLaunchAllowed(type: String, surface: String = "terminal"): Boolean {
        GuestGate.syncAsync(this)
        if (!ToolLauncherCatalog.isAiToolType(type)) return gateAllows(FeatureGate.canOpenShell(isPro()))
        if (!gateAllows(FeatureGate.canLaunchAiTool(isPro(), type))) return false
        // Launches still starting count too, or fast taps (or a chat handshake) pass the cap together.
        val ok = gateAllows(FeatureGate.canOpenAiSession(isPro(), aiSessionCount()))
        if (ok) {
            QuickActions.rememberTool(this, type) // widget "Agent" relaunches the last-used tool (Pro)
            Telemetry.logOnce(this, Telemetry.Event.FIRST_AI_SESSION, Telemetry.Param.TOOL to type)
            Telemetry.log(this, Telemetry.Event.AI_SESSION_START, Telemetry.Param.TOOL to type, Telemetry.Param.SURFACE to surface)
        }
        return ok
    }

    private var proCardStatusTv: TextView? = null

    private fun refreshProCard() {
        proCardStatusTv?.text = if (isPro()) tr(R.string.main_pro_active) else
            "Free plan · Upgrade · from ${BillingRepository.price(BillingRepository.SKU_MONTHLY) ?: "$3.99"}/mo"
    }

    private fun buildProCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cyberBrutalistBg(
            fillColor = NC.SURFACE_LOW,
            strokeColor = NC.PRIMARY,
            shadowColor = NC.SHADOW_DARK,
            offsetDp = 6,
            cornerRadiusDp = 0
        )
        setPadding(dp(16), dp(16), dp(16), dp(16))
        contentDescription = "NativeCode Pro"
        setOnClickListener { showUpgradeSheet(null) }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_lock_open)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_nativecode_pro)
                textSize = 15f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
            })
            proCardStatusTv = TextView(this@MainActivity).apply {
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
            }
            addView(proCardStatusTv)
        })
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        refreshProCard()
    }

    /** One shared Upgrade sheet: 3 plans (Play-localized prices), restore, manage. */
    private fun showUpgradeSheet(reason: String?) {
        if (!BuildConfig.MONETISATION_ENFORCE) return // fdroid: no paywall, nothing to upgrade
        Telemetry.log(this, Telemetry.Event.PAYWALL_VIEW)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        fun line(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NC.BG)
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        col.addView(line(tr(R.string.main_nativecode_pro), 20f, NC.PRIMARY, bold = true))
        reason?.let { col.addView(line("// $it", 12f, NC.ERROR)) }
        if (isPro()) col.addView(line(tr(R.string.main_pro_is_active_on_this), 13f, NC.ON_SURFACE))
        col.addView(
            line(tr(R.string.main_pro_benefits), 12f, NC.ON_SURFACE)
        )
        fun buy(label: String, sku: String, unit: String, fallback: String) = primaryButton(
            BillingRepository.label(label, sku, unit, fallback)
        ) {
            BillingRepository.launch(this, sku)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            sheet.dismiss()
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) } }
        if (!isPro()) {
            col.addView(buy(tr(R.string.main_monthly), BillingRepository.SKU_MONTHLY, "/mo", "$3.99/mo"))
            col.addView(buy(tr(R.string.main_yearly), BillingRepository.SKU_YEARLY, "/yr", "$29.99/yr"))
            col.addView(buy(tr(R.string.main_lifetime), BillingRepository.SKU_LIFETIME, "", "$19.99"))
            // Play subscription policy: trial terms visible before purchase.
            if (BillingRepository.hasTrial()) col.addView(
                line(
                    "Free trial for new subscribers. Renews at the price shown unless cancelled " +
                        "in Google Play before the trial ends.",
                    11f, NC.ON_SURF_VAR
                ).apply { setPadding(0, dp(10), 0, 0) }
            )
        }
        col.addView(secondaryButton(tr(R.string.main_restore_purchases)) {
            BillingRepository.refresh { ok ->
                refreshProCard()
                Toast.makeText(
                    this,
                    if (!ok) tr(R.string.main_google_play_unavailable) else if (isPro()) tr(R.string.main_pro_restored) else tr(R.string.main_no_purchases_found),
                    Toast.LENGTH_SHORT
                ).show()
            }
            sheet.dismiss()
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) } })
        col.addView(secondaryButton(tr(R.string.main_manage_subscription)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BillingRepository.manageUrl(this))))
            } catch (_: Exception) {
                Toast.makeText(this, tr(R.string.main_google_play_not_available), Toast.LENGTH_SHORT).show()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) } })
        sheet.setContentView(ScrollView(this).apply { addView(col) })
        sheet.show()
    }

    private fun buildPrivacyPolicyCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                if (pageStack.isEmpty() || pageStack.peek() != ID_PRIVACY_POLICY) {
                    pageStack.push(ID_PRIVACY_POLICY)
                }
                navigateToPage(ID_PRIVACY_POLICY)
            }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }

            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_shield_thick)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
            }
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val name = TextView(this@MainActivity).apply {
                text = tr(R.string.main_privacy_licenses)
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = TextView(this@MainActivity).apply {
                text = tr(R.string.main_data_policy_open_source_licenses)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
            }
            details.addView(name)
            details.addView(sub)
            val arrow = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            }
            addView(icon)
            addView(details)
            addView(arrow)
        }
    }

    private fun buildSettingsVersionFooter(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)

            val versionTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_version_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                textSize = 12f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val commitTv = TextView(this@MainActivity).apply {
                text = "COMMIT: ${BuildConfig.GIT_COMMIT}"
                textSize = 11f
                setTextColor(NC.OUTLINE)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            addView(versionTv)
            addView(commitTv)
        }
    }

    private fun buildScriptsLayout() {
        scriptsScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        scriptsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scriptsScrollView.addView(scriptsLayout)

        // Header: back + icon + title (match chroot settings)
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            setOnClickListener {
                navigateBackFromSubpage(ID_SCRIPTS)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val headerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_build)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) }
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_repairs)
            textSize = 22f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(headerIcon)
        headerTitleRow.addView(titleTv)
        val subTitleTv = TextView(this).apply {
            text = tr(R.string.main_caps_re_run_setup_fix_scripts)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(8))
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(12) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        headerCol.addView(headerTitleRow)
        headerCol.addView(subTitleTv)
        headerCol.addView(divider)
        scriptsLayout.addView(headerCol)

        // Root probe badge
        repairsRootBadge = TextView(this).apply {
            text = tr(R.string.main_root)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(NC.ON_SURF_VAR)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(12) }
        }
        scriptsLayout.addView(repairsRootBadge)

        // Segment tabs: HOST | GUEST
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }
        repairsTabHostBtn = makeRepairsTabBtn("HOST") {
            repairsSelectedTab = "host"
            applyRepairsTabStyles()
            populateRepairsList()
        }
        repairsTabGuestBtn = makeRepairsTabBtn(tr(R.string.main_guest)) {
            repairsSelectedTab = "guest"
            applyRepairsTabStyles()
            populateRepairsList()
        }
        repairsTabChrootBtn = makeRepairsTabBtn("CHROOT") {
            repairsSelectedTab = "chroot"
            applyRepairsTabStyles()
            populateRepairsList()
        }
        tabsRow.addView(repairsTabHostBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        tabsRow.addView(repairsTabGuestBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        scriptsLayout.addView(tabsRow)

        repairsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        scriptsLayout.addView(repairsListContainer)

        applyRepairsTabStyles()
        populateRepairsList()
    }

    private fun makeRepairsTabBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { onClick() }
        }
    }

    private fun applyRepairsTabStyles() {
        fun style(btn: TextView?, selected: Boolean) {
            btn ?: return
            btn.setTextColor(if (selected) NC.ON_PRIMARY else NC.ON_SURF_VAR)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (selected) NC.PRIMARY else NC.SURFACE_LOW)
                setStroke(dp(1), if (selected) NC.PRIMARY else NC.OUTLINE_VAR)
            }
        }
        style(repairsTabHostBtn, repairsSelectedTab == "host")
        style(repairsTabGuestBtn, repairsSelectedTab == "guest")
        style(repairsTabChrootBtn, repairsSelectedTab == "chroot")
        val chrootOk = repairsRootOk == true
        repairsTabChrootBtn?.visibility = if (chrootOk) View.VISIBLE else View.GONE
        if (!chrootOk && repairsSelectedTab == "chroot") {
            repairsSelectedTab = "host"
            style(repairsTabHostBtn, true)
            style(repairsTabChrootBtn, false)
        }
    }

    private fun refreshRepairsPage() {
        repairsRootBadge?.text = tr(R.string.main_root)
        repairsRootBadge?.setTextColor(NC.ON_SURF_VAR)
        executor.execute {
            val ok = try {
                RootShell.isRootAvailable()
            } catch (_: Exception) {
                false
            }
            mainHandler.post {
                repairsRootOk = ok
                repairsRootBadge?.text = if (ok) tr(R.string.main_root_ok) else tr(R.string.main_root_unavailable)
                repairsRootBadge?.setTextColor(if (ok) NC.PRIMARY else NC.SECONDARY)
                applyRepairsTabStyles()
                populateRepairsList()
            }
        }
    }

    private fun populateRepairsList() {
        val list = repairsListContainer ?: return
        list.removeAllViews()

        when (repairsSelectedTab) {
            "host" -> {
                // setup only — start/stop GUI live elsewhere (home / tools)
                val hostScripts = arrayOf(
                    "setup_termux.sh" to "Setup basic environment and directories on host.",
                    "flux_install.sh" to "One-click: install Alpine proot + setup_alpine_family (user/xfce/vnc)."
                )
                for ((name, desc) in hostScripts) {
                    list.addView(
                        buildScriptCard(
                            name = name,
                            desc = desc,
                            badge = "HOST",
                            runLabel = tr(R.string.main_run),
                            onRun = { runScriptInTerminal(name, "host") }
                        )
                    )
                }
            }
            "guest" -> {
                val rootOk = repairsRootOk == true
                val hint = TextView(this).apply {
                    text = tr(R.string.main_caps_alpine_guest_environment)
                    textSize = 10f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, 0, 0, dp(10))
                }
                list.addView(hint)
                val guestScripts = arrayOf(
                    "setup_alpine_family.sh" to "Create users and core configurations in guest.",
                    "setup_xfce4.sh" to "Install XFCE4 graphical desktop and themes in guest.",
                    "setup_customization_alpine.sh" to "Apply font, locales, and terminal enhancements in guest.",
                    "setup_cli_tools.sh" to "Install Node.js/NVM and AI CLI tools (Aider, Claude, Cline) in guest."
                )
                for ((name, desc) in guestScripts) {
                    list.addView(
                        buildScriptCard(
                            name = name,
                            desc = desc,
                            badge = tr(R.string.main_guest),
                            runLabel = tr(R.string.main_run),
                            onRun = { runGuestRepairScript(name) }
                        )
                    )
                }
            }
            "chroot" -> {
                if (repairsRootOk != true) {
                    val msg = TextView(this).apply {
                        text = tr(R.string.main_root_required_for_chroot_install)
                        textSize = 12f
                        setTextColor(NC.SECONDARY)
                        typeface = Typeface.MONOSPACE
                    }
                    list.addView(msg)
                    return
                }
                // install only — uninstall stays on Chroot Settings
                list.addView(
                    buildScriptCard(
                        name = "setup_alpine_chroot.sh",
                        desc = tr(R.string.main_install_alpine_linux_3_24),
                        badge = "ROOT",
                        runLabel = tr(R.string.main_run),
                        onRun = { runScriptInTerminal("setup_alpine_chroot.sh", "chroot_host") }
                    )
                )
            }
        }
    }

    /**
     * Guest Debian scripts run in default guest environment.
     */
    private fun runGuestRepairScript(scriptName: String) {
        if (scriptName == "setup_cli_tools.sh") {
            confirmAiCliScriptRun {
                runScriptInTerminal(scriptName, "proot")
            }
            return
        }
        runScriptInTerminal(scriptName, "proot")
    }

    private fun confirmAiCliScriptRun(onConfirm: () -> Unit) {
        showBrutalistConfirmDialog(
            title = tr(R.string.main_install_ai_cli_tools),
            message =
                "setup_cli_tools.sh installs Node/NVM and third-party AI CLIs " +
                    "inside the Alpaquita guest (network). Guest-only — not a host APK update.",
            confirmLabel = tr(R.string.main_run),
            cancelLabel = tr(R.string.x11_cancel),
            destructive = false,
            onConfirm = onConfirm
        )
    }

    /** Brutalist picker: PROOT vs CHROOT for guest repair scripts. */
    private fun showGuestRunTargetDialog(scriptName: String) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC0A0A0A"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3C4A3F")
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            isClickable = true
        }

        card.addView(TextView(this).apply {
            text = tr(R.string.main_run_where)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(12) }
            setBackgroundColor(NC.OUTLINE_VAR)
        })
        card.addView(TextView(this).apply {
            text = scriptName
            textSize = 12f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = tr(R.string.main_alpaquita_guest_scripts_work_on)
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(16))
        })

        fun targetBtn(
            label: String,
            fill: Int,
            textColor: Int,
            mode: String,
            endMargin: Int
        ): TextView {
            return TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(textColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = cyberBrutalistBg(
                    fillColor = fill,
                    strokeColor = NC.OUTLINE_VAR,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0
                )
                setPadding(dp(12), dp(14), dp(12), dp(14))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (endMargin > 0) rightMargin = endMargin
                }
                setOnClickListener {
                    dialog.dismiss()
                    runScriptInTerminal(scriptName, mode)
                }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                targetBtn(
                    "PROOT",
                    NC.PRIMARY,
                    NC.ON_PRIMARY,
                    "proot",
                    dp(10)
                )
            )
            addView(
                targetBtn(
                    "CHROOT",
                    NC.SURFACE_CONTAINER,
                    Color.WHITE,
                    "chroot_guest",
                    0
                )
            )
        }
        card.addView(row)

        card.addView(TextView(this).apply {
            text = tr(R.string.x11_cancel)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { dialog.dismiss() }
        })

        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.show()
    }

    private fun buildScriptCard(
        name: String,
        desc: String,
        badge: String,
        runLabel: String,
        onRun: () -> Unit,
        destructive: Boolean = false
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = if (destructive) NC.ERROR else NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dp(14)
            }

            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this@MainActivity).apply {
                text = name
                textSize = 14f
                setTextColor(NC.ON_SURFACE)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val badgeTv = TextView(this@MainActivity).apply {
                text = badge
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(if (destructive) NC.ERROR else NC.PRIMARY)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_LOWEST)
                    setStroke(dp(1), if (destructive) NC.ERROR else NC.OUTLINE_VAR)
                }
            }
            titleRow.addView(title)
            titleRow.addView(badgeTv)

            val descTv = TextView(this@MainActivity).apply {
                text = desc
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, dp(12))
            }

            val btnFill = if (destructive) NC.ERROR_CON else NC.PRIMARY
            val btnText = if (destructive) NC.ON_ERROR_CON else NC.ON_PRIMARY
            val btnStroke = if (destructive) NC.ERROR else NC.OUTLINE_VAR
            val runBtn = TextView(this@MainActivity).apply {
                text = runLabel
                textSize = 12f
                setTextColor(btnText)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = cyberBrutalistBg(
                    fillColor = btnFill,
                    strokeColor = btnStroke,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0
                )
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    gravity = Gravity.END
                }
                setOnClickListener { onRun() }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.translationX = dp(3).toFloat()
                            v.translationY = dp(3).toFloat()
                            v.background = cyberBrutalistBg(
                                fillColor = btnFill,
                                strokeColor = btnStroke,
                                shadowColor = NC.SHADOW_DARK,
                                offsetDp = 2,
                                cornerRadiusDp = 0
                            )
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.translationX = 0f
                            v.translationY = 0f
                            v.background = cyberBrutalistBg(
                                fillColor = btnFill,
                                strokeColor = btnStroke,
                                shadowColor = NC.SHADOW_DARK,
                                offsetDp = 4,
                                cornerRadiusDp = 0
                            )
                        }
                    }
                    false
                }
            }

            addView(titleRow)
            addView(descTv)
            addView(runBtn)
        }
    }

    private fun buildScriptInstallLayout() {
        scriptInstallLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#15121b"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        scriptInstallBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.ON_SURFACE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                onBackPressed()
            }
        }
        scriptInstallTitleTv = TextView(this).apply {
            text = tr(R.string.main_script_runner)
            textSize = 16f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        scriptInstallCopyBtn = TextView(this).apply {
            text = tr(R.string.skills_copy)
            textSize = 13f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            setOnClickListener { copyScriptInstallTranscript() }
        }

        topBar.addView(scriptInstallBackBtn)
        topBar.addView(scriptInstallTitleTv)
        topBar.addView(scriptInstallCopyBtn)
        scriptInstallLayout.addView(topBar)

        scriptInstallViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        scriptInstallTerminalView = TerminalView(this, null).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        scriptInstallViewContainer.addView(scriptInstallTerminalView)
        scriptInstallLayout.addView(scriptInstallViewContainer)
    }

    /** Top-bar label for script runner page — never always "Script Installation". */
    private fun scriptRunnerTitle(scriptName: String, runMode: String): String {
        return when (scriptName) {
            "uninstall_debian13_chroot.sh", "uninstall_alpine_chroot.sh" -> "Chroot Uninstall"
            "setup_debian13_chroot.sh", "setup_alpine_chroot.sh" -> "Chroot Installation"
            "setup_debian_family.sh", "setup_alpine_family.sh" -> "Distro Family Setup"
            "setup_xfce4.sh" -> "XFCE4 Desktop Setup"
            "setup_customization_debian.sh", "setup_customization_alpine.sh" -> "Distro Customization"
            "setup_hw_accel_debian.sh" -> "Hardware Acceleration Setup"
            "setup_cli_tools.sh" -> "CLI Tools Setup"
            "setup_termux.sh" -> "Host Environment Setup"
            "flux_install.sh" -> "Flux / PRoot Install"
            "start_gui.sh" -> "Start Graphical Desktop"
            "stop_gui.sh" -> "Stop Graphical Desktop"
            else -> when {
                scriptName.contains("uninstall", ignoreCase = true) -> "Script Uninstall"
                scriptName.startsWith("setup_") -> "Script Setup"
                scriptName.startsWith("start_") -> "Script Start"
                scriptName.startsWith("stop_") -> "Script Stop"
                else -> when (runMode) {
                    "chroot_host", "chroot" -> tr(R.string.main_chroot_script)
                    "chroot_guest" -> tr(R.string.main_chroot_guest_script)
                    "proot" -> tr(R.string.main_proot_script)
                    "host" -> tr(R.string.main_host_script)
                    else -> tr(R.string.main_script_runner)
                }
            }
        }
    }

    private fun runScriptInTerminal(scriptName: String, runMode: String = "proot") {
        if (scriptName == "setup_hw_accel_debian.sh") {
            Toast.makeText(
                this,
                tr(R.string.main_gpu_is_baked_into_the),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        scriptInstallTerminalView.setTextSize(scriptFontSize)
        if (::scriptInstallTitleTv.isInitialized) {
            scriptInstallTitleTv.text = scriptRunnerTitle(scriptName, runMode)
        }

        // Ensure all scripts are deployed to files/home
        deployScripts()

        val nld     = applicationInfo.nativeLibraryDir
        val shell   = File(nld, "libbash.so").absolutePath
        val cwd     = File(filesDir, "home").absolutePath
        val scriptFile = File(cwd, scriptName)
        if (!scriptFile.exists()) {
            try {
                val assetPath = when {
                    scriptName.contains("chroot") -> "scripts/chroot/$scriptName"
                    else -> "scripts/$scriptName"
                }
                assets.open(assetPath).use { input -> FileOutputStream(scriptFile).use { input.copyTo(it) } }
                scriptFile.setExecutable(true)
            } catch (e: Exception) {
                Log.e("ScriptRun", "Failed to deploy script $scriptName", e)
            }
        }
        val scriptPath = scriptFile.absolutePath
        val args: Array<String>
        val envMap: HashMap<String, String>

        when (runMode) {
            "chroot", "chroot_host" -> {
                // Host-level chroot script (full debug build only).
                args = arrayOf("/system/bin/sh", "-c", RootShell.shellRootCommand("sh $scriptPath"))
                envMap = HashMap(System.getenv()).apply {
                    put("PATH", "/system/bin:/system/xbin:/sbin:$nld:${com.zenithblue.nativecode.terminal.TermuxHostPaths.BIN}")
                    put("HOME", com.zenithblue.nativecode.terminal.TermuxHostPaths.HOME)
                    put("TERM", "xterm-256color")
                }
            }
            "chroot_guest" -> {
                // Guest script via SSOT helper (mounts + root shell entry)
                com.zenithblue.nativecode.terminal.ChrootCommandBuilder.ensureHelperScript(this)
                val ch = com.zenithblue.nativecode.terminal.ChrootCommandBuilder.CHROOT_PATH
                val helper = com.zenithblue.nativecode.terminal.ChrootCommandBuilder.CHROOT_HELPER
                val stageCmd =
                    "mkdir -p $ch/tmp && cp $scriptPath $ch/tmp/$scriptName && chmod +x $ch/tmp/$scriptName"
                val guestInner = if (scriptName == "setup_hw_accel_debian.sh") {
                    val gpu = GpuAccelDetector.fluxGpuEnv()
                    getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
                        .putString("flux_gpu", gpu).apply()
                    "env FLUX_GPU=$gpu bash /tmp/$scriptName"
                } else {
                    "bash /tmp/$scriptName"
                }
                val esc = guestInner.replace("'", "'\\''")
                val runCmd = "sh $helper sh --user root -- '$esc'"
                val rootInner = "$stageCmd && $runCmd"
                val shCmd = RootShell.shellRootCommand(rootInner)
                args = arrayOf("/system/bin/sh", "-c", shCmd)
                envMap = HashMap(System.getenv()).apply {
                    put("PATH", "/system/bin:/system/xbin:/sbin:$nld:${com.zenithblue.nativecode.terminal.TermuxHostPaths.BIN}")
                    put("HOME", com.zenithblue.nativecode.terminal.TermuxHostPaths.HOME)
                    put("TERM", "xterm-256color")
                }
            }
            "proot" -> {
                // Guest script executed inside Debian PRoot container
                val scriptCmd = if (scriptName == "setup_hw_accel_debian.sh") {
                    val gpu = GpuAccelDetector.fluxGpuEnv()
                    getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
                        .putString("flux_gpu", gpu).apply()
                    "env FLUX_GPU=$gpu bash ${com.zenithblue.nativecode.terminal.TermuxHostPaths.HOME}/$scriptName"
                } else {
                    "bash ${com.zenithblue.nativecode.terminal.TermuxHostPaths.HOME}/$scriptName"
                }
                val (a, e) = LinuxCommandBuilder.buildGuestBash(this, scriptCmd, user = "root")
                args = a; envMap = e
            }
            else -> { // "host"
                val force = HostCommandBuilder.shouldForceHostSetup(scriptName)
                if (force) HostCommandBuilder.clearSetupMarker(this)
                val (hostArgs, hostEnv) = HostCommandBuilder.build(
                    this,
                    scriptPath,
                    forceHostSetup = force
                )
                args = hostArgs
                envMap = hostEnv
            }
        }
        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()

        val scriptViewClient = object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    scriptFontSize = (scriptFontSize * scale).toInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                    scriptInstallTerminalView.setTextSize(scriptFontSize)
                    return 1.0f
                }
                return scale
            }
            override fun onSingleTapUp(e: MotionEvent) {}
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean      = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean      = false
            override fun isTerminalViewSelected(): Boolean            = false
            override fun copyModeChanged(active: Boolean) {}
            override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent, session: TerminalSession): Boolean = true
            override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean = true
            override fun onLongPress(e: MotionEvent): Boolean = true
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean     = false
            override fun readShiftKey(): Boolean   = false
            override fun readFnKey(): Boolean      = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = true
            override fun onEmulatorSet() {}
            override fun logError(tag: String, message: String)   {}
            override fun logWarn(tag: String, message: String)    {}
            override fun logInfo(tag: String, message: String)    {}
            override fun logDebug(tag: String, message: String)   {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }

        val scriptSessionClient = object : TerminalSessionClient {
            override fun onTextChanged(session: TerminalSession) {
                scriptInstallTerminalView.onScreenUpdated()
            }
            override fun onTitleChanged(session: TerminalSession) {}
            override fun onSessionFinished(session: TerminalSession) {
                Log.d("Terminal", "Script finished: ${session.exitStatus}")
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "$scriptName Finished!", Toast.LENGTH_LONG).show()
                     isScriptRunning = false
                     if (pageStack.isNotEmpty() && pageStack.peek() == ID_SCRIPT_INSTALL) {
                         if (::scriptInstallBackBtn.isInitialized) {
                             scriptInstallBackBtn.visibility = View.VISIBLE
                         }
                     }
                     // C6: mark AI suite provisioned for the method that ran the install
                     if (scriptName == "setup_cli_tools.sh") {
                         val provisionMethod = when (runMode) {
                             "chroot_guest", "chroot" -> "chroot"
                             else -> "proot"
                         }
                         val ok = session.exitStatus == 0
                         CliToolsInstaller.finishProvision(
                             this@MainActivity,
                             provisionMethod,
                             if (ok) 0 else 1
                         )
                         if (ok) {
                             refreshToolCardsForMethod()
                             if (::cliAuthScrollView.isInitialized &&
                                 cliAuthScrollView.visibility == View.VISIBLE
                             ) {
                                 paintCliAuth()
                             }
                             Toast.makeText(
                                 this@MainActivity,
                                 tr(R.string.main_ai_cli_tools_provisioned, provisionMethod),
                                 Toast.LENGTH_LONG
                             ).show()
                         }
                     }
                     // Chroot uninstall: flip prefs + refresh Settings card when rootfs gone
                     if (scriptName == "uninstall_debian13_chroot.sh" && pendingChrootUninstall) {
                         pendingChrootUninstall = false
                         if (session.exitStatus == 0 || !ProjectPathResolver.isChrootRootfsPresent()) {
                             onChrootUninstalled(fromCallback = false)
                         } else {
                             refreshChrootSettingsCard()
                             Toast.makeText(
                                 this@MainActivity,
                                 tr(R.string.main_uninstall_may_have_failed_check),
                                 Toast.LENGTH_LONG
                             ).show()
                         }
                     }
                }
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: return
                executor.execute {
                    session.emulator?.paste(text) ?: session.write(text)
                }
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = 1
            override fun logError(tag: String, message: String) { Log.e(tag, message) }
            override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
            override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
            override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
            override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
            override fun logStackTraceWithMessage(tag: String, message: String, e: java.lang.Exception) { Log.e(tag, message, e) }
            override fun logStackTrace(tag: String, e: java.lang.Exception) { Log.e(tag, "Stacktrace", e) }
        }

        scriptInstallTerminalView.setTerminalViewClient(scriptViewClient)
        val session = TerminalSession(shell, cwd, args, env, 10000, scriptSessionClient)
        scriptInstallSession = session
        scriptInstallTerminalView.attachSession(session)
         isScriptRunning = true
         if (::scriptInstallBackBtn.isInitialized) {
             scriptInstallBackBtn.visibility = View.GONE
         }

        if (pageStack.isEmpty() || pageStack.peek() != ID_SCRIPT_INSTALL) {
            pageStack.push(ID_SCRIPT_INSTALL)
        }
        navigateToPage(ID_SCRIPT_INSTALL)
    }

    // ── Sub-pages Builders ───────────────────────────────────────────────────

    private fun buildFileViewerLayout() {
        fileViewerRootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        fileViewerTopBar = createProjectSubpageTopBar()
        fileViewerStickyHeader = buildFileViewerStickyHeader()
        fileViewerContentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(NC.BG)
        }
        // Kept for callers that still scrollTo / removeAllViews on legacy fields
        fileViewerScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(NC.BG)
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))
        }
        fileViewerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        fileViewerScrollView.addView(fileViewerContainer)
        fileViewerRootContainer.addView(fileViewerTopBar)
        fileViewerRootContainer.addView(fileViewerStickyHeader)
        fileViewerRootContainer.addView(fileViewerContentHost)
    }

    /** Fixed chrome: back + FILE VIEWER + path + COPY PATH. */
    private fun buildFileViewerStickyHeader(): LinearLayout {
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(NC.BG)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val inPageBack = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) }
            contentDescription = "Back"
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissFileViewer() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(2).toFloat()
                        v.translationY = dp(2).toFloat()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                    }
                }
                false
            }
        }
        titleRow.addView(inPageBack)
        titleRow.addView(TextView(this).apply {
            text = tr(R.string.main_file_viewer)
            textSize = 16f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            maxLines = 1
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })

        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        }
        val pathTv = TextView(this).apply {
            text = "//"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        fileViewerPathTv = pathTv
        val copyPathHeaderBtn = TextView(this).apply {
            text = tr(R.string.main_copy_path)
            textSize = 10f
            setTextColor(Color.parseColor("#0A0A0A"))
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.PRIMARY_CON)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
            tag = "copy_path_btn"
        }
        pathRow.addView(pathTv)
        pathRow.addView(copyPathHeaderBtn)
        headerCard.addView(titleRow)
        headerCard.addView(pathRow)
        headerCard.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(12) }
            setBackgroundColor(NC.OUTLINE_VAR)
        })
        return headerCard
    }

    private fun buildDiffViewerLayout() {
        diffViewerRootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        diffViewerTopBar = createProjectSubpageTopBar()
        diffViewerScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(NC.BG)
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))
        }
        diffViewerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        diffViewerScrollView.addView(diffViewerContainer)
        diffViewerRootContainer.addView(diffViewerTopBar)
        diffViewerRootContainer.addView(diffViewerScrollView)
    }

    // ── Helper Sub-page Actions ──────────────────────────────────────────────

    private fun showFileViewer(name: String, backPage: Int = ID_FILES) {
        // Opening another file while the editor holds unsaved edits: ask first (Save / Discard / Cancel).
        fileEditor?.let { ed ->
            val f = File(name).let { if (!it.exists() && !it.isAbsolute) File(activeProjectHostDir(), name) else it }
            if (ed.dirty && ed.file.absolutePath != f.absolutePath) {
                ed.confirmLeave { showFileViewer(name, backPage) }
                return
            }
        }
        fileViewerBackPage = backPage
        if (backPage == ID_PROJECT_DIR_TREE ||
            backPage == ID_PROJECT_WORKSPACE ||
            backPage == ID_PROJECT_GIT_DIFF ||
            backPage == ID_PROJECT_SETTINGS
        ) {
            unifiedHeader.visibility = View.GONE
            if (::projectBottomNavigation.isInitialized) projectBottomNavigation.visibility = View.VISIBLE
            if (::bottomNavigation.isInitialized) bottomNavigation.visibility = View.GONE
            if (::customProjectBottomNav.isInitialized) customProjectBottomNav.visibility = View.VISIBLE
            if (::customBottomNav.isInitialized) customBottomNav.visibility = View.GONE
            fileViewerTopBar.visibility = View.VISIBLE
            updateProjectSubpageTopBar(fileViewerTopBar)
        } else {
            unifiedHeader.visibility = View.VISIBLE
            if (::projectBottomNavigation.isInitialized) projectBottomNavigation.visibility = View.GONE
            if (::bottomNavigation.isInitialized) bottomNavigation.visibility = View.VISIBLE
            if (::customProjectBottomNav.isInitialized) customProjectBottomNav.visibility = View.GONE
            fileViewerTopBar.visibility = View.GONE
        }
        homeScrollView.visibility = View.GONE
        fileExplorerScrollView.visibility = View.GONE
        terminalWorkspaceLayout.visibility = View.GONE
        gitOperationsScrollView.visibility = View.GONE
        settingsHubScrollView.visibility = View.GONE
        if (::prootSettingsScrollView.isInitialized) {
            prootSettingsScrollView.visibility = View.GONE
        }
        if (::prootFilesScrollView.isInitialized) {
            prootFilesScrollView.visibility = View.GONE
        }
        if (::chrootSettingsScrollView.isInitialized) {
            chrootSettingsScrollView.visibility = View.GONE
        }
        if (::diffViewerRootContainer.isInitialized) {
            diffViewerRootContainer.visibility = View.GONE
        }
        if (::projectsListContainer.isInitialized) {
            projectsListContainer.visibility = View.GONE
        }

        if (::projectGitDiffContainer.isInitialized) projectGitDiffContainer.visibility = View.GONE
        if (::projectWorkspaceContainer.isInitialized) projectWorkspaceContainer.visibility = View.GONE
        if (::projectSettingsContainer.isInitialized) projectSettingsContainer.visibility = View.GONE
        if (::projectDirTreeContainer.isInitialized) projectDirTreeContainer.visibility = View.GONE

        fileViewerRootContainer.visibility = View.VISIBLE
        fileViewerScrollView.visibility = View.VISIBLE
        // Keep project context on stack/nav (file viewer is overlay, not a stack page)
        if (backPage == ID_PROJECT_DIR_TREE ||
            backPage == ID_PROJECT_WORKSPACE ||
            backPage == ID_PROJECT_GIT_DIFF ||
            backPage == ID_PROJECT_SETTINGS
        ) {
            currentPageId = backPage
            updateCustomBottomNavSelection(backPage)
        }

        // Drop IME from directory search so binary/code pages don't open with keyboard
        currentFocus?.clearFocus()
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(fileViewerRootContainer.windowToken, 0)
        } catch (_: Exception) { }

        renderFileViewerContent(name, backPage)
    }

    /**
     * Close file viewer overlay and return to [fileViewerBackPage] (directory tree when
     * opened from project Directory). Does **not** pop pageStack to projects list.
     */
    private fun dismissFileViewer() {
        if (!::fileViewerRootContainer.isInitialized) return
        val ed = fileEditor
        if (ed != null && ed.dirty) {
            ed.confirmLeave { dismissFileViewer() }
            return
        }
        releaseFileEditor()
        // Drop the viewer: stops audio, closes the PDF, destroys the WebView (they release on detach).
        if (::fileViewerContentHost.isInitialized) fileViewerContentHost.removeAllViews()
        fileViewerRootContainer.visibility = View.GONE
        val target = when (fileViewerBackPage) {
            ID_PROJECT_DIR_TREE,
            ID_PROJECT_WORKSPACE,
            ID_PROJECT_GIT_DIFF,
            ID_PROJECT_SETTINGS,
            ID_PROOT_FILES,
            ID_TERMINAL, // a file link tapped in the Terminal tab's chat
            ID_FILES -> fileViewerBackPage
            else -> ID_PROJECT_DIR_TREE
        }
        // pushToStack=false: stay on same stack entry (DIR_TREE), never jump to projects
        navigateToPage(target, false)
    }

    private fun dismissDiffViewer() {
        if (!::diffViewerRootContainer.isInitialized) return
        diffViewerRootContainer.visibility = View.GONE
        val target = when (diffViewerBackPage) {
            ID_PROJECT_DIR_TREE,
            ID_PROJECT_WORKSPACE,
            ID_PROJECT_GIT_DIFF,
            ID_PROJECT_SETTINGS,
            ID_FILES -> diffViewerBackPage
            else -> ID_PROJECT_GIT_DIFF
        }
        navigateToPage(target, false)
    }

    private fun renderFileViewerContent(pathStr: String, backPage: Int) {
        var targetFile = File(pathStr)
        val projectHost = activeProjectHostDir()
        if (!targetFile.exists() && !targetFile.isAbsolute) {
            targetFile = File(projectHost, pathStr)
        }

        val relativePathStr = if (targetFile.absolutePath.startsWith(projectHost.absolutePath)) {
            targetFile.absolutePath.removePrefix(projectHost.absolutePath).trimStart('/')
        } else {
            targetFile.name
        }

        // Sticky chrome (fixed at top)
        fileViewerPathTv?.text = "// $relativePathStr"
        (fileViewerStickyHeader.findViewWithTag<TextView>("copy_path_btn"))?.setOnClickListener {
            copyToClipboard(tr(R.string.ws_file_path), relativePathStr)
        }

        if (!::fileViewerContentHost.isInitialized) return
        fileViewerContentHost.removeAllViews()

        if (!targetFile.exists()) {
            val scroll = ScrollView(this).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                setPadding(dp(16), dp(8), dp(16), dp(100))
            }
            scroll.addView(TextView(this).apply {
                text = "File not found: ${targetFile.absolutePath}"
                setTextColor(NC.ERROR)
                textSize = 14f
                typeface = Typeface.MONOSPACE
            })
            fileViewerContentHost.addView(scroll)
            return
        }

        // One type table (FileKinds) shared with the inline chat cards. Chat links, bridge cards and the Directory all land here.
        val kind = com.zenithblue.nativecode.agent.chat.ui.FileKinds.kindOf(targetFile.name)
        val forceEditor = editFileOnce == targetFile.absolutePath
        editFileOnce = null
        val external = { openExternalFile(targetFile, com.zenithblue.nativecode.agent.chat.ui.FileKinds.mimeOf(targetFile.name)) }
        fileViewerStickyHeader.visibility = View.VISIBLE
        // Code + markdown + full-bleed viewers fill the host; small cards (audio, video, binary) use the outer scroll
        val fullBleed: View? = when (kind) {
            com.zenithblue.nativecode.agent.chat.ui.FileType.IMAGE -> FileViewers.image(this, targetFile, external)
            com.zenithblue.nativecode.agent.chat.ui.FileType.SVG -> if (forceEditor) null else FileViewers.svg(this, targetFile, external)
            com.zenithblue.nativecode.agent.chat.ui.FileType.HTML -> if (forceEditor) null else FileViewers.html(this, targetFile, external)
            com.zenithblue.nativecode.agent.chat.ui.FileType.PDF -> FileViewers.pdf(this, targetFile, external)
            else -> null
        }
        when {
            fullBleed != null -> {
                fileViewerContentHost.addView(fullBleed.apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) })
                // Agent markup has a source: EDIT swaps the sandboxed render for the editor
                if (kind == com.zenithblue.nativecode.agent.chat.ui.FileType.HTML || kind == com.zenithblue.nativecode.agent.chat.ui.FileType.SVG) addEditPill(targetFile, backPage)
            }
            kind == com.zenithblue.nativecode.agent.chat.ui.FileType.MARKDOWN && !forceEditor -> {
                fileViewerContentHost.addView(
                    buildMarkdownViewerCard(targetFile).apply {
                        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                    }
                )
                addEditPill(targetFile, backPage)
            }
            isTextFile(targetFile, kind) -> {
                if (targetFile.length() <= com.zenithblue.nativecode.editor.EditorLimits.VIEW_MAX_BYTES) {
                    val ed = fileEditor?.takeIf { it.file.absolutePath == targetFile.absolutePath }
                        ?: run {
                            releaseFileEditor()
                            com.zenithblue.nativecode.editor.EditorView(this, targetFile).also { fileEditor = it }
                        }
                    fileViewerStickyHeader.visibility = View.GONE // the editor has its own header
                    (ed.parent as? ViewGroup)?.removeView(ed)
                    ed.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                    fileViewerContentHost.addView(ed)
                } else {
                    fileViewerContentHost.addView(
                        buildCodeViewerCard(targetFile, fillParent = true).apply {
                            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                        }
                    )
                }
            }
            else -> {
                val scroll = ScrollView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                    clipToPadding = false
                    setPadding(dp(16), dp(8), dp(16), dp(100))
                    setBackgroundColor(NC.BG)
                }
                val wrap = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
                }
                when (kind) {
                    com.zenithblue.nativecode.agent.chat.ui.FileType.VIDEO -> wrap.addView(buildVideoCard(targetFile))
                    com.zenithblue.nativecode.agent.chat.ui.FileType.AUDIO -> wrap.addView(FileViewers.audio(this, targetFile, external))
                    com.zenithblue.nativecode.agent.chat.ui.FileType.APK -> wrap.addView(buildApkCard(targetFile))
                    // Office documents have no native renderer: card with the file's own MIME so the chooser offers the right apps
                    else -> wrap.addView(buildBinaryFileCard(targetFile, com.zenithblue.nativecode.agent.chat.ui.FileKinds.mimeOf(targetFile.name)))
                }
                scroll.addView(wrap)
                fileViewerContentHost.addView(scroll)
            }
        }
        // Keep legacy fields in sync for any other callers
        fileViewerContainer.removeAllViews()
        fileViewerScrollView.scrollTo(0, 0)
    }

    // ── Built-in code editor (plan 16) ───────────────────────────────────────

    private var fileEditor: com.zenithblue.nativecode.editor.EditorView? = null
    /** Absolute path to open in the editor even when a rendered viewer (markdown / html) is the default. */
    private var editFileOnce: String? = null

    private fun releaseFileEditor() {
        fileEditor?.let { (it.parent as? ViewGroup)?.removeView(it); it.release() }
        fileEditor = null
    }

    /** EDIT pill over a rendered viewer (markdown, html) that switches to the editor. */
    private fun addEditPill(file: File, backPage: Int) {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(NC.PRIMARY_CON) }
            setPadding(dp(12), dp(10), dp(14), dp(10))
            elevation = dp(4).toFloat()
            contentDescription = "Edit ${file.name}"
            isClickable = true
            setOnClickListener {
                editFileOnce = file.absolutePath
                renderFileViewerContent(file.absolutePath, backPage)
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_lucide_pencil)
                setColorFilter(Color.parseColor("#0A0A0A"))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) }
            })
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.ssh_edit)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                setTextColor(Color.parseColor("#0A0A0A"))
            })
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(16)
                bottomMargin = dp(16)
            }
        }
        fileViewerContentHost.addView(pill)
    }

    private fun buildVideoCard(file: File): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(NC.SURFACE, NC.BORDER, dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val videoArea = FrameLayout(this).apply {
            setBackgroundColor(NC.SURFACE_HIGH)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(240))
        }

        val videoView = android.widget.VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)
            setVideoPath(file.absolutePath)
            // Tapping a chat video card should play it, not park on a black frame waiting for a hidden play button.
            setOnPreparedListener { it.start() }
        }
        val mediaController = android.widget.MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        videoArea.addView(videoView)
        card.addView(videoArea)

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val infoTv = TextView(this).apply {
            text = "  ${file.name} • VIDEO • ${formatFileSize(file.length())}"
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        infoRow.addView(infoTv)

        val openExtBtn = TextView(this).apply {
            text = tr(R.string.main_external_player)
            textSize = 10f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(NC.SURFACE_VAR, NC.BORDER, dp(4))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { openExternalFile(file, "video/*") }
        }
        infoRow.addView(openExtBtn)

        card.addView(infoRow)
        return card
    }

    private fun buildApkCard(file: File): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val apkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.BORDER,
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.BORDER
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val pm = packageManager
        val pkgInfo = try { pm.getPackageArchiveInfo(file.absolutePath, 0) } catch (e: Exception) { null }
        var appLabel = file.name
        var pkgName = tr(R.string.main_package_archive)
        var verStr = "v1.0"
        var iconDrawable: Drawable? = null

        pkgInfo?.applicationInfo?.let { appInfo ->
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            appLabel = try { appInfo.loadLabel(pm).toString() } catch (e: Exception) { file.name }
            pkgName = pkgInfo.packageName ?: ""
            verStr = "v${pkgInfo.versionName ?: "1.0"}"
            iconDrawable = try { appInfo.loadIcon(pm) } catch (e: Exception) { null }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconIv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) }
            if (iconDrawable != null) {
                setImageDrawable(iconDrawable)
            } else {
                setImageResource(R.drawable.ic_extension)
                setColorFilter(NC.PRIMARY)
            }
        }

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }

        val titleTv = TextView(this).apply {
            text = appLabel
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subTv = TextView(this).apply {
            text = "$pkgName • $verStr\n${formatFileSize(file.length())}"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        }
        infoCol.addView(titleTv)
        infoCol.addView(subTv)

        headerRow.addView(iconIv)
        headerRow.addView(infoCol)
        apkCard.addView(headerRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
        }

        val hintTv = TextView(this).apply {
            text = tr(R.string.main_nativecode_cannot_install_apks_save)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        }
        apkCard.addView(hintTv)

        val installBtn = primaryButton(tr(R.string.main_save_to_downloads_open)) {
            confirmAndExportApk(file, pkgInfo)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.4f)
        }
        val shareBtn = secondaryButton(tr(R.string.main_share)) {
            shareApkFromProject(file)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) }
        }

        btnRow.addView(installBtn)
        btnRow.addView(shareBtn)
        apkCard.addView(btnRow)

        container.addView(apkCard)
        return container
    }

    /**
     * Code panel: sticky file header + vertically scrollable body (horiz scroll for long lines).
     * [fillParent]=true when host has fixed height (file viewer body).
     */
    private fun buildCodeViewerCard(file: File, fillParent: Boolean = false): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#0A0A0A"))
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            layoutParams = if (fillParent) {
                LinearLayout.LayoutParams(MATCH, MATCH)
            } else {
                LinearLayout.LayoutParams(MATCH, WRAP)
            }
            clipChildren = true
            clipToPadding = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(NC.SURFACE_CONTAINER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val fileContentStr = try {
            val maxLength = 500 * 1024
            if (file.length() > maxLength) {
                file.inputStream().use { input ->
                    val bytes = ByteArray(maxLength)
                    val read = input.read(bytes)
                    String(bytes, 0, read, Charsets.UTF_8) + "\n... [Truncated file content]"
                }
            } else {
                file.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }

        val lines = fileContentStr.lines()

        header.addView(TextView(this).apply {
            text = "${file.name} (${lines.size} L · ${formatFileSize(file.length())})"
            textSize = 12f
            setTextColor(Color.parseColor("#FAFAFA"))
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        header.addView(TextView(this).apply {
            text = tr(R.string.skills_copy)
            textSize = 10f
            setTextColor(Color.parseColor("#0A0A0A"))
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.PRIMARY_CON)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(tr(R.string.main_file_content), fileContentStr)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, tr(R.string.main_copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        })
        card.addView(header)

        val codeLines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val maxLinesToShow = 1000
        val displayLines = if (lines.size > maxLinesToShow) lines.take(maxLinesToShow) else lines

        for ((idx, line) in displayLines.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            }
            row.addView(TextView(this).apply {
                text = "${idx + 1}".padStart(4, ' ')
                textSize = 11f
                setTextColor(NC.OUTLINE)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, dp(10), 0)
                minWidth = dp(36)
            })
            row.addView(TextView(this).apply {
                text = line.ifEmpty { " " }
                textSize = 11f
                setTextColor(Color.parseColor("#FAFAFA"))
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                isSingleLine = true
                setHorizontallyScrolling(true)
            })
            codeLines.addView(row)
        }

        if (lines.size > maxLinesToShow) {
            codeLines.addView(TextView(this).apply {
                text = "... ${lines.size - maxLinesToShow} more lines hidden"
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(dp(20), dp(8), 0, 0)
            })
        }

        // Vertical scroll (weight when fill) + horizontal for long lines
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
            addView(codeLines)
        }
        val vScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(80))
            layoutParams = if (fillParent) {
                LinearLayout.LayoutParams(MATCH, 0, 1f)
            } else {
                LinearLayout.LayoutParams(MATCH, WRAP)
            }
            addView(hScroll)
        }
        card.addView(vScroll)
        return card
    }

    private fun buildBinaryFileCard(file: File, mimeType: String = "*/*"): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                // room for two-tone extrusion
                bottomMargin = dp(8)
                rightMargin = dp(4)
            }
        }

        val iconIv = ImageView(this).apply {
            setImageResource(R.drawable.ic_extension)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                bottomMargin = dp(12)
            }
        }

        val titleTv = TextView(this).apply {
            text = file.name
            textSize = 14f
            setTextColor(Color.parseColor("#FAFAFA"))
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }

        val descTv = TextView(this).apply {
            text = tr(R.string.main_binary_file_direct_preview_not, formatFileSize(file.length()))
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(16))
        }

        val openExtBtn = primaryButton(tr(R.string.main_open_with_external_app)) {
            openExternalFile(file, mimeType)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        card.addView(iconIv)
        card.addView(titleTv)
        card.addView(descTv)
        card.addView(openExtBtn)

        return card
    }

    /** Editor-able: the kinds FileKinds calls text-like, plus an unknown type that is a dotfile or sniffs as text (no NUL in the head). */
    private fun isTextFile(file: File, kind: com.zenithblue.nativecode.agent.chat.ui.FileType): Boolean {
        when (kind) {
            com.zenithblue.nativecode.agent.chat.ui.FileType.TEXT,
            com.zenithblue.nativecode.agent.chat.ui.FileType.MARKDOWN,
            com.zenithblue.nativecode.agent.chat.ui.FileType.HTML,
            com.zenithblue.nativecode.agent.chat.ui.FileType.SVG -> return true
            com.zenithblue.nativecode.agent.chat.ui.FileType.OTHER -> if (file.name.startsWith(".")) return true
            else -> return false
        }
        if (file.length() > 2 * 1024 * 1024) return false
        return try {
            file.inputStream().use { input ->
                val bytes = ByteArray(1024.coerceAtMost(file.length().toInt()))
                val read = input.read(bytes)
                if (read <= 0) return true
                var nullCount = 0
                for (i in 0 until read) {
                    if (bytes[i] == 0.toByte()) nullCount++
                }
                nullCount == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    private val activeAgentDialogRef = java.util.concurrent.atomic.AtomicReference<android.app.Dialog?>(null)

    override fun isAlertActive(): Boolean {
        val dialog = activeAgentDialogRef.get()
        return dialog != null && dialog.isShowing
    }

    override fun dismissActiveAlert(): Boolean {
        val dialog = activeAgentDialogRef.getAndSet(null)
        if (dialog != null && dialog.isShowing) {
            runOnUiThread {
                try {
                    dialog.dismiss()
                } catch (_: Exception) {}
            }
            return true
        }
        return false
    }

    override fun showOpenLinkAlert(url: String, title: String?, message: String?): Boolean {
        if (isFinishing || isDestroyed) return false
        if (isAlertActive()) return false
        runOnUiThread {
            showAgentOpenLinkDialog(url, title, message)
        }
        return true
    }

    override fun showInstallApkAlert(apkFile: File, title: String?, message: String?): Boolean {
        if (isFinishing || isDestroyed) return false
        if (isAlertActive()) return false
        runOnUiThread {
            showAgentInstallApkDialog(apkFile, title, message)
        }
        return true
    }

    private fun showAgentOpenLinkDialog(url: String, title: String?, message: String?) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.BORDER
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            isClickable = true
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        titleRow.addView(TextView(this).apply {
            text = tr(R.string.main_link)
            textSize = 10f
            setTextColor(Color.parseColor("#0A0A0A"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.PRIMARY)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
        })
        titleRow.addView(TextView(this).apply {
            text = title?.takeIf { it.isNotBlank() } ?: tr(R.string.main_agent_open_link)
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        card.addView(titleRow)

        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(14) }
            setBackgroundColor(NC.OUTLINE_VAR)
        })

        if (!message.isNullOrBlank()) {
            card.addView(TextView(this).apply {
                text = message
                textSize = 13f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(2).toFloat(), 1.15f)
                setPadding(0, 0, 0, dp(12))
            })
        }

        val urlBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 3, 0)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        }
        val urlTv = TextView(this).apply {
            text = url
            textSize = 12f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        urlBox.addView(urlTv)
        card.addView(urlBox)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dismissBtn = TextView(this).apply {
            text = tr(R.string.main_dismiss)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(NC.ON_SURF_VAR)
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(NC.SURFACE_LOW, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
            setOnClickListener { dialog.dismiss() }
        }

        val copyBtn = TextView(this).apply {
            text = tr(R.string.skills_copy)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(NC.PRIMARY)
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clip?.setPrimaryClip(ClipData.newPlainText("URL", url))
                Toast.makeText(this@MainActivity, tr(R.string.main_url_copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }

        val openBtn = TextView(this).apply {
            text = tr(R.string.main_open_browser)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#0A0A0A"))
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(NC.PRIMARY, NC.BORDER, NC.SHADOW_GREEN, 4, 0)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.4f)
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(Intent.createChooser(intent, tr(R.string.main_open_with)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No browser found: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRow.addView(dismissBtn)
        btnRow.addView(copyBtn)
        btnRow.addView(openBtn)
        card.addView(btnRow)

        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.setOnDismissListener {
            activeAgentDialogRef.compareAndSet(dialog, null)
        }
        activeAgentDialogRef.set(dialog)
        dialog.show()
    }

    private fun showAgentInstallApkDialog(file: File, title: String?, message: String?) {
        val pm = packageManager
        val pkgInfo = try { pm.getPackageArchiveInfo(file.absolutePath, 0) } catch (_: Exception) { null }
        var appLabel = file.name
        var pkgName = tr(R.string.main_package_archive)
        var verStr = "v1.0"
        var iconDrawable: Drawable? = null

        pkgInfo?.applicationInfo?.let { appInfo ->
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            appLabel = try { appInfo.loadLabel(pm).toString() } catch (_: Exception) { file.name }
            pkgName = pkgInfo.packageName ?: ""
            verStr = "v${pkgInfo.versionName ?: "1.0"}"
            iconDrawable = try { appInfo.loadIcon(pm) } catch (_: Exception) { null }
        }

        val targetSdk = pkgInfo?.applicationInfo?.targetSdkVersion ?: 0
        val oldSdkWarn = if (targetSdk in 1 until 23) {
            "\n\nThis APK targets SDK $targetSdk. Android 14+ will refuse to install it."
        } else {
            ""
        }

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.BORDER
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            isClickable = true
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        titleRow.addView(TextView(this).apply {
            text = "APK"
            textSize = 10f
            setTextColor(Color.parseColor("#0A0A0A"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.PRIMARY)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
        })
        titleRow.addView(TextView(this).apply {
            text = title?.takeIf { it.isNotBlank() } ?: tr(R.string.main_agent_install_apk)
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        card.addView(titleRow)

        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(14) }
            setBackgroundColor(NC.OUTLINE_VAR)
        })

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val iconIv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
            if (iconDrawable != null) {
                setImageDrawable(iconDrawable)
            } else {
                setImageResource(R.drawable.ic_extension)
                setColorFilter(NC.PRIMARY)
            }
        }
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        infoCol.addView(TextView(this).apply {
            text = appLabel
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        infoCol.addView(TextView(this).apply {
            text = "$pkgName • $verStr\n${formatFileSize(file.length())}"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        })
        headerRow.addView(iconIv)
        headerRow.addView(infoCol)
        card.addView(headerRow)

        if (!message.isNullOrBlank()) {
            card.addView(TextView(this).apply {
                text = message
                textSize = 13f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(2).toFloat(), 1.15f)
                setPadding(0, 0, 0, dp(8))
            })
        }

        card.addView(TextView(this).apply {
            text = tr(R.string.main_nativecode_cannot_install_apks_direc, oldSdkWarn)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(16))
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dismissBtn = TextView(this).apply {
            text = tr(R.string.main_dismiss)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(NC.ON_SURF_VAR)
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(NC.SURFACE_LOW, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) }
            setOnClickListener { dialog.dismiss() }
        }

        val shareBtn = TextView(this).apply {
            text = tr(R.string.main_share)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(NC.PRIMARY)
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) }
            setOnClickListener {
                dialog.dismiss()
                shareApkFromProject(file)
            }
        }

        val saveOpenBtn = TextView(this).apply {
            text = tr(R.string.main_save_open)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#0A0A0A"))
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(NC.PRIMARY, NC.BORDER, NC.SHADOW_GREEN, 4, 0)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.4f)
            setOnClickListener {
                dialog.dismiss()
                exportApkToDownloads(file)
            }
        }

        btnRow.addView(dismissBtn)
        btnRow.addView(shareBtn)
        btnRow.addView(saveOpenBtn)
        card.addView(btnRow)

        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.setOnDismissListener {
            activeAgentDialogRef.compareAndSet(dialog, null)
        }
        activeAgentDialogRef.set(dialog)
        dialog.show()
    }

    /**
     * Play-safe APK handoff: copy to Downloads / share sheet.
     * Never ACTION_VIEW package-archive or PackageInstaller from this process.
     */
    private fun confirmAndExportApk(file: File, pkgInfo: android.content.pm.PackageInfo?) {
        val targetSdk = pkgInfo?.applicationInfo?.targetSdkVersion ?: 0
        val oldSdkWarn = if (targetSdk in 1 until 23) {
            "\n\nThis APK targets SDK $targetSdk. Android 14+ will refuse to install it."
        } else {
            ""
        }
        val pkgLine = pkgInfo?.packageName?.takeIf { it.isNotBlank() }?.let { "\nPackage: $it" } ?: ""
        val verLine = pkgInfo?.versionName?.let { "\nVersion: $it" } ?: ""
        showBrutalistConfirmDialog(
            title = tr(R.string.main_export_apk),
            message = "${file.name}\n${formatFileSize(file.length())}$pkgLine$verLine\n\n" +
                "Saves a copy to Downloads. Open that file to install." +
                oldSdkWarn,
            confirmLabel = tr(R.string.main_save_open),
            onConfirm = { exportApkToDownloads(file) }
        )
    }

    private fun exportApkToDownloads(file: File) {
        Toast.makeText(this, tr(R.string.main_preparing_apk), Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val staged = copyOutForShare(file)
                val displayName = file.name.ifBlank { "app.apk" }
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = contentResolver.insert(collection, values)
                        ?: throw IllegalStateException("Downloads insert failed")
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            staged.inputStream().use { it.copyTo(out) }
                        } ?: throw IllegalStateException("Cannot write Downloads")
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        runCatching { contentResolver.delete(uri, null, null) }
                        throw e
                    }
                    mainHandler.post {
                        Toast.makeText(
                            this,
                            tr(R.string.main_saved_to_downloads_as_tap, displayName),
                            Toast.LENGTH_LONG
                        ).show()
                        openDownloadsUi()
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(
                            this,
                            tr(R.string.main_use_share_and_save_to),
                            Toast.LENGTH_LONG
                        ).show()
                        shareApkFile(staged)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "exportApkToDownloads failed", e)
                mainHandler.post {
                    Toast.makeText(this, "Cannot export APK: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareApkFromProject(file: File, chooserTitle: String = "Share APK") {
        executor.execute {
            try {
                val staged = copyOutForShare(file)
                mainHandler.post { shareApkFile(staged, chooserTitle) }
            } catch (e: Exception) {
                Log.e("MainActivity", "shareApkFromProject failed", e)
                mainHandler.post {
                    Toast.makeText(this, "Cannot share APK: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareApkFile(file: File, chooserTitle: String = "Share APK") {
        val uri = getFileUri(file) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share APK: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDownloadsUi() {
        // DocumentsUI Downloads root first: ACTION_VIEW_DOWNLOADS has no handler on some ROMs
        // (HyperOS) and then fails silently (START_CLASS_NOT_FOUND, no exception).
        val root = Intent(Intent.ACTION_VIEW).setDataAndType(
            android.provider.DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"),
            android.provider.DocumentsContract.Root.MIME_TYPE_ITEM
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(root)
        } catch (_: Exception) {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this, tr(R.string.main_open_files_downloads_and_tap), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Readable copy under share/export. Chroot may need root cp. */
    private fun copyOutForShare(source: File): File {
        require(source.isFile) { "not a file" }
        if (source.length() > SHARE_MAX_BYTES) {
            throw IllegalArgumentException(
                "File too large to export (${formatFileSize(source.length())}; max ${formatFileSize(SHARE_MAX_BYTES)})"
            )
        }
        if (source.canRead()) {
            return stageForShare(source)
        }
        purgeOldShareExports()
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(shareStagingDir(), "${System.currentTimeMillis()}_$safeName")
        val result = RootShell.copyOutOfChroot(source, dest)
        if (result.exitCode != 0 || !dest.isFile) {
            throw IllegalStateException(
                "Cannot read APK from chroot. Grant root, or copy it into a proot project."
            )
        }
        return dest
    }

    private fun openExternalFile(file: File, mimeType: String = "*/*") {
        val uri = getFileUri(file) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(intent, tr(R.string.main_open_with)))
        } catch (e: Exception) {
            Toast.makeText(this, tr(R.string.main_no_application_available_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    /** B12: only files under filesDir/share/export may leave via FileProvider. */
    private fun shareStagingDir(): File =
        File(filesDir, SHARE_EXPORT_REL).also { it.mkdirs() }

    private fun isUnderShareDir(file: File): Boolean {
        val share = try {
            shareStagingDir().canonicalFile
        } catch (_: Exception) {
            shareStagingDir().absoluteFile
        }
        val target = try {
            file.canonicalFile
        } catch (_: Exception) {
            file.absoluteFile
        }
        val sharePath = share.path
        return target.path == sharePath || target.path.startsWith(sharePath + File.separator)
    }

    private fun purgeOldShareExports() {
        val cutoff = System.currentTimeMillis() - SHARE_MAX_AGE_MS
        shareStagingDir().listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
    }

    private fun stageForShare(source: File): File {
        require(source.isFile) { "not a file" }
        if (source.length() > SHARE_MAX_BYTES) {
            throw IllegalArgumentException(
                "File too large to share (${formatFileSize(source.length())}; max ${formatFileSize(SHARE_MAX_BYTES)})"
            )
        }
        purgeOldShareExports()
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(shareStagingDir(), "${System.currentTimeMillis()}_$safeName")
        source.copyTo(dest, overwrite = true)
        return dest
    }

    /**
     * Stages [file] under app-private share/export (proot or chroot host paths),
     * then returns a FileProvider content URI. Never uses root-path or Uri.fromFile.
     */
    private fun getFileUri(file: File): Uri? {
        return try {
            if (!file.isFile) {
                Toast.makeText(this, tr(R.string.main_file_not_found), Toast.LENGTH_SHORT).show()
                return null
            }
            val staged = if (isUnderShareDir(file)) file else stageForShare(file)
            androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                staged
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share file: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups.coerceAtMost(3)])
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    /** Full transcript of a terminal session (scrollback + screen), trimmed. */
    /** Full transcript of a terminal session (scrollback + screen), trimmed. */
    private fun transcriptOf(session: TerminalSession?): String =
        TerminalScrollViewHelper.transcriptOf(session)

    /** COPY on the script-install top bar — works for any script run + desktop log cat. */
    private fun copyScriptInstallTranscript() {
        val t = transcriptOf(scriptInstallSession)
        if (t.isEmpty()) {
            Toast.makeText(this, tr(R.string.term_nothing_to_copy_yet), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(tr(R.string.main_script_log), t))
        Toast.makeText(this, tr(R.string.main_log_copied_chars, t.length), Toast.LENGTH_SHORT).show()
    }

    private fun buildMarkdownViewerCard(file: File): View {
        // Sticky tabs + fill body (preview WebView / source code both scroll inside)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
            setPadding(dp(16), dp(8), dp(16), 0)
            setBackgroundColor(NC.BG)
        }

        var isPreviewMode = true

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }

        val previewTab = TextView(this).apply {
            text = tr(R.string.main_caps_preview)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) }
        }

        val sourceTab = TextView(this).apply {
            text = tr(R.string.main_source)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(4) }
        }

        fun updateTabs() {
            if (isPreviewMode) {
                previewTab.setTextColor(Color.parseColor("#0A0A0A"))
                previewTab.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.PRIMARY_CON)
                }
                sourceTab.setTextColor(Color.parseColor("#FAFAFA"))
                sourceTab.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_HIGH)
                }
            } else {
                sourceTab.setTextColor(Color.parseColor("#0A0A0A"))
                sourceTab.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.PRIMARY_CON)
                }
                previewTab.setTextColor(Color.parseColor("#FAFAFA"))
                previewTab.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_HIGH)
                }
            }
        }
        updateTabs()

        tabBar.addView(previewTab)
        tabBar.addView(sourceTab)
        container.addView(tabBar)

        val bodyHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        val mdContent = try { file.readText(Charsets.UTF_8) } catch (e: Exception) { "" }

        val webView = android.webkit.WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(NC.BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isVerticalScrollBarEnabled = true
            val html = buildObsidianMarkdownHtml(mdContent)
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

        val codeCard = buildCodeViewerCard(file, fillParent = true).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
        }

        previewTab.setOnClickListener {
            if (!isPreviewMode) {
                isPreviewMode = true
                updateTabs()
                webView.visibility = View.VISIBLE
                codeCard.visibility = View.GONE
            }
        }

        sourceTab.setOnClickListener {
            if (isPreviewMode) {
                isPreviewMode = false
                updateTabs()
                webView.visibility = View.GONE
                codeCard.visibility = View.VISIBLE
            }
        }

        bodyHost.addView(webView)
        bodyHost.addView(codeCard)
        container.addView(bodyHost)
        return container
    }

    private fun buildObsidianMarkdownHtml(rawMd: String): String {
        val htmlBody = markdownToHtml(rawMd)
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              body {
                background-color: #131313;
                color: #e5e2e1;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                padding: 16px;
                line-height: 1.6;
                margin: 0;
                word-wrap: break-word;
              }
              h1, h2, h3, h4, h5, h6 {
                color: #60f99e;
                font-family: monospace, sans-serif;
                border-bottom: 1px solid #3c4a3f;
                padding-bottom: 6px;
                margin-top: 24px;
                margin-bottom: 12px;
              }
              h1 { font-size: 1.5em; }
              h2 { font-size: 1.3em; }
              h3 { font-size: 1.1em; }
              a { color: #60f99e; text-decoration: none; font-weight: 500; }
              a:hover { text-decoration: underline; }
              code {
                background-color: #201f1f;
                color: #60f99e;
                font-family: monospace;
                padding: 2px 6px;
                border-radius: 4px;
                font-size: 0.9em;
              }
              pre {
                background-color: #0e0e0e;
                border: 1px solid #3c4a3f;
                padding: 12px;
                border-radius: 4px;
                overflow-x: auto;
                max-width: 100%;
              }
              pre code {
                background: transparent;
                padding: 0;
                color: #e5e2e1;
                font-family: monospace;
              }
              blockquote {
                border-left: 3px solid #60f99e;
                margin: 12px 0;
                padding: 6px 14px;
                color: #bbcbbc;
                background: #1c1b1b;
              }
              ul, ol {
                padding-left: 24px;
                margin: 12px 0;
              }
              li {
                margin-bottom: 6px;
              }
              p {
                margin: 8px 0;
              }
              hr {
                border: none;
                border-top: 1px solid #3c4a3f;
                margin: 20px 0;
              }
              table {
                width: 100%;
                border-collapse: collapse;
                margin: 16px 0;
                background: #1c1b1b;
                overflow-x: auto;
                display: block;
              }
              th, td {
                border: 1px solid #3c4a3f;
                padding: 8px 12px;
                text-align: left;
              }
              th {
                background-color: #201f1f;
                color: #60f99e;
                font-family: monospace;
              }
              del {
                color: #8c8c8c;
              }
            </style>
            </head>
            <body>
              $htmlBody
            </body>
            </html>
        """.trimIndent()
    }

    private fun markdownToHtml(md: String): String {
        val lines = md.lines()
        val out = StringBuilder()
        var inCodeBlock = false
        val codeBlockContent = StringBuilder()
        var inList = false
        var listType = ""
        var inBlockquote = false
        var inTable = false
        val tableLines = mutableListOf<String>()

        fun closeList() {
            if (inList) {
                out.append("</$listType>\n")
                inList = false
                listType = ""
            }
        }

        fun closeBlockquote() {
            if (inBlockquote) {
                out.append("</blockquote>\n")
                inBlockquote = false
            }
        }

        fun flushTable() {
            if (tableLines.size >= 2) {
                out.append("<table>\n")
                var isHeader = true
                for (tLine in tableLines) {
                    val trimmed = tLine.trim()
                    if (trimmed.contains("---")) {
                        isHeader = false
                        continue
                    }
                    val parts = trimmed.split('|').map { it.trim() }
                    val cells = parts.filterIndexed { idx, _ -> idx > 0 && idx < parts.size - 1 }
                    val tag = if (isHeader) "th" else "td"
                    out.append("<tr>")
                    for (cell in cells) {
                        out.append("<$tag>").append(parseInlineMarkdown(cell)).append("</$tag>")
                    }
                    out.append("</tr>\n")
                }
                out.append("</table>\n")
            }
            tableLines.clear()
            inTable = false
        }

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    out.append("<pre><code>")
                        .append(android.text.TextUtils.htmlEncode(codeBlockContent.toString()))
                        .append("</code></pre>\n")
                    inCodeBlock = false
                    codeBlockContent.clear()
                } else {
                    closeList()
                    closeBlockquote()
                    if (inTable) flushTable()
                    inCodeBlock = true
                }
                continue
            }
            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                continue
            }

            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                closeList()
                closeBlockquote()
                inTable = true
                tableLines.add(trimmed)
                continue
            } else if (inTable) {
                flushTable()
            }

            if (trimmed.isEmpty()) {
                closeList()
                closeBlockquote()
                continue
            }

            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                closeList()
                closeBlockquote()
                out.append("<hr>\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                closeList()
                closeBlockquote()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                val text = trimmed.drop(level).trim()
                val id = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                out.append("<h$level id=\"$id\">").append(parseInlineMarkdown(text)).append("</h$level>\n")
                continue
            }

            if (trimmed.startsWith(">")) {
                closeList()
                if (!inBlockquote) {
                    out.append("<blockquote>")
                    inBlockquote = true
                } else {
                    out.append("<br>")
                }
                val text = trimmed.removePrefix(">").trim()
                out.append(parseInlineMarkdown(text))
                continue
            } else {
                closeBlockquote()
            }

            val ulMatch = Regex("""^[-*+]\s+(.*)$""").matchEntire(trimmed)
            if (ulMatch != null) {
                if (!inList || listType != "ul") {
                    closeList()
                    out.append("<ul>\n")
                    inList = true
                    listType = "ul"
                }
                out.append("<li>").append(parseInlineMarkdown(ulMatch.groupValues[1])).append("</li>\n")
                continue
            }

            val olMatch = Regex("""^\d+\.\s+(.*)$""").matchEntire(trimmed)
            if (olMatch != null) {
                if (!inList || listType != "ol") {
                    closeList()
                    out.append("<ol>\n")
                    inList = true
                    listType = "ol"
                }
                out.append("<li>").append(parseInlineMarkdown(olMatch.groupValues[1])).append("</li>\n")
                continue
            }

            closeList()
            out.append("<p>").append(parseInlineMarkdown(trimmed)).append("</p>\n")
        }

        if (inCodeBlock) {
            out.append("<pre><code>")
                .append(android.text.TextUtils.htmlEncode(codeBlockContent.toString()))
                .append("</code></pre>\n")
        }
        closeList()
        closeBlockquote()
        if (inTable) flushTable()

        return out.toString()
    }

    private fun parseInlineMarkdown(raw: String): String {
        var s = android.text.TextUtils.htmlEncode(raw)

        s = s.replace(Regex("""!\[([^\]]*)\]\(([^)]+)\)""")) { m ->
            "<img src=\"${m.groupValues[2]}\" alt=\"${m.groupValues[1]}\" />"
        }
        s = s.replace(Regex("""\[([^\]]+)\]\(([^)]+)\)""")) { m ->
            "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
        }
        s = s.replace(Regex("""\*\*([^*]+)\*\*""")) { "<strong>${it.groupValues[1]}</strong>" }
        s = s.replace(Regex("""__([^_]+)__""")) { "<strong>${it.groupValues[1]}</strong>" }
        s = s.replace(Regex("""\*([^*]+)\*""")) { "<em>${it.groupValues[1]}</em>" }
        s = s.replace(Regex("""_([^_]+)_""")) { "<em>${it.groupValues[1]}</em>" }
        s = s.replace(Regex("""~~([^~]+)~~""")) { "<del>${it.groupValues[1]}</del>" }
        s = s.replace(Regex("""`([^`]+)`""")) { "<code>${it.groupValues[1]}</code>" }

        return s
    }

    private fun showDiffViewer(name: String, backPage: Int = ID_GIT, commit: String? = null) {
        diffViewerBackPage = backPage
        diffViewerCommit = commit
        if (backPage == ID_PROJECT_GIT_DIFF) {
            unifiedHeader.visibility = View.GONE
            projectBottomNavigation.visibility = View.VISIBLE
            bottomNavigation.visibility = View.GONE
            diffViewerTopBar.visibility = View.VISIBLE
            updateProjectSubpageTopBar(diffViewerTopBar)
        } else {
            unifiedHeader.visibility = View.VISIBLE
            projectBottomNavigation.visibility = View.GONE
            bottomNavigation.visibility = View.VISIBLE
            diffViewerTopBar.visibility = View.GONE
        }
        homeScrollView.visibility = View.GONE
        fileExplorerScrollView.visibility = View.GONE
        terminalWorkspaceLayout.visibility = View.GONE
        gitOperationsScrollView.visibility = View.GONE
        settingsHubScrollView.visibility = View.GONE
        if (::fileViewerRootContainer.isInitialized) {
            fileViewerRootContainer.visibility = View.GONE
        }

        if (::projectGitDiffContainer.isInitialized) projectGitDiffContainer.visibility = View.GONE
        if (::projectWorkspaceContainer.isInitialized) projectWorkspaceContainer.visibility = View.GONE
        if (::projectSettingsContainer.isInitialized) projectSettingsContainer.visibility = View.GONE
        if (::projectDirTreeContainer.isInitialized) projectDirTreeContainer.visibility = View.GONE

        diffViewerRootContainer.visibility = View.VISIBLE
        diffViewerScrollView.visibility = View.VISIBLE
        loadDiffForFile(name)
    }

    private val TYPE_CONTEXT = 0
    private val TYPE_DEL     = 1
    private val TYPE_ADD     = 2
    private val TYPE_HUNK    = 3

    private data class ParsedDiffRow(val type: Int, val old: String, val new_: String, val code: String)

    private fun loadDiffForFile(name: String) {
        diffViewerScrollView.scrollTo(0, 0)
        diffViewerContainer.removeAllViews()
        val loadingTv = TextView(this).apply {
            text = tr(R.string.main_loading_diff_for, name)
            setTextColor(NC.ON_SURF_VAR)
            textSize = 14f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        diffViewerContainer.addView(loadingTv)

        val project = activeProjectPath
        val method = projectGitMethod()
        val gen = ++diffRequestGen
        diffRequestName = name
        val commit = diffViewerCommit
        executor.execute {
            fun filterDiff(lines: List<String>): List<String> = lines.filter { line ->
                line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("--- ") || line.startsWith("+++ ") ||
                    line.startsWith("@@") || line.startsWith("+") ||
                    line.startsWith("-") || line.startsWith(" ") ||
                    line.startsWith("\\") || line.startsWith("new file") ||
                    line.startsWith("deleted file") || line.startsWith("Binary")
            }
            fun stale(): Boolean =
                gen != diffRequestGen || project != activeProjectPath || name != diffRequestName
            try {
                val gitCmd = if (commit != null) GitGuestCommands.commitFileDiff(project, commit, name)
                else GitGuestCommands.diffFileAuto(project, name)
                val (lcArgs, lcEnv) = LinuxCommandBuilder.build(this, gitCmd, method = method)
                val env = HashMap(lcEnv).apply { putAll(GitGuestCommands.gitEnv(project)) }
                val (rc, out) = ShellCommandRunner.runCaptureExit(this, lcArgs, env)
                if (rc >= 2) {
                    mainHandler.post {
                        if (stale()) return@post
                        diffViewerContainer.removeAllViews()
                        val errorTv = TextView(this@MainActivity).apply {
                            text = "Error running git diff: ${out.trim().ifEmpty { "exit $rc" }}"
                            setTextColor(NC.ERROR)
                            setPadding(dp(16), dp(16), dp(16), dp(16))
                        }
                        diffViewerContainer.addView(errorTv)
                    }
                    return@execute
                }
                val filteredLines = filterDiff(out.lines())
                mainHandler.post {
                    if (stale()) return@post
                    renderDiffLines(name, filteredLines)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (stale()) return@post
                    diffViewerContainer.removeAllViews()
                    val errorTv = TextView(this@MainActivity).apply {
                        text = "Error running git diff: ${e.message}"
                        setTextColor(NC.ERROR)
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                    }
                    diffViewerContainer.addView(errorTv)
                }
            }
        }
    }

    private fun renderDiffLines(fileName: String, lines: List<String>) {
        diffViewerContainer.removeAllViews()

        if (lines.isEmpty() || (lines.size == 1 && lines[0].trim().isEmpty())) {
            val noChanges = TextView(this).apply {
                text = "No changes detected in $fileName"
                setTextColor(NC.ON_SURF_VAR)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            diffViewerContainer.addView(noChanges)
            return
        }

        val isBinary = lines.any { it.startsWith("Binary files") || it.contains("differ") }
        if (isBinary) {
            val binaryTv = TextView(this).apply {
                text = tr(R.string.main_binary_file_changes_no_preview)
                setTextColor(NC.ON_SURF_VAR)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            diffViewerContainer.addView(binaryTv)
            return
        }

        var additions = 0
        var deletions = 0
        val diffRows = ArrayList<ParsedDiffRow>()

        var oldLineNum = 0
        var newLineNum = 0

        for (line in lines) {
            if (line.startsWith("diff --git") || line.startsWith("index ") || line.startsWith("new file mode") || line.startsWith("deleted file mode")) {
                continue
            }
            if (line.startsWith("--- ") || line.startsWith("+++ ")) {
                continue
            }

            if (line.startsWith("@@")) {
                val parts = line.split(" ")
                if (parts.size >= 3) {
                    val oldPart = parts[1].removePrefix("-").split(",")
                    val newPart = parts[2].removePrefix("+").split(",")
                    oldLineNum = oldPart[0].toIntOrNull() ?: 0
                    newLineNum = newPart[0].toIntOrNull() ?: 0
                }
                diffRows.add(ParsedDiffRow(type = TYPE_HUNK, old = "", new_ = "", code = line))
                continue
            }

            if (line.startsWith("+")) {
                additions++
                diffRows.add(ParsedDiffRow(type = TYPE_ADD, old = "", new_ = newLineNum.toString(), code = line.substring(1)))
                newLineNum++
            } else if (line.startsWith("-")) {
                deletions++
                diffRows.add(ParsedDiffRow(type = TYPE_DEL, old = oldLineNum.toString(), new_ = "", code = line.substring(1)))
                oldLineNum++
            } else {
                if (line.startsWith("\\")) continue
                val code = if (line.startsWith(" ")) line.substring(1) else line
                diffRows.add(ParsedDiffRow(type = TYPE_CONTEXT, old = oldLineNum.toString(), new_ = newLineNum.toString(), code = code))
                oldLineNum++
                newLineNum++
            }
        }

        val diffCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE,
                strokeColor = NC.BORDER,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 8,
                cornerRadiusDp = 0
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val cardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(NC.SURFACE_VAR)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val pathTv = TextView(this).apply {
            text = fileName
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val removedBadge = textBadge("-$deletions", Color.parseColor("#3d1212"), NC.ERROR)
        val addedBadge = textBadge("+$additions", Color.parseColor("#0d2a2a"), NC.SECONDARY)
        removedBadge.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }
        cardHeader.addView(pathTv)
        if (deletions > 0) cardHeader.addView(removedBadge)
        if (additions > 0) cardHeader.addView(addedBadge)
        diffCard.addView(cardHeader)

        val hScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(NC.LOGBG)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        for (row in diffRows) {
            val tableRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), dp(12), dp(3))
            }

            when (row.type) {
                TYPE_HUNK -> {
                    tableRow.setBackgroundColor(Color.argb(30, 255, 255, 255))
                    val hunkTv = TextView(this@MainActivity).apply {
                        text = row.code
                        textSize = 11f
                        setTextColor(NC.OUTLINE)
                        typeface = Typeface.MONOSPACE
                        setPadding(dp(12), dp(2), dp(12), dp(2))
                    }
                    tableRow.addView(hunkTv)
                }
                else -> {
                    val bg = when (row.type) {
                        TYPE_DEL -> Color.argb(50, 147, 0, 10)
                        TYPE_ADD -> Color.argb(50, 3, 181, 211)
                        else -> Color.TRANSPARENT
                    }
                    tableRow.setBackgroundColor(bg)

                    val oldLn = lineNumCell(row.old)
                    val newLn = lineNumCell(row.new_)

                    val marker = when (row.type) {
                        TYPE_DEL -> "-"
                        TYPE_ADD -> "+"
                        else -> " "
                    }
                    val markerColor = when (row.type) {
                        TYPE_DEL -> NC.ERROR
                        TYPE_ADD -> NC.SECONDARY
                        else -> NC.OUTLINE
                    }
                    val codeColor = when (row.type) {
                        TYPE_DEL -> NC.ON_SURF_VAR
                        else -> NC.ON_SURFACE
                    }

                    val markerTv = TextView(this@MainActivity).apply {
                        text = marker
                        textSize = 12f
                        setTextColor(markerColor)
                        typeface = Typeface.MONOSPACE
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(24), WRAP)
                    }
                    val codeTv = TextView(this@MainActivity).apply {
                        text = row.code
                        textSize = 12f
                        setTextColor(codeColor)
                        typeface = Typeface.MONOSPACE
                        if (row.type == TYPE_DEL) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    }

                    tableRow.addView(oldLn)
                    tableRow.addView(newLn)
                    tableRow.addView(markerTv)
                    tableRow.addView(codeTv)
                }
            }
            table.addView(tableRow)
        }

        hScroll.addView(table)
        diffCard.addView(hScroll)
        diffViewerContainer.addView(diffCard)

        diffViewerContainer.addView(spacer(16))
        // Read-only when viewing a commit from history: Discard/Commit act on the working tree.
        if (diffViewerCommit != null) return
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(16))
        }
        val discardBtn = outlineBtn(tr(R.string.main_discard))
        discardBtn.setOnClickListener {
            discardChanges(fileName)
        }
        val commitBtn = primaryBtn(tr(R.string.main_commit_changes))
        commitBtn.setOnClickListener {
            showCommitDialog(fileName)
        }
        discardBtn.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(12) }
        bottomBar.addView(discardBtn)
        bottomBar.addView(commitBtn)
        diffViewerContainer.addView(bottomBar)
    }

    private fun discardChanges(fileName: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr(R.string.main_discard_changes))
            .setMessage(tr(R.string.main_are_you_sure_you_want, fileName))
            .setPositiveButton(tr(R.string.main_discard)) { _, _ ->
                val project = activeProjectPath
                val method = projectGitMethod()
                val name = fileName
                executor.execute {
                    fun git(args: String): Pair<Int, String> {
                        val cmd = "cd ${GitGuestCommands.shellQuote(project)} && ${GitGuestCommands.gitCommand(project, args)}"
                        val (lcArgs, lcEnv) = LinuxCommandBuilder.build(this, cmd, method = method)
                        val env = HashMap(lcEnv).apply { putAll(GitGuestCommands.gitEnv(project)) }
                        return ShellCommandRunner.runCaptureExit(this, lcArgs, env)
                    }
                    try {
                        val path = GitGuestCommands.shellQuote(name)
                        val spec = GitGuestCommands.shellQuote(":(literal)$name")
                        val (trackedExit, trackedOut) = git("cat-file -e HEAD:$path")
                        val action = when (trackedExit) {
                            0 -> "checkout HEAD -- $spec"
                            else -> {
                                val (untrackedExit, untrackedOut) = git("ls-files --others -- $spec")
                                when {
                                    untrackedExit != 0 -> {
                                        mainHandler.post {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Failed to discard: ${untrackedOut.trim().take(120)}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        return@execute
                                    }
                                    untrackedOut.lineSequence().any { it == name } -> "clean -f -- $spec"
                                    else -> {
                                        mainHandler.post {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Failed to discard: ${trackedOut.trim().take(120).ifEmpty { "not a tracked or untracked file" }}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        return@execute
                                    }
                                }
                            }
                        }
                        val (exit, out) = git(action)
                        mainHandler.post {
                            if (exit == 0) {
                                onBackPressed()
                                refreshGitDiffTree()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Failed to discard: ${out.trim().take(120)}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            Toast.makeText(this@MainActivity, "Failed to discard: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(tr(R.string.git_cancel), null)
            .show()
    }

    private fun showCommitDialog(fileName: String) {
        val input = EditText(this).apply {
            hint = tr(R.string.main_commit_message)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val container = FrameLayout(this).apply {
            addView(input, FrameLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr(R.string.main_commit_changes_2))
            .setView(container)
            .setPositiveButton(tr(R.string.main_commit)) { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isEmpty()) {
                    Toast.makeText(this, tr(R.string.main_commit_message_cannot_be_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val project = activeProjectPath
                val method = projectGitMethod()
                val name = fileName
                executor.execute {
                    fun git(args: String): Pair<Int, String> {
                        val cmd = "cd ${GitGuestCommands.shellQuote(project)} && ${GitGuestCommands.gitCommand(project, args)}"
                        val (lcArgs, lcEnv) = LinuxCommandBuilder.build(this, cmd, method = method)
                        val env = HashMap(lcEnv).apply { putAll(GitGuestCommands.gitEnv(project)) }
                        return ShellCommandRunner.runCaptureExit(this, lcArgs, env)
                    }
                    try {
                        val spec = GitGuestCommands.shellQuote(":(literal)$name")
                        val quotedMsg = GitGuestCommands.shellQuote(msg)
                        val (addExit, addOut) = git("add -- $spec")
                        if (addExit != 0) {
                            mainHandler.post {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Commit failed: ${addOut.trim().take(120)}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@execute
                        }
                        val (exit, out) = git("commit -m $quotedMsg")
                        mainHandler.post {
                            if (exit == 0) {
                                Toast.makeText(this@MainActivity, tr(R.string.main_committed_successfully), Toast.LENGTH_SHORT).show()
                                onBackPressed()
                                refreshGitDiffTree()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Commit failed: ${out.trim().take(120)}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            Toast.makeText(this@MainActivity, "Commit failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(tr(R.string.git_cancel), null)
            .show()
    }

    // ── Shared widget builders (ported from old activities) ─────────────────────

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val cardTitle = TextView(this).apply {
            text = tr(R.string.main_environment_status); textSize = 18f; setTextColor(NC.PRIMARY); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        titleRow.addView(cardTitle); card.addView(titleRow)

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(NC.LOGBG, NC.BORDER, dp(6))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) }
        }
        homeStatusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) }
            background = circleDrawable(NC.SECONDARY)
        }
        homeStatusLabel = TextView(this).apply { text = tr(R.string.main_ready); textSize = 13f; setTextColor(NC.ON_SURFACE); typeface = Typeface.MONOSPACE }
        pill.addView(homeStatusDot); pill.addView(homeStatusLabel); card.addView(pill)

        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dnsIcon = ImageView(this).apply { setImageResource(R.drawable.ic_dns_thick); setColorFilter(NC.PRIMARY); layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) } }
        homeContainerLabel = TextView(this).apply { text = ProjectPathResolver.methodLabel(); textSize = 13f; setTextColor(NC.ON_SURF_VAR); typeface = Typeface.MONOSPACE }
        infoRow.addView(dnsIcon); infoRow.addView(homeContainerLabel); card.addView(infoRow)

        pulseView(homeStatusDot)
        return card
    }

    private fun buildGuiLaunchCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val sectionTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val sectionTitleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_laptop_thick)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
        }
        val sectionTitle = TextView(this).apply {
            text = tr(R.string.main_graphical_desktop)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        sectionTitleRow.addView(sectionTitleIcon)
        sectionTitleRow.addView(sectionTitle)
        val methodNow = getSharedPreferences("nativecode_prefs", MODE_PRIVATE).getString("linux_method", "proot") ?: "proot"
        val xfceReady = isXfceInstalled()
        val sectionSub = TextView(this).apply {
            text = when {
                !xfceReady -> tr(R.string.main_desktop_runtime_unavailable_reinstal)
                methodNow == "chroot" -> "Termux X11 + Phosh Mobile Shell inside Alpaquita (root)."
                else -> tr(R.string.main_termux_x11_phosh_mobile_shell)
            }
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(14))
        }
        card.addView(sectionTitleRow)
        card.addView(sectionSub)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        startGuiBtn = TextView(this).apply {
            text = tr(R.string.main_caps_start_phosh_desktop)
            textSize = 14f
            setTextColor(NC.ON_PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play_thick, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(NC.ON_PRIMARY))
            background = cyberBrutalistBg(
                fillColor = NC.PRIMARY_CON,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = Color.parseColor("#49E48F"),
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.PRIMARY_CON,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }

            // Does not flip to STOP yet — waits for first start log line (healthy start)
            setOnClickListener { desktop.start() }
        }
        startGuiBtn.isEnabled = false
        startGuiBtn.alpha = 0.5f
        startGuiBtn.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)

        stopGuiBtn = dangerButton(tr(R.string.main_stop_phosh_desktop)) { desktop.stop() }.apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop_thick, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Color.WHITE))
        }
        stopGuiBtn.isEnabled = false
        stopGuiBtn.alpha = 0.5f
        stopGuiBtn.visibility = View.GONE
        stopGuiBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)

        row.addView(startGuiBtn)
        row.addView(stopGuiBtn)
        card.addView(row)
        return card
    }

    private fun buildResourcesCard(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        // 1. APP STORAGE Used Card
        val storageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 6
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }

        // Header Row: APP STORAGE Used & analytics icon
        val storageHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val storageTitle = TextView(this).apply {
            text = tr(R.string.main_app_storage_used)
            textSize = 12f
            setTextColor(Color.parseColor("#99E5E2E1"))
            typeface = Typeface.MONOSPACE
        }
        val titleUnderline = View(this).apply {
            setBackgroundColor(Color.parseColor("#4D60F99E"))
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(1)).apply { topMargin = dp(2) }
        }
        titleCol.addView(storageTitle)
        titleCol.addView(titleUnderline)

        val storageIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_storage)
            imageTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        storageHeaderRow.addView(titleCol)
        storageHeaderRow.addView(storageIcon)
        storageCard.addView(storageHeaderRow)

        // Storage Value & Badge Row
        val valRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }
        val storageValTv = TextView(this).apply {
            tag = "APP_STORAGE_VAL"
            text = "84.2"
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(6), 0)
        }
        val storageUnitTv = TextView(this).apply {
            tag = "APP_STORAGE_SUB"
            text = "GB"
            textSize = 18f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        val versionBadge = TextView(this).apply {
            text = "STABLE_V1"
            textSize = 10f
            setTextColor(NC.SURFACE_LOWEST)
            typeface = Typeface.MONOSPACE
            background = cyberBrutalistBg(NC.PRIMARY, Color.parseColor("#3c4a3f"), NC.SHADOW_GREEN, 6, 0)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val valRowLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(storageValTv)
            addView(storageUnitTv)
        }
        valRow.addView(valRowLeft)
        valRow.addView(versionBadge)
        storageCard.addView(valRow)

        // Gauge Progress Bar (Height 32dp)
        val gaugeFrame = FrameLayout(this).apply {
            background = cyberBrutalistBg(NC.SURFACE_LOWEST, Color.parseColor("#1AFFFFFF"), NC.SHADOW_DARK, 6, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(32))
        }
        val barFill = View(this).apply {
            tag = "APP_STORAGE_BAR"
            background = roundedBg(NC.PRIMARY, NC.PRIMARY, 0)
            layoutParams = FrameLayout.LayoutParams(dp(200), MATCH)
        }
        gaugeFrame.addView(barFill)

        // Overlay gauge ticks (vertical tick lines)
        val ticksLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 10f
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setPadding(dp(8), 0, dp(8), 0)
        }
        for (i in 0..9) {
            val tick = View(this).apply {
                setBackgroundColor(Color.parseColor("#26FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(dp(1), MATCH)
            }
            val spacerView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            }
            ticksLayout.addView(tick)
            if (i < 9) ticksLayout.addView(spacerView)
        }
        gaugeFrame.addView(ticksLayout)
        storageCard.addView(gaugeFrame)

        // Ticks footer labels row
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        }
        val label0 = TextView(this).apply {
            text = tr(R.string.main_0_load)
            textSize = 10f
            setTextColor(Color.parseColor("#66FFFFFF"))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val label80 = TextView(this).apply {
            text = "CRITICAL_80%"
            textSize = 10f
            setTextColor(Color.parseColor("#66FFFFFF"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val label100 = TextView(this).apply {
            text = tr(R.string.main_100_max)
            textSize = 10f
            setTextColor(Color.parseColor("#66FFFFFF"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        footerRow.addView(label0)
        footerRow.addView(label80)
        footerRow.addView(label100)
        storageCard.addView(footerRow)

        container.addView(storageCard)

        // 2. STAT ROW (CPU, RAM, DISK - 3 columns grid)
        val statRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        fun buildStatCard(labelStr: String, defaultVal: String, fillPercent: Int, accentColor: Int, isLast: Boolean): Pair<View, TextView> {
            val card = RelativeLayout(this).apply {
                background = cyberBrutalistBg(NC.SURFACE_LOW, Color.parseColor("#3c4a3f"), NC.SHADOW_GREEN, 6, 0)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                val lp = LinearLayout.LayoutParams(0, dp(112), 1f)
                if (!isLast) lp.rightMargin = dp(8)
                layoutParams = lp
            }

            // Top-right corner indicator block
            val cornerBlock = View(this).apply {
                setBackgroundColor(accentColor)
                val rlp = RelativeLayout.LayoutParams(dp(10), dp(10)).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                }
                layoutParams = rlp
            }
            card.addView(cornerBlock)

            // Content container
            val contentCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val rlp = RelativeLayout.LayoutParams(MATCH, WRAP).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                }
                layoutParams = rlp
            }

            val labelTv = TextView(this).apply {
                text = labelStr
                textSize = 11f
                setTextColor(Color.parseColor("#80FFFFFF"))
                typeface = Typeface.MONOSPACE
            }
            val valueTv = TextView(this).apply {
                text = defaultVal
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, 0)
            }
            contentCol.addView(labelTv)
            contentCol.addView(valueTv)
            card.addView(contentCol)

            // Bottom progress bar
            val barTrack = FrameLayout(this).apply {
                setBackgroundColor(NC.SURFACE_LOWEST)
                val rlp = RelativeLayout.LayoutParams(MATCH, dp(4)).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                }
                layoutParams = rlp
            }
            val fillBar = View(this).apply {
                setBackgroundColor(accentColor)
                layoutParams = FrameLayout.LayoutParams((fillPercent * dp(80) / 100).coerceAtLeast(dp(6)), MATCH)
            }
            barTrack.addView(fillBar)
            card.addView(barTrack)

            return Pair(card, valueTv)
        }

        val (cpuCard, cpuTv) = buildStatCard("CPU", "12%", 12, NC.PRIMARY, false)
        val (ramCard, ramTv) = buildStatCard("RAM", "4.1G", 45, Color.parseColor("#80FFFFFF"), false)
        val (diskCard, diskTv) = buildStatCard(tr(R.string.main_disk), "94%", 94, NC.ERROR, true)

        statRow.addView(cpuCard)
        statRow.addView(ramCard)
        statRow.addView(diskCard)

        container.addView(statRow)

        startResourceMonitoring(cpuTv, ramTv, diskTv)
        updateAppStorageUsage(storageValTv, storageUnitTv, barFill, null)

        return container
    }

    /**
     * Home package ops: Software Manager.
     * Full width module tile with accent rails (ui_design cyber-brutalist).
     */
    private fun buildHomePackageOpsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val sectionTitle = TextView(this).apply {
            text = tr(R.string.main_caps_package_ops)
            textSize = 13f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        headerRow.addView(sectionTitle)
        section.addView(headerRow)

        val softwareCard = buildHomePackageModuleCard(
            accentFill = false,
            iconRes = R.drawable.ic_extension,
            kicker = tr(R.string.main_package_scan),
            title = tr(R.string.main_software_manager),
            subtitle = tr(R.string.main_installed_pkgs_by_category_size),
            footer = tr(R.string.main_browse_remove),
            pageId = ID_SOFTWARE_MANAGER
        ).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        section.addView(softwareCard)
        return section
    }

    /** Home: AI usage summary and the SSH entry (server status). */
    private fun buildHomeToolsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        section.addView(TextView(this).apply {
            text = tr(R.string.main_caps_ai_usage)
            textSize = 13f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(12))
        })
        section.addView(buildHomePackageModuleCard(
            accentFill = false,
            iconRes = R.drawable.ic_lucide_chart,
            kicker = tr(R.string.usage_usage),
            title = tr(R.string.main_ai_usage),
            subtitle = tr(R.string.main_tokens_and_cost_per_ai_2),
            footer = tr(R.string.main_day_week_month_per_cli),
            pageId = ID_USAGE,
            onSubtitle = { tv ->
                homeUsageSubtitle = tv
                tv.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)   // two lines (usage, limit) plus the logo row must all show
                (tv.parent as? LinearLayout)?.let { p ->
                    val row = LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) } }
                    p.addView(row, p.indexOfChild(tv) + 1)
                    homeUsageLogos = row
                }
                refreshHomeUsage()
            }
        ).apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) } })
        section.addView(TextView(this).apply {
            text = tr(R.string.main_caps_remote_access)
            textSize = 13f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(12))
        })
        section.addView(buildHomePackageModuleCard(
            accentFill = false,
            iconRes = R.drawable.ic_lucide_server,
            kicker = "SSH",
            title = tr(R.string.main_ssh_server_and_remote_hosts),
            subtitle = tr(R.string.main_log_in_to_this_phone),
            footer = tr(if (BuildConfig.MONETISATION_ENFORCE) R.string.main_server_pro_client_keys else R.string.fd_ssh_footer),
            pageId = ID_SSH,
            onSubtitle = { tv ->
                homeSshSubtitle = tv
                refreshHomeSsh()
            }
        ).apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP) })
        return section
    }

    private var homeSshSubtitle: TextView? = null

    private fun refreshHomeSsh() {
        val tv = homeSshSubtitle ?: return
        val st = com.zenithblue.nativecode.ssh.SshServerController.status
        tv.text = when (st.state) {
            com.zenithblue.nativecode.ssh.SshState.RUNNING -> "Server running: " + com.zenithblue.nativecode.ssh.SshModel.connectCommand(st.address ?: "?")
            com.zenithblue.nativecode.ssh.SshState.STARTING -> tr(R.string.main_server_starting)
            else -> tr(R.string.main_log_in_to_this_phone)
        }
    }

    private fun buildHomePackageModuleCard(
        accentFill: Boolean,
        iconRes: Int,
        kicker: String,
        title: String,
        subtitle: String,
        footer: String,
        pageId: Int,
        onSubtitle: ((TextView) -> Unit)? = null
    ): View {
        val fill = if (accentFill) Color.parseColor("#0F1A14") else NC.SURFACE_LOW
        val stroke = if (accentFill) NC.PRIMARY_CON else Color.parseColor("#3c4a3f")
        val shadow = if (accentFill) NC.SHADOW_GREEN else NC.SHADOW_DARK
        val railColor = if (accentFill) NC.PRIMARY_CON else NC.TERTIARY

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = fill,
                strokeColor = stroke,
                shadowColor = shadow,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            // Leave room for extrusion
            setPadding(0, 0, dp(6), dp(6))
            minimumHeight = dp(168)
            isClickable = true
            isFocusable = true

            // Accent rail + body
            val body = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
            }

            val topRail = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(4))
                setBackgroundColor(railColor)
            }
            body.addView(topRail)

            val content = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
            }

            val kickerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            }
            val iconBox = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (accentFill) Color.parseColor("#1A3D2A") else NC.SURFACE_LOWEST)
                    setStroke(dp(1), if (accentFill) Color.parseColor("#4060F99E") else Color.parseColor("#33FFB175"))
                }
            }
            iconBox.addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(if (accentFill) NC.PRIMARY else NC.TERTIARY)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
            val kickerTv = TextView(this@MainActivity).apply {
                text = kicker
                textSize = 10f
                setTextColor(if (accentFill) NC.PRIMARY else NC.TERTIARY)
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
            }
            kickerRow.addView(iconBox)
            kickerRow.addView(kickerTv)
            content.addView(kickerRow)

            val titleTv = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(Color.parseColor("#FAFAFA"))
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.05f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
            }
            content.addView(titleTv)

            val subTv = TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            content.addView(subTv)
            onSubtitle?.invoke(subTv)

            val footerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
            }
            val footerTv = TextView(this@MainActivity).apply {
                text = footer
                textSize = 9f
                setTextColor(if (accentFill) Color.parseColor("#80FAFAFA") else Color.parseColor("#66869587"))
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val chevron = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(if (accentFill) NC.PRIMARY else NC.TERTIARY)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            }
            footerRow.addView(footerTv)
            footerRow.addView(chevron)
            content.addView(footerRow)

            body.addView(content)
            addView(body)

            fun restBg() = cyberBrutalistBg(
                fillColor = fill,
                strokeColor = stroke,
                shadowColor = shadow,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            fun pressBg() = cyberBrutalistBg(
                fillColor = fill,
                strokeColor = stroke,
                shadowColor = shadow,
                offsetDp = 2,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = pressBg()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = restBg()
                    }
                }
                false
            }
            setOnClickListener {
                if (pageId == ID_MARKETPLACE) {
                    openMarketplace()
                    return@setOnClickListener
                }
                if (pageStack.isEmpty() || pageStack.peek() != pageId) {
                    pageStack.push(pageId)
                }
                navigateToPage(pageId)
            }
        }
    }

    private fun buildRecentProjectsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val sectionTitle = TextView(this).apply {
            text = tr(R.string.main_recent_workspaces)
            textSize = 13f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val viewAll = TextView(this).apply {
            text = tr(R.string.main_view_all)
            textSize = 12f
            setTextColor(Color.parseColor("#80FFFFFF"))
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setOnClickListener {
                if (pageStack.isEmpty() || pageStack.peek() != ID_PROJECTS_LIST) {
                    pageStack.push(ID_PROJECTS_LIST)
                }
                navigateToPage(ID_PROJECTS_LIST)
            }
        }
        headerRow.addView(sectionTitle)
        headerRow.addView(viewAll)
        section.addView(headerRow)

        recentProjectsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(recentProjectsContainer)
        populateRecentProjects()

        return section
    }

    private fun populateRecentProjects() {
        if (!::recentProjectsContainer.isInitialized) return
        recentProjectsContainer.removeAllViews()
        val projects = getProjects().sortedByDescending { it.lastOpened }
        if (projects.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = tr(R.string.main_no_recent_projects_found)
                textSize = 13f
                setTextColor(NC.ON_SURF_VAR)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                typeface = Typeface.MONOSPACE
            }
            recentProjectsContainer.addView(emptyTv)
            return
        }
        val dateFormat = java.text.SimpleDateFormat("MM.dd.yy", java.util.Locale.US)
        for (p in projects.take(5)) {
            val dateStr = if (p.lastOpened > 0) dateFormat.format(java.util.Date(p.lastOpened)) else "recent"
            val card = projectCard(p.name, p.path, dateStr, p.icon)
            card.setOnClickListener {
                if (!projectGateAllows()) return@setOnClickListener
                markProjectOpened(p.path)
                activeProjectName = p.name
                activeProjectPath = p.path
                applyProjectIsolation(p.path, p.linuxMethod)
                if (pageStack.isEmpty() || pageStack.peek() != ID_PROJECT_WORKSPACE) {
                    pageStack.push(ID_PROJECT_WORKSPACE)
                }
                navigateToPage(ID_PROJECT_WORKSPACE)
            }
            recentProjectsContainer.addView(card)
            recentProjectsContainer.addView(spacer(10))
        }
    }

    private fun buildAuthCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = roundedBg(NC.SURFACE, NC.BORDER, dp(12)); setPadding(dp(16))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_laptop_thick); setColorFilter(NC.PRIMARY); layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) } }
        val title = TextView(this).apply { text = tr(R.string.main_terminal_authentication); textSize = 16f; setTextColor(NC.ON_SURFACE); typeface = Typeface.DEFAULT_BOLD }
        header.addView(icon); header.addView(title); card.addView(header)

        val sub = TextView(this).apply { text = tr(R.string.main_authenticate_using_the_github_cli); textSize = 13f; setTextColor(NC.ON_SURF_VAR); setPadding(0, 0, 0, dp(12)) }
        card.addView(sub)

        val termBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(NC.LOGBG); background = roundedBg(NC.LOGBG, NC.BORDER, dp(8)); setPadding(dp(12))
        }
        val dotsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        for (col in listOf(NC.ERROR, NC.TERTIARY, NC.SECONDARY)) {
            val dot = LinearLayout(this).apply { background = circleDrawable(col); layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(6) } }
            dotsRow.addView(dot)
        }
        termBlock.addView(dotsRow)
        for ((prompt, line) in listOf(
            "\$ " to "gh auth login",
            "? " to tr(R.string.main_what_account_do_you_want),
            "? " to tr(R.string.main_preferred_protocol_for_git_operation),
            "! " to tr(R.string.main_first_copy_your_one_time)
        )) {
            val tv = TextView(this).apply { text = "$prompt$line"; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(if (prompt == "! ") NC.PRIMARY else NC.ON_SURF_VAR) }
            termBlock.addView(tv)
        }
        card.addView(termBlock)
        card.addView(spacer(12))

        val codeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val codeLbl = TextView(this).apply { text = "Code: "; textSize = 13f; setTextColor(NC.ON_SURF_VAR); typeface = Typeface.MONOSPACE }
        val codeVal = textBadge("ABCD-1234", NC.SURFACE_HIGH, NC.ON_SURFACE)
        val spacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val openBtn = primaryButton(tr(R.string.main_open_browser_2)) {}
        openBtn.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        codeRow.addView(codeLbl); codeRow.addView(codeVal); codeRow.addView(spacer2); codeRow.addView(openBtn)
        card.addView(codeRow)
        return card
    }

    private fun buildBranchCard(): LinearLayout {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundedBg(NC.SURFACE, NC.BORDER, dp(12)); setPadding(dp(16)) }
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_git_thick); setColorFilter(NC.PRIMARY); layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) } }
        val title = TextView(this).apply { text = tr(R.string.main_active_branches); textSize = 16f; setTextColor(NC.ON_SURFACE); typeface = Typeface.DEFAULT_BOLD; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
        val fetchBtn = TextView(this).apply {
            text = " git fetch"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_refresh, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(NC.ON_SURF_VAR)
            background = roundedBg(NC.SURFACE_HIGH, NC.BORDER, dp(4))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        headerRow.addView(icon); headerRow.addView(title); headerRow.addView(fetchBtn); card.addView(headerRow)

        val treeBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundedBg(NC.LOGBG, NC.BORDER, dp(8)); setPadding(dp(12)) }
        for ((prefix, branch, isHead) in listOf(Triple("├── ", "main", false), Triple("└── ", "feature/auth", true))) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            val preTv = TextView(this).apply { text = prefix; textSize = 12f; setTextColor(NC.OUTLINE); typeface = Typeface.MONOSPACE }
            val nameTv = TextView(this).apply { text = branch; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(if (isHead) NC.PRIMARY else NC.ON_SURF_VAR); if (isHead) typeface = Typeface.DEFAULT_BOLD }
            row.addView(preTv); row.addView(nameTv)
            if (isHead) {
                row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
                row.addView(textBadge("HEAD", Color.argb(51, 210, 187, 255), NC.PRIMARY))
            }
            treeBlock.addView(row)
        }
        card.addView(treeBlock)
        return card
    }

    private fun buildGitAuthInputCard(): LinearLayout {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundedBg(NC.SURFACE, NC.BORDER, dp(12)); setPadding(dp(16)) }
        card.addView(inputField(tr(R.string.main_github_username_token), "ghp_xxxxxxxxxxxxxxxxxxxx"))
        card.addView(spacer(12))
        card.addView(inputField(tr(R.string.main_repository_url_or_name), "owner/repo"))
        return card
    }

    private fun buildEnvironmentCard(): LinearLayout {
        if (com.zenithblue.nativecode.ProotOnly.ON) return buildPlayEnvironmentCard()
        val card = glassCard()
        card.addView(sectionHeader(R.drawable.ic_laptop_thick, tr(R.string.main_linux_isolation_mode), NC.PRIMARY))

        val descTv = TextView(this).apply {
            text = tr(R.string.main_select_execution_engine_for_linux)
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(descTv)

        val modeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val prootCard = LinearLayout(this)
        val chrootCard = LinearLayout(this)

        fun updateCardStyles() {
            val isProot = LinuxCommandBuilder.currentMethod == "proot"
            prootCard.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cyberBrutalistBg(
                    fillColor = if (isProot) NC.SURFACE_CONTAINER else NC.SURFACE_LOWEST,
                    strokeColor = if (isProot) NC.PRIMARY else NC.OUTLINE_VAR,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0
                )
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
                isClickable = true
                isFocusable = true
            }

            chrootCard.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cyberBrutalistBg(
                    fillColor = if (!isProot) NC.SURFACE_CONTAINER else NC.SURFACE_LOWEST,
                    strokeColor = if (!isProot) NC.SECONDARY else NC.OUTLINE_VAR,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0
                )
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                isClickable = true
                isFocusable = true
            }
        }

        // Build PRoot Option Row
        val prootLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val prootTitle = TextView(this).apply {
            text = tr(R.string.main_proot_rootless)
            textSize = 14f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val prootSub = TextView(this).apply {
            text = tr(R.string.main_user_space_isolation_no_root)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
        }
        prootLeft.addView(prootTitle)
        prootLeft.addView(prootSub)
        prootCard.addView(prootLeft)

        // Build Chroot Option Row
        val chrootLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val chrootTitle = TextView(this).apply {
            text = tr(R.string.main_chroot_root_required)
            textSize = 14f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val chrootSub = TextView(this).apply {
            text = if (ProjectPathResolver.isChrootInstalled()) tr(R.string.main_installed_ready_kernel_level_speed) else tr(R.string.main_requires_root_linux_chroot_setup)
            textSize = 11f
            setTextColor(if (ProjectPathResolver.isChrootInstalled()) NC.PRIMARY else NC.SECONDARY)
        }
        chrootLeft.addView(chrootTitle)
        chrootLeft.addView(chrootSub)
        chrootCard.addView(chrootLeft)

        updateCardStyles()

        prootCard.setOnClickListener {
            LinuxCommandBuilder.currentMethod = "proot"
            getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                .edit().putString("linux_method", "proot").apply()
            updateCardStyles()
            if (::homeContainerLabel.isInitialized) homeContainerLabel.text = ProjectPathResolver.methodLabel()
            refreshToolCardsForMethod()
            refreshSettingsGithubCard()
            Toast.makeText(this, tr(R.string.main_switched_to_proot_mode), Toast.LENGTH_SHORT).show()
        }

        chrootCard.setOnClickListener {
            executor.execute {
                val rootAvailable = RootShell.isRootAvailable()
                mainHandler.post {
                    if (!rootAvailable) {
                        Toast.makeText(this, tr(R.string.main_root_access_was_not_granted), Toast.LENGTH_LONG).show()
                    } else if (!ProjectPathResolver.isChrootInstalled()) {
                        Toast.makeText(this, tr(R.string.main_root_detected_please_run_chroot), Toast.LENGTH_LONG).show()
                        LinuxCommandBuilder.currentMethod = "chroot"
                        getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                            .edit().putString("linux_method", "chroot").apply()
                        updateCardStyles()
                        if (::homeContainerLabel.isInitialized) homeContainerLabel.text = ProjectPathResolver.methodLabel()
                        refreshToolCardsForMethod()
                        refreshSettingsGithubCard()
                    } else {
                        LinuxCommandBuilder.currentMethod = "chroot"
                        getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                            .edit().putString("linux_method", "chroot").apply()
                        updateCardStyles()
                        if (::homeContainerLabel.isInitialized) homeContainerLabel.text = ProjectPathResolver.methodLabel()
                        refreshToolCardsForMethod()
                        refreshSettingsGithubCard()
                        Toast.makeText(this, tr(R.string.main_switched_to_chroot_mode), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        modeContainer.addView(prootCard)
        modeContainer.addView(chrootCard)
        card.addView(modeContainer)

        card.addView(spacer(12))
        card.addView(infoRow(tr(R.string.main_os_version), tr(R.string.onb_alpaquita_linux_glibc_aarch64)))
        return card
    }

    private fun buildPlayEnvironmentCard(): LinearLayout = glassCard().apply {
        addView(sectionHeader(R.drawable.ic_laptop_thick, tr(R.string.main_linux_environment), NC.PRIMARY))
        addView(TextView(this@MainActivity).apply {
            text = tr(R.string.main_alpaquita_linux_runs_in_isolated)
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            setPadding(0, 0, 0, dp(12))
        })
        addView(infoRow(tr(R.string.main_execution_mode), tr(R.string.main_proot_rootless_2)))
        addView(infoRow(tr(R.string.main_os_version), tr(R.string.onb_alpaquita_linux_glibc_aarch64)))
    }

    /**
     * Dedicated Proot Settings page (Settings Hub → this).
     * Size-only: Debian proot rootfs under app filesDir + REFRESH + path.
     */
    private fun buildProotSettingsPage() {
        prootSettingsScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val pageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        prootSettingsScrollView.addView(pageLayout)

        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            setOnClickListener {
                navigateBackFromSubpage(ID_PROOT_SETTINGS)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val headerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_proot_debian)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) }
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_storage_settings)
            textSize = 22f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(headerIcon)
        headerTitleRow.addView(titleTv)
        val subTitleTv = TextView(this).apply {
            text = "// ALPAQUITA LINUX — APP STORAGE"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(8) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        headerCol.addView(headerTitleRow)
        headerCol.addView(subTitleTv)
        headerCol.addView(divider)
        pageLayout.addView(headerCol)
        pageLayout.addView(buildProotSettingsContentCard())
    }

    /** Proot detail card — rootfs size + host path only (no install/root). */
    private fun buildProotSettingsContentCard(): LinearLayout {
        val card = glassCard()
        card.addView(sectionHeader(R.drawable.ic_storage, tr(R.string.cli_storage), NC.PRIMARY))

        val subTv = TextView(this).apply {
            text = "// ALPAQUITA ROOTFS — MEASURED IN-APP"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(subTv)

        val sizePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }
        val sizeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizeTitle = TextView(this).apply {
            text = tr(R.string.main_linux_storage)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        prootRefreshBtn = TextView(this).apply {
            text = " " + tr(R.string.main_caps_refresh)
            textSize = 10f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_refresh, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(8), dp(4), dp(10), dp(4))
            setOnClickListener { refreshProotSettingsCard(force = true) }
        }
        sizeHeader.addView(sizeTitle)
        sizeHeader.addView(prootRefreshBtn)
        sizePanel.addView(sizeHeader)

        prootLoadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        prootProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
        }
        val loadingLabel = TextView(this).apply {
            text = " " + tr(R.string.main_caps_scanning)
            textSize = 10f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), 0, 0, 0)
        }
        prootLoadingRow?.addView(prootProgressBar)
        prootLoadingRow?.addView(loadingLabel)
        sizePanel.addView(prootLoadingRow)

        val sizeValRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(8), 0, 0)
        }
        prootSizeValueTv = TextView(this).apply {
            text = "—"
            textSize = 32f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        prootSizeUnitTv = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, 0, dp(6))
        }
        sizeValRow.addView(prootSizeValueTv)
        sizeValRow.addView(prootSizeUnitTv)
        sizePanel.addView(sizeValRow)

        prootSizeHintTv = TextView(this).apply {
            text = "…"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        }
        sizePanel.addView(prootSizeHintTv)
        card.addView(sizePanel)

        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
        }
        pathRow.addView(TextView(this).apply {
            text = tr(R.string.main_host_path)
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(4))
        })
        prootPathTv = TextView(this).apply {
            text = ProjectPathResolver.prootRootfsDir(this@MainActivity).absolutePath
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        pathRow.addView(prootPathTv)
        card.addView(pathRow)

        val browseFilesBtn = primaryButton(tr(R.string.main_browse_home_files)) {
            if (pageStack.isEmpty() || pageStack.peek() != ID_PROOT_FILES) {
                pageStack.push(ID_PROOT_FILES)
            }
            navigateToPage(ID_PROOT_FILES)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(12)
            }
        }
        card.addView(browseFilesBtn)

        applyCachedProotInfo()
        refreshProotSettingsCard(force = false)
        return card
    }

    private fun applyCachedProotInfo() {
        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        if (!prefs.contains(PREF_PROOT_LAST_MS)) return

        val bytes = prefs.getLong(PREF_PROOT_BYTES, -1L).takeIf { it >= 0L }
        val dirOk = prefs.getBoolean(PREF_PROOT_DIR, false)
        val lastMs = prefs.getLong(PREF_PROOT_LAST_MS, 0L)

        if (bytes != null) {
            val (v, u) = formatStorageBytes(bytes)
            prootSizeValueTv?.text = v
            prootSizeUnitTv?.text = u
            prootSizeValueTv?.alpha = 0.85f
            prootSizeHintTv?.text = formatProotCacheHint(lastMs)
            prootSizeHintTv?.setTextColor(NC.ON_SURF_VAR)
        } else if (!dirOk) {
            prootSizeValueTv?.text = "—"
            prootSizeUnitTv?.text = ""
            prootSizeHintTv?.text = tr(R.string.main_proot_rootfs_not_found)
            prootSizeHintTv?.setTextColor(NC.SECONDARY)
        }
    }

    private fun formatProotCacheHint(lastMs: Long): String {
        val age = if (lastMs > 0L) {
            val mins = ((System.currentTimeMillis() - lastMs) / 60000L).coerceAtLeast(0L)
            when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                mins < 1440 -> "${mins / 60}h ago"
                else -> "${mins / 1440}d ago"
            }
        } else "unknown"
        return "Cached · Alpaquita PRoot rootfs · $age"
    }

    private fun saveProotInfo(dirPresent: Boolean, bytes: Long?) {
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
            .putBoolean(PREF_PROOT_DIR, dirPresent)
            .putLong(PREF_PROOT_BYTES, bytes ?: -1L)
            .putLong(PREF_PROOT_LAST_MS, System.currentTimeMillis())
            .apply()
    }

    private fun setProotLoading(loading: Boolean) {
        prootMeasuring = loading
        prootLoadingRow?.visibility = if (loading) View.VISIBLE else View.GONE
        prootRefreshBtn?.isEnabled = !loading
        prootRefreshBtn?.alpha = if (loading) 0.45f else 1f
        if (loading) {
            prootSizeHintTv?.text = tr(R.string.main_measuring_alpaquita_proot_rootfs)
            prootSizeHintTv?.setTextColor(NC.PRIMARY)
            prootSizeValueTv?.alpha = 0.45f
        } else {
            prootSizeValueTv?.alpha = 1f
        }
    }

    /**
     * Measure complete proot rootfs under app filesDir (no root / no excludes).
     * Prefers `du -sb`; falls back to Java walk.
     */
    private fun refreshProotSettingsCard(force: Boolean = true) {
        if (prootSizeValueTv == null) return
        if (prootMeasuring) return

        val rootfs = ProjectPathResolver.prootRootfsDir(this)
        prootPathTv?.text = rootfs.absolutePath

        setProotLoading(true)
        executor.execute {
            val dirPresent = rootfs.isDirectory
            var bytes: Long? = null
            var measureNote: String

            if (!dirPresent) {
                measureNote = tr(R.string.main_proot_rootfs_not_found)
            } else {
                bytes = measureProotRootfsBytes(rootfs)
                measureNote = if (bytes != null) {
                    "Alpaquita PRoot rootfs · measured in-app"
                } else {
                    tr(R.string.main_size_probe_failed)
                }
            }

            saveProotInfo(dirPresent, bytes)
            val (valStr, unitStr) = formatStorageBytes(bytes)
            Log.d(
                "ProotSettings",
                "path=${rootfs.absolutePath} present=$dirPresent bytes=$bytes"
            )

            mainHandler.post {
                setProotLoading(false)
                prootSizeValueTv?.text = valStr
                prootSizeUnitTv?.text = unitStr
                prootSizeHintTv?.text = measureNote
                prootSizeHintTv?.setTextColor(
                    if (dirPresent && bytes != null) NC.ON_SURF_VAR else NC.SECONDARY
                )
            }
        }
    }

    /** `du -sb` on own filesDir; Java walk fallback. No bind excludes (plain tree). */
    private fun measureProotRootfsBytes(rootfs: File): Long? {
        val path = rootfs.absolutePath
        try {
            val pb = ProcessBuilder("du", "-sb", path)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val ok = proc.waitFor() == 0
            val parsed = out.trim().lines()
                .mapNotNull { line ->
                    line.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                }
                .firstOrNull()
            if (ok && parsed != null && parsed >= 0L) return parsed
            if (parsed != null && parsed >= 0L) return parsed
        } catch (_: Exception) {
            // fall through to walk
        }
        return try {
            var total = 0L
            rootfs.walkTopDown().forEach { f ->
                if (f.isFile) total += f.length()
            }
            total
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Dedicated Chroot Settings page (Settings Hub → this).
     * Hub only shows a nav row; full status/size/root/uninstall lives here.
     */
    private fun buildChrootSettingsPage() {
        chrootSettingsScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            clipToPadding = false
            setPadding(0, 0, 0, dp(80))
            setBackgroundColor(NC.BG)
        }
        val pageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        chrootSettingsScrollView.addView(pageLayout)

        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            setOnClickListener {
                navigateBackFromSubpage(ID_CHROOT_SETTINGS)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val headerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_chroot_debian)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) }
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_chroot_settings)
            textSize = 22f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(headerIcon)
        headerTitleRow.addView(titleTv)
        val subTitleTv = TextView(this).apply {
            text = "// ROOT-LEVEL ALPAQUITA — OUTSIDE APP STORAGE"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(8) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        headerCol.addView(headerTitleRow)
        headerCol.addView(subTitleTv)
        headerCol.addView(divider)
        pageLayout.addView(headerCol)
        pageLayout.addView(buildChrootSettingsContentCard())
        pageLayout.addView(spacer(16))
        pageLayout.addView(buildChrootProcessesCard())
    }

    /**
     * Chroot detail card content — external rootfs at CHROOT_PATH.
     * Instant status from marker + cache; root/size async.
     */
    private fun buildChrootSettingsContentCard(): LinearLayout {
        val card = glassCard()
        card.addView(sectionHeader(R.drawable.ic_storage, tr(R.string.main_storage_manage), NC.PRIMARY))

        val subTv = TextView(this).apply {
            text = "// ROOT-LEVEL ALPAQUITA — OUTSIDE APP STORAGE"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(subTv)

        // Warning strip
        val warnStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#2A1A10"))
                setStroke(dp(1), NC.TERTIARY)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        val warnIcon = TextView(this).apply {
            text = "!"
            textSize = 12f
            setTextColor(Color.parseColor("#0A0A0A"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.TERTIARY)
            }
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
        }
        val warnText = TextView(this).apply {
            text = tr(R.string.main_rootfs_is_not_removed_when)
            textSize = 11f
            setTextColor(NC.TERTIARY)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        warnStrip.addView(warnIcon)
        warnStrip.addView(warnText)
        card.addView(warnStrip)

        // STATUS row — updated instantly from marker/cache (before size)
        card.addView(buildChrootMetaRow(tr(R.string.main_status)) { row ->
            chrootStatusBadge = TextView(this).apply {
                text = "…"
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            row.addView(chrootStatusBadge)
        })

        // ROOT row — only visible when root is granted (hidden if no root → card = NOT INSTALLED only)
        chrootRootRowView = buildChrootMetaRow(tr(R.string.main_root_access)) { row ->
            chrootRootBadge = TextView(this).apply {
                text = tr(R.string.main_checking)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), dp(3), dp(8), dp(3))
                setTextColor(NC.ON_SURF_VAR)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_HIGHEST)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
            }
            row.addView(chrootRootBadge)
        }
        card.addView(chrootRootRowView)

        // Storage metric panel + loading strip
        val sizePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }
        chrootSizePanelView = sizePanel
        val sizeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizeTitle = TextView(this).apply {
            text = tr(R.string.main_linux_storage)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        chrootRefreshBtn = TextView(this).apply {
            text = " " + tr(R.string.main_caps_refresh)
            textSize = 10f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_refresh, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(8), dp(4), dp(10), dp(4))
            setOnClickListener { refreshChrootSettingsCard(force = true) }
        }
        sizeHeader.addView(sizeTitle)
        sizeHeader.addView(chrootRefreshBtn)
        sizePanel.addView(sizeHeader)

        // Indeterminate loading row (visible while root/du runs)
        chrootLoadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        chrootProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
        }
        val loadingLabel = TextView(this).apply {
            text = " " + tr(R.string.main_caps_scanning)
            textSize = 10f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), 0, 0, 0)
        }
        chrootLoadingRow?.addView(chrootProgressBar)
        chrootLoadingRow?.addView(loadingLabel)
        sizePanel.addView(chrootLoadingRow)

        val sizeValRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(8), 0, 0)
        }
        chrootSizeValueTv = TextView(this).apply {
            text = "—"
            textSize = 32f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        chrootSizeUnitTv = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, 0, dp(6))
        }
        sizeValRow.addView(chrootSizeValueTv)
        sizeValRow.addView(chrootSizeUnitTv)
        sizePanel.addView(sizeValRow)

        chrootSizeHintTv = TextView(this).apply {
            text = "…"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        }
        sizePanel.addView(chrootSizeHintTv)
        card.addView(sizePanel)

        // Path row (management detail — hidden when no root)
        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        }
        pathRow.addView(TextView(this).apply {
            text = tr(R.string.main_host_path)
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(4))
        })
        pathRow.addView(TextView(this).apply {
            text = ChrootCommandBuilder.CHROOT_PATH
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        chrootPathRowView = pathRow
        card.addView(pathRow)

        chrootUninstallBtn = TextView(this).apply {
            text = tr(R.string.main_uninstall_chroot)
            textSize = 13f
            setTextColor(NC.ERROR)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(
                fillColor = Color.parseColor("#1E1212"),
                strokeColor = NC.ERROR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3C4A3F")
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener { confirmAndUninstallChroot() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = Color.parseColor("#1E1212"),
                            strokeColor = NC.ERROR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0,
                            rightFaceColor = Color.parseColor("#3C4A3F")
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = Color.parseColor("#1E1212"),
                            strokeColor = NC.ERROR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0,
                            rightFaceColor = Color.parseColor("#3C4A3F")
                        )
                    }
                }
                false
            }
        }
        card.addView(chrootUninstallBtn)

        chrootInstallBtn = TextView(this).apply {
            text = tr(R.string.main_install_chroot)
            textSize = 13f
            setTextColor(Color.parseColor("#0A0A0A"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(
                fillColor = NC.PRIMARY,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3C4A3F")
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(0) }
            visibility = View.GONE
            setOnClickListener { launchChrootInstallOnboarding() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.PRIMARY,
                            strokeColor = NC.PRIMARY,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0,
                            rightFaceColor = Color.parseColor("#3C4A3F")
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.PRIMARY,
                            strokeColor = NC.PRIMARY,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0,
                            rightFaceColor = Color.parseColor("#3C4A3F")
                        )
                    }
                }
                false
            }
        }
        card.addView(chrootInstallBtn)

        // Instant status + cache only; live probe runs on page enter (sequential size→proc)
        applyInstantChrootStatus()
        applyCachedChrootInfo()
        return card
    }

    private fun buildChrootMetaRow(label: String, addValue: (LinearLayout) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addValue(this)
        }
    }

    /** Instant (main-thread): marker + dir File.exists — no su, no du. */
    private fun applyInstantChrootStatus() {
        val markerOk = ProjectPathResolver.isChrootInstalled()
        val dirExists = ProjectPathResolver.isChrootRootfsPresent()
        val installed = markerOk || dirExists
        applyChrootStatusBadge(chrootStatusBadge, installed, markerOk, dirExists)
        applyChrootInstallUninstallVisibility(installed)
    }

    /** Paint last known size/root from SharedPreferences (nativecode_prefs). */
    private fun applyCachedChrootInfo() {
        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        if (!prefs.contains(PREF_CHROOT_LAST_MS)) return

        val rootOk = prefs.getBoolean(PREF_CHROOT_ROOT_OK, false)
        // Cached no-root → NOT INSTALLED only (never show stale DENIED / partial GB)
        if (!rootOk) {
            applyNoRootChrootCardUi()
            return
        }

        val bytes = prefs.getLong(PREF_CHROOT_BYTES, -1L).takeIf { it >= 0L }
        val viaRoot = prefs.getBoolean(PREF_CHROOT_SIZE_VIA_ROOT, false)
        val lastMs = prefs.getLong(PREF_CHROOT_LAST_MS, 0L)
        val markerOk = prefs.getBoolean(PREF_CHROOT_INSTALLED, false)
        val dirOk = prefs.getBoolean(PREF_CHROOT_DIR, false)

        chrootRootRowView?.visibility = View.VISIBLE
        chrootSizePanelView?.visibility = View.VISIBLE
        chrootPathRowView?.visibility = View.VISIBLE

        val installed = markerOk || dirOk
        applyChrootStatusBadge(chrootStatusBadge, installed, markerOk, dirOk)
        applyChrootRootBadge(chrootRootBadge, rootOk = true, checking = false)
        applyChrootInstallUninstallVisibility(installed)

        if (bytes != null) {
            val (v, u) = formatStorageBytes(bytes)
            chrootSizeValueTv?.text = v
            chrootSizeUnitTv?.text = u
            chrootSizeValueTv?.alpha = 0.85f
        }
        chrootSizeHintTv?.text = formatChrootCacheHint(lastMs, viaRoot, rootOk = true)
        chrootSizeHintTv?.setTextColor(NC.ON_SURF_VAR)
    }

    private fun formatCacheAge(lastMs: Long): String {
        if (lastMs <= 0L) return "unknown"
        val mins = ((System.currentTimeMillis() - lastMs) / 60000L).coerceAtLeast(0L)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 1440 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }

    private fun formatChrootCacheHint(lastMs: Long, viaRoot: Boolean, rootOk: Boolean): String {
        val age = formatCacheAge(lastMs)
        val how = when {
            viaRoot -> "root du"
            rootOk -> "root"
            else -> "no-root walk"
        }
        return "Cached · $how · $age"
    }

    /**
     * Persist chroot card state. **Does not poison size cache on probe fail:**
     * when [bytes] is null and dir still present with a prior good size, leave
     * `chroot_size_bytes` + last-measure time alone so UI can show dimmed cache age.
     */
    private fun saveChrootInfo(
        installed: Boolean,
        dirExists: Boolean,
        bytes: Long?,
        rootOk: Boolean,
        viaRoot: Boolean
    ) {
        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        val edit = prefs.edit()
            .putBoolean(PREF_CHROOT_INSTALLED, installed)
            .putBoolean(PREF_CHROOT_DIR, dirExists)
            .putBoolean(PREF_CHROOT_ROOT_OK, rootOk)

        when {
            bytes != null && bytes >= 0L -> {
                edit.putLong(PREF_CHROOT_BYTES, bytes)
                edit.putBoolean(PREF_CHROOT_SIZE_VIA_ROOT, viaRoot)
                edit.putLong(PREF_CHROOT_LAST_MS, System.currentTimeMillis())
            }
            !rootOk || !dirExists -> {
                // Gone / no root — clear size
                edit.putLong(PREF_CHROOT_BYTES, -1L)
                edit.putBoolean(PREF_CHROOT_SIZE_VIA_ROOT, false)
                edit.putLong(PREF_CHROOT_LAST_MS, System.currentTimeMillis())
            }
            // else: probe failed but dir still there — keep previous good bytes + LAST_MS
            else -> {
                if (!prefs.contains(PREF_CHROOT_LAST_MS)) {
                    edit.putLong(PREF_CHROOT_LAST_MS, System.currentTimeMillis())
                }
            }
        }
        edit.apply()
    }

    /** Last good size from prefs, or null. */
    private fun cachedChrootBytes(): Long? {
        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        return prefs.getLong(PREF_CHROOT_BYTES, -1L).takeIf { it >= 0L }
    }

    private fun cachedChrootLastMs(): Long {
        return getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
            .getLong(PREF_CHROOT_LAST_MS, 0L)
    }

    private fun clearChrootInfoCache() {
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
            .remove(PREF_CHROOT_INSTALLED)
            .remove(PREF_CHROOT_DIR)
            .remove(PREF_CHROOT_BYTES)
            .remove(PREF_CHROOT_ROOT_OK)
            .remove(PREF_CHROOT_SIZE_VIA_ROOT)
            .remove(PREF_CHROOT_LAST_MS)
            .remove(PREF_CHROOT_PROC_COUNT)
            .remove(PREF_CHROOT_PROC_LAST_MS)
            .apply()
        chrootProcLastCount = -1
    }

    private fun setChrootLoading(loading: Boolean) {
        chrootMeasuring = loading
        chrootLoadingRow?.visibility = if (loading) View.VISIBLE else View.GONE
        chrootRefreshBtn?.isEnabled = !loading
        chrootRefreshBtn?.alpha = if (loading) 0.45f else 1f
        if (loading) {
            chrootRootBadge?.let {
                it.text = tr(R.string.main_checking)
                it.setTextColor(NC.ON_SURF_VAR)
                it.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_HIGHEST)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
            }
            chrootSizeHintTv?.text = tr(R.string.main_checking_root_measuring_rootfs)
            chrootSizeHintTv?.setTextColor(NC.PRIMARY)
            chrootSizeValueTv?.alpha = 0.45f
        } else {
            chrootSizeValueTv?.alpha = 1f
        }
    }

    /**
     * Page-level sequential refresh: size probe **then** process list on one BG
     * thread so two RootShell.capture never race on enter (F1).
     */
    private fun refreshChrootSettingsPage(force: Boolean = false) {
        if (chrootSizeValueTv == null) return
        if (chrootMeasuring || chrootProcMeasuring || chrootProcKilling) return

        setChrootLoading(true)
        chrootRootRowView?.visibility = View.VISIBLE
        chrootSizePanelView?.visibility = View.VISIBLE
        chrootPathRowView?.visibility = View.VISIBLE
        // Block process SCAN/KILL while size runs
        setChrootProcKillEnabled(false)
        chrootProcScanBtn?.isEnabled = false
        chrootProcScanBtn?.alpha = 0.45f

        val appCtx = applicationContext
        executor.execute {
            if (force) RootShell.clearSuCache()
            runChrootSizeProbeOnBg(appCtx)
            // Sequential: processes after size (same executor job)
            if (chrootProcCountTv != null) {
                mainHandler.post { setChrootProcLoading(loading = true) }
                val procResult = ChrootProcessManager.list(appCtx)
                mainHandler.post {
                    setChrootProcLoading(loading = false)
                    applyChrootProcessListUi(procResult)
                    chrootProcScanBtn?.isEnabled = true
                    chrootProcScanBtn?.alpha = 1f
                }
            } else {
                mainHandler.post {
                    chrootProcScanBtn?.isEnabled = true
                    chrootProcScanBtn?.alpha = 1f
                }
            }
        }
    }

    /**
     * @param force true = user tapped REFRESH (clear su cache + re-probe).
     * Size only — does not start process list (use [refreshChrootSettingsPage] on enter).
     */
    private fun refreshChrootSettingsCard(force: Boolean = true) {
        if (chrootSizeValueTv == null) return
        if (chrootMeasuring || chrootProcMeasuring || chrootProcKilling) return

        setChrootLoading(true)
        chrootRootRowView?.visibility = View.VISIBLE
        chrootSizePanelView?.visibility = View.VISIBLE
        chrootPathRowView?.visibility = View.VISIBLE
        setChrootProcKillEnabled(false)
        chrootProcScanBtn?.isEnabled = false
        chrootProcScanBtn?.alpha = 0.45f

        val appCtx = applicationContext
        executor.execute {
            if (force) RootShell.clearSuCache()
            runChrootSizeProbeOnBg(appCtx)
            mainHandler.post {
                chrootProcScanBtn?.isEnabled = true
                chrootProcScanBtn?.alpha = 1f
                // Re-enable kill if processes were known running
                if (chrootProcLastCount > 0 && !chrootProcMeasuring && !chrootProcKilling) {
                    setChrootProcKillEnabled(true)
                }
            }
        }
    }

    /**
     * BG: root probe + staged size helper. Posts UI. Must run on executor only.
     * Keeps good size cache on fail (F2); paints dimmed cache + fail hint.
     */
    private fun runChrootSizeProbeOnBg(appCtx: Context) {
        val rootOk = RootShell.isRootAvailable()

        if (!rootOk) {
            saveChrootInfo(
                installed = false,
                dirExists = false,
                bytes = null,
                rootOk = false,
                viaRoot = false
            )
            mainHandler.post {
                setChrootLoading(false)
                applyNoRootChrootCardUi()
            }
            return
        }

        mainHandler.post {
            chrootRootRowView?.visibility = View.VISIBLE
            chrootSizePanelView?.visibility = View.VISIBLE
            chrootPathRowView?.visibility = View.VISIBLE
            applyChrootRootBadge(chrootRootBadge, rootOk = true, checking = false)
        }

        val markerOk = ProjectPathResolver.isChrootInstalled()
        val measure = ChrootSizeManager.measure(appCtx)
        var dirExists = measure.dirExists
        if (measure.error == "no_dir") {
            dirExists = false
        } else if (measure.bytes != null || measure.error == null) {
            dirExists = true
        } else {
            // Soft-fail: keep local marker/dir hints
            dirExists = ProjectPathResolver.isChrootRootfsPresent() || markerOk || dirExists
        }

        val liveBytes = measure.bytes
        val viaRoot = measure.viaRoot
        val priorBytes = cachedChrootBytes()
        val priorMs = cachedChrootLastMs()

        val measureNote: String
        val displayBytes: Long?
        val dimmedCache: Boolean

        when {
            liveBytes != null -> {
                displayBytes = liveBytes
                dimmedCache = false
                measureNote = tr(R.string.main_alpaquita_rootfs_binds_excluded_sdca)
            }
            !dirExists -> {
                displayBytes = null
                dimmedCache = false
                measureNote = tr(R.string.main_no_chroot_rootfs_on_host)
            }
            priorBytes != null -> {
                // F2 fix: keep last good size on probe fail
                displayBytes = priorBytes
                dimmedCache = true
                val age = formatCacheAge(priorMs)
                val why = when (measure.error) {
                    "timeout" -> "timeout"
                    "empty_output" -> "empty"
                    else -> "probe failed"
                }
                measureNote = "Size $why · showing cache · $age"
                Log.w("ChrootSize", "probe fail keep cache err=${measure.error} raw=${measure.raw.take(400)}")
            }
            else -> {
                displayBytes = null
                dimmedCache = false
                measureNote = tr(R.string.main_root_ok_size_probe_failed)
                Log.w("ChrootSize", "probe fail no cache err=${measure.error} raw=${measure.raw.take(400)}")
            }
        }

        val installed = markerOk || dirExists
        saveChrootInfo(installed, dirExists, liveBytes, rootOk = true, viaRoot)
        val (valStr, unitStr) = formatStorageBytes(displayBytes)

        mainHandler.post {
            setChrootLoading(false)
            applyChrootStatusBadge(chrootStatusBadge, installed, markerOk, dirExists)
            applyChrootRootBadge(chrootRootBadge, rootOk = true, checking = false)
            chrootSizeValueTv?.text = valStr
            chrootSizeUnitTv?.text = unitStr
            chrootSizeValueTv?.alpha = if (dimmedCache) 0.75f else 1f
            chrootSizeHintTv?.text = measureNote
            chrootSizeHintTv?.setTextColor(
                when {
                    dimmedCache -> NC.TERTIARY
                    installed -> NC.ON_SURF_VAR
                    else -> NC.SECONDARY
                }
            )
            applyChrootInstallUninstallVisibility(installed)
        }
    }

    /**
     * No root access for this app → card only reports NOT INSTALLED.
     * Hide ROOT / path / uninstall management chrome (no false DENIED, no partial size).
     */
    private fun applyNoRootChrootCardUi() {
        applyChrootStatusBadge(
            chrootStatusBadge,
            installed = false,
            markerOk = false,
            dirExists = false
        )
        chrootRootRowView?.visibility = View.GONE
        chrootPathRowView?.visibility = View.GONE
        // Not detected → install CTA only (onboarding grants root path)
        applyChrootInstallUninstallVisibility(installed = false)
        // Keep size panel as empty state (not partial walk)
        chrootSizePanelView?.visibility = View.VISIBLE
        chrootSizeValueTv?.text = "—"
        chrootSizeUnitTv?.text = ""
        chrootSizeValueTv?.alpha = 1f
        chrootSizeHintTv?.text = tr(R.string.main_root_required_install_via_onboarding)
        chrootSizeHintTv?.setTextColor(NC.SECONDARY)
    }

    private fun applyChrootStatusBadge(
        badge: TextView?,
        installed: Boolean,
        markerOk: Boolean,
        dirExists: Boolean
    ) {
        if (badge == null) return
        when {
            markerOk -> {
                badge.text = tr(R.string.main_caps_installed)
                badge.setTextColor(Color.parseColor("#0A0A0A"))
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.PRIMARY)
                }
            }
            dirExists || installed -> {
                badge.text = tr(R.string.main_partial)
                badge.setTextColor(Color.parseColor("#0A0A0A"))
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.TERTIARY)
                }
            }
            else -> {
                badge.text = tr(R.string.main_not_installed)
                badge.setTextColor(NC.ON_SURF_VAR)
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_HIGHEST)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
            }
        }
    }

    private fun applyChrootRootBadge(badge: TextView?, rootOk: Boolean, checking: Boolean) {
        if (badge == null) return
        when {
            checking -> {
                badge.text = tr(R.string.main_checking)
                badge.setTextColor(NC.ON_SURF_VAR)
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_HIGHEST)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
            }
            rootOk -> {
                badge.text = tr(R.string.main_granted)
                badge.setTextColor(Color.parseColor("#0A0A0A"))
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.PRIMARY)
                }
            }
            else -> {
                badge.text = tr(R.string.main_denied)
                badge.setTextColor(Color.parseColor("#0A0A0A"))
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.ERROR)
                }
            }
        }
    }

    private fun formatStorageBytes(bytes: Long?): Pair<String, String> {
        if (bytes == null) return "—" to ""
        if (bytes < 0L) return "—" to ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.1f", mb / 1024.0) to "GB"
        } else if (mb >= 1.0) {
            String.format(Locale.US, "%.1f", mb) to "MB"
        } else {
            val kb = bytes / 1024.0
            if (kb >= 1.0) {
                String.format(Locale.US, "%.0f", kb) to "KB"
            } else {
                bytes.toString() to "B"
            }
        }
    }

    /**
     * Install CTA only when rootfs/marker not detected.
     * Uninstall only when installed (or partial dir present).
     */
    private fun applyChrootInstallUninstallVisibility(installed: Boolean) {
        if (installed) {
            chrootInstallBtn?.visibility = View.GONE
            chrootUninstallBtn?.visibility = View.VISIBLE
            chrootUninstallBtn?.isEnabled = true
            chrootUninstallBtn?.alpha = 1f
        } else {
            chrootUninstallBtn?.visibility = View.GONE
            chrootUninstallBtn?.isEnabled = false
            chrootUninstallBtn?.alpha = 0.4f
            chrootInstallBtn?.visibility = View.VISIBLE
        }
    }

    /** Full chroot install chain via Onboarding (isolation=chroot → Environment Setup). */
    private fun launchChrootInstallOnboarding() {
        val intent = Intent(this, OnboardingActivity::class.java).apply {
            putExtra("force_onboarding", true)
            putExtra("preferred_isolation", "chroot")
            putExtra("target_page", 6) // Environment Setup (after plan page insert)
            putExtra("auto_start_setup", true)
        }
        startActivity(intent)
    }

    private fun confirmAndUninstallChroot() {
        if (chrootUninstallBtn?.isEnabled != true) {
            Toast.makeText(this, tr(R.string.main_no_chroot_rootfs_to_uninstall), Toast.LENGTH_SHORT).show()
            return
        }
        showBrutalistConfirmDialog(
            title = tr(R.string.main_uninstall_chroot_2),
            message =
                "Permanently deletes ${ChrootCommandBuilder.CHROOT_PATH}, unmounts binds, " +
                    "and removes host launcher scripts.\n\n" +
                    "The app package stays installed. PRoot data is not touched.\n\n" +
                    "This cannot be undone.",
            confirmLabel = tr(R.string.main_uninstall),
            cancelLabel = tr(R.string.x11_cancel),
            destructive = true,
            onConfirm = {
                pendingChrootUninstall = true
                runScriptInTerminal("uninstall_debian13_chroot.sh", "chroot_host")
            }
        )
    }

    // ── Chroot Processes card (detect → kill → verify) ───────────────────────

    private fun buildChrootProcessesCard(): LinearLayout {
        val card = glassCard()
        card.addView(sectionHeader(R.drawable.ic_stop_thick, tr(R.string.main_processes), NC.ERROR))

        val subTv = TextView(this).apply {
            text = "// orphans survive app close (unlike proot). Kill before uninstall if stuck."
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(subTv)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = tr(R.string.main_chroot_processes)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        chrootProcScanBtn = TextView(this).apply {
            text = " SCAN"
            textSize = 10f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_refresh, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(8), dp(4), dp(10), dp(4))
            setOnClickListener { refreshChrootProcessesCard(force = true) }
        }
        header.addView(title)
        header.addView(chrootProcScanBtn)
        panel.addView(header)

        chrootProcLoadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(NC.ERROR)
        }
        val loadingLabel = TextView(this).apply {
            text = " " + tr(R.string.main_caps_scanning)
            textSize = 10f
            setTextColor(NC.ERROR)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), 0, 0, 0)
        }
        chrootProcLoadingRow?.addView(progress)
        chrootProcLoadingRow?.addView(loadingLabel)
        panel.addView(chrootProcLoadingRow)

        val countRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(8), 0, 0)
        }
        chrootProcCountTv = TextView(this).apply {
            text = "—"
            textSize = 32f
            setTextColor(NC.ERROR)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        chrootProcUnitTv = TextView(this).apply {
            text = "running"
            textSize = 14f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), 0, 0, dp(6))
        }
        countRow.addView(chrootProcCountTv)
        countRow.addView(chrootProcUnitTv)
        panel.addView(countRow)

        panel.addView(TextView(this).apply {
            text = "root=${ChrootCommandBuilder.CHROOT_PATH}"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(6))
            setTextIsSelectable(true)
        })

        chrootProcHintTv = TextView(this).apply {
            text = "…"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        }
        panel.addView(chrootProcHintTv)

        chrootProcSampleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(4))
        }
        panel.addView(chrootProcSampleBox)
        card.addView(panel)

        chrootProcKillBtn = TextView(this).apply {
            text = tr(R.string.main_kill_all_chroot_processes)
            textSize = 13f
            setTextColor(NC.ERROR)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(
                fillColor = Color.parseColor("#1E1212"),
                strokeColor = NC.ERROR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3C4A3F")
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { confirmAndKillChrootProcesses() }
            setOnTouchListener { v, event ->
                if (!v.isEnabled) return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = Color.parseColor("#1E1212"),
                            strokeColor = NC.ERROR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0,
                            rightFaceColor = Color.parseColor("#3C4A3F")
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = Color.parseColor("#1E1212"),
                            strokeColor = NC.ERROR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0,
                            rightFaceColor = Color.parseColor("#3C4A3F")
                        )
                    }
                }
                false
            }
        }
        card.addView(chrootProcKillBtn)

        chrootProcStatusTv = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        card.addView(chrootProcStatusTv)

        applyCachedChrootProcInfo()
        return card
    }

    private fun applyCachedChrootProcInfo() {
        if (chrootProcCountTv == null) return
        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        if (!prefs.contains(PREF_CHROOT_PROC_LAST_MS)) return
        val count = prefs.getInt(PREF_CHROOT_PROC_COUNT, -1)
        val lastMs = prefs.getLong(PREF_CHROOT_PROC_LAST_MS, 0L)
        if (count < 0) return
        chrootProcLastCount = count
        chrootProcCountTv?.text = count.toString()
        chrootProcCountTv?.alpha = 0.75f
        chrootProcUnitTv?.text = if (count == 1) "running" else "running"
        val age = if (lastMs > 0L) {
            val mins = ((System.currentTimeMillis() - lastMs) / 60000L).coerceAtLeast(0L)
            when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                else -> "${mins / 60}h ago"
            }
        } else "unknown"
        chrootProcHintTv?.text = "Cached · $age"
        chrootProcHintTv?.setTextColor(NC.ON_SURF_VAR)
        setChrootProcKillEnabled(count > 0 && !chrootProcMeasuring && !chrootProcKilling)
    }

    private fun saveChrootProcInfo(count: Int) {
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
            .putInt(PREF_CHROOT_PROC_COUNT, count)
            .putLong(PREF_CHROOT_PROC_LAST_MS, System.currentTimeMillis())
            .apply()
        chrootProcLastCount = count
    }

    private fun setChrootProcLoading(loading: Boolean, killing: Boolean = false) {
        chrootProcMeasuring = loading && !killing
        chrootProcKilling = killing
        chrootProcLoadingRow?.visibility = if (loading || killing) View.VISIBLE else View.GONE
        // Update loading label text
        (chrootProcLoadingRow?.getChildAt(1) as? TextView)?.text =
            if (killing) " KILLING" else " SCANNING"
        chrootProcScanBtn?.isEnabled = !loading && !killing
        chrootProcScanBtn?.alpha = if (loading || killing) 0.45f else 1f
        if (loading || killing) {
            chrootProcCountTv?.alpha = 0.45f
            chrootProcHintTv?.text = if (killing) tr(R.string.main_sending_sigkill) else tr(R.string.main_scanning_proc_for_chroot_root)
            chrootProcHintTv?.setTextColor(if (killing) NC.ERROR else NC.PRIMARY)
            setChrootProcKillEnabled(false)
        } else {
            chrootProcCountTv?.alpha = 1f
        }
    }

    private fun setChrootProcKillEnabled(enabled: Boolean) {
        chrootProcKillBtn?.isEnabled = enabled
        chrootProcKillBtn?.alpha = if (enabled) 1f else 0.4f
    }

    private fun refreshChrootProcessesCard(force: Boolean = true) {
        if (chrootProcCountTv == null) return
        // P3: do not race size measure / concurrent su
        if (chrootMeasuring || chrootProcMeasuring || chrootProcKilling) return

        setChrootProcLoading(loading = true)
        val appCtx = applicationContext
        executor.execute {
            if (force) RootShell.clearSuCache()
            val result = ChrootProcessManager.list(appCtx)
            mainHandler.post {
                setChrootProcLoading(loading = false)
                applyChrootProcessListUi(result)
            }
        }
    }

    private fun applyChrootProcessListUi(result: ChrootProcessManager.ListResult) {
        if (!result.rootOk || result.error == "root_required") {
            chrootProcCountTv?.text = "—"
            chrootProcUnitTv?.text = ""
            chrootProcHintTv?.text = tr(R.string.main_root_required)
            chrootProcHintTv?.setTextColor(NC.SECONDARY)
            chrootProcSampleBox?.removeAllViews()
            chrootProcSampleBox?.visibility = View.GONE
            setChrootProcKillEnabled(false)
            chrootProcStatusTv?.visibility = View.GONE
            return
        }
        if (result.error == "stage_failed") {
            chrootProcCountTv?.text = "—"
            chrootProcHintTv?.text = tr(R.string.main_failed_to_stage_helper_script)
            chrootProcHintTv?.setTextColor(NC.ERROR)
            setChrootProcKillEnabled(false)
            return
        }

        val n = result.processes.size
        saveChrootProcInfo(n)
        chrootProcCountTv?.text = n.toString()
        chrootProcCountTv?.alpha = 1f
        chrootProcCountTv?.setTextColor(if (n > 0) NC.ERROR else NC.PRIMARY)
        chrootProcUnitTv?.text = "running"

        chrootProcHintTv?.text = when {
            n == 0 -> "No chroot processes"
            n == 1 -> "1 process uses chroot root"
            else -> "$n processes use chroot root"
        }
        chrootProcHintTv?.setTextColor(if (n > 0) NC.TERTIARY else NC.ON_SURF_VAR)

        fillChrootProcSample(result.processes)
        setChrootProcKillEnabled(n > 0)
    }

    private fun fillChrootProcSample(procs: List<ChrootProcessManager.Proc>) {
        val box = chrootProcSampleBox ?: return
        box.removeAllViews()
        if (procs.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        box.addView(TextView(this).apply {
            text = tr(R.string.main_sample)
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(4))
        })
        val show = procs.take(5)
        for (p in show) {
            box.addView(TextView(this).apply {
                text = "${p.pid}  ${p.comm}"
                textSize = 11f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        val more = procs.size - show.size
        if (more > 0) {
            box.addView(TextView(this).apply {
                text = tr(R.string.main_more, more)
                textSize = 10f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun applyChrootProcessKillUi(result: ChrootProcessManager.KillResult) {
        if (!result.rootOk || result.error == "root_required") {
            chrootProcHintTv?.text = tr(R.string.main_root_required)
            chrootProcHintTv?.setTextColor(NC.SECONDARY)
            setChrootProcKillEnabled(false)
            Toast.makeText(this, tr(R.string.main_root_required_to_kill_chroot), Toast.LENGTH_SHORT).show()
            return
        }
        if (result.error == "stage_failed") {
            chrootProcHintTv?.text = tr(R.string.main_failed_to_stage_helper_script)
            chrootProcHintTv?.setTextColor(NC.ERROR)
            Toast.makeText(this, tr(R.string.main_could_not_stage_kill_helper), Toast.LENGTH_SHORT).show()
            return
        }

        val rem = result.remaining.size
        saveChrootProcInfo(rem)
        chrootProcCountTv?.text = rem.toString()
        chrootProcCountTv?.alpha = 1f
        chrootProcCountTv?.setTextColor(if (rem > 0) NC.ERROR else NC.PRIMARY)
        chrootProcUnitTv?.text = "running"
        fillChrootProcSample(result.remaining)
        setChrootProcKillEnabled(rem > 0)

        val status = if (result.verifiedClean) {
            "last: killed ${result.killed} · verified 0 remaining"
        } else {
            "last: killed ${result.killed} · ${rem} still alive — retry"
        }
        chrootProcStatusTv?.text = status
        chrootProcStatusTv?.visibility = View.VISIBLE
        chrootProcStatusTv?.setTextColor(if (result.verifiedClean) NC.PRIMARY else NC.ERROR)

        chrootProcHintTv?.text = if (result.verifiedClean) {
            "No chroot processes"
        } else {
            "$rem process(es) still use chroot root"
        }
        chrootProcHintTv?.setTextColor(if (result.verifiedClean) NC.ON_SURF_VAR else NC.TERTIARY)

        Toast.makeText(
            this,
            if (result.verifiedClean) tr(R.string.main_killed_clean, result.killed) else tr(R.string.main_killed_remaining, result.killed, rem),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmAndKillChrootProcesses() {
        if (chrootProcKillBtn?.isEnabled != true) return
        if (chrootMeasuring) return // P3: no concurrent su with size probe
        val n = chrootProcLastCount.coerceAtLeast(0)
        showBrutalistConfirmDialog(
            title = tr(R.string.main_kill_chroot_processes),
            message =
                "Sends SIGKILL to every process whose root is ${ChrootCommandBuilder.CHROOT_PATH}.\n\n" +
                    "Open chroot shells and guest daemons will die. Host Android processes are not targeted.\n\n" +
                    "Rootfs and mounts stay (use Uninstall to remove)." +
                    if (n > 0) "\n\nDetected: $n process(es)." else "",
            confirmLabel = tr(R.string.main_kill_all),
            cancelLabel = tr(R.string.x11_cancel),
            destructive = true,
            onConfirm = {
                if (!chrootMeasuring) {
                    setChrootProcLoading(loading = true, killing = true)
                    val appCtx = applicationContext
                    executor.execute {
                        val result = ChrootProcessManager.killAll(appCtx)
                        mainHandler.post {
                            setChrootProcLoading(loading = false, killing = false)
                            applyChrootProcessKillUi(result)
                        }
                    }
                }
            }
        )
    }

    /**
     * Cyber-brutalist confirm sheet (ui_design.md): sharp 0px, NC surfaces,
     * two-tone extrusion, mono labels — never stock Material AlertDialog.
     */
    private fun showBrutalistConfirmDialog(
        title: String,
        message: String,
        confirmLabel: String,
        cancelLabel: String = tr(R.string.x11_cancel),
        destructive: Boolean = false,
        body: View? = null,
        onConfirm: () -> Unit
    ): android.app.Dialog {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC0A0A0A"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = if (destructive) NC.ERROR else NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3C4A3F")
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            isClickable = true // absorb scrim clicks
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        if (destructive) {
            titleRow.addView(TextView(this).apply {
                text = "!"
                textSize = 14f
                setTextColor(Color.parseColor("#0A0A0A"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.ERROR)
                }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
            })
        }
        titleRow.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(if (destructive) NC.ERROR else Color.WHITE)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        card.addView(titleRow)

        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(14) }
            setBackgroundColor(NC.OUTLINE_VAR)
        })

        card.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(0, 0, 0, dp(20))
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun pressable(
            label: String,
            fill: Int,
            stroke: Int,
            textColor: Int,
            weight: Float,
            endMargin: Int,
            action: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(textColor)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                background = cyberBrutalistBg(
                    fillColor = fill,
                    strokeColor = stroke,
                    shadowColor = NC.SHADOW_DARK,
                    offsetDp = 4,
                    cornerRadiusDp = 0,
                    rightFaceColor = Color.parseColor("#3C4A3F")
                )
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, weight).apply {
                    if (endMargin > 0) rightMargin = endMargin
                }
                setOnClickListener { action() }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.translationX = dp(2).toFloat()
                            v.translationY = dp(2).toFloat()
                            v.background = cyberBrutalistBg(
                                fillColor = fill,
                                strokeColor = stroke,
                                shadowColor = NC.SHADOW_DARK,
                                offsetDp = 2,
                                cornerRadiusDp = 0,
                                rightFaceColor = Color.parseColor("#3C4A3F")
                            )
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.translationX = 0f
                            v.translationY = 0f
                            v.background = cyberBrutalistBg(
                                fillColor = fill,
                                strokeColor = stroke,
                                shadowColor = NC.SHADOW_DARK,
                                offsetDp = 4,
                                cornerRadiusDp = 0,
                                rightFaceColor = Color.parseColor("#3C4A3F")
                            )
                        }
                    }
                    false
                }
            }
        }

        val cancelBtn = pressable(
            cancelLabel,
            fill = NC.SURFACE_CONTAINER,
            stroke = NC.OUTLINE_VAR,
            textColor = Color.WHITE,
            weight = 1f,
            endMargin = dp(10)
        ) { dialog.dismiss() }

        val confirmFill = if (destructive) Color.parseColor("#1E1212") else NC.PRIMARY
        val confirmStroke = if (destructive) NC.ERROR else NC.PRIMARY
        val confirmText = if (destructive) NC.ERROR else Color.parseColor("#0A0A0A")
        val confirmBtn = pressable(
            confirmLabel,
            fill = confirmFill,
            stroke = confirmStroke,
            textColor = confirmText,
            weight = 1f,
            endMargin = 0
        ) {
            dialog.dismiss()
            onConfirm()
        }

        btnRow.addView(cancelBtn)
        btnRow.addView(confirmBtn)
        if (body != null) card.addView(body, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })
        card.addView(btnRow)

        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.window?.apply {
            setLayout(MATCH, MATCH)
            setBackgroundDrawableResource(android.R.color.transparent)
            // keep status bar readable
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            if (body != null) setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        return dialog
    }

    private fun onChrootUninstalled(fromCallback: Boolean) {
        AiCliProvisionState.clearAiCliProvisioned(this, "chroot")
        if (LinuxCommandBuilder.currentMethod == "chroot") {
            LinuxCommandBuilder.currentMethod = "proot"
            getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                .edit().putString("linux_method", "proot").apply()
            if (::homeContainerLabel.isInitialized) {
                homeContainerLabel.text = ProjectPathResolver.methodLabel()
            }
            refreshToolCardsForMethod()
        } else {
            refreshToolCardsForMethod()
        }
        clearChrootInfoCache()
        applyInstantChrootStatus()
        chrootSizeValueTv?.text = "—"
        chrootSizeUnitTv?.text = ""
        chrootSizeHintTv?.text = tr(R.string.main_uninstalled)
        applyChrootRootBadge(chrootRootBadge, rootOk = false, checking = false)
        chrootProcCountTv?.text = "0"
        chrootProcHintTv?.text = "No chroot processes"
        chrootProcSampleBox?.removeAllViews()
        chrootProcSampleBox?.visibility = View.GONE
        chrootProcStatusTv?.visibility = View.GONE
        setChrootProcKillEnabled(false)
        refreshChrootSettingsPage(force = true)
        val msg = if (fromCallback) {
            tr(R.string.main_chroot_uninstalled_switched_to_proot)
        } else {
            tr(R.string.main_chroot_uninstall_finished_storage_wi)
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ── Helper UI properties / primitives ─────────────────────────────────────

    private fun fileRow(icon: String, name: String, isFolder: Boolean, modified: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            val iconTv = TextView(this@MainActivity).apply { text = icon; textSize = 16f; setPadding(0, 0, dp(10), 0); setTextColor(if (isFolder) NC.SECONDARY else NC.OUTLINE) }
            val nameTv = TextView(this@MainActivity).apply { text = name; textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURFACE); layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
            addView(iconTv); addView(nameTv)
            if (modified) {
                val badge = TextView(this@MainActivity).apply { text = "M"; textSize = 10f; setTextColor(NC.ON_SURFACE); background = roundedBg(NC.TERTIARY, NC.TERTIARY, dp(4)); setPadding(dp(6), dp(2), dp(6), dp(2)) }
                addView(badge)
            }
        }
    }

    // ── Software Manager + Marketplace ───────────────────────────────────────

    private fun currentLinuxEnv(): String =
        if (LinuxCommandBuilder.currentMethod == "chroot") "chroot" else "qemu"

    private fun envBadgeLabel(): String =
        if (LinuxCommandBuilder.currentMethod == "chroot") "CHROOT" else "QEMU"

    private fun buildSoftwareManagerPage() {
        softwareManagerScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        softwareManagerBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        softwareManagerScrollView.addView(softwareManagerBody)

        softwareManagerBody.addView(buildSubPageHeader(
            title = tr(R.string.main_software_manager),
            subtitle = tr(R.string.main_caps_installed_packages),
            iconRes = R.drawable.ic_extension,
            pageId = ID_SOFTWARE_MANAGER
        ) { smEnvBadge = it })

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        val search = EditText(this).apply {
            hint = tr(R.string.main_search_packages)
            setHintTextColor(NC.ON_SURF_VAR)
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    smSearchQuery = s?.toString().orEmpty()
                    renderSoftwareManagerList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        toolbar.addView(search)
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun chip(label: String, key: String): TextView {
            return TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
                setOnClickListener {
                    smFilter = key
                    renderSoftwareManagerList()
                    // restyle all chips
                    for (i in 0 until chips.childCount) {
                        val c = chips.getChildAt(i) as TextView
                        val active = c.tag == smFilter
                        c.setTextColor(if (active) NC.ON_PRIMARY else NC.ON_SURFACE)
                        c.background = GradientDrawable().apply {
                            setColor(if (active) NC.PRIMARY_CON else NC.SURFACE_CONTAINER)
                            setStroke(dp(1), if (active) NC.PRIMARY else NC.OUTLINE_VAR)
                        }
                    }
                }
                tag = key
                val active = smFilter == key
                setTextColor(if (active) NC.ON_PRIMARY else NC.ON_SURFACE)
                background = GradientDrawable().apply {
                    setColor(if (active) NC.PRIMARY_CON else NC.SURFACE_CONTAINER)
                    setStroke(dp(1), if (active) NC.PRIMARY else NC.OUTLINE_VAR)
                }
            }
        }
        chips.addView(chip(tr(R.string.chat_all), "all"))
        chips.addView(chip(tr(R.string.main_system), "system"))
        toolbar.addView(chips)

        val refreshRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        smStatusTv = TextView(this).apply {
            text = tr(R.string.main_tap_refresh_to_scan_guest)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        smRefreshBtn = TextView(this).apply {
            text = tr(R.string.git_refresh)
            textSize = 12f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.PRIMARY)
            }
            setOnClickListener { refreshSoftwareManagerPage(force = true) }
        }
        refreshRow.addView(smStatusTv)
        refreshRow.addView(smRefreshBtn)
        toolbar.addView(refreshRow)
        softwareManagerBody.addView(toolbar)

        smListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        softwareManagerBody.addView(smListContainer)
    }

    private fun buildMarketplacePage() {
        marketplaceScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            clipToPadding = false
            setPadding(0, 0, 0, dp(80))
            setBackgroundColor(NC.BG)
        }
        marketplaceBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        marketplaceScrollView.addView(marketplaceBody)

        marketplaceBody.addView(buildSubPageHeader(
            title = tr(R.string.main_marketplace),
            subtitle = tr(R.string.main_caps_curated_components_apps),
            iconRes = R.drawable.ic_download,
            pageId = ID_MARKETPLACE
        ) { mpEnvBadge = it })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }
        mpSyncTv = TextView(this).apply {
            text = tr(R.string.main_catalog_not_loaded)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val updateBtn = TextView(this).apply {
            text = tr(R.string.main_update_catalog)
            textSize = 11f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.PRIMARY)
            }
            setOnClickListener { refreshMarketplacePage(force = true) }
        }
        top.addView(mpSyncTv)
        top.addView(updateBtn)
        marketplaceBody.addView(top)

        mpStatusTv = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        }
        marketplaceBody.addView(mpStatusTv)

        val mpSearch = EditText(this).apply {
            hint = tr(R.string.main_search_apps_components_deps)
            setHintTextColor(NC.ON_SURF_VAR)
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    mpSearchQuery = s?.toString().orEmpty()
                    if (mpCatalog != null) renderMarketplaceList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        marketplaceBody.addView(mpSearch)

        mpListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        marketplaceBody.addView(mpListContainer)
        // Install/uninstall logs use script-runner terminal page (same as repairs).
        mpLogTv = null
    }


    // ── Apps (installed launchable apps) ─────────────────────────────────────

    private fun buildAppsPage() {
        appsScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            clipToPadding = false
            setPadding(0, 0, 0, dp(80))
            setBackgroundColor(NC.BG)
        }
        appsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        appsScrollView.addView(appsBody)

        appsBody.addView(buildSubPageHeader(
            title = tr(R.string.main_apps),
            subtitle = tr(R.string.main_caps_installed_launchable_apps),
            iconRes = R.drawable.ic_play_thick,
            pageId = ID_APPS
        ) { appsEnvBadge = it })

        val search = EditText(this).apply {
            hint = tr(R.string.main_filter_apps)
            setHintTextColor(NC.ON_SURF_VAR)
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    appsSearchQuery = s?.toString().orEmpty()
                    renderAppsList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            setOnFocusChangeListener { v, hasFocus ->
                (v.background as? GradientDrawable)?.setStroke(
                    dp(1), if (hasFocus) NC.PRIMARY else NC.OUTLINE_VAR
                )
            }
        }
        appsBody.addView(search)

        val refreshRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        appsStatusTv = TextView(this).apply {
            text = tr(R.string.main_installed_apps_for_current_env)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        appsRefreshBtn = TextView(this).apply {
            text = tr(R.string.git_refresh)
            textSize = 12f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.PRIMARY)
            }
            setOnClickListener { refreshAppsPage(force = true) }
        }
        refreshRow.addView(appsStatusTv)
        refreshRow.addView(appsRefreshBtn)
        appsBody.addView(refreshRow)

        appsListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appsBody.addView(appsListContainer)
    }

    private fun refreshAppsPage(force: Boolean) {
        appsEnvBadge?.text = envBadgeLabel()
        val env = currentLinuxEnv()
        if (appsLoading) return
        appsLoading = true
        appsStatusTv?.text = tr(R.string.main_loading_apps, env)
        appsRefreshBtn?.isEnabled = false
        appsRefreshBtn?.alpha = 0.45f
        appsListContainer?.removeAllViews()
        appsListContainer?.addView(buildListLoadingView(tr(R.string.main_loading_apps_2), skeletonRows = 4))
        executor.execute {
            // Hydrate catalog titles when possible (offline → registry ids only)
            val cat = try {
                if (force || mpCatalog == null) {
                    MarketplaceClient.loadCatalog(this, forceRefresh = force).getOrNull()
                } else mpCatalog
            } catch (_: Exception) { mpCatalog }
            val items = AppsInventory.listInstalledApps(this, env, cat)
            mainHandler.post {
                appsLoading = false
                appsRefreshBtn?.isEnabled = true
                appsRefreshBtn?.alpha = 1f
                if (cat != null) mpCatalog = cat
                appsItems = items
                val total = items.sumOf { it.sizeBytes }
                appsStatusTv?.text =
                    "${items.size} apps · ${AptInventoryService.formatSize(total)} · $env"
                renderAppsList()
            }
        }
    }

    private fun renderAppsList() {
        val container = appsListContainer ?: return
        container.removeAllViews()
        val env = currentLinuxEnv()

        if (!AptInventoryService.guestReady(this, env)) {
            container.addView(TextView(this).apply {
                text = if (env == "chroot")
                    "Guest not ready. Open Settings → Chroot Settings to install rootfs."
                else
                    tr(R.string.main_guest_not_ready_complete_onboarding)
                setTextColor(NC.ERROR)
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        val q = appsSearchQuery.trim().lowercase(Locale.US)
        val filtered = if (q.isEmpty()) appsItems else appsItems.filter {
            it.title.lowercase(Locale.US).contains(q) ||
                it.id.lowercase(Locale.US).contains(q) ||
                it.summary.lowercase(Locale.US).contains(q)
        }

        if (filtered.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(16), 0, dp(8))
            }
            empty.addView(TextView(this).apply {
                text = if (appsItems.isEmpty()) tr(R.string.main_no_installed_apps_found)
                else tr(R.string.main_no_apps_match, appsSearchQuery)
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setPadding(0, 0, 0, dp(12))
            })
            container.addView(empty)
            return
        }

        for (item in filtered) {
            container.addView(buildAppsRow(item))
            container.addView(spacer(10))
        }
    }

    private fun buildAppsRow(item: AppListItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { showAppsDetailSheet(item) }
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            if (item.catalog != null) MarketplaceIcons.bind(this, item.catalog)
            else setImageResource(MarketplaceIcons.bundledRes(item.id))
        }
        row.addView(icon)

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        mid.addView(TextView(this).apply {
            text = item.title
            textSize = 15f
            setTextColor(Color.parseColor("#FAFAFA"))
            typeface = Typeface.DEFAULT_BOLD
        })
        val meta = buildString {
            if (item.version.isNotBlank()) append("v").append(item.version)
            if (item.sizeBytes > 0) {
                if (isNotEmpty()) append(" · ")
                append(AptInventoryService.formatSize(item.sizeBytes))
            }
            if (isNotEmpty()) append(" · ")
            append("APP")
        }
        mid.addView(TextView(this).apply {
            text = meta
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        })
        if (item.summary.isNotBlank()) {
            mid.addView(TextView(this).apply {
                text = item.summary
                textSize = 11f
                setTextColor(NC.OUTLINE)
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(mid)

        val canLaunch = !item.launchCommand.isNullOrBlank()
        val launchBtn = TextView(this).apply {
            text = tr(R.string.main_launch)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(if (canLaunch) NC.ON_PRIMARY else NC.ON_SURF_VAR)
            background = GradientDrawable().apply {
                setColor(if (canLaunch) NC.PRIMARY_CON else NC.SURFACE_CONTAINER)
                setStroke(dp(1), if (canLaunch) NC.PRIMARY else NC.OUTLINE_VAR)
            }
            alpha = if (canLaunch) 1f else 0.5f
            isEnabled = canLaunch
            setOnClickListener {
                if (!mpBusy) launchInstalledApp(item)
            }
        }
        row.addView(launchBtn)
        return row
    }

    private fun showAppsDetailSheet(item: AppListItem) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(12), dp(12), dp(12), dp(24))
            }
            setOnClickListener { /* absorb */ }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            if (item.catalog != null) MarketplaceIcons.bind(this, item.catalog)
            else setImageResource(MarketplaceIcons.bundledRes(item.id))
        }
        head.addView(icon)
        val headText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        headText.addView(TextView(this).apply {
            text = item.title
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        headText.addView(TextView(this).apply {
            text = buildString {
                append(envBadgeLabel())
                if (item.version.isNotBlank()) append(" · v").append(item.version)
                if (item.sizeBytes > 0) append(" · ").append(AptInventoryService.formatSize(item.sizeBytes))
            }
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        })
        head.addView(headText)
        card.addView(head)

        if (item.summary.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = item.summary
                textSize = 12f
                setTextColor(NC.ON_SURF_VAR)
                setPadding(0, 0, 0, dp(10))
            })
        }
        card.addView(TextView(this).apply {
            text = buildString {
                append("id=").append(item.id)
                if (item.registry.installedAt.isNotBlank())
                    append("\ninstalled=").append(item.registry.installedAt)
                if (!item.launchCommand.isNullOrBlank())
                    append("\nlaunch=").append(item.launchCommand)
            }
            textSize = 10f
            setTextColor(NC.OUTLINE)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(14))
        })

        fun sheetBtn(label: String, primary: Boolean, destructive: Boolean = false, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setTextColor(
                    when {
                        destructive -> NC.ERROR
                        primary -> NC.ON_PRIMARY
                        else -> NC.PRIMARY
                    }
                )
                background = GradientDrawable().apply {
                    setColor(
                        when {
                            destructive -> NC.SURFACE_CONTAINER
                            primary -> NC.PRIMARY_CON
                            else -> NC.SURFACE_CONTAINER
                        }
                    )
                    setStroke(dp(1), if (destructive) NC.ERROR else NC.PRIMARY)
                }
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
                setOnClickListener {
                    dialog.dismiss()
                    if (!mpBusy) onClick()
                }
            }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.addView(sheetBtn(tr(R.string.term_close), primary = false) { })
        if (!item.launchCommand.isNullOrBlank()) {
            btnRow.addView(sheetBtn(tr(R.string.main_launch), primary = true) { launchInstalledApp(item) })
        }
        btnRow.addView(sheetBtn(tr(R.string.main_uninstall), primary = false, destructive = true) {
            showBrutalistConfirmDialog(
                title = tr(R.string.main_uninstall_2, item.title),
                message = tr(R.string.main_removes_marketplace_install_for_in, item.id, currentLinuxEnv()),
                confirmLabel = tr(R.string.main_uninstall),
                destructive = true,
                onConfirm = { runAppsUninstall(item) }
            )
        })
        card.addView(btnRow)
        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.show()
    }

    private fun launchInstalledApp(item: AppListItem) {
        val env = currentLinuxEnv()
        val entry = item.registry
        if (!entry.isApp || item.launchCommand.isNullOrBlank()) {
            Toast.makeText(this, tr(R.string.main_not_a_launchable_app), Toast.LENGTH_SHORT).show()
            return
        }
        if (!desktop.isRunning) {
            Toast.makeText(this, tr(R.string.main_start_graphical_desktop_for_best), Toast.LENGTH_SHORT).show()
        }
        try {
            desktop.view(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } catch (_: Exception) { /* ignore */ }
        if (mpBusy || isScriptRunning) {
            Toast.makeText(this, tr(R.string.main_busy_try_again), Toast.LENGTH_SHORT).show()
            return
        }
        mpBusy = true
        // Prefer registry launch cmd; patch entry if only catalog had it
        val launchEntry = if (entry.launchCommand.isNullOrBlank() && !item.launchCommand.isNullOrBlank()) {
            entry.copy(launchCommand = item.launchCommand)
        } else entry
        executor.execute {
            PackageInstallRunner.launchApp(this, launchEntry, env) { ev ->
                mainHandler.post {
                    when (ev) {
                        is InstallEvent.Log -> Log.d("Apps", ev.line)
                        is InstallEvent.Finished -> {
                            mpBusy = false
                            if (!ev.success) {
                                Toast.makeText(this, tr(R.string.main_launch_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun runAppsUninstall(item: AppListItem) {
        val env = currentLinuxEnv()
        if (item.source != "marketplace" && item.registry.source != "marketplace") {
            // v1: only marketplace-installed apps
            Toast.makeText(this, tr(R.string.main_only_marketplace_apps_can_be), Toast.LENGTH_SHORT).show()
            return
        }
        val cat = mpCatalog
        if (cat == null) {
            // Registry-only fallback (same as MP when catalog missing)
            InstallRegistry.remove(this, env, item.id)
            Toast.makeText(this, tr(R.string.main_removed_registry_entry), Toast.LENGTH_SHORT).show()
            refreshAppsPage(force = false)
            if (::marketplaceScrollView.isInitialized) renderMarketplaceList()
            return
        }
        if (mpBusy || isScriptRunning) {
            Toast.makeText(this, tr(R.string.main_another_install_is_running), Toast.LENGTH_SHORT).show()
            return
        }
        mpBusy = true
        Toast.makeText(this, tr(R.string.main_preparing_uninstall, item.id), Toast.LENGTH_SHORT).show()
        executor.execute {
            val prepared = PackageInstallRunner.prepareUninstall(this, cat, item.id, env)
            mainHandler.post {
                if (prepared.isFailure) {
                    mpBusy = false
                    Toast.makeText(
                        this,
                        prepared.exceptionOrNull()?.message ?: tr(R.string.main_prepare_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                // Keep Apps under installer on stack
                while (pageStack.isNotEmpty() && pageStack.peek() == ID_MP_INSTALLER) {
                    pageStack.pop()
                }
                if (pageStack.isEmpty() || pageStack.peek() != ID_APPS) {
                    pageStack.push(ID_APPS)
                }
                startMarketplaceTerminal(prepared.getOrThrow())
            }
        }
    }

    private fun buildSubPageHeader(
        title: String,
        subtitle: String,
        iconRes: Int,
        pageId: Int,
        onEnvBadge: (TextView) -> Unit
    ): LinearLayout {
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            setOnClickListener {
                navigateBackFromSubpage(pageId)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val headerIcon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) }
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val badge = TextView(this).apply {
            text = envBadgeLabel()
            textSize = 10f
            setTextColor(NC.ON_PRIMARY)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(NC.PRIMARY_CON)
            }
        }
        onEnvBadge(badge)
        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(headerIcon)
        headerTitleRow.addView(titleTv)
        headerTitleRow.addView(badge)
        val subTitleTv = TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(8) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        headerCol.addView(headerTitleRow)
        headerCol.addView(subTitleTv)
        headerCol.addView(divider)
        return headerCol
    }

    private fun refreshSoftwareManagerPage(force: Boolean) {
        smEnvBadge?.text = envBadgeLabel()
        val env = currentLinuxEnv()
        if (!AptInventoryService.guestReady(this, env)) {
            smPackages = emptyList()
            smScanEnv = null
            smStatusTv?.text = tr(R.string.main_guest_not_ready_install_alpaquita, env)
            smListContainer?.removeAllViews()
            smListContainer?.addView(TextView(this).apply {
                text = if (env == "chroot") "Open Chroot Settings to install rootfs."
                else tr(R.string.main_complete_onboarding_proot_alpaquita)
                setTextColor(NC.ERROR)
                typeface = Typeface.MONOSPACE
                textSize = 12f
            })
            return
        }
        if (smScanning) return
        // Reuse cache only when same isolation env — avoid CHROOT badge + "· proot" status
        val cacheOk = !force && smPackages.isNotEmpty() && smScanEnv == env
        if (cacheOk) {
            smStatusTv?.text =
                "${smPackages.size} packages · ${AptInventoryService.formatSize(smPackages.sumOf { it.sizeBytes })} · $env"
            renderSoftwareManagerList()
            return
        }
        smScanning = true
        smStatusTv?.text = tr(R.string.main_scanning_packages, env)
        smRefreshBtn?.isEnabled = false
        smRefreshBtn?.alpha = 0.45f
        smListContainer?.removeAllViews()
        smListContainer?.addView(buildListLoadingView(tr(R.string.main_scanning_packages_2), skeletonRows = 8))
        executor.execute {
            val result = AptInventoryService.scan(this, env)
            mainHandler.post {
                smScanning = false
                smRefreshBtn?.isEnabled = true
                smRefreshBtn?.alpha = 1f
                result.fold(
                    onSuccess = { list ->
                        smPackages = list
                        smScanEnv = env
                        // Fresh scan → collapse all (search still auto-expands)
                        if (smSearchQuery.isBlank()) smExpandedCategories.clear()
                        smStatusTv?.text =
                            "${list.size} packages · ${AptInventoryService.formatSize(list.sumOf { it.sizeBytes })} · $env"
                        renderSoftwareManagerList()
                    },
                    onFailure = { e ->
                        smPackages = emptyList()
                        smScanEnv = null
                        smStatusTv?.text = "Scan failed ($env): ${e.message}"
                        smListContainer?.removeAllViews()
                        smListContainer?.addView(TextView(this).apply {
                            text = e.message ?: tr(R.string.main_scan_failed)
                            setTextColor(NC.ERROR)
                            typeface = Typeface.MONOSPACE
                            textSize = 12f
                        })
                    }
                )
            }
        }
    }

    /** Indeterminate bar + skeleton rows for list loads (SM / MP). */
    private fun buildListLoadingView(label: String, skeletonRows: Int = 6): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        barRow.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
        })
        barRow.addView(TextView(this).apply {
            text = " $label"
            textSize = 10f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, 0, 0)
        })
        wrap.addView(barRow)

        repeat(skeletonRows) { i ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(NC.SURFACE_LOW)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
                setPadding(dp(12), dp(14), dp(12), dp(14))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(8)
                }
                alpha = 0.55f + (i % 3) * 0.1f
            }
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) }
                background = GradientDrawable().apply { setColor(NC.SURFACE_CONTAINER) }
            })
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            mid.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams((120 + i * 18).coerceAtMost(200).let { dp(it) }, dp(12))
                background = GradientDrawable().apply { setColor(NC.SURFACE_HIGH) }
            })
            mid.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(80), dp(8)).apply { topMargin = dp(8) }
                background = GradientDrawable().apply { setColor(NC.SURFACE_CONTAINER) }
            })
            row.addView(mid)
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(10))
                background = GradientDrawable().apply { setColor(NC.SURFACE_CONTAINER) }
            })
            wrap.addView(row)
            // pulse fade
            ObjectAnimator.ofFloat(row, "alpha", row.alpha, 0.35f, row.alpha).apply {
                duration = 1100L + i * 80L
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
        return wrap
    }

    private fun renderSoftwareManagerList() {
        val container = smListContainer ?: return
        if (smScanning) {
            container.removeAllViews()
            container.addView(buildListLoadingView(tr(R.string.main_scanning_packages_2), skeletonRows = 8))
            return
        }
        container.removeAllViews()
        val env = currentLinuxEnv()
        val q = smSearchQuery.trim().lowercase(Locale.US)
        var filtered = smPackages
        if (q.isNotEmpty()) {
            filtered = filtered.filter {
                it.name.lowercase(Locale.US).contains(q) ||
                    it.section.lowercase(Locale.US).contains(q)
            }
        }
        filtered = when (smFilter) {
            "mp" -> filtered.filter { InstallRegistry.isMarketplaceApt(this, env, it.name) }
            "system" -> filtered.filter { !InstallRegistry.isMarketplaceApt(this, env, it.name) }
            else -> filtered
        }
        if (filtered.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (smPackages.isEmpty()) tr(R.string.main_no_packages_loaded_tap_refresh)
                else tr(R.string.main_no_packages_match)
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                textSize = 12f
            })
            return
        }
        val groups = AptInventoryService.groupByCategory(filtered)
        // Merge same friendly titles
        val merged = linkedMapOf<String, MutableList<AptPackage>>()
        groups.forEach { g ->
            merged.getOrPut(g.title) { mutableListOf() }.addAll(g.packages)
        }
        // Searching → auto-expand matching categories
        val searching = q.isNotEmpty()
        if (searching) {
            smExpandedCategories.clear()
            smExpandedCategories.addAll(merged.keys)
        }

        merged.forEach { (title, pkgs) ->
            val sorted = pkgs.sortedBy { it.name }
            val catSize = sorted.sumOf { it.sizeBytes }
            val expanded = smExpandedCategories.contains(title)
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(NC.SURFACE_LOWEST)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(6)
                    bottomMargin = if (expanded) 0 else dp(8)
                }
                setOnClickListener {
                    if (smExpandedCategories.contains(title)) smExpandedCategories.remove(title)
                    else smExpandedCategories.add(title)
                    renderSoftwareManagerList()
                }
            }
            header.addView(TextView(this).apply {
                text = if (expanded) "▼" else "▶"
                textSize = 12f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), 0, dp(10), 0)
            })
            header.addView(TextView(this).apply {
                text = title.uppercase(Locale.US)
                textSize = 13f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            header.addView(TextView(this).apply {
                text = "${sorted.size} · ${AptInventoryService.formatSize(catSize)}"
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, dp(8), 0)
            })
            header.addView(TextView(this).apply {
                text = if (expanded) tr(R.string.main_collapse) else tr(R.string.main_expand)
                textSize = 10f
                setTextColor(if (expanded) NC.ON_SURF_VAR else NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply {
                    setColor(NC.SURFACE_CONTAINER)
                    setStroke(dp(1), if (expanded) NC.OUTLINE_VAR else NC.PRIMARY)
                }
            })
            container.addView(header)

            if (!expanded) return@forEach

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(NC.SURFACE_LOW)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(8)
                }
            }
            sorted.forEachIndexed { idx, pkg ->
                val isMp = InstallRegistry.isMarketplaceApt(this, env, pkg.name)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                }
                val left = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                }
                left.addView(TextView(this).apply {
                    text = pkg.name
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                })
                left.addView(TextView(this).apply {
                    text = pkg.version
                    setTextColor(NC.ON_SURF_VAR)
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                })
                row.addView(left)
                if (isMp) {
                    row.addView(TextView(this).apply {
                        text = "MP"
                        textSize = 10f
                        setTextColor(NC.ON_PRIMARY)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        background = GradientDrawable().apply { setColor(NC.PRIMARY_CON) }
                        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
                    })
                }
                row.addView(TextView(this).apply {
                    text = AptInventoryService.formatSize(pkg.sizeBytes)
                    setTextColor(NC.PRIMARY)
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                })
                if (isMp) {
                    row.setOnClickListener { confirmMarketplaceUninstall(pkg.name) }
                }
                card.addView(row)
                if (idx < sorted.lastIndex) {
                    card.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(MATCH, 1)
                        setBackgroundColor(NC.OUTLINE_VAR)
                    })
                }
            }
            container.addView(card)
        }
        val total = filtered.sumOf { it.sizeBytes }
        container.addView(TextView(this).apply {
            text = "${filtered.size} packages · ${AptInventoryService.formatSize(total)} · tap category to expand"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun confirmMarketplaceUninstall(aptOrId: String) {
        if (com.zenithblue.nativecode.ProotOnly.ON) return
        val env = currentLinuxEnv()
        val entry = InstallRegistry.forEnv(this, env).find {
            it.id == aptOrId || it.aptProvides.any { p -> p.equals(aptOrId, true) }
        } ?: run {
            Toast.makeText(this, tr(R.string.main_not_a_marketplace_package), Toast.LENGTH_SHORT).show()
            return
        }
        showBrutalistConfirmDialog(
            title = tr(R.string.main_uninstall_2, entry.id),
            message = tr(R.string.main_runs_marketplace_uninstall_sh_for),
            confirmLabel = tr(R.string.main_uninstall),
            destructive = true,
            onConfirm = { runMarketplaceUninstall(entry.id) }
        )
    }

    /**
     * First-use gate: remote catalog + install scripts from GitHub.
     * Home + Settings entries call this; already-accepted skips dialog.
     */
    private fun openMarketplace() {
        if (com.zenithblue.nativecode.ProotOnly.ON) {
            Toast.makeText(this, tr(if (BuildConfig.FDROID) R.string.fd_marketplace_unavailable else R.string.main_marketplace_is_unavailable_in_the), Toast.LENGTH_SHORT).show()
            return
        }
        if (MarketplaceConsent.isAccepted(this)) {
            goToMarketplace()
            return
        }
        showBrutalistConfirmDialog(
            title = tr(R.string.main_marketplace_consent),
            message = "Marketplace loads a catalog and optional install scripts from " +
                "GitHub (${MarketplacePaths.OWNER}/${MarketplacePaths.REPO}).\n\n" +
                "Installs run inside your Linux guest (proot/chroot), may use network, " +
                "and can change packages there.\n\n" +
                "Third-party scripts — review package details before install. " +
                "Not required for core NativeCode or Alpaquita shells.\n\n" +
                "Accept once to open Marketplace.",
            confirmLabel = tr(R.string.main_i_understand),
            cancelLabel = tr(R.string.main_not_now),
            onConfirm = {
                MarketplaceConsent.setAccepted(this, true)
                goToMarketplace()
            }
        )
    }

    private fun goToMarketplace() {
        if (com.zenithblue.nativecode.ProotOnly.ON) return
        if (pageStack.isEmpty() || pageStack.peek() != ID_MARKETPLACE) {
            pageStack.push(ID_MARKETPLACE)
        }
        navigateToPage(ID_MARKETPLACE)
    }

    private fun refreshMarketplacePage(force: Boolean) {
        if (com.zenithblue.nativecode.ProotOnly.ON) return
        mpEnvBadge?.text = envBadgeLabel()
        if (!MarketplaceConsent.isAccepted(this)) {
            mpStatusTv?.text = tr(R.string.main_consent_required)
            mpListContainer?.removeAllViews()
            mpListContainer?.addView(TextView(this).apply {
                text = tr(R.string.main_accept_marketplace_consent_to_load)
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        if (mpLoading) return
        mpLoading = true
        mpStatusTv?.text = tr(R.string.main_loading_catalog)
        mpListContainer?.removeAllViews()
        mpListContainer?.addView(buildListLoadingView(tr(R.string.main_loading_catalog_2), skeletonRows = 5))
        executor.execute {
            val result = MarketplaceClient.loadCatalog(this, forceRefresh = force)
            mainHandler.post {
                mpLoading = false
                result.fold(
                    onSuccess = { cat ->
                        mpCatalog = cat
                        val sync = MarketplaceClient.lastSyncMs(this)
                        mpSyncTv?.text = if (sync > 0)
                            "Last sync · ${java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(sync))}"
                        else tr(R.string.main_catalog_loaded)
                        mpStatusTv?.text = "${cat.packages.size} packages · github.com/${MarketplacePaths.OWNER}/${MarketplacePaths.REPO}"
                        renderMarketplaceList()
                    },
                    onFailure = { e ->
                        mpStatusTv?.text = "Catalog error: ${e.message}"
                        mpListContainer?.removeAllViews()
                        mpListContainer?.addView(TextView(this).apply {
                            text = tr(R.string.main_need_network_once_to_fetch)
                            setTextColor(NC.ERROR)
                            typeface = Typeface.MONOSPACE
                            textSize = 12f
                        })
                    }
                )
            }
        }
    }

    private fun renderMarketplaceList() {
        val container = mpListContainer ?: return
        if (mpLoading) {
            container.removeAllViews()
            container.addView(buildListLoadingView(tr(R.string.main_loading_catalog_2), skeletonRows = 5))
            return
        }
        val cat = mpCatalog ?: return
        container.removeAllViews()
        val env = currentLinuxEnv()
        val q = mpSearchQuery.trim().lowercase(Locale.US)

        // category chips
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
        }
        fun addChip(label: String, key: String) {
            val tv = TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
                val active = mpCategoryFilter == key
                setTextColor(if (active) NC.ON_PRIMARY else NC.ON_SURFACE)
                background = GradientDrawable().apply {
                    setColor(if (active) NC.PRIMARY_CON else NC.SURFACE_CONTAINER)
                    setStroke(dp(1), if (active) NC.PRIMARY else NC.OUTLINE_VAR)
                }
                setOnClickListener {
                    mpCategoryFilter = key
                    renderMarketplaceList()
                }
            }
            chips.addView(tv)
        }
        addChip(tr(R.string.chat_all), "all")
        cat.categoriesSorted().forEach { c -> addChip(c.title, c.id) }
        horizontal.addView(chips)
        container.addView(horizontal)

        val pkgs = cat.packages
            .filter { it.supportsEnv(env) }
            .filter { mpCategoryFilter == "all" || it.category == mpCategoryFilter }
            .filter { pkg ->
                if (q.isEmpty()) true
                else {
                    val hay = buildString {
                        append(pkg.id); append(' ')
                        append(pkg.name); append(' ')
                        append(pkg.summary); append(' ')
                        append(pkg.description); append(' ')
                        append(pkg.category); append(' ')
                        append(pkg.kind.name); append(' ')
                        append(pkg.version); append(' ')
                        pkg.deps.forEach { append(it); append(' ') }
                        pkg.aptProvides.forEach { append(it); append(' ') }
                    }.lowercase(Locale.US)
                    hay.contains(q)
                }
            }
            .sortedWith(compareBy({ it.category }, { it.name.lowercase(Locale.US) }))

        if (pkgs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (q.isNotEmpty()) tr(R.string.main_no_results_for, q)
                else tr(R.string.main_no_packages_for_in_this, env)
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(16), 0, 0)
            })
            return
        }

        // All → 2-col square grid by category (less vertical scroll).
        // Category tabs → detail list with install/launch buttons.
        if (mpCategoryFilter == "all") {
            var lastCat = ""
            var groupPkgs = mutableListOf<MpPackage>()
            fun flushGroup() {
                if (groupPkgs.isEmpty()) return
                renderMarketplaceGrid(container, groupPkgs, env)
                groupPkgs = mutableListOf()
            }
            pkgs.forEach { pkg ->
                if (pkg.category != lastCat) {
                    flushGroup()
                    lastCat = pkg.category
                    val title = cat.categories.find { it.id == pkg.category }?.title
                        ?: pkg.category.uppercase(Locale.US)
                    container.addView(TextView(this).apply {
                        text = title.uppercase(Locale.US)
                        textSize = 13f
                        setTextColor(NC.PRIMARY)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(12), 0, dp(8))
                    })
                }
                groupPkgs.add(pkg)
            }
            flushGroup()
        } else {
            pkgs.forEach { pkg ->
                container.addView(buildMarketplacePackageCard(pkg, env))
            }
        }
    }

    /** 2-column square cards: icon center, name, status. Tap → actions sheet. */
    private fun renderMarketplaceGrid(
        container: LinearLayout,
        pkgs: List<MpPackage>,
        env: String
    ) {
        val gap = dp(10)
        var row: LinearLayout? = null
        pkgs.forEachIndexed { index, pkg ->
            if (index % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        bottomMargin = gap
                    }
                }
                container.addView(row)
            }
            val cell = buildMarketplaceGridCard(pkg, env).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (index % 2 == 0) rightMargin = gap / 2 else leftMargin = gap / 2
                }
            }
            row!!.addView(cell)
            // pad odd last item so single card doesn't stretch full width
            if (index == pkgs.lastIndex && index % 2 == 0) {
                row!!.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                        leftMargin = gap / 2
                    }
                })
            }
        }
    }

    private fun buildMarketplaceGridCard(pkg: MpPackage, env: String): View {
        val installed = InstallRegistry.get(this, env, pkg.id)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOW)
                setStroke(dp(1), if (installed != null) NC.PRIMARY else NC.OUTLINE_VAR)
            }
            setPadding(dp(10), dp(14), dp(10), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { showMarketplacePackageSheet(pkg, env) }
        }
        val iconWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = pkg.name
        }
        MarketplaceIcons.bind(icon, pkg)
        iconWrap.addView(icon)
        card.addView(iconWrap)
        card.addView(TextView(this).apply {
            text = pkg.name
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })
        card.addView(TextView(this).apply {
            text = when {
                installed != null -> "INSTALLED"
                pkg.experimental -> tr(R.string.main_experimental)
                else -> if (pkg.kind == PackageKind.APP) "APP" else "COMPONENT"
            }
            setTextColor(if (installed != null) NC.PRIMARY else NC.ON_SURF_VAR)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (installed != null)
                AptInventoryService.formatSize(installed.sizeBytes)
            else if (pkg.sizeHintMb > 0) "~${pkg.sizeHintMb} MB"
            else "v${pkg.version}"
            setTextColor(NC.PRIMARY)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        return card
    }

    /** Grid-card tap: icon + summary + multi actions (install / launch / uninstall). */
    private fun showMarketplacePackageSheet(pkg: MpPackage, env: String) {
        val installed = InstallRegistry.get(this, env, pkg.id)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC0A0A0A"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3C4A3F")
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            isClickable = true
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        MarketplaceIcons.bind(icon, pkg)
        head.addView(icon)
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        titles.addView(TextView(this).apply {
            text = pkg.name
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        titles.addView(TextView(this).apply {
            text = "${if (pkg.kind == PackageKind.APP) "app" else "component"} · v${pkg.version}" +
                if (pkg.experimental) " · experimental" else ""
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        })
        head.addView(titles)
        card.addView(head)

        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(12) }
            setBackgroundColor(NC.OUTLINE_VAR)
        })

        val body = buildString {
            append(pkg.summary.ifBlank { pkg.description })
            if (pkg.deps.isNotEmpty()) append("\n\ndeps: ").append(pkg.deps.joinToString(", "))
            if (installed != null) {
                append("\n\nInstalled · ").append(AptInventoryService.formatSize(installed.sizeBytes))
            } else if (pkg.sizeHintMb > 0) {
                append("\n\n~").append(pkg.sizeHintMb).append(" MB")
            }
        }
        card.addView(TextView(this).apply {
            text = body
            textSize = 13f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(0, 0, 0, dp(16))
        })

        fun sheetBtn(label: String, primary: Boolean, destructive: Boolean = false, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setTextColor(
                    when {
                        destructive -> NC.ERROR
                        primary -> NC.ON_PRIMARY
                        else -> NC.PRIMARY
                    }
                )
                background = GradientDrawable().apply {
                    setColor(
                        when {
                            destructive -> NC.SURFACE_CONTAINER
                            primary -> NC.PRIMARY_CON
                            else -> NC.SURFACE_CONTAINER
                        }
                    )
                    setStroke(dp(1), if (destructive) NC.ERROR else NC.PRIMARY)
                }
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
                setOnClickListener {
                    dialog.dismiss()
                    if (!mpBusy) onClick()
                }
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.addView(sheetBtn(tr(R.string.term_close), primary = false) { })
        if (installed != null) {
            if (pkg.kind == PackageKind.APP && !pkg.launch?.command.isNullOrBlank()) {
                btnRow.addView(sheetBtn(tr(R.string.main_launch), primary = true) { launchMarketplaceApp(pkg) })
            }
            btnRow.addView(sheetBtn(tr(R.string.main_uninstall), primary = false, destructive = true) {
                showBrutalistConfirmDialog(
                    title = tr(R.string.main_uninstall_2, pkg.name),
                    message = tr(R.string.main_runs_uninstall_sh_dependencies_stay),
                    confirmLabel = tr(R.string.main_uninstall),
                    destructive = true,
                    onConfirm = { runMarketplaceUninstall(pkg.id) }
                )
            })
        } else {
            btnRow.addView(sheetBtn(tr(R.string.usage_install), primary = true) {
                val cat = mpCatalog ?: return@sheetBtn
                val plan = try {
                    PackageInstallRunner.resolveOrder(cat, pkg.id)
                        .filter { !InstallRegistry.isInstalled(this, env, it.id) }
                        .joinToString(" → ") { it.id }
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    return@sheetBtn
                }
                showBrutalistConfirmDialog(
                    title = tr(R.string.main_install, pkg.name),
                    message = tr(R.string.main_will_install_source_runs_as, plan, MarketplacePaths.OWNER, MarketplacePaths.REPO, env),
                    confirmLabel = tr(R.string.usage_install),
                    onConfirm = { runMarketplaceInstall(pkg.id) }
                )
            })
        }
        card.addView(btnRow)
        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.show()
    }

    private fun buildMarketplacePackageCard(pkg: MpPackage, env: String): View {
        val installed = InstallRegistry.get(this, env, pkg.id)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOW)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            contentDescription = pkg.name
        }
        MarketplaceIcons.bind(icon, pkg)
        top.addView(icon)
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        titles.addView(TextView(this).apply {
            text = pkg.name
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        titles.addView(TextView(this).apply {
            text = "${if (pkg.kind == PackageKind.APP) "app" else "component"} · v${pkg.version}" +
                if (pkg.experimental) " · experimental" else ""
            setTextColor(NC.ON_SURF_VAR)
            textSize = 10f
            typeface = Typeface.MONOSPACE
        })
        top.addView(titles)
        top.addView(TextView(this).apply {
            text = if (installed != null)
                AptInventoryService.formatSize(installed.sizeBytes)
            else if (pkg.sizeHintMb > 0) "~${pkg.sizeHintMb} MB"
            else "—"
            setTextColor(NC.PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        })
        card.addView(top)
        card.addView(TextView(this).apply {
            text = pkg.summary.ifBlank { pkg.description }
            setTextColor(NC.ON_SURF_VAR)
            textSize = 12f
            setPadding(0, dp(6), 0, dp(8))
        })
        if (pkg.deps.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "deps: ${pkg.deps.joinToString(", ")}"
                setTextColor(NC.OUTLINE)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(8))
            })
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun actionBtn(label: String, primary: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(if (primary) NC.ON_PRIMARY else NC.PRIMARY)
                background = GradientDrawable().apply {
                    setColor(if (primary) NC.PRIMARY_CON else NC.SURFACE_CONTAINER)
                    setStroke(dp(1), NC.PRIMARY)
                }
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
                setOnClickListener { if (!mpBusy) onClick() }
            }
        }
        if (installed != null) {
            if (pkg.kind == PackageKind.APP && !pkg.launch?.command.isNullOrBlank()) {
                actions.addView(actionBtn(tr(R.string.main_launch), true) {
                    launchMarketplaceApp(pkg)
                })
            }
            actions.addView(actionBtn(tr(R.string.main_uninstall), false) {
                showBrutalistConfirmDialog(
                    title = tr(R.string.main_uninstall_2, pkg.id),
                    message = tr(R.string.main_runs_uninstall_sh_dependencies_stay),
                    confirmLabel = tr(R.string.main_uninstall),
                    destructive = true,
                    onConfirm = { runMarketplaceUninstall(pkg.id) }
                )
            })
        } else {
            actions.addView(actionBtn(tr(R.string.usage_install), true) {
                val cat = mpCatalog ?: return@actionBtn
                val plan = try {
                    PackageInstallRunner.resolveOrder(cat, pkg.id)
                        .filter { !InstallRegistry.isInstalled(this, env, it.id) }
                        .joinToString(" → ") { it.id }
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    return@actionBtn
                }
                showBrutalistConfirmDialog(
                    title = tr(R.string.main_install, pkg.name),
                    message = tr(R.string.main_will_install_source_runs_as, plan, MarketplacePaths.OWNER, MarketplacePaths.REPO, env),
                    confirmLabel = tr(R.string.usage_install),
                    onConfirm = { runMarketplaceInstall(pkg.id) }
                )
            })
        }
        card.addView(actions)
        return card
    }

    private fun appendMpLog(line: String) {
        // Legacy no-op — marketplace ops stream to script-runner terminal.
        Log.d("Marketplace", line)
    }

    /**
     * Prepare stage + open repairs script-runner terminal for marketplace install.
     */
    private fun runMarketplaceInstall(id: String) {
        if (com.zenithblue.nativecode.ProotOnly.ON) return
        if (!MarketplaceConsent.isAccepted(this)) {
            openMarketplace()
            return
        }
        val cat = mpCatalog ?: return
        val env = currentLinuxEnv()
        if (!AptInventoryService.guestReady(this, env)) {
            Toast.makeText(this, tr(R.string.main_guest_not_ready, env), Toast.LENGTH_SHORT).show()
            return
        }
        if (mpBusy || isScriptRunning) {
            Toast.makeText(this, tr(R.string.main_another_install_is_running), Toast.LENGTH_SHORT).show()
            return
        }
        mpBusy = true
        Toast.makeText(this, tr(R.string.main_preparing, id), Toast.LENGTH_SHORT).show()
        executor.execute {
            val prepared = PackageInstallRunner.prepareInstall(this, cat, id, env)
            mainHandler.post {
                if (prepared.isFailure) {
                    mpBusy = false
                    Toast.makeText(
                        this,
                        prepared.exceptionOrNull()?.message ?: tr(R.string.main_prepare_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                startMarketplaceTerminal(prepared.getOrThrow())
            }
        }
    }

    private fun runMarketplaceUninstall(id: String) {
        if (com.zenithblue.nativecode.ProotOnly.ON) return
        val cat = mpCatalog
        if (cat == null) {
            InstallRegistry.remove(this, currentLinuxEnv(), id)
            Toast.makeText(this, tr(R.string.main_removed_registry_entry), Toast.LENGTH_SHORT).show()
            renderMarketplaceList()
            if (::softwareManagerScrollView.isInitialized) renderSoftwareManagerList()
            return
        }
        val env = currentLinuxEnv()
        if (mpBusy || isScriptRunning) {
            Toast.makeText(this, tr(R.string.main_another_install_is_running), Toast.LENGTH_SHORT).show()
            return
        }
        mpBusy = true
        Toast.makeText(this, tr(R.string.main_preparing_uninstall, id), Toast.LENGTH_SHORT).show()
        executor.execute {
            val prepared = PackageInstallRunner.prepareUninstall(this, cat, id, env)
            mainHandler.post {
                if (prepared.isFailure) {
                    mpBusy = false
                    Toast.makeText(
                        this,
                        prepared.exceptionOrNull()?.message ?: tr(R.string.main_prepare_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                startMarketplaceTerminal(prepared.getOrThrow())
            }
        }
    }


    /** Cyber-brutalist marketplace installer page — icon, status, log, DONE. */
    private fun buildMarketplaceInstallerLayout() {
        mpInstallerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(NC.BG)
            visibility = View.GONE
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#15121b"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        mpInstallerBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.ON_SURFACE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { leaveMarketplaceInstaller() }
        }
        mpInstallerTitleTv = TextView(this).apply {
            text = tr(R.string.main_installer)
            textSize = 16f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) }
        }
        topBar.addView(mpInstallerBackBtn)
        topBar.addView(mpInstallerTitleTv)
        mpInstallerLayout.addView(topBar)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        mpInstallerIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(14) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        mpInstallerNameTv = TextView(this).apply {
            text = tr(R.string.main_package)
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        mpInstallerMetaTv = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        }
        heroText.addView(mpInstallerNameTv)
        heroText.addView(mpInstallerMetaTv)
        mpInstallerStatusTv = TextView(this).apply {
            text = tr(R.string.main_caps_running)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#0A0A0A"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(NC.PRIMARY) }
        }
        hero.addView(mpInstallerIcon)
        hero.addView(heroText)
        hero.addView(mpInstallerStatusTv)
        mpInstallerLayout.addView(hero)

        mpInstallerPlanTv = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        mpInstallerLayout.addView(mpInstallerPlanTv)

        mpInstallerLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
            setBackgroundColor(NC.OUTLINE_VAR)
        })

        val termHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        mpInstallerTerminalView = TerminalView(this, null).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        termHost.addView(mpInstallerTerminalView)
        mpInstallerLayout.addView(termHost)

        mpInstallerFooter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            setBackgroundColor(NC.SURFACE_LOW)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        mpInstallerHintTv = TextView(this).apply {
            text = tr(R.string.main_installation_complete)
            textSize = 14f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        mpInstallerDoneBtn = TextView(this).apply {
            text = tr(R.string.main_done)
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#0A0A0A"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cyberBrutalistBg(
                fillColor = NC.PRIMARY,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3C4A3F")
            )
            setOnClickListener { leaveMarketplaceInstaller() }
        }
        mpInstallerFooter.addView(mpInstallerHintTv)
        mpInstallerFooter.addView(mpInstallerDoneBtn)
        mpInstallerLayout.addView(mpInstallerFooter)
    }

    /** Always return to Marketplace — never projects / home stack guess. */
    private fun leaveMarketplaceInstaller() {
        if (isScriptRunning && mpInstallerFinishedOk == null) {
            Toast.makeText(this, tr(R.string.main_install_still_running), Toast.LENGTH_SHORT).show()
            return
        }
        mpInstallerSession?.finishIfRunning()
        mpInstallerSession = null
        isScriptRunning = false
        mpBusy = false
        mpInstallerFinishedOk = null
        pendingMarketplaceRun = null
        // Drop installer from stack if present
        while (pageStack.isNotEmpty() && pageStack.peek() == ID_MP_INSTALLER) {
            pageStack.pop()
        }
        val target = when {
            pageStack.isNotEmpty() && pageStack.peek() == ID_APPS -> ID_APPS
            pageStack.isNotEmpty() && pageStack.peek() == ID_MARKETPLACE -> ID_MARKETPLACE
            else -> {
                pageStack.push(ID_MARKETPLACE)
                ID_MARKETPLACE
            }
        }
        navigateToPage(target, false)
        if (target == ID_MARKETPLACE) renderMarketplaceList()
        if (target == ID_APPS && ::appsScrollView.isInitialized) refreshAppsPage(force = true)
    }

    private fun setMpInstallerStatus(state: String, ok: Boolean?) {
        when {
            ok == true -> {
                mpInstallerStatusTv.text = tr(R.string.main_done)
                mpInstallerStatusTv.setTextColor(Color.parseColor("#0A0A0A"))
                mpInstallerStatusTv.background = GradientDrawable().apply { setColor(NC.PRIMARY) }
                mpInstallerHintTv.text = if (pendingMarketplaceRun?.uninstall == true)
                    "Uninstall complete" else tr(R.string.main_installation_complete)
                mpInstallerHintTv.setTextColor(NC.PRIMARY)
                mpInstallerDoneBtn.text = tr(R.string.main_done)
                mpInstallerFooter.visibility = View.VISIBLE
                mpInstallerBackBtn.visibility = View.VISIBLE
            }
            ok == false -> {
                mpInstallerStatusTv.text = tr(R.string.ssh_failed)
                mpInstallerStatusTv.setTextColor(Color.WHITE)
                mpInstallerStatusTv.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#93000A"))
                }
                mpInstallerHintTv.text = tr(R.string.main_install_failed_see_log_above)
                mpInstallerHintTv.setTextColor(NC.ERROR)
                mpInstallerDoneBtn.text = tr(R.string.term_close)
                mpInstallerFooter.visibility = View.VISIBLE
                mpInstallerBackBtn.visibility = View.VISIBLE
            }
            else -> {
                mpInstallerStatusTv.text = state
                mpInstallerStatusTv.setTextColor(Color.parseColor("#0A0A0A"))
                mpInstallerStatusTv.background = GradientDrawable().apply { setColor(NC.PRIMARY) }
                mpInstallerFooter.visibility = View.GONE
            }
        }
    }

    /**
     * Custom marketplace installer: package hero + live terminal log + DONE footer.
     */
    private fun startMarketplaceTerminal(run: PackageInstallRunner.PreparedRun) {
        pendingMarketplaceRun = run
        mpInstallerFinishedOk = null
        val primary = run.packages.lastOrNull()
        val action = if (run.uninstall) tr(R.string.main_uninstall_3) else tr(R.string.chat_install)
        mpInstallerTitleTv.text = "$action · ${run.method}"
        mpInstallerNameTv.text = primary?.name ?: run.packageIds.joinToString()
        mpInstallerMetaTv.text = buildString {
            append(if (run.uninstall) "uninstall" else "install")
            append(" · ")
            append(run.method)
            if (primary?.experimental == true) append(" · experimental")
            if (primary?.kind == PackageKind.APP) append(" · app")
        }
        mpInstallerPlanTv.text = "plan: ${run.packageIds.joinToString(" → ")}"
        if (primary != null) {
            MarketplaceIcons.bind(mpInstallerIcon, primary)
        } else {
            mpInstallerIcon.setImageResource(R.drawable.mp_ic_package)
        }
        setMpInstallerStatus("RUNNING", null)
        mpInstallerBackBtn.visibility = View.GONE
        mpInstallerTerminalView.setTextSize(scriptFontSize)

        val nld = applicationInfo.nativeLibraryDir
        val shell = File(nld, "libbash.so").absolutePath
        val cwd = File(filesDir, "home").absolutePath
        val env = run.envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        val args = run.args

        val scriptViewClient = object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    scriptFontSize =
                        (scriptFontSize * scale).toInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                    mpInstallerTerminalView.setTextSize(scriptFontSize)
                    return 1.0f
                }
                return scale
            }
            override fun onSingleTapUp(e: MotionEvent) {}
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = false
            override fun copyModeChanged(active: Boolean) {}
            override fun onKeyDown(
                keyCode: Int,
                event: android.view.KeyEvent,
                session: TerminalSession
            ): Boolean = true
            override fun onKeyUp(
                keyCode: Int,
                event: android.view.KeyEvent
            ): Boolean = false
            override fun onLongPress(event: MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(
                codePoint: Int,
                ctrlDown: Boolean,
                session: TerminalSession
            ): Boolean = true
            override fun onEmulatorSet() {}
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(
                tag: String,
                message: String,
                e: Exception
            ) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }

        val scriptSessionClient = object : TerminalSessionClient {
            override fun onTextChanged(session: TerminalSession) {
                mpInstallerTerminalView.onScreenUpdated()
            }
            override fun onTitleChanged(session: TerminalSession) {}
            override fun onSessionFinished(session: TerminalSession) {
                val code = session.exitStatus
                Log.d("Marketplace", "installer finished exit=$code title=${run.title}")
                mainHandler.post {
                    isScriptRunning = false
                    mpBusy = false
                    val ok = code == 0
                    mpInstallerFinishedOk = ok
                    val pending = pendingMarketplaceRun
                    if (pending != null) {
                        PackageInstallRunner.applyRegistryAfterRun(
                            this@MainActivity, pending, ok
                        )
                    }
                    setMpInstallerStatus(if (ok) tr(R.string.main_done) else tr(R.string.ssh_failed), ok)
                    smPackages = emptyList()
                    smScanEnv = null
                    if (::marketplaceScrollView.isInitialized) renderMarketplaceList()
                    if (::appsScrollView.isInitialized) {
                        // Registry may have changed; refresh cache for Apps page
                        appsItems = emptyList()
                        if (appsScrollView.visibility == View.VISIBLE) {
                            refreshAppsPage(force = true)
                        }
                    }
                    if (::softwareManagerScrollView.isInitialized &&
                        softwareManagerScrollView.visibility == View.VISIBLE
                    ) {
                        refreshSoftwareManagerPage(force = true)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) {
                            if (run.uninstall) tr(R.string.main_uninstalled)
                            else "Installed ${run.packageIds.joinToString()}"
                        } else tr(R.string.main_failed_exit, code),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)
                    ?.coerceToText(this@MainActivity)?.toString() ?: return
                executor.execute {
                    session.emulator?.paste(text) ?: session.write(text)
                }
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = 1
            override fun logError(tag: String, message: String) { Log.e(tag, message) }
            override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
            override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
            override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
            override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
            override fun logStackTraceWithMessage(
                tag: String,
                message: String,
                e: java.lang.Exception
            ) { Log.e(tag, message, e) }
            override fun logStackTrace(tag: String, e: java.lang.Exception) {
                Log.e(tag, "Stacktrace", e)
            }
        }

        mpInstallerSession?.finishIfRunning()
        scriptInstallSession?.finishIfRunning()
        mpInstallerTerminalView.setTerminalViewClient(scriptViewClient)
        val session = TerminalSession(shell, cwd, args, env, 10000, scriptSessionClient)
        mpInstallerSession = session
        mpInstallerTerminalView.attachSession(session)
        isScriptRunning = true

        // Keep Marketplace or Apps under installer on stack
        while (pageStack.isNotEmpty() && pageStack.peek() == ID_MP_INSTALLER) {
            pageStack.pop()
        }
        val under = if (pageStack.isNotEmpty()) pageStack.peek() else null
        if (under != ID_MARKETPLACE && under != ID_APPS) {
            pageStack.push(ID_MARKETPLACE)
        }
        pageStack.push(ID_MP_INSTALLER)
        navigateToPage(ID_MP_INSTALLER, false)
    }

    private fun launchMarketplaceApp(pkg: MpPackage) {
        val env = currentLinuxEnv()
        val entry = InstallRegistry.get(this, env, pkg.id) ?: return
        // Require GUI already running (locked product decision)
        Toast.makeText(this, tr(R.string.main_ensure_graphical_desktop_is_running), Toast.LENGTH_SHORT).show()
        try {
            desktop.view(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } catch (_: Exception) { /* ignore */ }
        mpBusy = true
        appendMpLog(tr(R.string.main_launch_2, pkg.launch?.command))
        executor.execute {
            PackageInstallRunner.launchApp(this, entry, env) { ev ->
                mainHandler.post {
                    when (ev) {
                        is InstallEvent.Log -> appendMpLog(ev.line)
                        is InstallEvent.Finished -> {
                            mpBusy = false
                            appendMpLog(if (ev.success) "launched" else "launch failed")
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun glassCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cyberBrutalistBg(
            fillColor = NC.SURFACE_LOW,
            strokeColor = NC.OUTLINE_VAR,
            shadowColor = NC.SHADOW_DARK,
            offsetDp = 6,
            cornerRadiusDp = 0
        )
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    private fun sectionHeader(iconRes: Int, title: String, iconColor: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10))
        val iconIv = ImageView(this@MainActivity).apply {
            setImageResource(iconRes)
            setColorFilter(iconColor)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
        }
        val titleTv = TextView(this@MainActivity).apply { text = title; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        addView(iconIv); addView(titleTv)
    }
    private fun infoRow(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(NC.SURFACE_LOWEST)
            setStroke(dp(1), NC.OUTLINE_VAR)
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)

        val labelTv = TextView(this@MainActivity).apply {
            text = label.uppercase()
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val valTv = TextView(this@MainActivity).apply {
            text = value
            textSize = 12f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
        }
        addView(labelTv)
        addView(valTv)
    }
    private fun inputField(label: String, hint: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(10))
        addView(TextView(this@MainActivity).apply { text = label; textSize = 10f; setTextColor(NC.ON_SURF_VAR); typeface = Typeface.MONOSPACE; setPadding(0, 0, 0, dp(4)) })
        addView(EditText(this@MainActivity).apply { this.hint = hint; setHintTextColor(NC.OUTLINE); textSize = 13f; setTextColor(NC.ON_SURFACE); typeface = Typeface.MONOSPACE; setBackgroundColor(NC.SURFACE_HIGH); background = roundedBg(NC.SURFACE_HIGH, NC.BORDER_VAR, dp(6)); setPadding(dp(12), dp(10), dp(12), dp(10)) })
    }

    private fun lineNumCell(num: String) = TextView(this).apply { text = num; textSize = 11f; setTextColor(NC.OUTLINE); typeface = Typeface.MONOSPACE; gravity = Gravity.END; setPadding(dp(6), 0, dp(6), 0); layoutParams = LinearLayout.LayoutParams(dp(36), WRAP) }
    private fun textBadge(text: String, bg: Int, fg: Int) = TextView(this).apply { this.text = text; textSize = 10f; setTextColor(fg); typeface = Typeface.MONOSPACE; background = roundedBg(bg, fg, dp(4)); setPadding(dp(6), dp(2), dp(6), dp(2)) }
    private fun primaryBtn(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.parseColor("#0A0A0A")); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        background = cyberBrutalistBg(
            fillColor = Color.parseColor("#3DDC84"),
            strokeColor = Color.parseColor("#3DDC84"),
            shadowColor = Color.parseColor("#393939"),
            offsetDp = 6,
            cornerRadiusDp = 0,
            rightFaceColor = Color.parseColor("#3C4A3F")
        )
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }
    private fun outlineBtn(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.parseColor("#FAFAFA")); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        background = cyberBrutalistBg(
            fillColor = Color.parseColor("#1E1E1E"),
            strokeColor = Color.parseColor("#3DDC84"),
            shadowColor = Color.parseColor("#393939"),
            offsetDp = 6,
            cornerRadiusDp = 0,
            rightFaceColor = Color.parseColor("#3C4A3F")
        )
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }
    private fun spacer(dp_: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(dp_)) }
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /**
     * Obsidian Terminal scrollbar (ui_design.md): sharp 3dp thumb Terminal Green on dark track.
     * API 29+ uses custom drawables; older APIs keep system bar but still force-visible.
     */
    private fun applyDesignScrollbar(view: View) {
        view.isVerticalScrollBarEnabled = true
        view.isHorizontalScrollBarEnabled = false
        view.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        view.isScrollbarFadingEnabled = false
        view.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.verticalScrollbarThumbDrawable =
                resources.getDrawable(R.drawable.scrollbar_thumb_vertical, theme)
            view.verticalScrollbarTrackDrawable =
                resources.getDrawable(R.drawable.scrollbar_track_vertical, theme)
        }
    }

    /**
     * ScrollView that wins nested vertical drags against an outer page ScrollView.
     * Must use dispatchTouchEvent (not OnTouchListener): touches land on child rows,
     * so a listener on the ScrollView never sees MOVE and the outer page steals scroll.
     */
    private class NestedInnerScrollView(context: android.content.Context) : ScrollView(context) {
        private var lastY = 0f

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = ev.y
                    // Claim before outer ScrollView intercepts MOVE
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - lastY
                    lastY = ev.y
                    if (dy != 0f) {
                        val dir = if (dy < 0f) 1 else -1
                        // At edge in drag direction → let outer page scroll
                        parent?.requestDisallowInterceptTouchEvent(canScrollVertically(dir))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }
    private fun roundedBg(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(fill); setStroke(dp(1), stroke); cornerRadius = r.toFloat() }
    private fun circleDrawable(color: Int) = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply { paint.color = color }
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── Setup & shell integrations (original working logic preserved) ─────────

    private fun onSetupComplete() {
        mainHandler.post {
            desktop.syncUi()
            if (::stopGuiBtn.isInitialized) {
                stopGuiBtn.isEnabled = true; stopGuiBtn.alpha = 1f
            }
            if (::homeStatusLabel.isInitialized) {
                homeStatusLabel.text = tr(R.string.main_ready)
            }
            initTerminalView()
        }
    }

    private fun deployScripts() {
        try {
            TermuxHostPaths.applyPackageToExtractedPrefix(filesDir)
            val appLibDir = File(filesDir.parentFile, "lib")
            appLibDir.mkdirs()
            val srcXlorie = File(applicationInfo.nativeLibraryDir, "libXlorie.so")
            val dstXlorie = File(appLibDir, "libXlorie.so")
            if (srcXlorie.exists() && (!dstXlorie.exists() || dstXlorie.length() != srcXlorie.length())) {
                srcXlorie.copyTo(dstXlorie, overwrite = true)
            }
            val homeDir = File(filesDir, "home").also { it.mkdirs() }
            val scripts = arrayOf(
                "setup_termux.sh",
                "flux_install.sh",
                "start_gui.sh",
                "stop_gui.sh",
                "start_gui_chroot.sh",
                "stop_gui_chroot.sh",
                "start_alpine_gui.sh",
                "stop_alpine_gui.sh",
                "start_debian13_gui.sh",
                "stop_debian13_gui.sh",
                "setup_alpine_family.sh",
                "setup_debian_family.sh",
                "setup_xfce4.sh",
                "setup_customization_alpine.sh",
                "setup_customization_debian.sh",
                "setup_hw_accel_debian.sh",
                "setup_cli_tools.sh",
                "setup_alpine_chroot.sh",
                "setup_debian13_chroot.sh",
                "uninstall_alpine_chroot.sh",
                "uninstall_debian13_chroot.sh",
                "chroot_processes.sh",
                "chroot_size.sh",
                "nativecode_chroot.sh"
            ).let { all ->
                if (com.zenithblue.nativecode.ProotOnly.ON) {
                    all.filter { it in setOf("setup_termux.sh", "flux_install.sh", "start_gui.sh", "stop_gui.sh") }
                        .toTypedArray()
                } else all
            }
            for (script in scripts) {
                val assetPath = when {
                    script.contains("chroot") ||
                        script.contains("alpine_gui") ||
                        script == "start_debian13_gui.sh" ||
                        script == "stop_debian13_gui.sh" ||
                        script == "nativecode_chroot.sh" -> "scripts/chroot/$script"
                    else -> "scripts/$script"
                }
                val out = File(homeDir, script)
                try {
                    assets.open(assetPath).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
                    out.setExecutable(true)
                } catch (e: Exception) {
                    Log.w("Setup", "Script $assetPath not found in assets", e)
                }
            }
            // Deploy font.ttf
            val termuxDir = File(homeDir, ".termux").also { it.mkdirs() }
            val fontOut = File(termuxDir, "font.ttf")
            assets.open("fonts/font.ttf").use { input -> FileOutputStream(fontOut).use { input.copyTo(it) } }
            // Pinned Alpine rootfs for proot-distro install ./file --name alpine
            deployRootfsArchive(homeDir)
        } catch (e: Exception) {
            Log.e("Setup", "Failed to deploy scripts", e)
        }
    }

    /** Copy assets/rootfs/alpine_rootfs.tar.gz → $HOME (skip if already present & large). */
    private fun deployRootfsArchive(homeDir: File) {
        val out = File(homeDir, "alpine_rootfs.tar.gz")
        // Skip re-copy once deployed
        if (out.isFile && out.length() > 2L * 1024L * 1024L) {
            Log.i("Setup", "Rootfs already deployed: ${out.absolutePath} (${out.length()} bytes)")
            return
        }
        try {
            assets.open("rootfs/alpine_rootfs.tar.gz").use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            Log.i("Setup", "Deployed rootfs: ${out.absolutePath} (${out.length()} bytes)")
        } catch (e: Exception) {
            Log.e("Setup", "Failed to deploy rootfs archive from assets", e)
        }
    }

    // ── Graphical Desktop card painting (logic lives in desktop/DesktopController) ──

    /** Disable START on Settings + Home while desktop start is in flight. */
    private fun setGuiStartBusy() {
        if (::startGuiBtn.isInitialized) {
            startGuiBtn.isEnabled = false
            startGuiBtn.alpha = 0.5f
            startGuiBtn.text = tr(R.string.ssh_starting_2)
        }
    }

    /** Set STOP busy state with immediate feedback on UI. */
    private fun setGuiStopBusy() {
        if (::stopGuiBtn.isInitialized) {
            stopGuiBtn.isEnabled = false
            stopGuiBtn.alpha = 0.5f
            stopGuiBtn.text = tr(R.string.main_stopping)
        }
    }

    private fun isXfceInstalled(): Boolean {
        val method = getSharedPreferences("nativecode_prefs", MODE_PRIVATE).getString("linux_method", "proot") ?: "proot"
        val checkDirs = if (!com.zenithblue.nativecode.ProotOnly.ON && method == "chroot") {
            listOf(File(ChrootCommandBuilder.CHROOT_PATH))
        } else {
            listOf(
                ProjectPathResolver.prootRootfsDir(this),
                File(TermuxHostPaths.PREFIX, "var/lib/proot-distro/containers/alpaquita/rootfs"),
                File(TermuxHostPaths.PREFIX, "var/lib/proot-distro/containers/alpine/rootfs")
            )
        }
        return checkDirs.any { root ->
            File(root, "usr/local/bin/phoc").exists() ||
                File(root, "usr/local/bin/nativecode-desktop").exists() ||
                File(root, "usr/bin/startxfce4").exists() ||
                File(root, "usr/local/bin/startxfce4").exists()
        }
    }

    /** Free tier: idle START shows today's remaining desktop minutes; lock at 0. No-op unless idle START. */
    private fun paintGuiBudget() {
        if (!::startGuiBtn.isInitialized || !startGuiBtn.text.startsWith(tr(R.string.main_caps_start_phosh_desktop))) return
        if (!proLocked()) {
            startGuiBtn.text = tr(R.string.main_caps_start_phosh_desktop)
            startGuiBtn.markProLocked(false)
            return
        }
        val min = (desktop.remainingMs + 59_999) / 60_000
        startGuiBtn.text = tr(R.string.main_start_phosh_desktop_min_left, min)
        startGuiBtn.markProLocked(min == 0L)
    }

    /** Idle: full-width START only (no VIEW LOGS). */
    private fun setGuiCardIdle() {
        if (!::startGuiBtn.isInitialized || !::stopGuiBtn.isInitialized) return
        startGuiBtn.visibility = View.VISIBLE
        val installed = isXfceInstalled()
        if (installed) {
            startGuiBtn.text = tr(R.string.main_caps_start_phosh_desktop)
            startGuiBtn.isEnabled = true
            startGuiBtn.alpha = 1f
            paintGuiBudget()
        } else {
            startGuiBtn.text = tr(R.string.main_phosh_not_installed)
            startGuiBtn.isEnabled = false
            startGuiBtn.alpha = 0.4f
        }
        startGuiBtn.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        stopGuiBtn.visibility = View.GONE
        stopGuiBtn.isEnabled = false
        stopGuiBtn.alpha = 0.5f
        stopGuiBtn.text = tr(R.string.main_stop_phosh_desktop)
    }

    /** Running: full-width STOP. Log capture stays; no VIEW LOGS button. */
    private fun setGuiCardRunning() {
        if (!::startGuiBtn.isInitialized || !::stopGuiBtn.isInitialized) return
        startGuiBtn.visibility = View.GONE
        stopGuiBtn.visibility = View.VISIBLE
        stopGuiBtn.isEnabled = true
        stopGuiBtn.alpha = 1f
        stopGuiBtn.text = tr(R.string.main_stop_phosh_desktop)
        stopGuiBtn.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    /** Desktop start/stop/view/state/budget: desktop/DesktopController. This activity only paints. */
    private val desktop: DesktopController by lazy {
        DesktopController(this, executor, mainHandler, object : DesktopController.Host {
            override fun isPro() = this@MainActivity.isPro()
            override fun gateAllows(result: GateResult) = this@MainActivity.gateAllows(result)
            override fun showUpgradeSheet(reason: String?) = this@MainActivity.showUpgradeSheet(reason)
            override fun ensureBootstrapExtracted() = this@MainActivity.ensureBootstrapExtracted()
            override fun deployScripts() = this@MainActivity.deployScripts()
            override fun paintStartBusy() = setGuiStartBusy()
            override fun paintStopBusy() = setGuiStopBusy()
            override fun paintIdle() = setGuiCardIdle()
            override fun paintRunning() = setGuiCardRunning()
            override fun stopRowVisible() = ::stopGuiBtn.isInitialized && stopGuiBtn.visibility == View.VISIBLE
            override fun desktopInstalled() = isXfceInstalled()
            override fun openLogs() = openGuiLogPage()
        })
    }

    /** VIEW LOGS from Graphical Desktop card → short host cat on the script page. */
    private fun openGuiLogPage() {
        val f = desktop.logFile()
        if (!f.exists() || f.length() == 0L) {
            Toast.makeText(this, tr(R.string.main_no_desktop_log_yet), Toast.LENGTH_SHORT).show()
            return
        }
        if (isScriptRunning) {
            // Real setup/uninstall in progress — do not steal the shared session
            Toast.makeText(this, tr(R.string.main_script_runner_busy), Toast.LENGTH_SHORT).show()
            return
        }
        showLogFileInScriptPage(f, title = tr(R.string.main_graphical_desktop_log))
    }

    /**
     * Short host `cat` of a log file on script-install chrome.
     * Uses /system/bin/sh (not libbash) so missing host libandroid-support.so
     * cannot block log viewing. isScriptRunning only for cat duration (ms).
     * Isolation builders untouched.
     */
    private fun showLogFileInScriptPage(file: File, title: String) {
        if (::scriptInstallTerminalView.isInitialized) {
            scriptInstallTerminalView.setTextSize(scriptFontSize)
        }
        if (::scriptInstallTitleTv.isInitialized) {
            scriptInstallTitleTv.text = title
        }

        // System shell only — never libbash.so (avoids libandroid-support link errors)
        val shell = "/system/bin/sh"
        val cwd = File(filesDir, "home").absolutePath
        val quoted = file.absolutePath.replace("'", "'\\''")
        val args = arrayOf(shell, "-c", "cat '$quoted'")
        // Minimal env — no Termux LD_* required for /system/bin/sh + cat
        val env = arrayOf(
            "PATH=/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "HOME=$cwd"
        )

        val logViewClient = object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    scriptFontSize = (scriptFontSize * scale).toInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                    scriptInstallTerminalView.setTextSize(scriptFontSize)
                    return 1.0f
                }
                return scale
            }
            override fun onSingleTapUp(e: MotionEvent) {}
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean      = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean      = false
            override fun isTerminalViewSelected(): Boolean            = false
            override fun copyModeChanged(active: Boolean) {}
            override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent, session: TerminalSession): Boolean = true
            override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean = true
            override fun onLongPress(e: MotionEvent): Boolean = true
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean     = false
            override fun readShiftKey(): Boolean   = false
            override fun readFnKey(): Boolean      = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = true
            override fun onEmulatorSet() {}
            override fun logError(tag: String, message: String)   {}
            override fun logWarn(tag: String, message: String)    {}
            override fun logInfo(tag: String, message: String)    {}
            override fun logDebug(tag: String, message: String)   {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }

        val logSessionClient = object : TerminalSessionClient {
            override fun onTextChanged(session: TerminalSession) {
                scriptInstallTerminalView.onScreenUpdated()
            }
            override fun onTitleChanged(session: TerminalSession) {}
            override fun onSessionFinished(session: TerminalSession) {
                mainHandler.post {
                    isScriptRunning = false
                    if (pageStack.isNotEmpty() && pageStack.peek() == ID_SCRIPT_INSTALL &&
                        ::scriptInstallBackBtn.isInitialized
                    ) {
                        scriptInstallBackBtn.visibility = View.VISIBLE
                    }
                }
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: return
                executor.execute {
                    session.emulator?.paste(text) ?: session.write(text)
                }
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = 1
            override fun logError(tag: String, message: String) { Log.e(tag, message) }
            override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
            override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
            override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
            override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
            override fun logStackTraceWithMessage(tag: String, message: String, e: java.lang.Exception) { Log.e(tag, message, e) }
            override fun logStackTrace(tag: String, e: java.lang.Exception) { Log.e(tag, "Stacktrace", e) }
        }

        // Finish prior session so we never stack libbash leftovers
        scriptInstallSession?.finishIfRunning()
        scriptInstallTerminalView.setTerminalViewClient(logViewClient)
        val session = TerminalSession(shell, cwd, args, env, 10000, logSessionClient)
        scriptInstallSession = session
        scriptInstallTerminalView.attachSession(session)
        isScriptRunning = true
        if (::scriptInstallBackBtn.isInitialized) {
            scriptInstallBackBtn.visibility = View.GONE
        }

        if (pageStack.isEmpty() || pageStack.peek() != ID_SCRIPT_INSTALL) {
            pageStack.push(ID_SCRIPT_INSTALL)
        }
        navigateToPage(ID_SCRIPT_INSTALL)
    }

    /** After a recreate (language change): shells and tabs are still in [TerminalStore]; re-point their clients here and re-attach. */
    private fun adoptTerminals() {
        val st = TerminalStore
        if (st.sessions.isEmpty() && st.projectSessions.isEmpty()) return
        val oldApp = st.appClient
        if (st.sessions.isNotEmpty() || oldApp != null) {
            initTerminalView()
            val fresh = sessionClient as NativeCodeTerminalSessionClient
            if (oldApp != null) { oldApp.rebindFrom(fresh); sessionClient = oldApp }
            st.appClient = sessionClient as NativeCodeTerminalSessionClient
            sessionsList.getOrNull(activeSessionIndex)?.let { switchTerminalSession(activeSessionIndex) }
        }
        val freshWs = newWsClient()
        st.wsClients.forEach { it.rebindFrom(freshWs) }
    }

    private fun initTerminalView() {
        terminalView.setTextSize(termFontSize)
        try {
            val tf = Typeface.createFromAsset(assets, "fonts/font.ttf")
            terminalView.setTypeface(tf)
        } catch (e: Exception) {
            Log.e("Terminal", "Failed to set custom typeface", e)
        }

        viewClient = NativeCodeTerminalViewClient(
            context = this,
            terminalViewRef = { if (::terminalView.isInitialized) terminalView else null },
            modState = termModState,
            onScaleChanged = { scale ->
                val nextSize = (termFontSize * scale).toInt()
                setGlobalTerminalZoom(nextSize)
            }
        )

        sessionClient = NativeCodeTerminalSessionClient(
            context = this,
            terminalViewRef = { if (::terminalView.isInitialized) terminalView else null },
            activeSessionRef = { terminalSession },
            onSessionFinishedCallback = { session ->
                mainHandler.post {
                    val idx = sessionsList.indexOf(session)
                    if (idx != -1) {
                        closeTerminalSession(idx)
                    }
                }
            },
            onPreviewUrl = { url -> showPreviewChip(url) }
        )
        TerminalStore.appClient = sessionClient as NativeCodeTerminalSessionClient

        terminalView.setTerminalViewClient(viewClient)
    }

    override fun onDestroy() {
        terminalLifecycleGeneration++
        if (NativeCodeBridge.agentUiDelegate === this) {
            NativeCodeBridge.agentUiDelegate = null
        }
        ghAuthSession?.cancel()
        dismissGithubAuthOverlay()
        super.onDestroy()
        resourceMonitorRunnable?.let { mainHandler.removeCallbacks(it) }
        desktop.onHostDestroyed(isChangingConfigurations)
        if (AgentChats.onChanged === chatsChanged) AgentChats.onChanged = null
        if (AgentChats.gate === chatGate) AgentChats.gate = null
        // A recreate (language change) keeps shells, tab state and the service: the new Activity adopts them from
        // TerminalStore. A real finish (back, swipe-away) ends every tab, workspace and background-project ones too,
        // or they run on uncounted (R4 B5), and stops chats (process-scoped: a recreate keeps them).
        if (isChangingConfigurations) {
            saveActiveProjectSessions()
            TerminalStore.restorePage = currentPageId
        } else {
            for (session in (sessionsList + workspaceSessions + projectSessionsMap.values.flatten()).distinct()) {
                session.finishIfRunning()
            }
            TerminalStore.clear()
            AgentChats.stopAll()
            if (AgentChats.liveCount() + AgentChats.startingCount() == 0) {
                stopService(Intent(this, TerminalService::class.java))
            }
        }
        scriptInstallSession?.finishIfRunning()
        mpInstallerSession?.finishIfRunning()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        if (v === terminalView || v === workspaceTerminalView) {
            TerminalContextMenuHelper.onCreateContextMenu(this, menu, v)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val tv = if (terminalView.hasFocus()) terminalView else workspaceTerminalView
        val session = tv.getCurrentSession()
        val customTf = try { Typeface.createFromAsset(assets, "fonts/font.ttf") } catch (_: Exception) { null }
        if (TerminalContextMenuHelper.onContextItemSelected(this, item, tv, session, customTf)) {
            return true
        }
        return super.onContextItemSelected(item)
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        for (tv in arrayOf(terminalView, workspaceTerminalView)) {
            try {
                val method = tv.javaClass.getMethod("onContextMenuClosed", Menu::class.java)
                method.invoke(tv, menu)
            } catch (e: Exception) {
                try {
                    val method = tv.javaClass.getMethod("unsetStoredSelectedText")
                    method.invoke(tv)
                } catch (ex: Exception) {}
            }
        }
    }

    // ── Show page helpers ────────────────────────────────────────────────────

    private fun showHome() {
        bottomNavigation.selectedItemId = ID_HOME
        navigateToPage(ID_HOME)
    }

    private fun iconButton(icon: String): TextView = TextView(this).apply {
        text = icon; textSize = 18f; setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.parseColor("#0A0A0A"))
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        background = cyberBrutalistBg(
            fillColor = NC.PRIMARY_CON,
            strokeColor = Color.parseColor("#3C4A3F"),
            shadowColor = NC.SHADOW_DARK,
            offsetDp = 6,
            cornerRadiusDp = 0,
            rightFaceColor = Color.parseColor("#3C4A3F")
        )
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setOnClickListener { onClick() }
        setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.translationX = dp(4).toFloat()
                    v.translationY = dp(4).toFloat()
                    v.background = cyberBrutalistBg(
                        fillColor = NC.PRIMARY_CON,
                        strokeColor = Color.parseColor("#3C4A3F"),
                        shadowColor = NC.SHADOW_DARK,
                        offsetDp = 2,
                        cornerRadiusDp = 0,
                        rightFaceColor = Color.parseColor("#3C4A3F")
                    )
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.translationX = 0f
                    v.translationY = 0f
                    v.background = cyberBrutalistBg(
                        fillColor = NC.PRIMARY_CON,
                        strokeColor = Color.parseColor("#3C4A3F"),
                        shadowColor = NC.SHADOW_DARK,
                        offsetDp = 6,
                        cornerRadiusDp = 0,
                        rightFaceColor = Color.parseColor("#3C4A3F")
                    )
                }
            }
            false
        }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(NC.ON_SURFACE)
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        background = cyberBrutalistBg(
            fillColor = NC.SURFACE_CONTAINER,
            strokeColor = NC.PRIMARY,
            shadowColor = NC.SHADOW_DARK,
            offsetDp = 4,
            cornerRadiusDp = 0,
            rightFaceColor = Color.parseColor("#3C4A3F")
        )
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
        setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.translationX = dp(2).toFloat()
                    v.translationY = dp(2).toFloat()
                    v.background = cyberBrutalistBg(
                        fillColor = NC.SURFACE_CONTAINER,
                        strokeColor = NC.PRIMARY,
                        shadowColor = NC.SHADOW_DARK,
                        offsetDp = 2,
                        cornerRadiusDp = 0,
                        rightFaceColor = Color.parseColor("#3C4A3F")
                    )
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.translationX = 0f
                    v.translationY = 0f
                    v.background = cyberBrutalistBg(
                        fillColor = NC.SURFACE_CONTAINER,
                        strokeColor = NC.PRIMARY,
                        shadowColor = NC.SHADOW_DARK,
                        offsetDp = 4,
                        cornerRadiusDp = 0,
                        rightFaceColor = Color.parseColor("#3C4A3F")
                    )
                }
            }
            false
        }
    }

    private fun dangerButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        background = cyberBrutalistBg(
            fillColor = Color.parseColor("#93000A"),
            strokeColor = Color.parseColor("#FFB4AB"),
            shadowColor = NC.SHADOW_DARK,
            offsetDp = 4,
            cornerRadiusDp = 0,
            rightFaceColor = Color.parseColor("#690005")
        )
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun statWidget(label: String, value: String, color: Int): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val labelTv = TextView(this).apply { text = label; textSize = 12f; setTextColor(color); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER }
        val valueTv = TextView(this).apply { tag = label; text = value; textSize = 20f; setTextColor(NC.ON_SURFACE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        col.addView(labelTv); col.addView(valueTv); return col
    }

    private fun isProjectEmojiIcon(iconStr: String): Boolean =
        iconStr.isNotEmpty() &&
            iconStr.length <= 4 &&
            !iconStr.startsWith("/") &&
            !iconStr.startsWith("http") &&
            !iconStr.startsWith("content")

    private fun getCachedProjectIcon(iconStr: String): android.graphics.Bitmap? {
        if (iconStr.isEmpty()) return null
        projectIconCache.get(iconStr)?.let { return it }
        if (iconStr == cachedProjectIconPath) return cachedProjectIconBitmap
        return null
    }

    /** Decode + cache project icon; call off main thread. */
    private fun decodeProjectIconBitmap(iconStr: String): android.graphics.Bitmap? {
        if (iconStr.isEmpty() || isProjectEmojiIcon(iconStr)) return null
        getCachedProjectIcon(iconStr)?.let { return it }
        return try {
            val raw = when {
                iconStr.startsWith("content://") -> {
                    contentResolver.openInputStream(android.net.Uri.parse(iconStr))?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }
                iconStr.startsWith("http://") || iconStr.startsWith("https://") -> {
                    java.net.URL(iconStr).openStream().use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }
                else -> android.graphics.BitmapFactory.decodeFile(iconStr)
            } ?: return null
            val maxPx = 128
            val bitmap = if (raw.width > maxPx || raw.height > maxPx) {
                val scaled = android.graphics.Bitmap.createScaledBitmap(raw, maxPx, maxPx, true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else {
                raw
            }
            projectIconCache.put(iconStr, bitmap)
            cachedProjectIconPath = iconStr
            cachedProjectIconBitmap = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fill [container] with project icon. Uses cache for instant paint (no blink on list rebuild).
     */
    private fun bindProjectIconContainer(
        container: FrameLayout,
        iconStr: String,
        glyphSizeDp: Int
    ) {
        container.removeAllViews()
        when {
            iconStr.isEmpty() -> {
                container.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_folder_special)
                    setColorFilter(NC.PRIMARY)
                    layoutParams = FrameLayout.LayoutParams(dp(glyphSizeDp), dp(glyphSizeDp), Gravity.CENTER)
                })
            }
            isProjectEmojiIcon(iconStr) -> {
                container.addView(TextView(this).apply {
                    text = iconStr
                    textSize = if (glyphSizeDp >= 28) 24f else 20f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                })
            }
            else -> {
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                    tag = iconStr
                }
                container.addView(iv)
                val cached = getCachedProjectIcon(iconStr)
                if (cached != null) {
                    iv.setImageBitmap(cached)
                } else {
                    // Placeholder until decode finishes (never empty flash after first load)
                    iv.setImageResource(R.drawable.ic_folder_special)
                    iv.setColorFilter(NC.PRIMARY)
                    iv.scaleType = ImageView.ScaleType.CENTER
                    executor.execute {
                        val bmp = decodeProjectIconBitmap(iconStr)
                        mainHandler.post {
                            if (iv.tag != iconStr) return@post
                            if (bmp != null) {
                                iv.clearColorFilter()
                                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                                iv.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Workspace / top-bar ImageView: cache-first, never hide while same path reloads. */
    private fun bindWorkspaceProjectIcon(imageView: ImageView, iconStr: String) {
        if (iconStr.isEmpty() || isProjectEmojiIcon(iconStr)) {
            imageView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        imageView.tag = iconStr
        val cached = getCachedProjectIcon(iconStr)
        if (cached != null) {
            imageView.clearColorFilter()
            imageView.setImageBitmap(cached)
            return
        }
        executor.execute {
            val bmp = decodeProjectIconBitmap(iconStr)
            mainHandler.post {
                if (imageView.tag != iconStr) return@post
                if (bmp != null) {
                    imageView.clearColorFilter()
                    imageView.setImageBitmap(bmp)
                    imageView.visibility = View.VISIBLE
                } else {
                    imageView.visibility = View.GONE
                }
            }
        }
    }

    private fun projectCard(name: String, path: String, time: String, iconStr: String = ""): View {
        val isGit = path.endsWith(".git") || File(path, ".git").exists() || path.contains("git", ignoreCase = true) || !name.contains("ui_shell", ignoreCase = true)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))

            val iconContainer = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    rightMargin = dp(14)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_LOWEST)
                    setStroke(dp(1), Color.parseColor("#3360F99E"))
                    cornerRadius = dp(4).toFloat()
                }
            }
            bindProjectIconContainer(iconContainer, iconStr, glyphSizeDp = 22)

            val infoCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }

            val titleTv = TextView(this@MainActivity).apply {
                text = name.uppercase()
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            val timeTv = TextView(this@MainActivity).apply {
                text = if (time.startsWith("UPDATED:", ignoreCase = true)) time.uppercase() else "UPDATED: ${time.uppercase()}"
                textSize = 11f
                setTextColor(Color.parseColor("#66FFFFFF"))
                typeface = Typeface.MONOSPACE
            }
            if (proLocked()) {
                timeTv.text = "${timeTv.text} · PRO"
                timeTv.markProLocked()
                contentDescription = "$name, Pro required"
            }
            infoCol.addView(titleTv)
            infoCol.addView(timeTv)

            val badgeBox = TextView(this@MainActivity).apply {
                text = if (isGit) "GIT" else tr(R.string.main_local)
                textSize = 11f
                setTextColor(if (isGit) NC.PRIMARY else Color.WHITE)
                typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_LOWEST)
                    setStroke(dp(1), if (isGit) Color.parseColor("#3360F99E") else Color.parseColor("#33FFFFFF"))
                }
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }

            val actionIcon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(10) }
            }

            addView(iconContainer)
            addView(infoCol)
            addView(actionIcon)

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = Color.parseColor("#3c4a3f"),
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0,
                            rightFaceColor = Color.parseColor("#3c4a3f")
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_LOW,
                            strokeColor = Color.parseColor("#3c4a3f"),
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 6,
                            cornerRadiusDp = 0,
                            rightFaceColor = Color.parseColor("#3c4a3f")
                        )
                    }
                }
                false
            }
        }
    }

    private fun pulseView(v: View) {
        val anim = ObjectAnimator.ofFloat(v, "alpha", 0.4f, 1f).apply {
            duration = 1500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        }
        anim.start()
    }

    private fun iconTextBtn(icon: String, onClick: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 18f; setTextColor(NC.ON_SURF_VAR)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun startResourceMonitoring(cpuTv: TextView? = null, memTv: TextView? = null, diskTv: TextView? = null) {
        if (resourceMonitorRunnable != null) {
            executor.execute {
                val cpuUsage = readCpuUsage()
                val memUsage = readMemUsage()
                val memPercent = readMemPercent()
                val diskUsage = readDiskUsage()
                mainHandler.post {
                    cpuTv?.text = "$cpuUsage%"
                    memTv?.text = memUsage
                    diskTv?.text = "$diskUsage%"
                    val memStats = readMemDetails()
                    if (::homeLayout.isInitialized) {
                        composeCpuState.intValue = cpuUsage
                        composeMemState.intValue = memPercent
                        composeRamUsedState.longValue = memStats.ramUsedMb
                        composeRamTotalState.longValue = memStats.ramTotalMb
                        composeSwapUsedState.longValue = memStats.swapUsedMb
                        composeSwapTotalState.longValue = memStats.swapTotalMb
                        composeDiskState.intValue = diskUsage

                        val cpuValTv = homeLayout.findViewWithTag<TextView>("HOME_CPU_VAL")
                        val cpuRing = homeLayout.findViewWithTag<CircularProgressView>("HOME_CPU_RING")
                        val cpuTag = homeLayout.findViewWithTag<TextView>("HOME_CPU_TAG")

                        cpuValTv?.text = "$cpuUsage%"
                        cpuRing?.progress = cpuUsage.toFloat()
                        cpuTag?.text = if (cpuUsage > 80) "[WARN]" else "[OK]"
                        cpuTag?.setTextColor(if (cpuUsage > 80) Color.parseColor("#FF8A8A") else Color.parseColor("#3DDC84"))

                        val memValTv = homeLayout.findViewWithTag<TextView>("HOME_MEM_VAL")
                        val memRing = homeLayout.findViewWithTag<CircularProgressView>("HOME_MEM_RING")
                        val memTag = homeLayout.findViewWithTag<TextView>("HOME_MEM_TAG")

                        memValTv?.text = "$memPercent%"
                        memRing?.progress = memPercent.toFloat()
                        memTag?.text = if (memPercent > 80) "[WARN]" else "[OK]"
                        memTag?.setTextColor(if (memPercent > 80) Color.parseColor("#FF8A8A") else Color.parseColor("#3DDC84"))
                    }
                }
            }
            return
        }
        val monitorRunnable = object : Runnable {
            override fun run() {
                executor.execute {
                    val cpuUsage = readCpuUsage()
                    val memUsage = readMemUsage()
                    val memPercent = readMemPercent()
                    val diskUsage = readDiskUsage()
                    mainHandler.post {
                        cpuTv?.text = "$cpuUsage%"
                        memTv?.text = memUsage
                        diskTv?.text = "$diskUsage%"
                        val memStats = readMemDetails()
                        if (::homeLayout.isInitialized) {
                            composeCpuState.intValue = cpuUsage
                            composeMemState.intValue = memPercent
                            composeRamUsedState.longValue = memStats.ramUsedMb
                            composeRamTotalState.longValue = memStats.ramTotalMb
                            composeSwapUsedState.longValue = memStats.swapUsedMb
                            composeSwapTotalState.longValue = memStats.swapTotalMb
                            composeDiskState.intValue = diskUsage

                            val cpuValTv = homeLayout.findViewWithTag<TextView>("HOME_CPU_VAL")
                            val cpuRing = homeLayout.findViewWithTag<CircularProgressView>("HOME_CPU_RING")
                            val cpuTag = homeLayout.findViewWithTag<TextView>("HOME_CPU_TAG")

                            cpuValTv?.text = "$cpuUsage%"
                            cpuRing?.progress = cpuUsage.toFloat()
                            cpuTag?.text = if (cpuUsage > 80) "[WARN]" else "[OK]"
                            cpuTag?.setTextColor(if (cpuUsage > 80) Color.parseColor("#FF8A8A") else Color.parseColor("#3DDC84"))

                            val memValTv = homeLayout.findViewWithTag<TextView>("HOME_MEM_VAL")
                            val memRing = homeLayout.findViewWithTag<CircularProgressView>("HOME_MEM_RING")
                            val memTag = homeLayout.findViewWithTag<TextView>("HOME_MEM_TAG")

                            memValTv?.text = "$memPercent%"
                            memRing?.progress = memPercent.toFloat()
                            memTag?.text = if (memPercent > 80) "[WARN]" else "[OK]"
                            memTag?.setTextColor(if (memPercent > 80) Color.parseColor("#FF8A8A") else Color.parseColor("#3DDC84"))
                        }
                    }
                }
                mainHandler.postDelayed(this, 2000)
            }
        }
        resourceMonitorRunnable = monitorRunnable
        mainHandler.post(monitorRunnable)
    }

    private fun readMemPercent(): Int {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null) {
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                if (mi.totalMem > 0) {
                    return (((mi.totalMem - mi.availMem) * 100) / mi.totalMem).toInt().coerceIn(0, 100)
                }
            }
        } catch (e: Exception) {}
        try {
            val file = File("/proc/meminfo")
            if (!file.exists()) return 0
            var totalKb = 0L
            var availKb = 0L
            file.forEachLine { line ->
                if (line.startsWith("MemTotal:")) {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) totalKb = parts[1].toLong()
                } else if (line.startsWith("MemAvailable:")) {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) availKb = parts[1].toLong()
                }
            }
            if (totalKb > 0L) {
                return (((totalKb - availKb) * 100) / totalKb).toInt().coerceIn(0, 100)
            }
        } catch (e: Exception) {}
        return 0
    }

    private fun readCpuUsage(): Int {
        val snapshot = CpuUtilizationProvider.getCpuSnapshot()
        return snapshot.overallPercent.toInt()
    }

    data class MemStats(
        val ramUsedMb: Long = 0,
        val ramTotalMb: Long = 0,
        val swapUsedMb: Long = 0,
        val swapTotalMb: Long = 0
    )

    private fun readMemDetails(): MemStats {
        var ramTotalKb = 0L
        var ramAvailKb = 0L
        var swapTotalKb = 0L
        var swapFreeKb = 0L

        try {
            val file = File("/proc/meminfo")
            if (file.exists()) {
                file.forEachLine { line ->
                    if (line.startsWith("MemTotal:")) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) ramTotalKb = parts[1].toLong()
                    } else if (line.startsWith("MemAvailable:")) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) ramAvailKb = parts[1].toLong()
                    } else if (line.startsWith("SwapTotal:")) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) swapTotalKb = parts[1].toLong()
                    } else if (line.startsWith("SwapFree:")) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) swapFreeKb = parts[1].toLong()
                    }
                }
            }
        } catch (e: Exception) {}

        if (ramTotalKb == 0L) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                if (am != null) {
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    ramTotalKb = mi.totalMem / 1024
                    ramAvailKb = mi.availMem / 1024
                }
            } catch (e: Exception) {}
        }

        val ramTotalMb = ramTotalKb / 1024
        val ramUsedMb = ((ramTotalKb - ramAvailKb).coerceAtLeast(0)) / 1024
        val swapTotalMb = swapTotalKb / 1024
        val swapUsedMb = ((swapTotalKb - swapFreeKb).coerceAtLeast(0)) / 1024

        return MemStats(ramUsedMb, ramTotalMb, swapUsedMb, swapTotalMb)
    }

    private fun readMemUsage(): String {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null) {
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val usedBytes = mi.totalMem - mi.availMem
                val usedGb = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                return String.format(java.util.Locale.US, "%.1fG", usedGb)
            }
        } catch (e: Exception) {
            // fallback to /proc/meminfo
        }
        try {
            val file = File("/proc/meminfo")
            if (!file.exists()) return "0.0G"
            var totalKb = 0L
            var availKb = 0L
            file.forEachLine { line ->
                if (line.startsWith("MemTotal:")) {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) totalKb = parts[1].toLong()
                } else if (line.startsWith("MemAvailable:")) {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) availKb = parts[1].toLong()
                }
            }
            if (totalKb <= 0L) return "0.0G"
            val usedKb = totalKb - availKb
            val usedGb = usedKb.toDouble() / (1024 * 1024)
            return String.format(java.util.Locale.US, "%.1fG", usedGb)
        } catch (e: Exception) {
            return "0.0G"
        }
    }

    private fun readDiskUsage(): Int {
        try {
            val stat = android.os.StatFs(filesDir.absolutePath)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            if (totalBytes <= 0) return 0
            val usedBytes = totalBytes - availableBytes
            return (usedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            return 0
        }
    }

    // ── Project Management & Storage ──────────────────────────────────────────

    data class Project(val name: String, val icon: String, val path: String, val lastOpened: Long = 0L, val linuxMethod: String = "proot")

    private fun getProjects(): List<Project> {
        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        val data = prefs.getString("projects_json", null) ?: return defaultProjects()
        val list = ArrayList<Project>()
        try {
            val arr = org.json.JSONArray(data)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Project(
                    obj.getString("name"),
                    obj.getString("icon"),
                    obj.getString("path"),
                    obj.optLong("lastOpened", 0L),
                    obj.optString("linuxMethod", "proot")
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun defaultProjects(): List<Project> {
        return emptyList()
    }

    private fun saveProjects(list: List<Project>) {
        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        val arr = org.json.JSONArray()
        for (p in list) {
            val obj = org.json.JSONObject()
            obj.put("name", p.name)
            obj.put("icon", p.icon)
            obj.put("path", p.path)
            obj.put("lastOpened", p.lastOpened)
            obj.put("linuxMethod", p.linuxMethod)
            arr.put(obj)
        }
        prefs.edit().putString("projects_json", arr.toString()).apply()
    }

    private fun markProjectOpened(path: String) {
        val list = getProjects().toMutableList()
        val idx = list.indexOfFirst { it.path == path }
        if (idx >= 0) {
            val p = list[idx]
            list[idx] = p.copy(lastOpened = System.currentTimeMillis())
            saveProjects(list)
        }
    }

    private fun formatRelativeTime(timestamp: Long): String {
        if (timestamp <= 0L) return tr(R.string.main_recent)
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> tr(R.string.main_just_now)
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> tr(R.string.main_recently)
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            val stat = android.system.Os.lstat(file.absolutePath)
            android.system.OsConstants.S_ISLNK(stat.st_mode)
        } catch (e: Exception) {
            false
        }
    }

    private fun getFolderSize(file: File): Long {
        if (!file.exists() || isSymlink(file)) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        val files = file.listFiles() ?: return 0L
        for (f in files) {
            if (isSymlink(f)) continue
            size += if (f.isDirectory) getFolderSize(f) else f.length()
        }
        return size
    }

    private fun getTotalDeviceStorageBytes(): Long {
        return try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            stat.totalBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun getAvailableDeviceStorageBytes(): Long {
        return try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            stat.availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun updateAppStorageUsage(valTv: TextView, subTv: TextView, fillView: View, refreshBtn: View?) {
        valTv.text = tr(R.string.main_calculating)
        executor.execute {
            try {
                val apkSize = File(applicationInfo.sourceDir).length()
                val dataDir = File(applicationInfo.dataDir)
                val dataSize = getFolderSize(dataDir)
                val extCacheSize = externalCacheDir?.let { getFolderSize(it) } ?: 0L

                val totalAppBytes = apkSize + dataSize + extCacheSize
                val appMB = apkSize / (1024.0 * 1024.0)
                val dataMB = (dataSize + extCacheSize) / (1024.0 * 1024.0)
                val totalAppMB = totalAppBytes / (1024.0 * 1024.0)

                val deviceTotalBytes = getTotalDeviceStorageBytes()
                val deviceFreeBytes = getAvailableDeviceStorageBytes()
                val deviceTotalGB = deviceTotalBytes / (1024.0 * 1024.0 * 1024.0)
                val deviceFreeGB = deviceFreeBytes / (1024.0 * 1024.0 * 1024.0)
                val percentageOfDevice = if (deviceTotalBytes > 0) {
                    (totalAppBytes.toDouble() / deviceTotalBytes.toDouble()) * 100.0
                } else 0.0

                mainHandler.post {
                    val formattedVal = if (totalAppMB >= 1024.0) {
                        String.format(java.util.Locale.US, "%.1f", totalAppMB / 1024.0)
                    } else {
                        String.format(java.util.Locale.US, "%.1f", totalAppMB)
                    }
                    valTv.text = formattedVal
                    subTv.text = if (totalAppMB >= 1024.0) "GB" else "MB"

                    fillView.post {
                        val parentView = fillView.parent as? View
                        if (parentView != null && parentView.width > 0) {
                            val parentWidth = parentView.width
                            val fraction = (totalAppMB / (totalAppMB + 2048.0)).coerceIn(0.25, 0.95)
                            val targetWidth = (parentWidth * fraction).toInt()

                            val anim = ValueAnimator.ofInt(fillView.layoutParams.width, targetWidth)
                            anim.duration = 600
                            anim.addUpdateListener { va ->
                                fillView.layoutParams.width = va.animatedValue as Int
                                fillView.requestLayout()
                            }
                            anim.start()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    valTv.text = "N/A"
                    subTv.text = tr(R.string.main_could_not_calculate_storage_size)
                }
            }
        }
    }

    private fun styleCreateMethodChip(chip: TextView, selected: Boolean) {
        // ui_design: primary filled selected / secondary outline unselected; sharp 0px; two-tone extrusion
        chip.background = cyberBrutalistBg(
            fillColor = if (selected) NC.PRIMARY_CON else NC.SURFACE_CONTAINER,
            strokeColor = if (selected) Color.parseColor("#3C4A3F") else NC.PRIMARY,
            shadowColor = NC.SHADOW_DARK,
            offsetDp = 4,
            cornerRadiusDp = 0,
            rightFaceColor = Color.parseColor("#3C4A3F")
        )
        chip.setTextColor(if (selected) Color.parseColor("#0A0A0A") else NC.ON_SURFACE)
        chip.paint.isFakeBoldText = true
    }

    /**
     * Create-project Chroot selectable only when **both**:
     * 1) root (su) available — same gate as Settings → Chroot / method switch
     * 2) chroot installed (`.flux_configured` via [ProjectPathResolver.isChrootInstalled])
     */
    private fun isCreateChrootSelectable(): Boolean {
        val installed = ProjectPathResolver.isChrootInstalled()
        val rootOk = createChrootRootOk
            ?: getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                .getBoolean(PREF_CHROOT_ROOT_OK, false)
        return installed && rootOk
    }

    private fun createChrootDisabledReason(): String {
        val installed = ProjectPathResolver.isChrootInstalled()
        val rootOk = createChrootRootOk
            ?: getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                .getBoolean(PREF_CHROOT_ROOT_OK, false)
        return when {
            !rootOk && !installed ->
                tr(R.string.main_chroot_needs_root_install_settings)
            !rootOk ->
                tr(R.string.main_root_access_was_not_granted)
            !installed ->
                tr(R.string.main_chroot_not_installed_install_via)
            else ->
                tr(R.string.main_chroot_unavailable)
        }
    }

    private fun probeCreateChrootRoot() {
        RootShell.probeRootAvailable { ok ->
            createChrootRootOk = ok
            // Keep prefs in sync for other UI (settings cache)
            getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
                .putBoolean(PREF_CHROOT_ROOT_OK, ok)
                .apply()
            refreshProjectCreateMethodChips()
        }
    }

    private fun refreshProjectCreateMethodChips() {
        // Seed root from prefs until live probe returns
        if (createChrootRootOk == null) {
            createChrootRootOk = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                .getBoolean(PREF_CHROOT_ROOT_OK, false)
        }
        val chrootOk = isCreateChrootSelectable()
        // Snap off chroot if gates fail (root + install)
        if (projectCreateSelectedMethod == "chroot" && !chrootOk) {
            projectCreateSelectedMethod = "proot"
        }
        projectCreateProotChip?.let {
            styleCreateMethodChip(it, projectCreateSelectedMethod == "proot")
            it.isEnabled = true
            it.isClickable = true
            it.alpha = 1f
        }
        projectCreateChrootChip?.let {
            // Never paint selected if not selectable
            styleCreateMethodChip(it, projectCreateSelectedMethod == "chroot" && chrootOk)
            // Keep clickable so disabled taps can Toast why
            it.isEnabled = true
            it.isClickable = true
            it.alpha = if (chrootOk) 1f else 0.4f
        }
    }

    // ── GitHub Connect UI ───────────────────────────────────────────────────

    private fun buildCreateGithubBanner(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_github)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Full-color GitHub mark — do not tint
            clearColorFilter()
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        col.addView(TextView(this).apply {
            text = tr(R.string.main_connect_to_github)
            setTextColor(NC.PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
        })
        val sub = TextView(this).apply {
            text = tr(R.string.cli_checking_gh)
            setTextColor(NC.ON_SURF_VAR)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        }
        ghCreateSubtitle = sub
        col.addView(sub)
        val action = primaryButton(tr(R.string.ssh_connect)) {
            startGithubConnect(projectCreateSelectedMethod, fromSettings = false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 12f
        }
        ghCreateActionBtn = action
        card.addView(icon)
        card.addView(col)
        card.addView(action)
        return card
    }

    private fun buildCreateGithubRepoCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
            visibility = View.GONE
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        titleRow.addView(TextView(this).apply {
            text = tr(R.string.main_your_repositories)
            setTextColor(NC.PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val countTv = TextView(this).apply {
            text = "0"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            background = roundedBg(NC.SURFACE_HIGHEST, NC.OUTLINE_VAR, dp(0))
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        ghRepoCountTv = countTv
        titleRow.addView(countTv)
        card.addView(titleRow)

        // Trigger (closed state label)
        val trigger = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOWEST,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            isClickable = true
            isFocusable = true
        }
        val triggerLabel = TextView(this).apply {
            text = tr(R.string.main_select_a_repository)
            textSize = 13f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        ghRepoTriggerLabel = triggerLabel
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_down)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        trigger.addView(triggerLabel)
        trigger.addView(chevron)
        trigger.setOnClickListener { toggleGithubRepoPanel() }
        trigger.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.translationX = dp(2).toFloat()
                    v.translationY = dp(2).toFloat()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.translationX = 0f
                    v.translationY = 0f
                }
            }
            false
        }
        card.addView(trigger)

        // Expandable panel: search + list
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        ghRepoPanel = panel

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(2), NC.SURFACE_HIGH)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }
        searchRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
        })
        val searchEt = EditText(this).apply {
            hint = "// SEARCH REPOS…"
            setHintTextColor(NC.OUTLINE)
            setTextColor(Color.parseColor("#FAFAFA"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        ghRepoSearchInput = searchEt
        searchEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderGithubRepoList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        searchEt.setOnFocusChangeListener { _, hasFocus ->
            searchRow.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(2), if (hasFocus) NC.PRIMARY else NC.SURFACE_HIGH)
            }
        }
        searchRow.addView(searchEt)
        panel.addView(searchRow)

        // Fixed-height host so measure never expands list to full content height
        // (outer ScrollView measures children with UNSPECIFIED height).
        val listHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(240))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#0A0A0A"))
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val listScroll = NestedInnerScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            isFillViewport = false
            clipToPadding = true
            setPadding(0, 0, dp(2), 0)
            applyDesignScrollbar(this)
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(4), 0) // room for 3dp design scrollbar
        }
        ghRepoListLayout = listLayout
        listScroll.addView(listLayout, ViewGroup.LayoutParams(MATCH, WRAP))
        listHost.addView(listScroll)
        panel.addView(listHost)
        card.addView(panel)

        ghRepoCard = card
        return card
    }

    private fun toggleGithubRepoPanel() {
        ghRepoPanelOpen = !ghRepoPanelOpen
        ghRepoPanel?.visibility = if (ghRepoPanelOpen) View.VISIBLE else View.GONE
        if (ghRepoPanelOpen) {
            ghRepoSearchInput?.requestFocus()
            renderGithubRepoList(ghRepoSearchInput?.text?.toString().orEmpty())
        }
    }

    private fun closeGithubRepoPanel() {
        ghRepoPanelOpen = false
        ghRepoPanel?.visibility = View.GONE
    }

    private fun buildSettingsGithubCard(): LinearLayout {
        val card = glassCard()
        // Full-color GitHub mark (no tint) — not sectionHeader which color-filters icons
        val ghHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        ghHeader.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_github)
            scaleType = ImageView.ScaleType.FIT_CENTER
            clearColorFilter()
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
        })
        ghHeader.addView(TextView(this).apply {
            text = tr(R.string.main_github_account)
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(ghHeader)
        val sub = TextView(this).apply {
            text = tr(R.string.main_checking_2)
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        }
        settingsGhSubtitle = sub
        card.addView(sub)
        val badge = textBadge("…", NC.SURFACE_HIGHEST, NC.ON_SURF_VAR)
        settingsGhBadge = badge
        card.addView(badge)
        card.addView(spacer(10))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val connectBtn = primaryButton(tr(R.string.main_connect_with_github)) {
            startGithubConnect(LinuxCommandBuilder.currentMethod, fromSettings = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            textSize = 12f
            if (proLocked()) markProLocked()
        }
        settingsGhActionBtn = connectBtn
        val logoutBtn = secondaryButton(tr(R.string.main_logout)) {
            performGithubLogout()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
            visibility = View.GONE
        }
        settingsGhLogoutBtn = logoutBtn
        row.addView(connectBtn)
        row.addView(logoutBtn)
        card.addView(row)
        settingsGhCard = card
        return card
    }

    /** Logout + cyber-brutalist card animation (depress → flash badge → fade state). */
    private fun performGithubLogout() {
        val method = LinuxCommandBuilder.currentMethod
        val card = settingsGhCard
        val logoutBtn = settingsGhLogoutBtn
        val connectBtn = settingsGhActionBtn
        val badge = settingsGhBadge
        val sub = settingsGhSubtitle

        logoutBtn?.isEnabled = false
        connectBtn?.isEnabled = false
        badge?.apply {
            text = tr(R.string.main_logging_out)
            setTextColor(Color.parseColor("#0A0A0A"))
            background = roundedBg(NC.PRIMARY, NC.PRIMARY, dp(0))
            visibility = View.VISIBLE
            alpha = 1f
        }
        sub?.text = "Signing out · ${method.uppercase()}"

        // Physical depress
        card?.animate()
            ?.translationX(dp(4).toFloat())
            ?.translationY(dp(4).toFloat())
            ?.setDuration(80)
            ?.withEndAction {
                card.animate()
                    ?.translationX(0f)
                    ?.translationY(0f)
                    ?.setDuration(120)
                    ?.start()
            }
            ?.start()

        // Badge pulse while waiting
        val pulse = ObjectAnimator.ofFloat(badge, View.ALPHA, 1f, 0.35f, 1f).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        pulse.start()

        GitHubCliService.logout(this, method) { ok, msg ->
            pulse.cancel()
            badge?.alpha = 1f
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

            // Fade out → swap state → fade in
            card?.animate()
                ?.alpha(0.25f)
                ?.setDuration(160)
                ?.withEndAction {
                    paintSettingsGithubStatus(
                        GhAuthStatus(
                            method = method,
                            ghInstalled = true,
                            loggedIn = false,
                            username = null
                        )
                    )
                    if (ok) {
                        badge?.apply {
                            text = tr(R.string.main_signed_out)
                            setTextColor(Color.parseColor("#0A0A0A"))
                            background = roundedBg(NC.PRIMARY_CON, NC.PRIMARY_CON, dp(0))
                            visibility = View.VISIBLE
                        }
                    } else {
                        badge?.apply {
                            text = tr(R.string.ssh_failed)
                            setTextColor(Color.parseColor("#0A0A0A"))
                            background = roundedBg(NC.ERROR, NC.ERROR, dp(0))
                            visibility = View.VISIBLE
                        }
                    }
                    card.animate()
                        ?.alpha(1f)
                        ?.setDuration(220)
                        ?.withEndAction {
                            // Settle to final offline paint after brief success flash
                            mainHandler.postDelayed({
                                refreshSettingsGithubCard()
                                if (projectCreateSelectedMethod == method) refreshCreateGithubUi()
                            }, 450)
                        }
                        ?.start()
                }
                ?.start()

            logoutBtn?.isEnabled = true
            connectBtn?.isEnabled = true
        }
    }

    private fun refreshCreateGithubUi() {
        val method = projectCreateSelectedMethod
        ghCreateSubtitle?.text = tr(R.string.cli_checking_gh)
        ghCreateActionBtn?.apply {
            isEnabled = false
            alpha = 0.5f
            text = "…"
        }
        ghRepoCard?.visibility = View.GONE
        closeGithubRepoPanel()
        ghSelectedRepo = null
        ghReposLoading = false
        ghReposForMethod = emptyList()
        ghRepoSearchInput?.setText("")
        ghRepoTriggerLabel?.text = tr(R.string.main_select_a_repository)
        ghRepoTriggerLabel?.setTextColor(NC.ON_SURFACE)
        ghRepoCountTv?.text = "0"
        renderGithubRepoList("")
        GitHubCliService.cachedStatus(method)?.let { paintCreateGithubStatus(it) }
        GitHubCliService.probeStatus(this, method) { status ->
            if (projectCreateSelectedMethod != method) return@probeStatus
            paintCreateGithubStatus(status)
            if (status.loggedIn) loadCreateGithubRepos(method)
        }
    }

    private fun paintCreateGithubStatus(status: GhAuthStatus) {
        val sub = ghCreateSubtitle
        val btn = ghCreateActionBtn
        when {
            !status.ghInstalled || !status.loggedIn -> {
                sub?.text = tr(R.string.main_not_connected)
                btn?.apply {
                    text = tr(R.string.ssh_connect)
                    markProLocked(proLocked())
                    isEnabled = !GitHubCliService.isSessionActive()
                    alpha = if (isEnabled) 1f else 0.5f
                    setOnClickListener {
                        startGithubConnect(projectCreateSelectedMethod, fromSettings = false)
                    }
                }
                ghRepoCard?.visibility = View.GONE
            }
            else -> {
                sub?.text = "@${status.username}"
                btn?.apply {
                    text = tr(R.string.git_refresh)
                    markProLocked(false)
                    isEnabled = true
                    alpha = 1f
                    setOnClickListener {
                        loadCreateGithubRepos(projectCreateSelectedMethod)
                        Toast.makeText(this@MainActivity, tr(R.string.main_refreshing_repos), Toast.LENGTH_SHORT).show()
                    }
                }
                ghRepoCard?.visibility = View.VISIBLE
            }
        }
        if (status.error != null) {
            sub?.text = tr(R.string.main_error)
            btn?.text = tr(R.string.preview_retry)
        }
    }

    private fun loadCreateGithubRepos(method: String) {
        ghReposLoading = true
        ghReposForMethod = emptyList()
        ghRepoTriggerLabel?.text = tr(R.string.main_loading_repositories)
        ghRepoTriggerLabel?.setTextColor(NC.ON_SURF_VAR)
        ghRepoCountTv?.text = "…"
        // Immediate loading UI (panel open or closed — visible once expanded)
        renderGithubRepoList(ghRepoSearchInput?.text?.toString().orEmpty())
        GitHubCliService.listRepos(this, method) { result ->
            if (projectCreateSelectedMethod != method) {
                ghReposLoading = false
                return@listRepos
            }
            ghReposLoading = false
            result.onSuccess { repos ->
                bindGithubRepoPicker(repos)
            }.onFailure {
                Toast.makeText(this, "Repo list: ${it.message}", Toast.LENGTH_SHORT).show()
                bindGithubRepoPicker(emptyList())
            }
        }
    }

    private fun bindGithubRepoPicker(repos: List<GhRepo>) {
        ghReposLoading = false
        ghReposForMethod = repos
        ghRepoCountTv?.text = "${repos.size}"
        // Keep selection if still in list
        val prev = ghSelectedRepo?.nameWithOwner
        ghSelectedRepo = repos.firstOrNull { it.nameWithOwner == prev }
        if (ghSelectedRepo != null) {
            paintGithubRepoSelection(ghSelectedRepo!!)
        } else {
            ghRepoTriggerLabel?.text = if (repos.isEmpty()) tr(R.string.main_no_repositories_found) else tr(R.string.main_select_a_repository)
            ghRepoTriggerLabel?.setTextColor(if (repos.isEmpty()) NC.ON_SURF_VAR else NC.ON_SURFACE)
        }
        renderGithubRepoList(ghRepoSearchInput?.text?.toString().orEmpty())
        ghRepoCard?.visibility = if (
            repos.isNotEmpty() ||
            GitHubCliService.cachedStatus(projectCreateSelectedMethod)?.loggedIn == true
        ) View.VISIBLE else View.GONE
    }

    private fun renderGithubRepoList(query: String) {
        val list = ghRepoListLayout ?: return
        list.removeAllViews()

        if (ghReposLoading) {
            ghRepoCountTv?.text = "…"
            val loadingRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            loadingRow.addView(ProgressBar(this).apply {
                isIndeterminate = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    indeterminateTintList =
                        android.content.res.ColorStateList.valueOf(NC.PRIMARY)
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(12)
                }
            })
            loadingRow.addView(TextView(this).apply {
                text = "// LOADING REPOS…"
                textSize = 12f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
            })
            loadingRow.addView(TextView(this).apply {
                text = tr(R.string.main_fetching_from_gh)
                textSize = 10f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            list.addView(loadingRow)
            return
        }

        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            ghReposForMethod
        } else {
            ghReposForMethod.filter { r ->
                r.nameWithOwner.lowercase().contains(q) ||
                    (r.description?.lowercase()?.contains(q) == true) ||
                    r.url.lowercase().contains(q)
            }
        }
        ghRepoCountTv?.text = if (q.isEmpty()) {
            "${ghReposForMethod.size}"
        } else {
            "${filtered.size}/${ghReposForMethod.size}"
        }

        if (filtered.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (ghReposForMethod.isEmpty()) tr(R.string.main_caps_no_repos_loaded) else "// NO MATCH FOR \"$query\""
                textSize = 12f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(16), dp(12), dp(16))
                gravity = Gravity.CENTER
            })
            return
        }

        filtered.forEachIndexed { index, repo ->
            val selected = ghSelectedRepo?.nameWithOwner == repo.nameWithOwner
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (selected) NC.SURFACE_CONTAINER else Color.TRANSPARENT)
                }
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            col.addView(TextView(this).apply {
                text = repo.nameWithOwner
                textSize = 13f
                setTextColor(if (selected) NC.PRIMARY else Color.parseColor("#FAFAFA"))
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = selected
            })
            val desc = repo.description?.trim().orEmpty()
            if (desc.isNotEmpty()) {
                col.addView(TextView(this).apply {
                    text = desc
                    textSize = 10f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                })
            }
            row.addView(col)
            if (repo.isPrivate) {
                row.addView(TextView(this).apply {
                    text = tr(R.string.main_private)
                    textSize = 9f
                    setTextColor(Color.parseColor("#0A0A0A"))
                    typeface = Typeface.MONOSPACE
                    paint.isFakeBoldText = true
                    background = roundedBg(NC.TERTIARY_CON, NC.TERTIARY_CON, dp(0))
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
                })
            } else if (selected) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_check_circle)
                    setColorFilter(NC.PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(8) }
                })
            }
            row.setOnClickListener { selectGithubRepo(repo) }
            row.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        (v.background as? GradientDrawable)?.setColor(NC.SURFACE_HIGH)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        (v.background as? GradientDrawable)?.setColor(
                            if (ghSelectedRepo?.nameWithOwner == repo.nameWithOwner) NC.SURFACE_CONTAINER
                            else Color.TRANSPARENT
                        )
                    }
                }
                false
            }
            list.addView(row)
            // divider
            if (index < filtered.lastIndex) {
                list.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
                    setBackgroundColor(NC.SURFACE_HIGH)
                })
            }
        }
    }

    private fun selectGithubRepo(repo: GhRepo) {
        ghSelectedRepo = repo
        paintGithubRepoSelection(repo)
        if (::projectGithubInput.isInitialized) {
            projectGithubInput.setText(repo.url)
        }
        val repoName = repo.nameWithOwner.substringAfterLast('/').removeSuffix(".git").trim()
        if (::projectNameInput.isInitialized && repoName.isNotEmpty()) {
            projectNameInput.setText(repoName)
            projectNameInput.setSelection(repoName.length)
        }
        closeGithubRepoPanel()
        // clear search so next open shows full list
        ghRepoSearchInput?.setText("")
    }

    private fun paintGithubRepoSelection(repo: GhRepo) {
        ghRepoTriggerLabel?.text = if (repo.isPrivate) {
            "${repo.nameWithOwner}  · private"
        } else {
            repo.nameWithOwner
        }
        ghRepoTriggerLabel?.setTextColor(NC.PRIMARY)
    }

    private fun refreshSettingsGithubCard() {
        val method = LinuxCommandBuilder.currentMethod
        settingsGhSubtitle?.text = tr(R.string.main_checking_2)
        settingsGhActionBtn?.isEnabled = false
        settingsGhLogoutBtn?.visibility = View.GONE
        // Clear stale badge
        settingsGhBadge?.apply {
            text = "…"
            setTextColor(NC.ON_SURF_VAR)
            background = roundedBg(NC.SURFACE_HIGHEST, NC.OUTLINE_VAR, dp(4))
            visibility = View.VISIBLE
        }
        // Only paint cache for THIS method (never cross-method stale LOGGED IN)
        GitHubCliService.cachedStatus(method)?.let { paintSettingsGithubStatus(it) }
        GitHubCliService.probeStatus(this, method) { status ->
            if (LinuxCommandBuilder.currentMethod != method) return@probeStatus
            paintSettingsGithubStatus(status)
        }
    }

    private fun paintSettingsGithubStatus(status: GhAuthStatus) {
        when {
            status.loggedIn && !status.username.isNullOrBlank() -> {
                settingsGhSubtitle?.text = "@${status.username}"
                settingsGhBadge?.apply {
                    text = tr(R.string.main_logged_in)
                    setTextColor(Color.parseColor("#0A0A0A"))
                    background = roundedBg(NC.PRIMARY, NC.PRIMARY, dp(4))
                    visibility = View.VISIBLE
                }
                settingsGhActionBtn?.apply {
                    text = tr(R.string.main_re_auth)
                    isEnabled = !GitHubCliService.isSessionActive()
                    setOnClickListener {
                        startGithubConnect(LinuxCommandBuilder.currentMethod, fromSettings = true)
                    }
                }
                settingsGhLogoutBtn?.visibility = View.VISIBLE
            }
            else -> {
                settingsGhSubtitle?.text = tr(R.string.main_not_signed_in)
                settingsGhBadge?.apply {
                    text = tr(R.string.main_offline)
                    setTextColor(NC.ON_SURF_VAR)
                    background = roundedBg(NC.SURFACE_HIGHEST, NC.OUTLINE_VAR, dp(4))
                    visibility = View.VISIBLE
                }
                settingsGhActionBtn?.apply {
                    text = tr(R.string.main_connect_with_github)
                    isEnabled = !GitHubCliService.isSessionActive()
                    setOnClickListener {
                        startGithubConnect(LinuxCommandBuilder.currentMethod, fromSettings = true)
                    }
                }
                settingsGhLogoutBtn?.visibility = View.GONE
            }
        }
        settingsGhActionBtn?.markProLocked(proLocked())
    }

    private fun startGithubConnect(method: String, fromSettings: Boolean) {
        if (!gateAllows(FeatureGate.canUse(isPro(), ProFeature.GITHUB_LOGIN))) return
        if (GitHubCliService.isSessionActive()) {
            Toast.makeText(this, tr(R.string.cli_auth_already_in_progress), Toast.LENGTH_SHORT).show()
            return
        }
        if (method == "chroot" && !ProjectPathResolver.isChrootInstalled()) {
            Toast.makeText(this, tr(R.string.main_chroot_not_installed), Toast.LENGTH_SHORT).show()
            return
        }
        showGithubAuthOverlay(method, fromSettings)
    }

    private fun showGithubAuthOverlay(method: String, fromSettings: Boolean) {
        dismissGithubAuthOverlay()
        val overlayRoot = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.parseColor("#E6131313"))
            isClickable = true
            isFocusable = true
        }
        ghAuthOverlay = overlayRoot

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply {
                setMargins(dp(12), dp(48), dp(12), dp(48))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        header.addView(TextView(this).apply {
            text = "GitHub auth · ${method.uppercase()}"
            textSize = 14f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val phaseTv = TextView(this).apply {
            text = tr(R.string.main_starting)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        }
        panel.addView(header)
        panel.addView(phaseTv)

        val otpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
        }
        val otpTv = TextView(this).apply {
            text = "----"
            textSize = 18f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val openBrowserBtn = secondaryButton(tr(R.string.main_open_browser)) {}.apply {
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        otpRow.addView(otpTv)
        otpRow.addView(openBrowserBtn)
        panel.addView(otpRow)

        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }
        val logLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        logScroll.addView(logLayout)
        panel.addView(logScroll)

        fun appendLog(line: String) {
            logLayout.addView(TextView(this).apply {
                text = line
                textSize = 11f
                setTextColor(
                    when {
                        line.contains("error", true) || line.contains("fatal", true) -> NC.ERROR
                        else -> NC.ON_SURFACE
                    }
                )
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(1), 0, dp(1))
            })
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        val cancelBtn = primaryButton(tr(R.string.x11_cancel)) {
            ghAuthSession?.cancel()
            dismissGithubAuthOverlay()
            if (fromSettings) refreshSettingsGithubCard() else refreshCreateGithubUi()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        panel.addView(cancelBtn)

        overlayRoot.addView(panel)
        contentFrame.addView(overlayRoot)
        overlayRoot.bringToFront()

        var lastOtp: String? = null
        openBrowserBtn.setOnClickListener {
            GitHubCliService.openDeviceBrowser(this, lastOtp)
        }

        val listener = object : GhAuthListener {
            override fun onPhase(phase: GhAuthPhase, message: String) {
                phaseTv.text = message
            }
            override fun onLog(line: String) {
                appendLog(line)
            }
            override fun onOtp(code: String) {
                lastOtp = code
                otpRow.visibility = View.VISIBLE
                otpTv.text = "$code  · Copied"
                phaseTv.text = tr(R.string.main_waiting_for_browser)
            }
            override fun onDone(status: GhAuthStatus) {
                phaseTv.text = tr(R.string.cli_signed_in_as, status.username)
                appendLog("OK @${status.username}")
                mainHandler.postDelayed({
                    dismissGithubAuthOverlay()
                    if (fromSettings) refreshSettingsGithubCard()
                    refreshCreateGithubUi()
                    Toast.makeText(
                        this@MainActivity,
                        "GitHub: @${status.username} · ${status.method.uppercase()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }, 400)
            }
            override fun onFailed(message: String) {
                phaseTv.text = tr(R.string.chat_failed)
                appendLog(message)
                cancelBtn.text = tr(R.string.term_close)
                cancelBtn.setOnClickListener {
                    dismissGithubAuthOverlay()
                    if (fromSettings) refreshSettingsGithubCard() else refreshCreateGithubUi()
                }
            }
            override fun onCancelled() {
                dismissGithubAuthOverlay()
                if (fromSettings) refreshSettingsGithubCard() else refreshCreateGithubUi()
            }
        }

        ghAuthSession = GitHubCliService.connect(this, method, listener)
        if (ghAuthSession == null) {
            dismissGithubAuthOverlay()
        }
        ghCreateActionBtn?.isEnabled = false
        settingsGhActionBtn?.isEnabled = false
    }

    private fun dismissGithubAuthOverlay() {
        ghAuthOverlay?.let {
            try { contentFrame.removeView(it) } catch (_: Exception) {}
        }
        ghAuthOverlay = null
        ghAuthSession = null
        ghCreateActionBtn?.isEnabled = true
        settingsGhActionBtn?.isEnabled = true
    }

    // ── AI CLI Login page ───────────────────────────────────────────────────

    private fun buildCliAuthPage() {
        cliAuthScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val pageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        cliAuthScrollView.addView(pageLayout)

        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            setOnClickListener { navigateBackFromSubpage(ID_CLI_AUTH) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_caps_ai_cli_tools)
            textSize = 22f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val methodBadge = textBadge(
            LinuxCommandBuilder.currentMethod.uppercase(),
            NC.SURFACE_HIGHEST,
            NC.PRIMARY
        )
        cliAuthMethodBadge = methodBadge
        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(titleTv)
        headerTitleRow.addView(methodBadge)
        pageLayout.addView(headerTitleRow)
        pageLayout.addView(spacer(12))
        pageLayout.addView(androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                com.zenithblue.nativecode.agent.chat.ui.ChatTheme {
                    com.zenithblue.nativecode.cliauth.ui.CliToolsPage(cliTools, cliToolsHost)
                }
            }
        })
    }


    // ── AI Safety & Report (C5 / B16 + C10 pointers) ─────────────────────────

    private fun buildAiSafetyPage() {
        aiSafetyScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val pageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        aiSafetyScrollView.addView(pageLayout)

        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            setOnClickListener {
                navigateBackFromSubpage(ID_AI_SAFETY)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_ai_safety_report)
            textSize = 20f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val methodBadge = textBadge(
            LinuxCommandBuilder.currentMethod.uppercase(),
            NC.SURFACE_HIGHEST,
            NC.PRIMARY
        )
        aiSafetyMethodBadge = methodBadge
        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(titleTv)
        headerTitleRow.addView(methodBadge)
        pageLayout.addView(headerTitleRow)
        pageLayout.addView(TextView(this).apply {
            text = "// DISCLAIMER · REPORT · VENDOR ToS · DATA"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        })

        // Disclaimer
        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_disclaimer)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                text =
                    "Third-party AI CLIs generate outputs under their own terms. " +
                        "NativeCode does not host the model. You are responsible for prompts " +
                        "and use. Intended for adult / professional use."
                textSize = 12f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(2).toFloat(), 1.2f)
            })
        })
        pageLayout.addView(spacer(12))

        // Report to NativeCode
        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_report_to_nativecode)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Submit inside NativeCode without opening another app. " +
                    "Use this for app bugs that surface AI tools or safety concerns. " +
                    "Prefer vendor form when the issue is model output itself."
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(2).toFloat(), 1.2f)
                setPadding(0, 0, 0, dp(10))
            })
            addView(
                primaryButton(tr(R.string.main_report_issue)) {
                    showAiReportComposer(preselectedToolId = null)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    textSize = 12f
                }
            )
        })
        pageLayout.addView(spacer(12))

        // Vendor list
        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_report_tos_by_vendor)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })
            val list = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            aiSafetyVendorList = list
            addView(list)
        })
        pageLayout.addView(spacer(12))

        // Data / sign-out (C10)
        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_your_data_sign_out)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                text =
                    "· GitHub: Settings hub → LOGOUT\n" +
                        "· AI keys/sessions: AI CLI tools → LOGOUT / CLEAR ALL\n" +
                        "· Full wipe: Android App info → Clear storage\n" +
                        "· Uninstall removes app private data" +
                        if (com.zenithblue.nativecode.ProotOnly.ON) "" else
                            "\n· Chroot on shared storage: Chroot Settings uninstall"
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(2).toFloat(), 1.25f)
                setPadding(0, 0, 0, dp(10))
            })
            addView(
                primaryButton(tr(R.string.main_open_ai_cli_tools)) {
                    if (pageStack.isEmpty() || pageStack.peek() != ID_CLI_AUTH) {
                        pageStack.push(ID_CLI_AUTH)
                    }
                    navigateToPage(ID_CLI_AUTH)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
                    textSize = 12f
                }
            )
            addView(
                secondaryButton(tr(R.string.main_privacy_licenses)) {
                    if (pageStack.isEmpty() || pageStack.peek() != ID_PRIVACY_POLICY) {
                        pageStack.push(ID_PRIVACY_POLICY)
                    }
                    navigateToPage(ID_PRIVACY_POLICY)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    textSize = 12f
                }
            )
        })

        paintAiSafetyVendorList()
    }

    private fun refreshAiSafetyPage() {
        aiSafetyMethodBadge?.text = LinuxCommandBuilder.currentMethod.uppercase()
        paintAiSafetyVendorList()
    }

    private fun paintAiSafetyVendorList() {
        val list = aiSafetyVendorList ?: return
        list.removeAllViews()
        val method = LinuxCommandBuilder.currentMethod
        val vendors = AiVendorSafetyCatalog.forMethod(method)
        if (vendors.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No vendor rows for $method"
                textSize = 11f
                setTextColor(NC.OUTLINE)
                typeface = Typeface.MONOSPACE
            })
            return
        }
        vendors.forEachIndexed { index, links ->
            if (index > 0) {
                list.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                        topMargin = dp(10)
                        bottomMargin = dp(10)
                    }
                    setBackgroundColor(NC.OUTLINE_VAR)
                })
            }
            list.addView(buildVendorSafetyRow(links))
        }
    }

    private fun buildVendorSafetyRow(links: VendorSafetyLinks): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = links.displayName
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            if (links.notes.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = links.notes
                    textSize = 10f
                    setTextColor(NC.OUTLINE)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(2), 0, dp(6))
                })
            }
            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            var buttons = 0
            fun addAction(label: String, action: () -> Unit) {
                if (buttons > 0) {
                    btnRow.addView(View(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(6), 1)
                    })
                }
                btnRow.addView(
                    secondaryButton(label, action).apply {
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                        textSize = 10f
                        setPadding(dp(6), dp(10), dp(6), dp(10))
                    }
                )
                buttons++
            }
            when {
                !links.reportUrl.isNullOrBlank() ->
                    addAction(tr(R.string.main_report)) {
                        openExternalUrl(links.reportUrl!!, tr(R.string.main_no_browser_for_report_link))
                    }
                !links.reportMailto.isNullOrBlank() ->
                    addAction(tr(R.string.main_report)) {
                        val ok = ReportMailHelper.openVendorMailto(
                            this@MainActivity,
                            links.reportMailto!!,
                            links.displayName
                        )
                        if (!ok) {
                            Toast.makeText(
                                this@MainActivity,
                                tr(R.string.main_no_mail_app_available),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                else ->
                    addAction(tr(R.string.main_report_app)) {
                        showAiReportComposer(preselectedToolId = links.toolId)
                    }
            }
            if (!links.tosUrl.isNullOrBlank()) {
                addAction("ToS") {
                    openExternalUrl(links.tosUrl!!, tr(R.string.main_no_browser_for_tos))
                }
            }
            if (!links.aupUrl.isNullOrBlank()) {
                addAction("AUP") {
                    openExternalUrl(links.aupUrl!!, tr(R.string.main_no_browser_for_aup))
                }
            }
            addView(btnRow)
        }
    }

    private fun openExternalUrl(url: String, failToast: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            Toast.makeText(this, failToast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPrivacyPolicyUrl() {
        val url = "https://zenithblue-dev.github.io/nativecode-ai/privacy/"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            val fallbackUrl = "https://zenithblue-dev.github.io/nativecode-ai/privacy/"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
            } catch (_: Exception) {
                Toast.makeText(this, tr(R.string.main_no_browser_available_to_open), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Privacy Policy & Open-Source Licenses Page ────────────────────────────

    private fun buildPrivacyPolicyPage() {
        privacyPolicyScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
            isVerticalScrollBarEnabled = true
        }
        val pageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        privacyPolicyScrollView.addView(pageLayout)

        // Header with Back Button
        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            setOnClickListener {
                navigateBackFromSubpage(ID_PRIVACY_POLICY, fallback = ID_SETTINGS)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_privacy_licenses)
            textSize = 20f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val fossBadge = textBadge(tr(R.string.main_gpl_3_0_foss), NC.SURFACE_HIGHEST, NC.PRIMARY)
        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(titleTv)
        headerTitleRow.addView(fossBadge)
        pageLayout.addView(headerTitleRow)

        pageLayout.addView(TextView(this).apply {
            text = "// ZERO TELEMETRY · LOCAL CONTAINERS · AUDITABLE LICENSES"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        })

        // Web Link Action Card
        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_official_web_portal)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = "https://zenithblue-dev.github.io/nativecode-ai/privacy/"
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(10))
            })
            addView(
                primaryButton(tr(R.string.main_open_in_browser)) {
                    openPrivacyPolicyUrl()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    textSize = 12f
                }
            )
        })
        pageLayout.addView(spacer(12))

        // Privacy Summary Card
        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_privacy_policy_overview)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })
            val bullets = listOf(
                tr(R.string.main_local_first_zero_tracking_nativecode),
                tr(R.string.main_isolated_guest_environment_alpaquita),
                "• On-Demand Network Only: Internet permission is invoked exclusively when you trigger package installs, git sync, or external repository downloads.",
                "• Third-Party Tools: AI CLIs may send prompts, selected code, account identifiers, and diagnostics directly to the provider you authenticate; each provider's policy applies.",
                tr(R.string.main_data_erasure_deleting_projects_clear)
            )
            bullets.forEach { b ->
                addView(TextView(this@MainActivity).apply {
                    text = b
                    textSize = 11f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(dp(2).toFloat(), 1.25f)
                    setPadding(0, 0, 0, dp(6))
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_privacy_contact_zenithblue_dev_gmail)
                textSize = 10f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, 0)
            })
        })
        pageLayout.addView(spacer(12))

        // Open Source Header
        pageLayout.addView(TextView(this).apply {
            text = tr(R.string.main_open_source_licenses)
            textSize = 14f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        })
        pageLayout.addView(TextView(this).apply {
            text = tr(R.string.main_nativecode_is_built_on_free)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        })

        // Render Licenses
        com.zenithblue.nativecode.legal.OpenSourceLicenses.ITEMS.forEach { item ->
            pageLayout.addView(glassCard().apply {
                val titleRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val nameTv = TextView(this@MainActivity).apply {
                    text = item.name
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                }
                val licBadge = textBadge(item.licenseType, NC.SURFACE_HIGHEST, NC.PRIMARY)
                titleRow.addView(nameTv)
                titleRow.addView(licBadge)
                addView(titleRow)

                addView(TextView(this@MainActivity).apply {
                    text = item.component
                    textSize = 11f
                    setTextColor(NC.PRIMARY_CON)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(4), 0, dp(2))
                })

                addView(TextView(this@MainActivity).apply {
                    text = item.copyright
                    textSize = 10f
                    setTextColor(NC.OUTLINE)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, 0, 0, dp(6))
                })

                addView(TextView(this@MainActivity).apply {
                    text = item.licenseSummary
                    textSize = 11f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(dp(2).toFloat(), 1.2f)
                    setPadding(0, 0, 0, dp(8))
                })

                if (item.url.isNotBlank()) {
                    addView(
                        secondaryButton(tr(R.string.main_view_source_repository)) {
                            openExternalUrl(item.url, "No browser available to open ${item.name}")
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            textSize = 11f
                        }
                    )
                }
            })
            pageLayout.addView(spacer(10))
        }
    }

    private fun buildHardwareSectionButton(): View {
        return settingsHubNavRow(
            iconRes = R.drawable.ic_tune_thick,
            title = tr(R.string.cli_hardware),
            subtitle = tr(R.string.main_gpu_vpu_host_ipc),
            pageId = ID_HARDWARE
        )
    }

    private fun buildHardwarePage() {
        hardwareScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
            isVerticalScrollBarEnabled = true
        }
        val pageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        hardwareScrollView.addView(pageLayout)

        // Header with Back Button
        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            setOnClickListener {
                navigateBackFromSubpage(ID_HARDWARE, fallback = ID_SETTINGS)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_hardware_vpu)
            textSize = 20f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val vpuBadge = textBadge(tr(R.string.main_mediacodec_ipc), NC.SURFACE_HIGHEST, NC.PRIMARY)
        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(titleTv)
        headerTitleRow.addView(vpuBadge)
        pageLayout.addView(headerTitleRow)

        pageLayout.addView(TextView(this).apply {
            text = tr(R.string.main_caps_gpu_accel_vpu_host_agent_bridge)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        })

        pageLayout.addView(glassCard().apply {
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cardTitle = TextView(this@MainActivity).apply {
                text = tr(R.string.main_gpu_acceleration)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            gpuStatusBadge = textBadge(tr(R.string.main_detect), NC.SURFACE_HIGHEST, NC.PRIMARY)
            titleRow.addView(cardTitle)
            titleRow.addView(gpuStatusBadge)
            addView(titleRow)
            gpuDetailTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_gpu_loading)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, 0)
            }
            addView(gpuDetailTv)
        })

        pageLayout.addView(spacer(16))

        // GPU driver card: PanVK (Mali) / Turnip (Adreno) package state, same check as the Components update cards.
        pageLayout.addView(glassCard().apply {
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this@MainActivity).apply {
                text = tr(R.string.hw_driver_title)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            hwDriverBadge = textBadge(tr(R.string.hw_checking), NC.SURFACE_HIGHEST, NC.ON_SURF_VAR)
            titleRow.addView(hwDriverBadge)
            addView(titleRow)
            hwDriverTv = TextView(this@MainActivity).apply {
                text = tr(R.string.hw_checking)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, 0)
            }
            addView(hwDriverTv)
            hwDriverBtn = secondaryButton(tr(R.string.hw_driver_open_components)) {
                pageStack.push(ID_COMPONENTS); navigateToPage(ID_COMPONENTS)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
                textSize = 11f
                visibility = View.GONE
            }
            addView(hwDriverBtn)
        })

        pageLayout.addView(spacer(16))

        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_host_servers)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })
            hostServersBlurbTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_pulseaudio_tcp_fluxlinux_style_no)
                textSize = 10f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(8))
            }
            addView(hostServersBlurbTv)

            venusHostBlock = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val venusRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            venusRow.addView(TextView(this@MainActivity).apply {
                text = "VENUS"
                textSize = 12f
                setTextColor(NC.PRIMARY_CON)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            venusServerBadge = textBadge(tr(R.string.ssh_stopped), NC.SURFACE_HIGHEST, NC.ERROR)
            venusRow.addView(venusServerBadge)
            venusHostBlock.addView(venusRow)
            venusServerDetailTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_venus_probing)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(6))
            }
            venusHostBlock.addView(venusServerDetailTv)
            venusHostBlock.addView(hostServerButtonRow(HostServerController.Kind.VENUS))
            addView(venusHostBlock)

            val pulseRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            pulseRow.addView(TextView(this@MainActivity).apply {
                text = "PULSEAUDIO"
                textSize = 12f
                setTextColor(NC.PRIMARY_CON)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            pulseServerBadge = textBadge(tr(R.string.ssh_stopped), NC.SURFACE_HIGHEST, NC.ERROR)
            pulseRow.addView(pulseServerBadge)
            addView(pulseRow)
            pulseServerDetailTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_pulseaudio_probing)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(6))
            }
            addView(pulseServerDetailTv)
            addView(hostServerButtonRow(HostServerController.Kind.PULSE))
        })

        pageLayout.addView(spacer(16))

        // VPU Card
        pageLayout.addView(glassCard().apply {
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cardTitle = TextView(this@MainActivity).apply {
                text = tr(R.string.main_vpu_hardware_acceleration)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            vpuStatusBadge = textBadge(tr(R.string.main_active), NC.SURFACE_HIGHEST, NC.PRIMARY)
            titleRow.addView(cardTitle)
            titleRow.addView(vpuStatusBadge)
            addView(titleRow)

            vpuSocDetailTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_soc_loading)
                textSize = 12f
                setTextColor(NC.PRIMARY_CON)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, dp(4))
            }
            addView(vpuSocDetailTv)

            vpuDecodersTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_decoders_loading)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(4))
            }
            addView(vpuDecodersTv)

            vpuEncodersTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_encoders_loading)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(4))
            }
            addView(vpuEncodersTv)
        })

        pageLayout.addView(spacer(16))

        // Bridge Daemon Card
        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_nativecode_host_ipc_bridge)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })

            bridgeStatusTv = TextView(this@MainActivity).apply {
                text = tr(R.string.hw_checking)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(8))
            }
            addView(bridgeStatusTv)

            val btnRow1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val testPingBtn = Button(this@MainActivity).apply {
                text = tr(R.string.main_test_ping)
                textSize = 11f
                setTextColor(NC.PRIMARY)
                background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) }
                setOnClickListener {
                    bridgeLogTv.text = tr(R.string.main_testing_nativecode_agent_v1_ping)
                    Thread {
                        try {
                            val sock = connectAgentSocket()
                            val startMs = System.currentTimeMillis()
                            val req = JSONObject().apply {
                                put("v", 1)
                                put("cmd", "ping")
                                put("client_id", "ui-tester")
                            }
                            AgentBridgeProtocol.writeControlString(sock.outputStream, req.toString())
                            val respStr = AgentBridgeProtocol.readControlString(sock.inputStream)
                            val elapsed = System.currentTimeMillis() - startMs
                            val resp = JSONObject(respStr)
                            sock.close()

                            runOnUiThread {
                                if (resp.optBoolean("ok", false)) {
                                    Toast.makeText(this@MainActivity, tr(R.string.main_agent_ping_ok_ms, elapsed), Toast.LENGTH_SHORT).show()
                                    bridgeLogTv.text = "Agent Ping: OK (Host UID ${resp.optInt("host_uid")}, RT ${elapsed}ms)"
                                } else {
                                    bridgeLogTv.text = "Agent Ping Failed: ${resp.optString("error")}"
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                bridgeLogTv.text = "Agent Ping Error: ${e.message}"
                            }
                        }
                    }.start()
                }
            }

            val testNotifBtn = Button(this@MainActivity).apply {
                text = tr(R.string.main_test_notify)
                textSize = 11f
                setTextColor(NC.PRIMARY)
                background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                setOnClickListener {
                    bridgeLogTv.text = tr(R.string.main_dispatching_notification_via_nativec)
                    Thread {
                        try {
                            val sock = connectAgentSocket()
                            val req = JSONObject().apply {
                                put("v", 1)
                                put("cmd", "notify")
                                put("id", 1002)
                                put("tag", "hardware_ui")
                                put("title", "NativeCode Agent Bridge")
                                put("message", "Live notification dispatched via AF_UNIX bridge!")
                                put("priority", "high")
                            }
                            AgentBridgeProtocol.writeControlString(sock.outputStream, req.toString())
                            val respStr = AgentBridgeProtocol.readControlString(sock.inputStream)
                            val resp = JSONObject(respStr)
                            sock.close()

                            runOnUiThread {
                                if (resp.optBoolean("ok", false)) {
                                    Toast.makeText(this@MainActivity, tr(R.string.main_notification_posted_via_bridge), Toast.LENGTH_SHORT).show()
                                    bridgeLogTv.text = tr(R.string.main_notification_posted_ok_tag_hardware)
                                } else {
                                    bridgeLogTv.text = "Notification Failed: ${resp.optString("error")}"
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                bridgeLogTv.text = "Notification Error: ${e.message}"
                            }
                        }
                    }.start()
                }
            }

            btnRow1.addView(testPingBtn)
            btnRow1.addView(testNotifBtn)
            addView(btnRow1)

            val btnRow2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }

            val benchDecBtn = Button(this@MainActivity).apply {
                text = tr(R.string.main_bench_decode)
                textSize = 11f
                setTextColor(NC.PRIMARY)
                background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) }
                setOnClickListener {
                    val testFile = File(filesDir, "home/test_1080p30.mp4")
                    if (!testFile.exists()) {
                        bridgeLogTv.text = tr(R.string.main_benchmark_home_test_1080p30_mp4)
                        return@setOnClickListener
                    }
                    bridgeLogTv.text = tr(R.string.main_benchmarking_1080p_vpu_hardware_deco)
                    Thread {
                        val res = MediaCodecDecodeBenchmark.runDecode(testFile, MediaCodecDecodeBenchmark.OutputMode.SURFACE_IMAGE_READER)
                        runOnUiThread {
                            if (res.error == null) {
                                val fpsFormatted = String.format(Locale.US, "%.1f", res.fps)
                                val rtFormatted = String.format(Locale.US, "%.1f", res.realtimeFactor)
                                bridgeLogTv.text = tr(R.string.main_vpu_decode_frames_in_ms, res.codecName, res.decodedFrames, res.wallTimeMs, fpsFormatted, rtFormatted)
                            } else {
                                bridgeLogTv.text = "VPU Decode Error: ${res.error}"
                            }
                        }
                    }.start()
                }
            }

            val benchEncBtn = Button(this@MainActivity).apply {
                text = tr(R.string.main_bench_encode)
                textSize = 11f
                setTextColor(NC.PRIMARY)
                background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                setOnClickListener {
                    bridgeLogTv.text = tr(R.string.main_benchmarking_720p_vpu_hardware_encod)
                    Thread {
                        val testOutputFile = File(cacheDir, "bench_encoded_720p.mp4")
                        val res = MediaCodecEncodeBenchmark.runEncodeBenchmark(
                            outputFile = testOutputFile,
                            width = 1280,
                            height = 720,
                            frameCount = 150,
                            fps = 30,
                            bitrate = 4_000_000,
                            mime = "video/avc"
                        )
                        runOnUiThread {
                            if (res.error == null) {
                                val fpsFormatted = String.format(Locale.US, "%.1f", res.fps)
                                val rtFormatted = String.format(Locale.US, "%.1f", res.realtimeFactor)
                                val sizeKb = res.outputSizeBytes / 1024
                                bridgeLogTv.text = tr(R.string.main_vpu_encode_frames_in_ms, res.codecName, res.encodedFrames, res.wallTimeMs, fpsFormatted, rtFormatted, sizeKb)
                            } else {
                                bridgeLogTv.text = "VPU Encode Error: ${res.error}"
                            }
                        }
                    }.start()
                }
            }

            btnRow2.addView(benchDecBtn)
            btnRow2.addView(benchEncBtn)
            addView(btnRow2)

            val btnRow3 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }

            val testOpenLinkBtn = Button(this@MainActivity).apply {
                text = tr(R.string.main_test_open_link)
                textSize = 11f
                setTextColor(NC.PRIMARY)
                background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) }
                setOnClickListener {
                    bridgeLogTv.text = tr(R.string.main_testing_nativecode_agent_v1_open)
                    Thread {
                        try {
                            val sock = connectAgentSocket()
                            val req = JSONObject().apply {
                                put("v", 1)
                                put("cmd", "open_link")
                                put("url", "http://localhost:8080")
                                put("title", "TEST AGENT LINK")
                                put("message", "Dev server ready at http://localhost:8080")
                            }
                            AgentBridgeProtocol.writeControlString(sock.outputStream, req.toString())
                            val respStr = AgentBridgeProtocol.readControlString(sock.inputStream)
                            val resp = JSONObject(respStr)
                            sock.close()

                            runOnUiThread {
                                if (resp.optBoolean("ok", false)) {
                                    bridgeLogTv.text = "Agent Open Link: OK (${resp.optString("url")})"
                                } else {
                                    bridgeLogTv.text = "Agent Open Link Failed: ${resp.optString("error")}"
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                bridgeLogTv.text = "Agent Open Link Error: ${e.message}"
                            }
                        }
                    }.start()
                }
            }

            val testInstallApkBtn = Button(this@MainActivity).apply {
                text = tr(R.string.main_test_install_apk)
                textSize = 11f
                setTextColor(NC.PRIMARY)
                background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                setOnClickListener {
                    bridgeLogTv.text = tr(R.string.main_testing_nativecode_agent_v1_install)
                    Thread {
                        try {
                            var apkTarget = File(filesDir, "home/test_app.apk")
                            if (!apkTarget.exists()) {
                                val altApk = File(TermuxHostPaths.LOADER_APK)
                                if (altApk.exists()) {
                                    apkTarget = altApk
                                } else {
                                    apkTarget = File(cacheDir, "agent_preview_test.apk")
                                    if (!apkTarget.exists()) {
                                        apkTarget.writeBytes(ByteArray(1024))
                                    }
                                }
                            }

                            val sock = connectAgentSocket()
                            val req = JSONObject().apply {
                                put("v", 1)
                                put("cmd", "install_apk")
                                put("path", apkTarget.absolutePath)
                                put("title", "TEST AGENT APK")
                                put("message", "Background build completed. Test install:")
                            }
                            AgentBridgeProtocol.writeControlString(sock.outputStream, req.toString())
                            val respStr = AgentBridgeProtocol.readControlString(sock.inputStream)
                            val resp = JSONObject(respStr)
                            sock.close()

                            runOnUiThread {
                                if (resp.optBoolean("ok", false)) {
                                    val name = resp.optString("app_label", apkTarget.name)
                                    bridgeLogTv.text = tr(R.string.main_agent_install_apk_ok, name)
                                } else {
                                    bridgeLogTv.text = "Agent Install APK Failed: ${resp.optString("error")}"
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                bridgeLogTv.text = "Agent Install APK Error: ${e.message}"
                            }
                        }
                    }.start()
                }
            }

            btnRow3.addView(testOpenLinkBtn)
            btnRow3.addView(testInstallApkBtn)
            addView(btnRow3)

            bridgeLogTv = TextView(this@MainActivity).apply {
                text = tr(R.string.main_bridge_status_ready)
                textSize = 11f
                setTextColor(NC.PRIMARY_CON)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, 0)
            }
            addView(bridgeLogTv)
        })

        pageLayout.addView(spacer(16))

        pageLayout.addView(glassCard().apply {
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.hw_features_title)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })
            hwFeaturesTv = TextView(this@MainActivity).apply {
                text = tr(R.string.hw_checking)
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(2).toFloat(), 1.1f)
            }
            addView(hwFeaturesTv)
        })
    }

    private fun refreshHardwarePage() {
        val gpu = GpuRuntimeStatus.snapshot(this)
        gpuStatusBadge.text = gpu.badge
        gpuStatusBadge.setTextColor(
            if (gpu.badge == "SOFTWARE" || gpu.badge == "UNAVAILABLE") NC.ERROR else NC.PRIMARY
        )
        gpuDetailTv.text = buildString {
            append("GPU: ${gpu.gpuName}\n")
            append("GL vendor: ${gpu.glVendor}\n")
            append("OpenGL: ${gpu.glVersion}\n")
            append("GLSL: ${gpu.glslVersion}\n")
            append(tr(R.string.hw_gpu_host_vulkan, gpu.vulkanVersion)).append('\n')
            append(tr(R.string.hw_gpu_guest_driver, when {
                GpuAccelDetector.isTurnip() -> TurnipDriverUpdate.VERSION_LABEL
                GpuAccelDetector.isPanvk() -> PanvkDriverUpdate.VERSION_LABEL
                else -> "n/a"
            })).append('\n')
            append(tr(R.string.hw_gpu_guest_vulkan, gpu.guestVulkan)).append('\n')
            append("Vendor: ${gpu.vendor}\n")
            append("SoC: ${gpu.soc}\n")
            append("Method: ${gpu.method}\n")
            append("Backend: ${gpu.backend}\n")
            if (gpu.lastProbe.isNotBlank() && gpu.lastProbe != gpu.backend) {
                append("Last probe: ${gpu.lastProbe}\n")
            }
            append("Hint: ${gpu.modeHint}\n")
            append("KGSL: ${if (gpu.kgsl) "yes /dev/kgsl-3d0" else "no"}\n")
            if (!GpuAccelDetector.isTurnip()) {
                append("Host vtest: ${if (gpu.hostSocket) "socket live" else "offline"}\n")
                append("Venus server: ${gpu.venusServer}\n")
            }
            append("PulseAudio: ${gpu.pulseServer}\n")
            append("ICD: ${gpu.icd}")
        }
        bindHostServerStatus()
        android.util.Log.i(
            "GpuRuntimeStatus",
            tr(R.string.main_gpu_gl_vk_method, gpu.gpuName, gpu.glVersion, gpu.vulkanVersion, gpu.method)
        )

        val soc = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})"
        vpuSocDetailTv.text = "Device: $soc\nSoC: ${gpu.soc}\nAndroid API: ${Build.VERSION.SDK_INT}"

        val avcDec = MediaCodecSelector.selectHardwareDecoder("video/avc")?.name ?: "None"
        val hevcDec = MediaCodecSelector.selectHardwareDecoder("video/hevc")?.name ?: "None"
        val av1Dec = MediaCodecSelector.selectHardwareDecoder("video/av01")?.name ?: "None"
        val vp9Dec = MediaCodecSelector.selectHardwareDecoder("video/x-vnd.on2.vp9")?.name ?: "None"

        val avcEnc = MediaCodecSelector.selectHardwareEncoder("video/avc")?.name ?: "None"
        val hevcEnc = MediaCodecSelector.selectHardwareEncoder("video/hevc")?.name ?: "None"

        val hasHw = avcDec != "None" || hevcDec != "None"
        vpuStatusBadge.text = if (hasHw) tr(R.string.main_active) else tr(R.string.main_caps_unavailable)
        vpuStatusBadge.setTextColor(if (hasHw) NC.PRIMARY else NC.ERROR)

        vpuDecodersTv.text = "Hardware Decoders:\n• H.264 / AVC: $avcDec\n• HEVC / H.265: $hevcDec\n• AV1: $av1Dec\n• VP9: $vp9Dec"
        vpuEncodersTv.text = "Hardware Encoders:\n• H.264: $avcEnc\n• HEVC: $hevcEnc"

        bridgeStatusTv.text = tr(R.string.hw_checking)
        refreshHardwareProbes()
    }

    /** Hardware page probes that touch files / sockets / hashes: off the main thread, painted when done. */
    private fun refreshHardwareProbes() {
        val uid = android.os.Process.myUid()
        executor.execute {
            // Bound socket names, not "plain or uid": with Play + F-Droid side by side the plain name can be the OTHER app's.
            val agentName = NativeCodeBridge.agentSocketName()
            val mediaName = NativeCodeBridge.mediaSocketName()
            fun live(name: String?) = when {
                name == null -> tr(R.string.hw_bridge_not_started)
                probeLocalSocket(name) -> tr(R.string.main_active_live)
                else -> tr(R.string.main_offline_2)
            }
            val rdvText = try {
                File(ProjectPathResolver.prootRootfsDir(this), "run/${com.zenithblue.nativecode.agent.BridgeRendezvous.FILE_NAME}")
                    .takeIf { it.isFile }?.readText()
            } catch (_: Exception) { null }
            val rdv = when (HardwareStatus.rendezvous(rdvText, com.zenithblue.nativecode.agent.BridgeRendezvous.json(uid))) {
                HardwareStatus.Rendezvous.OK -> tr(R.string.hw_rdv_ok, uid)
                HardwareStatus.Rendezvous.STALE -> tr(R.string.hw_rdv_stale)
                HardwareStatus.Rendezvous.MISSING -> tr(R.string.hw_rdv_missing)
            }
            val bridgeText = "Agent socket: @${agentName ?: "-"}  ${live(agentName)}\n" +
                "Media socket: @${mediaName ?: "-"}  ${live(mediaName)}\n" +
                "Rendezvous: /run/${com.zenithblue.nativecode.agent.BridgeRendezvous.FILE_NAME}  $rdv\n" +
                "Peer UID: $uid (SO_PEERCRED)"

            // GPU driver package: same installed-sha / needsUpdate check as the Components update cards.
            val turnip = GpuAccelDetector.isTurnip()
            val applicable = turnip || GpuAccelDetector.isPanvk()
            val label = if (turnip) TurnipDriverUpdate.VERSION_LABEL else PanvkDriverUpdate.VERSION_LABEL
            var installed: String? = null
            var shipped: String? = null
            var needs = false
            if (applicable) try {
                installed = if (turnip) TurnipDriverUpdate.installedSha(this) else PanvkDriverUpdate.installedSha(this)
                shipped = if (turnip) TurnipDriverUpdate.bundledSha(this) else PanvkDriverUpdate.bundledSha(this)
                needs = if (turnip) TurnipDriverUpdate.needsUpdate(this) else PanvkDriverUpdate.needsUpdate(this)
            } catch (_: Exception) { }
            val drv = HardwareStatus.driver(applicable, installed, needs)
            val source = tr(if (BuildConfig.FDROID) R.string.hw_src_download else R.string.hw_src_bundled)
            val drvText = when (drv) {
                HardwareStatus.Driver.NOT_APPLICABLE -> tr(R.string.hw_driver_na_detail)
                HardwareStatus.Driver.MISSING -> tr(R.string.hw_driver_missing_detail, label)
                else -> tr(R.string.hw_driver_detail, label, HardwareStatus.shortSha(installed), HardwareStatus.shortSha(shipped), source) +
                    if (drv == HardwareStatus.Driver.UPDATE) "\n" + tr(R.string.hw_driver_update_hint) else ""
            }

            // Bridge features and system lines.
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val bat = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batPct = bat?.let {
                val lv = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val sc = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (lv >= 0 && sc > 0) "${lv * 100 / sc}%" else null
            } ?: "n/a"
            val thermal = (getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.let { HardwareStatus.thermal(it.currentThermalStatus) } ?: "UNKNOWN"
            val xlorie = File(applicationInfo.nativeLibraryDir, "libXlorie.so").isFile
            val mcpPort = com.zenithblue.nativecode.agent.chat.tools.NcMcp.port()
            val ports = com.zenithblue.nativecode.preview.PortProbe.probe()
            val skills = try { com.zenithblue.nativecode.skills.SkillsCatalog.all().size } catch (_: Throwable) { 0 }
            val features = listOf(
                tr(R.string.hw_device_line, mem.availMem / (1024 * 1024), mem.totalMem / (1024 * 1024), batPct, thermal),
                tr(if (!ProotOnly.ON && LinuxCommandBuilder.currentMethod == "chroot") R.string.hw_guest_chroot else R.string.hw_guest_proot),
                tr(if (!xlorie) R.string.hw_x11_missing else if (BuildConfig.FDROID) R.string.hw_x11_source else R.string.hw_x11_prebuilt),
                tr(R.string.hw_feat_commands, AgentBridgeServer.COMMANDS.joinToString(", ")),
                tr(R.string.hw_feat_chat),
                if (mcpPort > 0) tr(R.string.hw_feat_mcp_up, mcpPort) else tr(R.string.hw_feat_mcp_idle),
                tr(R.string.hw_feat_browser),
                if (ports.isEmpty()) tr(R.string.hw_feat_preview_none) else tr(R.string.hw_feat_preview_found, ports.joinToString(", ")),
                tr(R.string.hw_feat_skills, skills)
            ).joinToString("\n")

            runOnUiThread {
                bridgeStatusTv.text = bridgeText
                hwDriverBadge.text = when (drv) {
                    HardwareStatus.Driver.UP_TO_DATE -> tr(R.string.hw_driver_badge_ok)
                    HardwareStatus.Driver.UPDATE -> tr(R.string.hw_driver_badge_update)
                    HardwareStatus.Driver.MISSING -> tr(R.string.hw_driver_badge_missing)
                    HardwareStatus.Driver.NOT_APPLICABLE -> "N/A"
                }
                hwDriverBadge.setTextColor(
                    when (drv) {
                        HardwareStatus.Driver.UP_TO_DATE -> NC.PRIMARY
                        HardwareStatus.Driver.MISSING -> NC.ERROR
                        else -> NC.ON_SURF_VAR
                    }
                )
                hwDriverTv.text = drvText
                hwDriverBtn.visibility = if (drv == HardwareStatus.Driver.UPDATE) View.VISIBLE else View.GONE
                hwFeaturesTv.text = features
            }
        }
    }

    private fun hostServerButtonRow(kind: HostServerController.Kind): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, action: (Context) -> String): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(NC.PRIMARY)
                background = cyberBrutalistBg(NC.SURFACE_HIGH, NC.OUTLINE_VAR, NC.SHADOW_DARK, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) }
                setOnClickListener {
                    isEnabled = false
                    Thread {
                        val msg = try {
                            action(this@MainActivity)
                        } catch (e: Exception) {
                            "${kind.name}: ${e.message}"
                        }
                        runOnUiThread {
                            isEnabled = true
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            bindHostServerStatus()
                            refreshHardwarePage()
                        }
                    }.start()
                }
            }
        }
        row.addView(btn(tr(R.string.ssh_start)) { HostServerController.start(it, kind) })
        row.addView(btn(tr(R.string.chat_stop)) { HostServerController.stop(kind) })
        row.addView(btn(tr(R.string.main_restart)) { HostServerController.restart(it, kind) })
        return row
    }

    private fun bindHostServerStatus() {
        if (!::pulseServerBadge.isInitialized) return
        val turnip = GpuAccelDetector.isTurnip()
        if (::venusHostBlock.isInitialized) {
            venusHostBlock.visibility = if (turnip) View.GONE else View.VISIBLE
        }
        if (::hostServersBlurbTv.isInitialized) {
            hostServersBlurbTv.text = if (turnip) {
                tr(R.string.hw_pulse_turnip_blurb)
            } else {
                tr(R.string.main_venus_vtest_pulseaudio_tcp_fluxlinux)
            }
        }
        val venus = HostServerController.status(HostServerController.Kind.VENUS)
        val pulse = HostServerController.status(HostServerController.Kind.PULSE)
        fun paint(badge: TextView, detail: TextView, st: HostServerController.Status, title: String, optional: Boolean, extra: String = "") {
            val state = HardwareStatus.host(st.binaryExists, st.running, optional)
            badge.text = when (state) {
                HardwareStatus.Host.RUNNING -> "RUNNING"
                HardwareStatus.Host.MISSING -> "MISSING"
                HardwareStatus.Host.NOT_BUNDLED -> tr(R.string.hw_host_not_bundled)
                HardwareStatus.Host.STOPPED -> tr(R.string.ssh_stopped)
            }
            badge.setTextColor(
                when (state) {
                    HardwareStatus.Host.RUNNING -> NC.PRIMARY
                    HardwareStatus.Host.MISSING -> NC.ERROR
                    else -> NC.ON_SURF_VAR
                }
            )
            detail.text = if (state == HardwareStatus.Host.NOT_BUNDLED) tr(R.string.hw_venus_not_bundled) else "$title: ${st.detail}$extra"
        }
        // Venus is optional: F-Droid builds may not ship the host server (PanVK/Turnip do not need it).
        if (!turnip && ::venusServerBadge.isInitialized) {
            paint(venusServerBadge, venusServerDetailTv, venus, "Venus", optional = BuildConfig.FDROID)
        }
        paint(
            pulseServerBadge, pulseServerDetailTv, pulse, "PulseAudio", optional = false,
            extra = "\n" + tr(R.string.hw_pulse_port, PulseHost.PORT, if (PulseHost.USES_AAUDIO) "AAudio" else "OpenSL ES")
        )
    }

    private fun probeLocalSocket(name: String): Boolean {
        return try {
            val s = android.net.LocalSocket()
            s.connect(android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT))
            s.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun connectAgentSocket(): android.net.LocalSocket {
        val candidates = listOf(
            "nativecode.agent.v1",
            "nativecode.agent.v1.${android.os.Process.myUid()}"
        )
        for (name in candidates) {
            try {
                val s = android.net.LocalSocket()
                s.connect(android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT))
                return s
            } catch (ignored: Exception) {}
        }
        throw java.io.IOException("Agent bridge offline")
    }

    /** In-app report composer. Failed deliveries remain queued for automatic retry. */
    private fun showAiReportComposer(preselectedToolId: String?) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)

        val method = LinuxCommandBuilder.currentMethod
        val tools = CliToolCatalog.forMethod(method)
        val categories = listOf(
            tr(R.string.main_harmful_content),
            tr(R.string.main_illegal_content),
            tr(R.string.main_privacy),
            tr(R.string.main_other)
        )
        var selectedCategory = categories[0]
        var selectedToolId: String? = preselectedToolId

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC0A0A0A"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3C4A3F")
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(20)
                rightMargin = dp(20)
            }
            isClickable = true
        }

        card.addView(TextView(this).apply {
            text = tr(R.string.main_report_issue)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(TextView(this).apply {
            text = tr(R.string.main_submits_in_app_no_files)
            textSize = 11f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(12))
        })

        card.addView(TextView(this).apply {
            text = tr(R.string.main_category)
            textSize = 10f
            setTextColor(NC.OUTLINE)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        })
        val catRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val catButtons = mutableListOf<TextView>()
        fun paintCatSelection() {
            catButtons.forEach { btn ->
                val on = btn.tag == selectedCategory
                btn.setTextColor(if (on) Color.parseColor("#0A0A0A") else NC.ON_SURF_VAR)
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (on) NC.PRIMARY else NC.SURFACE_CONTAINER)
                    setStroke(dp(1), if (on) NC.PRIMARY else NC.OUTLINE_VAR)
                }
            }
        }
        // two columns of chips via horizontal rows of 2
        var row: LinearLayout? = null
        categories.forEachIndexed { i, cat ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        bottomMargin = dp(6)
                    }
                }
                catRow.addView(row)
            }
            val chip = TextView(this).apply {
                text = cat.uppercase()
                tag = cat
                textSize = 10f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (i % 2 == 0) rightMargin = dp(6)
                }
                setOnClickListener {
                    selectedCategory = cat
                    paintCatSelection()
                }
            }
            catButtons.add(chip)
            row!!.addView(chip)
        }
        paintCatSelection()
        card.addView(catRow)

        card.addView(TextView(this).apply {
            text = tr(R.string.main_tool_optional)
            textSize = 10f
            setTextColor(NC.OUTLINE)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(6))
        })
        val toolLabels = listOf(tr(R.string.main_all_general)) + tools.map { it.displayName }
        val toolIds: List<String?> = listOf(null) + tools.map { it.id }
        var toolIndex = toolIds.indexOf(preselectedToolId).let { if (it < 0) 0 else it }
        selectedToolId = toolIds[toolIndex]
        val toolBtn = secondaryButton(toolLabels[toolIndex]) {}.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
            textSize = 11f
        }
        toolBtn.setOnClickListener {
            toolIndex = (toolIndex + 1) % toolLabels.size
            selectedToolId = toolIds[toolIndex]
            toolBtn.text = toolLabels[toolIndex]
        }
        card.addView(toolBtn)

        card.addView(TextView(this).apply {
            text = tr(R.string.main_description)
            textSize = 10f
            setTextColor(NC.OUTLINE)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        })
        val descInput = EditText(this).apply {
            hint = tr(R.string.main_what_happened_optional_but_helpful)
            setHintTextColor(NC.OUTLINE)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }
        card.addView(descInput)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnRow.addView(
            secondaryButton(tr(R.string.x11_cancel)) { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
                textSize = 12f
            }
        )
        val submitButton = primaryButton(tr(R.string.main_submit_in_app)) {}
        submitButton.setOnClickListener {
                submitButton.isEnabled = false
                submitButton.text = tr(R.string.main_submitting)
                InAppReportService.submit(
                    context = this@MainActivity,
                    toolId = selectedToolId,
                    category = selectedCategory,
                    description = descInput.text?.toString().orEmpty()
                ) { result ->
                    runOnUiThread {
                        val message = when (result) {
                            InAppReportService.Result.DELIVERED -> tr(R.string.main_report_submitted)
                            InAppReportService.Result.QUEUED ->
                                "Report queued securely; automatic retry enabled"
                            InAppReportService.Result.REJECTED ->
                                tr(R.string.main_report_not_saved_description_is)
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        if (result == InAppReportService.Result.REJECTED) {
                            submitButton.isEnabled = true
                            submitButton.text = tr(R.string.main_submit_in_app)
                        } else {
                            dialog.dismiss()
                        }
                    }
                }
        }
        submitButton.apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            textSize = 12f
        }
        btnRow.addView(submitButton)
        card.addView(btnRow)

        scrim.addView(card)
        dialog.setContentView(scrim)
        dialog.show()
    }

    // ── AI CLI sign-in: Settings list (one row per CLI); every sign-in / sign-out runs in the CliLoginSheet popup ──

    /** Last probed login state per CLI id; a change rebuilds the card grids (see paintCliAuth). */
    private val cliAuthStates = HashMap<String, AuthState>()

    // ── AI CLI tools page (Compose: cliauth/ui/CliToolsPage.kt): one card per CLI with version, status chips and actions ──
    private val cliTools = com.zenithblue.nativecode.cliauth.ui.CliToolsModel()

    private fun cliUpdLocked(id: String) = aiToolLocked(CliToolCatalog.byId(id)?.terminalType ?: id)

    /** Guest `--version` + vendor latest for every unlocked CLI, off the main thread; cards update as each lands. */
    private fun checkCliUpdates(force: Boolean) {
        if (cliTools.checking) return
        val ids = cliTools.tools.map { it.id }.filter { !cliUpdLocked(it) && it !in cliTools.busy && (force || CliUpdates.stale(it)) }
        if (ids.isEmpty()) return
        cliTools.checking = true
        Thread {
            ids.forEach { id -> val r = CliUpdates.status(this, id, force); runOnUiThread { r?.let { cliTools.rows[id] = it } } }
            runOnUiThread { cliTools.checking = false; cliTools.checkedAt = cliTools.rows.values.maxOfOrNull { it.at } ?: 0L }
        }.start()
    }

    /** A chat process or terminal tab of this CLI is open: updating would swap the binary under it. */
    private fun cliInUse(def: CliToolDef): Boolean {
        val others = projectTabNamesMap.filterKeys { it != workspaceLoadedPath }.values.flatten()
        return AgentChats.hasTool(def.terminalType) || (terminalSessionTypes + workspaceTabNames + others).any { it == def.terminalType }
    }

    /** Update or (re)install [id] under the same gate as launching the tool (Free: opencode only). [then] runs when it ends or is refused: null = done, else why not. */
    private fun runCliOp(id: String, install: Boolean, then: (String?) -> Unit = {}) {
        val def = CliToolCatalog.byId(id) ?: return then("Unknown tool")
        if (id in cliTools.busy) return then(tr(R.string.main_updating))
        if (!gateAllows(FeatureGate.canLaunchAiTool(isPro(), def.terminalType))) return then(tr(R.string.chat_err_pro))
        if (cliInUse(def)) {
            val why = tr(R.string.cli_upd_in_use, def.displayName)
            Toast.makeText(this, why, Toast.LENGTH_LONG).show()
            return then(why)
        }
        cliTools.busy[id] = tr(R.string.main_updating)
        val onState = { st: CliVersionCheck.State ->
            when (st) {
                is CliVersionCheck.State.Running -> cliTools.busy[id] = st.text
                is CliVersionCheck.State.Done -> Thread {
                    val row = CliUpdates.status(this, id, force = true)
                    runOnUiThread {
                        row?.let { cliTools.rows[id] = it }
                        cliTools.checkedAt = cliTools.rows.values.maxOfOrNull { it.at } ?: 0L
                        cliTools.busy.remove(id)
                        val ver = row?.installed.orEmpty()
                        Toast.makeText(this, when {
                            st.ok && install -> tr(R.string.cli_page_install_done, def.displayName, ver)
                            st.ok -> tr(R.string.cli_upd_done, def.displayName, ver)
                            install -> tr(R.string.cli_page_install_failed, def.displayName, st.message)
                            else -> tr(R.string.cli_upd_failed, def.displayName, st.message)
                        }, Toast.LENGTH_LONG).show()
                        paintCliAuth()
                        then(if (st.ok) null else st.message)
                    }
                }.start()
            }
        }
        if (install) CliVersionCheck.install(this, id, onState) else CliVersionCheck.update(this, id, onState)
    }

    private val cliToolsHost = object : com.zenithblue.nativecode.cliauth.ui.CliToolsHost {
        override fun check() = checkCliUpdates(true)
        override fun updateAll() {
            val ids = cliTools.pending().map { it.id }
            fun next(i: Int) { if (i < ids.size) runCliOp(ids[i], false) { _ -> next(i + 1) } }
            next(0)
        }
        override fun update(id: String) = runCliOp(id, false)
        override fun install(id: String) = runCliOp(id, true)
        override fun signIn(id: String) = showCliLogin(id)
        override fun openTerminal(def: CliToolDef) = openCliTerminal(def)
        override fun upgrade(id: String) { gateAllows(FeatureGate.canLaunchAiTool(isPro(), CliToolCatalog.byId(id)?.terminalType ?: id)) }
        override fun github(signedIn: Boolean) {
            val method = LinuxCommandBuilder.currentMethod
            if (signedIn) confirmGhLogout(method) else startGithubConnect(method, fromSettings = true)
        }
        override fun openSafety() {
            if (pageStack.isEmpty() || pageStack.peek() != ID_AI_SAFETY) pageStack.push(ID_AI_SAFETY)
            navigateToPage(ID_AI_SAFETY)
        }
        override fun clearCredentials() {
            CredentialClearService.clearAllAiCredentials(this@MainActivity, LinuxCommandBuilder.currentMethod) { _, msg ->
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                paintCliAuth()
            }
        }
        override fun clearSuiteFlag() {
            CredentialClearService.clearAiSuiteProvisionFlag(this@MainActivity, LinuxCommandBuilder.currentMethod)
            refreshTerminalToolSelector()
            refreshWorkspaceHubTools()
            paintCliAuth()
        }
    }

    /**
     * ONE shared login status row for every AI CLI card (terminal grid, workspace hub, Settings list):
     * hairline divider, state dot + label, and a LOGIN action on the right. 48dp touch target.
     * Login-optional CLIs (opencode) show the same row with an "optional" label; sign-in is never required.
     */
    private fun cliLoginControl(id: String, state: AuthState, onClick: () -> Unit): View? {
        val signedIn = state == AuthState.SIGNED_IN
        val optional = !signedIn && CliToolCatalog.byId(id)?.loginOptional == true
        val name = cliAuthName(id)
        fun label(t: String, color: Int, size: Float, bold: Boolean) = TextView(this).apply {
            text = t
            textSize = size
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = bold
            maxLines = 1
            includeFontPadding = false
            setTextColor(color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val dotColor = when (state) {
            AuthState.SIGNED_IN -> NC.PRIMARY
            AuthState.SIGNED_OUT -> NC.TERTIARY_CON
            AuthState.UNKNOWN -> NC.OUTLINE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
            // hairline divider on top, same rule colour as the section dividers
            background = LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(NC.OUTLINE_VAR),
                android.graphics.drawable.ColorDrawable(NC.SURFACE_LOW)
            )).apply { setLayerInset(1, 0, dp(1), 0, 0) }
            setPadding(0, dp(1), 0, 0)
            isClickable = true
            isFocusable = true
            contentDescription = if (signedIn) "Signed in to $name. Open login page" else if (optional) "Log in to $name (optional)" else "Log in to $name"
            tooltipText = contentDescription
            setOnClickListener { onClick() }
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(8) }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
            })
            addView(label(
                when (state) {
                    AuthState.SIGNED_IN -> tr(R.string.main_signed_in)
                    AuthState.SIGNED_OUT -> tr(if (optional) R.string.main_not_signed_in_optional else R.string.main_not_signed_in_2)
                    AuthState.UNKNOWN -> if (optional) tr(R.string.cli_page_login_optional).uppercase() else tr(R.string.main_unknown)
                },
                NC.ON_SURF_VAR, 10f, false
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!signedIn) addView(label(tr(R.string.main_login), NC.PRIMARY, 11f, true))
        }
    }

    /** Sign-in popup for AI CLI [id], from every entry point (Settings list, tool cards, chat banners, Usage). */
    private fun showCliLogin(id: String) {
        val def = CliToolCatalog.byId(id) ?: return
        if (!gateAllows(FeatureGate.canLaunchAiTool(isPro(), def.terminalType))) return
        // Version gate: the popup opens at once unless the cached version is already known too old; the (cached, once per session) probe
        // then either lets it be or swaps it for the update popup, which reopens the sign-in after a successful update.
        val open = CliVersionCheck.cached(id)?.tooOld(CliNeed.LOGIN) != true
        if (open) cliLoginId.value = id
        var asked = false
        Thread {
            val r = CliVersionCheck.probe(this, id, CliNeed.LOGIN)
            runOnUiThread {
                CliGate.run(r?.installed, def.minLoginVersion,
                    ask = { cb -> asked = true; if (cliLoginId.value == id) cliLoginId.value = null; showCliUpdate(id, CliNeed.LOGIN, cb) },
                    go = { if (!open || asked) cliLoginId.value = id },
                    stop = {})
            }
        }.start()
    }
    /** The update popup for [id] (cached version = the one that was just found too old); [done] true once it was updated, false on cancel / failure. */
    private fun showCliUpdate(id: String, need: CliNeed, done: (Boolean) -> Unit) {
        val r = CliVersionCheck.cached(id)
        if (r?.installed == null) return done(false)
        cliUpdateReq.value?.done?.invoke(false)
        cliUpdateErr.value = null
        cliUpdateReq.value = CliUpdateReq(r, need, done)
    }
    private fun runCliUpdate() {
        val q = cliUpdateReq.value ?: return
        cliUpdateErr.value = null
        runCliOp(q.r.tool, false) { err ->
            if (err == null) { cliUpdateReq.value = null; q.done(true) } else cliUpdateErr.value = err
        }
    }
    private fun closeCliUpdate() {
        val q = cliUpdateReq.value ?: return
        cliUpdateReq.value = null
        q.done(false)
    }

    /** Card pill: probes login state off-thread, then appends the shared pill to [card]. */
    private fun addCliLoginChip(card: LinearLayout, id: String) {
        val method = LinuxCommandBuilder.currentMethod
        Thread {
            val state = CliCredentialProbe.probe(id, ProjectPathResolver.guestHomeDir(this, method)).state
            runOnUiThread {
                cliAuthStates[id] = state
                cliLoginControl(id, state) { showCliLogin(id) }?.let { card.addView(it) }
            }
        }.start()
    }

    private fun cliAuthName(id: String) = if (id == "gh") "GitHub CLI" else CliToolCatalog.byId(id)?.displayName ?: id

    private fun cliAuthDetail(i: CliAuthInfo): String? =
        listOfNotNull(i.account, i.detail).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** Re-reads login state from the auth files (plain file I/O off the main thread) and refreshes the page model. */
    private fun paintCliAuth() {
        val method = LinuxCommandBuilder.currentMethod
        cliAuthMethodBadge?.text = method.uppercase()
        val tools = CliToolCatalog.forMethod(method)
        val ids = tools.map { it.id } + "gh"
        Thread {
            val home = ProjectPathResolver.guestHomeDir(this, method)
            val infos = ids.associateWith { CliCredentialProbe.probe(it, home) }
            runOnUiThread {
                // Login state changed since last look: rebuild the card grids so their pills update.
                val changed = infos.any { (k, v) -> cliAuthStates[k]?.let { it != v.state } == true }
                infos.forEach { (k, v) -> cliAuthStates[k] = v.state; cliTools.auth[k] = v }
                if (changed) {
                    refreshTerminalToolSelector()
                    refreshWorkspaceHubTools()
                }
                cliTools.tools = tools
                tools.forEach { t ->
                    cliTools.locked[t.id] = cliUpdLocked(t.id)
                    CliUpdates.row(t.id)?.let { cliTools.rows[t.id] = it }
                }
                cliTools.checkedAt = cliTools.rows.values.maxOfOrNull { it.at } ?: 0L
                checkCliUpdates(false)
            }
        }.start()
    }

    private fun openCliTerminal(def: CliToolDef) {
        createNewTerminalSession(def.terminalType)
        if (pageStack.isEmpty() || pageStack.peek() != ID_TERMINAL) pageStack.push(ID_TERMINAL)
        navigateToPage(ID_TERMINAL)
    }

    private fun confirmGhLogout(method: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr(R.string.main_sign_out_of, cliAuthName("gh")))
            .setMessage(tr(R.string.main_this_removes_the_saved_login))
            .setPositiveButton(tr(R.string.main_sign_out)) { _, _ ->
                GitHubCliService.logout(this, method) { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    paintCliAuth()
                }
            }
            .setNegativeButton(tr(R.string.x11_cancel), null)
            .show()
    }

    private fun buildProjectCreateLayout() {
        projectCreateContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        projectCreateScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            clipToPadding = false
            setPadding(0, 0, 0, dp(24))
            applyDesignScrollbar(this)
        }
        projectCreateLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        projectCreateScrollView.addView(projectCreateLayout)

        // Sticky page title bar (app unifiedHeader sits above this)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), Color.parseColor("#3C4A3F"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val backBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.ON_SURFACE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(4) }
            setOnClickListener {
                onBackPressed()
            }
        }
        val logoIv = ImageView(this).apply {
            setImageResource(R.drawable.logo_highres)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val titleTv = TextView(this).apply {
            text = tr(R.string.main_create_import_project)
            textSize = 16f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        topBar.addView(backBtn)
        topBar.addView(logoIv)
        topBar.addView(titleTv)
        projectCreateContainer.addView(topBar)
        projectCreateContainer.addView(projectCreateScrollView)

        // --- Card 0: Connect to GitHub ---
        projectCreateLayout.addView(buildCreateGithubBanner())

        // --- Card 1: Project Name ---
        val nameCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        }
        nameCard.addView(TextView(this).apply {
            text = tr(R.string.main_project_name)
            setTextColor(NC.PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(8))
        })
        projectNameInput = EditText(this).apply {
            hint = tr(R.string.main_my_awesome_app)
            setHintTextColor(NC.OUTLINE)
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOWEST,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = Color.TRANSPARENT,
                offsetDp = 0,
                cornerRadiusDp = 0
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        nameCard.addView(projectNameInput)
        projectCreateLayout.addView(nameCard)

        // --- Card 2: Project Icon (Only Browse + Preview Box) ---
        val iconCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        }
        iconCard.addView(TextView(this).apply {
            text = tr(R.string.main_project_icon)
            setTextColor(NC.PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(8))
        })

        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val iconPreviewFrame = FrameLayout(this).apply {
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_HIGH,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 2,
                cornerRadiusDp = 0
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) }
        }

        val iconPreviewIv = ImageView(this).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(NC.PRIMARY)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val iconPreviewTv = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        iconPreviewFrame.addView(iconPreviewIv)
        iconPreviewFrame.addView(iconPreviewTv)

        projectIconInput = EditText(this).apply {
            hint = ""
        }

        val chooseIconBtn = secondaryButton(tr(R.string.main_browse)) {
            activeIconInput = projectIconInput
            activeIconPreviewIv = iconPreviewIv
            activeIconPreviewTv = iconPreviewTv
            projectIconPickerLauncher.launch("image/*")
        }

        iconRow.addView(iconPreviewFrame)
        iconRow.addView(chooseIconBtn)
        iconCard.addView(iconRow)
        projectCreateLayout.addView(iconCard)

        // --- Card 2a: Location (parent folder inside the guest home; New and Clone create <location>/<name>) ---
        projectCreateLayout.addView(buildProjectLocationCard())

        // --- Card 2b: Your repositories (visible when logged in) ---
        projectCreateLayout.addView(buildCreateGithubRepoCard())

        // --- Card 3: GitHub Repository URL ---
        val githubCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        }
        val ghUrlHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        ghUrlHeader.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_github)
            scaleType = ImageView.ScaleType.FIT_CENTER
            clearColorFilter()
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
        })
        ghUrlHeader.addView(TextView(this).apply {
            text = tr(R.string.main_github_repository_url_optional)
            setTextColor(NC.PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
        })
        githubCard.addView(ghUrlHeader)
        projectGithubInput = EditText(this).apply {
            hint = "github.com/owner/repository"
            setHintTextColor(NC.OUTLINE)
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOWEST,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = Color.TRANSPARENT,
                offsetDp = 0,
                cornerRadiusDp = 0
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        githubCard.addView(projectGithubInput)
        projectCreateLayout.addView(githubCard)

        // --- Card 4: Linux Isolation Mode ---
        val methodCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        }
        methodCard.addView(TextView(this).apply {
            text = tr(R.string.main_linux_isolation_mode_2)
            setTextColor(NC.PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(8))
        })
        methodCard.addView(TextView(this).apply {
            text = tr(R.string.main_select_engine_for_this_project)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(12))
        })

        // Default proot; chroot only if root + install gates pass (refreshed below)
        projectCreateSelectedMethod = "proot"

        val prootChip = TextView(this).apply {
            text = tr(R.string.main_standard)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        }
        val chrootChip = TextView(this).apply {
            text = "Root"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) }
        }
        projectCreateProotChip = prootChip
        projectCreateChrootChip = chrootChip
        probeCreateChrootRoot()
        refreshProjectCreateMethodChips()

        prootChip.setOnClickListener {
            projectCreateSelectedMethod = "proot"
            styleCreateMethodChip(prootChip, true)
            styleCreateMethodChip(chrootChip, false)
            chrootChip.alpha = if (isCreateChrootSelectable()) 1f else 0.4f
            refreshCreateGithubUi()
        }
        chrootChip.setOnClickListener {
            if (!isCreateChrootSelectable()) {
                Toast.makeText(this, createChrootDisabledReason(), Toast.LENGTH_SHORT).show()
                // Force visual back to unselected disabled
                styleCreateMethodChip(chrootChip, false)
                chrootChip.alpha = 0.4f
                if (projectCreateSelectedMethod == "chroot") {
                    projectCreateSelectedMethod = "proot"
                    styleCreateMethodChip(prootChip, true)
                }
                return@setOnClickListener
            }
            projectCreateSelectedMethod = "chroot"
            styleCreateMethodChip(prootChip, false)
            styleCreateMethodChip(chrootChip, true)
            chrootChip.alpha = 1f
            refreshCreateGithubUi()
        }
        prootChip.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().translationX(dp(2).toFloat()).translationY(dp(2).toFloat()).setDuration(60).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().translationX(0f).translationY(0f).setDuration(60).start()
            }
            false
        }
        chrootChip.setOnTouchListener { v, event ->
            // No press animation when disabled (root/install gate fail)
            if (!isCreateChrootSelectable()) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().translationX(dp(2).toFloat()).translationY(dp(2).toFloat()).setDuration(60).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().translationX(0f).translationY(0f).setDuration(60).start()
            }
            false
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        chipRow.addView(prootChip)
        chipRow.addView(chrootChip)
        methodCard.addView(chipRow)
        // Project import: isolation selection removed per UI requirements
        // projectCreateSelectedMethod stays default "proot"

        // --- Primary Button ---
        projectCreateBtn = primaryButton(tr(R.string.main_create_import_project_2)) {
            val name = projectNameInput.text.toString().trim()
            val gitUrl = projectGithubInput.text.toString().trim()
            val createFeature = if (gitUrl.isEmpty()) ProFeature.PROJECTS else ProFeature.PROJECT_IMPORT
            if (!gateAllows(FeatureGate.canUse(isPro(), createFeature))) return@primaryButton
            val icon = projectIconInput.text.toString().trim()
            var method = projectCreateSelectedMethod
            // Hard gate: never create with chroot unless root + install (same as chip)
            if (method == "chroot" && !isCreateChrootSelectable()) {
                Toast.makeText(this, createChrootDisabledReason(), Toast.LENGTH_SHORT).show()
                method = "proot"
                projectCreateSelectedMethod = "proot"
                refreshProjectCreateMethodChips()
                return@primaryButton
            }

            if (name.isEmpty()) {
                Toast.makeText(this, tr(R.string.main_please_enter_a_project_name), Toast.LENGTH_SHORT).show()
                return@primaryButton
            }

            // Location field + folder name; shows the inline error and stops on any validation failure.
            val path = validateProject(method, name, gitUrl) ?: return@primaryButton

            // Switch isolation to chip method BEFORE clone so all nested builds match
            LinuxCommandBuilder.currentMethod = method
            getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                .edit().putString("linux_method", method).apply()
            refreshToolCardsForMethod()

            if (gitUrl.isNotEmpty()) {
                projectCreateBtn.isEnabled = false
                projectCreateBtn.alpha = 0.5f

                // Build full-screen clone progress overlay
                val overlayRoot = FrameLayout(this).apply {
                    setBackgroundColor(NC.SURFACE_LOWEST)
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                }

                val overlayHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(NC.SURFACE_LOWEST)
                        setStroke(dp(1), Color.parseColor("#3C4A3F"))
                    }
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP }
                }
                val overlayTitleRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val pulseIcon = TextView(this).apply {
                    text = "\u25cf"
                    textSize = 12f
                    setTextColor(NC.PRIMARY)
                    setPadding(0, 0, dp(8), 0)
                    tag = "PULSE_ICON"
                }
                val overlayTitle = TextView(this).apply {
                    text = tr(R.string.main_cloning_repository)
                    textSize = 14f
                    setTextColor(NC.ON_SURFACE)
                    typeface = Typeface.MONOSPACE
                    paint.isFakeBoldText = true
                }
                val repoShortName = gitUrl.substringAfterLast("/")
                val overlaySubtitle = TextView(this).apply {
                    text = "\$ git clone $repoShortName"
                    textSize = 12f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(4), 0, 0)
                }
                overlayTitleRow.addView(pulseIcon)
                overlayTitleRow.addView(overlayTitle)
                overlayHeader.addView(overlayTitleRow)
                overlayHeader.addView(overlaySubtitle)

                // Indeterminate progress strip
                val progressStrip = FrameLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH, dp(3)).apply {
                        gravity = Gravity.TOP
                        topMargin = dp(62)
                    }
                    setBackgroundColor(Color.parseColor("#22FFFFFF"))
                }
                val progressFill = View(this).apply {
                    setBackgroundColor(NC.PRIMARY)
                    layoutParams = FrameLayout.LayoutParams(0, MATCH)
                    tag = "PROGRESS_FILL"
                }
                progressStrip.addView(progressFill)

                // Animate progress fill indefinitely
                val animRunnable = object : Runnable {
                    private var pos = 0f
                    override fun run() {
                        pos += 0.02f
                        if (pos > 1f) pos = 0f
                        val totalW = progressStrip.width
                        val fillW = (totalW * 0.3f).toInt()
                        val offset = (totalW * pos).toInt()
                        val lp = progressFill.layoutParams as FrameLayout.LayoutParams
                        lp.width = fillW
                        lp.leftMargin = offset
                        progressFill.layoutParams = lp
                        mainHandler.postDelayed(this, 16)
                    }
                }
                mainHandler.post(animRunnable)

                // Log scroll area
                val logScroll = ScrollView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply {
                        topMargin = dp(66)
                        bottomMargin = dp(52)
                    }
                    setBackgroundColor(Color.parseColor("#0A0A0A"))
                }
                val logLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                logScroll.addView(logLayout)

                // Status bar at bottom
                val statusBar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(NC.SURFACE_CONTAINER)
                        setStroke(dp(1), Color.parseColor("#3C4A3F"))
                    }
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = FrameLayout.LayoutParams(MATCH, dp(48)).apply { gravity = Gravity.BOTTOM }
                }
                val statusTv = TextView(this).apply {
                    text = tr(R.string.main_connecting_to_github)
                    textSize = 12f
                    setTextColor(NC.PRIMARY)
                    typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                }
                val lineTv = TextView(this).apply {
                    text = tr(R.string.main_0_lines)
                    textSize = 11f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                }
                statusBar.addView(statusTv)
                statusBar.addView(lineTv)

                overlayRoot.addView(overlayHeader)
                overlayRoot.addView(progressStrip)
                overlayRoot.addView(logScroll)
                overlayRoot.addView(statusBar)

                contentFrame.addView(overlayRoot)
                overlayRoot.bringToFront()

                // Animate pulse icon
                val pulseRunnable = object : Runnable {
                    var visible = true
                    override fun run() {
                        visible = !visible
                        pulseIcon.setTextColor(if (visible) NC.PRIMARY else Color.TRANSPARENT)
                        mainHandler.postDelayed(this, 600)
                    }
                }
                mainHandler.post(pulseRunnable)

                var lineCount = 0

                fun appendLog(line: String) {
                    lineCount++
                    val tv = TextView(this).apply {
                        text = line
                        textSize = 11f
                        setTextColor(when {
                            line.contains("error", ignoreCase = true) || line.contains("fatal", ignoreCase = true) -> NC.ERROR
                            line.contains("Cloning") || line.contains("Receiving") || line.contains("Resolving") -> NC.PRIMARY
                            line.contains("done.") || line.contains("complete") -> Color.parseColor("#60F99E")
                            else -> NC.ON_SURFACE
                        })
                        typeface = Typeface.MONOSPACE
                        setPadding(0, dp(2), 0, dp(2))
                    }
                    logLayout.addView(tv)
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    lineTv.text = "$lineCount lines"
                    statusTv.text = when {
                        line.contains("Cloning") -> "Cloning objects..."
                        line.contains("Receiving") -> "Receiving objects..."
                        line.contains("Resolving") -> "Resolving deltas..."
                        line.contains("Checking out") -> "Checking out files..."
                        line.contains("done.") -> "Finalizing..."
                        else -> statusTv.text
                    }
                }

                executor.execute {
                    ProjectManager.cloneRepo(
                        this@MainActivity,
                        gitUrl,
                        destPath = path,
                        method = method,
                        onProgress = { line ->
                            mainHandler.post { appendLog(line) }
                        },
                        onDone = { exitCode ->
                            mainHandler.post {
                                mainHandler.removeCallbacks(pulseRunnable)
                                mainHandler.removeCallbacks(animRunnable)
                                contentFrame.removeView(overlayRoot)
                                projectCreateBtn.isEnabled = true
                                projectCreateBtn.alpha = 1f
                                if (exitCode == 0) {
                                    val hostDir = ProjectPathResolver.resolve(this@MainActivity, path, method)
                                    if (!File(hostDir, ".git").isDirectory) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Clone reported OK but no .git under ${ProjectPathResolver.methodLabel(method)}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        navigateToPage(ID_PROJECT_CREATE)
                                        return@post
                                    }
                                    addAndOpenProject(name, icon, path, method, skipEnsureDir = true)
                                } else {
                                    Toast.makeText(this@MainActivity, tr(R.string.main_clone_failed_check_url_or), Toast.LENGTH_LONG).show()
                                    navigateToPage(ID_PROJECT_CREATE)
                                }
                            }
                        }
                    )
                }
            } else {
                addAndOpenProject(name, icon, path, method)
            }
        }
        projectCreateBtn.trackProLock()
        projectCreateLayout.addView(projectCreateBtn)
        wireLocationPreview()
    }

    // ── Project location (folder inside the guest home; see terminal/ProjectLocation.kt) ──

    private lateinit var projectLocationMsgTv: TextView

    private fun rootfsFor(method: String): File = ProjectPathResolver.resolve(this, "/", method)

    /** Writability can only be probed where the app owns the rootfs (proot); a root-owned chroot is skipped. */
    private fun canProbeWrite(method: String) = method != "chroot"

    private fun locError(e: ProjectLocation.Error): String = tr(
        when (e) {
            ProjectLocation.Error.EMPTY -> R.string.loc_err_empty
            ProjectLocation.Error.TRAVERSAL -> R.string.loc_err_traversal
            ProjectLocation.Error.OUTSIDE_HOME -> R.string.loc_err_outside
            ProjectLocation.Error.NOT_FOUND -> R.string.loc_err_not_found
            ProjectLocation.Error.NOT_DIR -> R.string.loc_err_not_dir
            ProjectLocation.Error.NOT_WRITABLE -> R.string.loc_err_not_writable
            ProjectLocation.Error.DUPLICATE -> R.string.loc_err_duplicate
            ProjectLocation.Error.NOT_EMPTY -> R.string.loc_err_not_empty
            ProjectLocation.Error.BAD_NAME -> R.string.loc_err_bad_name
            ProjectLocation.Error.IS_HOME -> R.string.loc_err_home
        }
    )

    private fun projectLeaf(name: String, gitUrl: String): String =
        if (gitUrl.isNotEmpty()) ProjectManager.repoNameFromUrl(gitUrl)
        else name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun showLocationMsg(error: String?, preview: String? = null) {
        if (!::projectLocationMsgTv.isInitialized) return
        projectLocationMsgTv.text = error ?: preview.orEmpty()
        projectLocationMsgTv.setTextColor(if (error != null) NC.ERROR else NC.ON_SURF_VAR)
        projectLocationMsgTv.visibility = if (projectLocationMsgTv.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** "Open project in this folder" (ON): the Location folder itself is the project, no subfolder. */
    private var projectUseFolder = false
    /** Set once the user types in the name field; auto-fill from the folder name only runs while false. */
    private var projectNameTouched = false
    private var projectNameAuto = false

    /** Checks the Location (+ folder name when OFF). Shows the inline error and returns null, else the canonical project path. */
    private fun validateProject(method: String, name: String, gitUrl: String): String? {
        val c = checkProjectLocation(method, name, gitUrl)
        if (!c.ok) { showLocationMsg(locError(c.error!!)); return null }
        showLocationMsg(null, tr(R.string.loc_project_folder, ProjectLocation.display(c.path!!)))
        return c.path
    }

    private fun checkProjectLocation(method: String, name: String, gitUrl: String): ProjectLocation.Check {
        val known = getProjects().map { it.path }.toSet()
        val rootfs = rootfsFor(method)
        val loc = projectPathInput.text.toString()
        return if (projectUseFolder) ProjectLocation.checkFolder(rootfs, loc, known, gitUrl.isNotEmpty(), canProbeWrite(method))
        else ProjectLocation.checkDest(rootfs, loc, projectLeaf(name, gitUrl), known, gitUrl.isNotEmpty(), canProbeWrite(method))
    }

    /** Live feedback under the Location field: the resulting folder, or the first problem. Auto-fills the name in folder mode. */
    private fun refreshLocationPreview() {
        val method = projectCreateSelectedMethod
        val gitUrl = projectGithubInput.text.toString().trim()
        val name = projectNameInput.text.toString()
        if (projectUseFolder) {
            val c = checkProjectLocation(method, name, gitUrl)
            if (c.path != null && !projectNameTouched) {
                val auto = ProjectLocation.slug(c.path.substringAfterLast('/'))
                if (projectNameInput.text.toString() != auto) {
                    projectNameAuto = true
                    projectNameInput.setText(auto)
                    projectNameAuto = false
                }
            }
            showLocationMsg(c.error?.let { locError(it) }, c.path?.let { tr(R.string.loc_project_folder, ProjectLocation.display(it)) })
        } else if (projectLeaf(name, gitUrl).isEmpty()) {
            // no name yet: still catch a bad Location
            val c = ProjectLocation.check(rootfsFor(method), projectPathInput.text.toString(), emptySet(), false, canProbeWrite(method))
            showLocationMsg(c.error?.let { locError(it) })
        } else {
            validateProject(method, name, gitUrl)
        }
    }

    private fun wireLocationPreview() {
        val w = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refreshLocationPreview() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        projectPathInput.addTextChangedListener(w)
        projectNameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (!projectNameAuto) projectNameTouched = true
                refreshLocationPreview()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        projectGithubInput.addTextChangedListener(w)
    }

    private fun buildProjectLocationCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        }
        card.addView(TextView(this).apply {
            text = tr(R.string.loc_location)
            setTextColor(NC.PRIMARY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(8))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        projectPathInput = EditText(this).apply {
            setText(ProjectLocation.DEFAULT_PARENT_DISPLAY)
            hint = ProjectLocation.DEFAULT_PARENT_DISPLAY
            setHintTextColor(NC.OUTLINE)
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOWEST,
                strokeColor = Color.parseColor("#3c4a3f"),
                shadowColor = Color.TRANSPARENT,
                offsetDp = 0,
                cornerRadiusDp = 0
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        row.addView(projectPathInput, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(secondaryButton(tr(R.string.loc_choose)) { openLocationPicker() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        card.addView(row)
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        toggleRow.addView(TextView(this).apply {
            text = tr(R.string.loc_use_folder)
            textSize = 13f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        toggleRow.addView(com.zenithblue.nativecode.ui.BrutalistToggle(this, projectUseFolder) { on ->
            projectUseFolder = on
            refreshLocationPreview()
        }.apply { contentDescription = tr(R.string.loc_use_folder) })
        card.addView(toggleRow)
        card.addView(TextView(this).apply {
            text = tr(R.string.loc_use_folder_hint)
            textSize = 11f
            setTextColor(NC.OUTLINE)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        })
        projectLocationMsgTv = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(projectLocationMsgTv)
        return card
    }

    private fun openLocationPicker() {
        val method = projectCreateSelectedMethod
        val rootfs = rootfsFor(method)
        if (!ProjectLocation.hostFile(rootfs, ProjectLocation.HOME).isDirectory) {
            Toast.makeText(this, tr(R.string.loc_err_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        FolderPicker(
            this, rootfs, projectPathInput.text.toString(), tr(R.string.loc_picker_title),
            validate = { g -> ProjectLocation.check(rootfs, g, emptySet(), true, canProbeWrite(method)).error?.let { locError(it) } },
            onPick = { g -> projectPathInput.setText(ProjectLocation.display(g)) },
        ).show()
    }

    /** Import: pick any folder inside the guest home and register it as a project in place (nothing is copied or moved). */
    private fun showImportProjectDialog() {
        val method = LinuxCommandBuilder.currentMethod
        val rootfs = rootfsFor(method)
        if (!ProjectLocation.hostFile(rootfs, ProjectLocation.HOME).isDirectory) {
            Toast.makeText(this, tr(R.string.loc_err_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        fun known() = getProjects().map { it.path }.toSet()
        FolderPicker(
            this, rootfs, ProjectLocation.DEFAULT_PARENT, tr(R.string.loc_use_existing),
            validate = { g -> ProjectLocation.check(rootfs, g, known(), true, canProbeWrite(method)).error?.let { locError(it) } },
            onPick = { g ->
                val register = {
                    if (projectGateAllows()) {
                        addAndOpenProject(if (g == ProjectLocation.HOME) "home" else g.substringAfterLast('/'), "", g, method, skipEnsureDir = true)
                        markProjectOpened(g)
                    }
                }
                // the whole home as one project is legal but rarely intended: ask first
                if (g == ProjectLocation.HOME) {
                    showBrutalistConfirmDialog(
                        title = tr(R.string.loc_home_title),
                        message = tr(R.string.loc_home_body),
                        confirmLabel = tr(R.string.loc_home_confirm),
                    ) { register() }
                } else register()
            },
        ).show()
    }

    private fun addAndOpenProject(
        name: String,
        icon: String,
        path: String,
        method: String = "proot",
        skipEnsureDir: Boolean = false
    ) {
        val projects = getProjects().toMutableList()
        val existingIndex = projects.indexOfFirst { it.path == path }
        val newProj = Project(name, icon, path, linuxMethod = method)
        if (existingIndex >= 0) {
            projects[existingIndex] = newProj
        } else {
            projects.add(newProj)
        }
        saveProjects(projects)

        activeProjectName = name
        activeProjectPath = path
        activeProjectMethod = method

        // Auto-switch global mode to project's mode
        LinuxCommandBuilder.currentMethod = method
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
            .edit()
            .putString("linux_method", method)
            .putString("active_project_name", name)
            .putString("active_project_path", path)
            .putString("active_project_method", method)
            .apply()
        refreshToolCardsForMethod()

        // Non-git create only: mkdir under the selected method (avoid empty shell over real clone)
        if (!skipEnsureDir) {
            ProjectManager.ensureDir(this, path, method = method)
        }

        Toast.makeText(this, "Project opened: $name", Toast.LENGTH_SHORT).show()

        if (pageStack.isEmpty() || pageStack.peek() != ID_PROJECT_WORKSPACE) {
            pageStack.push(ID_PROJECT_WORKSPACE)
        }
        navigateToPage(ID_PROJECT_WORKSPACE)
    }

    private fun buildProjectsListLayout() {
        projectsListContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        projectsListScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            clipToPadding = false
            setPadding(0, 0, 0, dp(24))
        }
        projectsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        projectsListScrollView.addView(projectsListLayout)
        projectsListContainer.addView(projectsListScrollView)
    }

    // ── Projects list (cards, search/sort, New / Clone / Import, per-project actions) ──

    private var projectsQuery = ""
    private var projectsSortByName = false
    private var projectsCardsHost: LinearLayout? = null

    private fun populateProjectsList() {
        projectsListLayout.removeAllViews()
        val list = getProjects()

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = tr(R.string.main_projects_2)
            textSize = 22f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = if (list.size == 1) tr(R.string.main_one_project_caps) else tr(R.string.main_n_projects_caps, list.size)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        projectsListLayout.addView(topRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
        }
        fun action(label: String, icon: Int, primary: Boolean, last: Boolean, onClick: () -> Unit) =
            actions.addView(projectsActionButton(label, icon, primary, onClick),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (!last) rightMargin = dp(10) })
        action(tr(R.string.main_new), R.drawable.ic_lucide_plus, true, false) { openProjectCreate(clone = false) }
        action(tr(R.string.main_clone), R.drawable.ic_lucide_download, false, false) { openProjectCreate(clone = true) }
        action(tr(R.string.main_import), R.drawable.ic_lucide_folder_input, false, true) { showImportProjectDialog() }
        projectsListLayout.addView(actions)

        if (list.isEmpty()) {
            projectsCardsHost = null
            projectsListLayout.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(40), dp(24), dp(24))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ill_projects_empty)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(150), dp(120)).apply { bottomMargin = dp(20) }
                })
                addView(TextView(this@MainActivity).apply {
                    text = tr(R.string.main_no_projects_yet)
                    textSize = 18f
                    setTextColor(NC.ON_SURFACE)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = tr(R.string.main_create_a_blank_project_clone)
                    textSize = 13f
                    setTextColor(NC.ON_SURF_VAR)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                })
            })
            return
        }

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) }
        }
        val search = EditText(this).apply {
            hint = tr(R.string.main_search_projects)
            setText(projectsQuery)
            setHintTextColor(NC.OUTLINE)
            setTextColor(NC.ON_SURFACE)
            textSize = 14f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val d = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_lucide_search)?.mutate()
            d?.setBounds(0, 0, dp(18), dp(18)); d?.setTint(ToolRail.ICON_TINT)
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(8)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    projectsQuery = s?.toString().orEmpty()
                    renderProjectCards()
                }
            })
        }
        val sort = TextView(this).apply {
            fun paint() {
                text = if (projectsSortByName) tr(R.string.ssh_name) else tr(R.string.main_recent_2)
                contentDescription = "Sort by ${if (projectsSortByName) "name" else "last opened"}. Tap to change."
            }
            paint()
            textSize = 12f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            minWidth = dp(76)
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.PRIMARY)
            }
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { projectsSortByName = !projectsSortByName; paint(); renderProjectCards() }
        }
        tools.addView(search)
        tools.addView(sort)
        projectsListLayout.addView(tools)

        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        projectsCardsHost = host
        projectsListLayout.addView(host)
        renderProjectCards()
    }

    private fun projectsActionButton(label: String, icon: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        val fg = if (primary) NC.ON_PRIMARY else NC.PRIMARY
        setTextColor(fg)
        background = cyberBrutalistBg(
            fillColor = if (primary) NC.PRIMARY_CON else NC.SURFACE_LOW,
            strokeColor = if (primary) NC.PRIMARY_CON else NC.OUTLINE_VAR,
            shadowColor = NC.SHADOW_DARK,
            offsetDp = 3,
            cornerRadiusDp = 0,
            rightFaceColor = Color.parseColor("#3c4a3f")
        )
        val d = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, icon)?.mutate()
        d?.setBounds(0, 0, dp(18), dp(18)); d?.setTint(fg)
        setCompoundDrawablesRelative(d, null, null, null)
        compoundDrawablePadding = dp(6)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        minHeight = dp(48)
        if (primary && proLocked()) markProLocked()
        setOnClickListener { onClick() }
    }

    private fun openProjectCreate(clone: Boolean) {
        if (pageStack.isEmpty() || pageStack.peek() != ID_PROJECT_CREATE) pageStack.push(ID_PROJECT_CREATE)
        navigateToPage(ID_PROJECT_CREATE)
        if (clone && ::projectGithubInput.isInitialized) projectGithubInput.post {
            projectGithubInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(projectGithubInput, 0)
        }
    }

    private fun renderProjectCards() {
        val host = projectsCardsHost ?: return
        host.removeAllViews()
        val q = projectsQuery.trim()
        val shown = getProjects()
            .filter { q.isEmpty() || it.name.contains(q, true) || it.path.contains(q, true) }
            .let { l -> if (projectsSortByName) l.sortedBy { it.name.lowercase() } else l.sortedByDescending { it.lastOpened } }
        if (shown.isEmpty()) {
            host.addView(TextView(this).apply {
                text = tr(R.string.main_no_project_matches, q)
                textSize = 13f
                setTextColor(NC.ON_SURF_VAR)
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            })
            return
        }
        val cols = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        shown.chunked(cols).forEach { items ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            }
            items.forEachIndexed { i, p ->
                row.addView(projectListCard(p), LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(12) })
            }
            if (items.size < cols) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(12) })
            host.addView(row)
        }
    }

    /** Branch from .git/HEAD (no git process); null when not a repo or unreadable. */
    private fun projectGitBranch(dir: File): String? = runCatching {
        val head = File(dir, ".git/HEAD").takeIf { it.isFile }?.readText()?.trim() ?: return null
        if (head.startsWith("ref: ")) head.substringAfterLast("refs/heads/") else head.take(7)
    }.getOrNull()

    // ponytail: marker-file lookup only (root dir); monorepos / nested apps show the root's kind or none.
    private fun projectKind(dir: File): String? = runCatching {
        listOf(
            "package.json" to "NODE", "Cargo.toml" to "RUST", "go.mod" to "GO", "pyproject.toml" to "PYTHON",
            "requirements.txt" to "PYTHON", "CMakeLists.txt" to "C/C++", "build.gradle.kts" to "GRADLE",
            "build.gradle" to "GRADLE", "pom.xml" to "MAVEN", "pubspec.yaml" to "FLUTTER", "Gemfile" to "RUBY",
            "composer.json" to "PHP", "index.html" to tr(R.string.main_web),
        ).firstOrNull { File(dir, it.first).exists() }?.second
    }.getOrNull()

    private fun projectChip(label: String, icon: Int?, color: Int) = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        maxLines = 1
        maxWidth = dp(240)
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(NC.SURFACE_LOWEST)
            setStroke(dp(1), NC.OUTLINE_VAR)
        }
        if (icon != null) {
            val d = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, icon)?.mutate()
            d?.setBounds(0, 0, dp(13), dp(13)); d?.setTint(color)
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(4)
        }
        setPadding(dp(6), dp(3), dp(6), dp(3))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }
    }

    private fun projectListCard(p: Project): View {
        val dir = runCatching { ProjectPathResolver.resolve(this, p.path, p.linuxMethod) }.getOrNull()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = Color.parseColor("#3c4a3f")
            )
            setPadding(dp(14), dp(12), dp(10), dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "Project ${p.name}, ${p.path}"
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), Color.parseColor("#3360F99E"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        bindProjectIconContainer(iconBox, p.icon, glyphSizeDp = 22)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) }
        }
        col.addView(TextView(this).apply {
            text = p.name
            textSize = 16f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = p.path
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        val more = ImageView(this).apply {
            setImageResource(R.drawable.ic_lucide_more_vertical)
            setColorFilter(ToolRail.ICON_TINT)
            val pad = dp((44 - ToolRail.ICON_DP) / 2)
            setPadding(pad, pad, pad, pad)
            contentDescription = "Actions for ${p.name}"
            tooltipText = tr(R.string.main_actions)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        more.setOnClickListener { showProjectActions(more, p) }
        top.addView(iconBox); top.addView(col); top.addView(more)
        card.addView(top)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dir?.let { projectGitBranch(it) }?.let { chips.addView(projectChip(it, R.drawable.ic_lucide_git_branch, NC.PRIMARY)) }
        dir?.let { projectKind(it) }?.let { chips.addView(projectChip(it, null, NC.ON_SURF_VAR)) }
        if (p.linuxMethod == "chroot") chips.addView(projectChip("CHROOT", null, NC.TERTIARY))
        val running = projectSessionsMap[p.path]?.count { it.isRunning } ?: 0
        if (running > 0) chips.addView(projectChip("$running RUNNING", null, NC.PRIMARY))
        if (FeatureGate.canUse(isPro(), ProFeature.WORKSPACE) is GateResult.Deny) chips.addView(projectChip("PRO", R.drawable.ic_lock, NC.ERROR))
        val opened = if (p.lastOpened <= 0L) tr(R.string.main_never_opened)
            else if (System.currentTimeMillis() - p.lastOpened < 60_000L) tr(R.string.main_opened_just_now) else tr(R.string.main_opened_ago,
            android.text.format.DateUtils.getRelativeTimeSpanString(p.lastOpened, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
        chips.addView(projectChip(opened, R.drawable.ic_lucide_clock, NC.ON_SURF_VAR))
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10); leftMargin = dp(52) }
            addView(chips)
        })

        card.setOnClickListener { openProjectFromList(p) }
        card.setOnLongClickListener { showProjectActions(more, p); true }
        return card
    }

    private fun showProjectActions(anchor: View, p: Project) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, tr(R.string.chat_open))
        menu.menu.add(0, 2, 1, tr(R.string.chat_open_terminal_here))
        menu.menu.add(0, 3, 2, tr(R.string.term_rename))
        menu.menu.add(0, 4, 3, tr(R.string.main_remove_project))
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openProjectFromList(p)
                2 -> openProjectFromList(p, terminal = true)
                3 -> showRenameProjectDialog(p)
                4 -> showBrutalistConfirmDialog(
                    title = tr(R.string.main_remove_project_2),
                    message = tr(R.string.main_remove_from_nativecode_its_sessions, p.name, p.path),
                    confirmLabel = tr(R.string.ssh_remove),
                    destructive = true,
                ) { removeProject(p.path); populateProjectsList() }
            }
            true
        }
        menu.show()
    }

    private fun openProjectFromList(p: Project, terminal: Boolean = false) {
        if (!projectGateAllows()) return
        markProjectOpened(p.path)
        activeProjectName = p.name
        activeProjectPath = p.path
        applyProjectIsolation(p.path, p.linuxMethod)
        Toast.makeText(this, "Project opened: ${p.name}", Toast.LENGTH_SHORT).show()
        if (pageStack.isEmpty() || pageStack.peek() != ID_PROJECT_WORKSPACE) pageStack.push(ID_PROJECT_WORKSPACE)
        navigateToPage(ID_PROJECT_WORKSPACE)
        if (terminal) createWorkspaceTerminalTab("shell")
    }

    private fun showRenameProjectDialog(p: Project) {
        val input = EditText(this).apply {
            setText(p.name)
            setSelection(p.name.length)
            isSingleLine = true
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        showBrutalistConfirmDialog(
            title = tr(R.string.main_rename_project),
            message = tr(R.string.main_display_name_only_the_folder, p.path),
            confirmLabel = tr(R.string.x11_save),
            body = input,
        ) {
            val name = input.text.toString().trim()
            if (name.isEmpty()) return@showBrutalistConfirmDialog
            val list = getProjects().toMutableList()
            val idx = list.indexOfFirst { it.path == p.path }
            if (idx < 0) return@showBrutalistConfirmDialog
            list[idx] = list[idx].copy(name = name)
            saveProjects(list)
            if (activeProjectPath == p.path) {
                activeProjectName = name
                getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit().putString("active_project_name", name).apply()
                if (::workspaceProjectNameTv.isInitialized) workspaceProjectNameTv.text = name
            }
            populateProjectsList()
        }
        input.requestFocus()
    }

    /** Unregister a project: list entry, its sessions and chat threads. Files on disk stay. */
    private fun removeProject(path: String) {
        val list = getProjects().toMutableList()
        if (!list.removeAll { it.path == path }) return
        saveProjects(list)
        stopProjectSessions(path) // else they run on, counted, with no tab to close (R4 B20)
        deleteProjectThreads(path) // its chat threads + checkpoint refs go with it (plan 9.2.8)
        if (activeProjectPath == path) {
            activeProjectName = ""
            activeProjectPath = ""
        }
        Toast.makeText(this, tr(R.string.main_project_removed), Toast.LENGTH_SHORT).show()
    }

    private fun refreshWorkspaceDirTree() {
        if (::directoryPage.isInitialized) directoryPage.refresh()
    }

    private fun projectGitMethod(): String =
        activeProjectMethod.ifBlank { LinuxCommandBuilder.currentMethod }

    /**
     * Point global linux_method at the open project’s isolation so Settings
     * AI install / probes hit the same guest as workspace tools.
     */
    private fun alignGlobalMethodToProject() {
        val m = projectGitMethod()
        if (m.isBlank() || LinuxCommandBuilder.currentMethod == m) return
        LinuxCommandBuilder.currentMethod = m
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
            .edit()
            .putString("linux_method", m)
            .apply()
        refreshToolCardsForMethod()
    }

    /** CHANGES tab refresh (3 git calls, off the main thread). */
    private fun refreshGitDiffTree() {
        if (!::projectGitChangesView.isInitialized) return
        projectGitChangesView.refresh(activeProjectPath, projectGitMethod())
    }

    private fun openProjectWorkspace() {
        workspaceProjectNameTv.text = activeProjectName

        val iconStr = getProjects().find { it.path == activeProjectPath }?.icon ?: ""
        bindWorkspaceProjectIcon(workspaceProjectIconIv, iconStr)

        refreshWorkspaceDirTree()

        saveActiveProjectSessions() // the tabs on screen still belong to the previously loaded project
        workspaceLoadedPath = activeProjectPath
        val sessions = projectSessionsMap.getOrPut(activeProjectPath) { ArrayList() }
        val tabNames = projectTabNamesMap.getOrPut(activeProjectPath) { ArrayList() }
        val activeIdx = projectActiveTabIndexMap.get(activeProjectPath) ?: -1
        
        workspaceSessions.clear()
        workspaceSessions.addAll(sessions)
        workspaceTabNames.clear()
        workspaceTabNames.addAll(tabNames)
        activeWorkspaceTabIndex = activeIdx
        
        rebuildWorkspaceTabs()
        if (activeWorkspaceTabIndex >= 0 && activeWorkspaceTabIndex < workspaceSessions.size) {
            switchWorkspaceTab(activeWorkspaceTabIndex)
        }
        updateProjectTerminalService()
    }

    private fun createWorkspaceTerminalTab(type: String) {
        if (!gateAllows(FeatureGate.canUse(isPro(), ProFeature.WORKSPACE))) return
        if (workspaceSessions.size + pendingWorkspaceTerminalSlots >= 10) {
            Toast.makeText(this, tr(R.string.main_maximum_10_tabs_allowed), Toast.LENGTH_SHORT).show()
            return
        }
        if (!aiLaunchAllowed(type)) return
        val method = projectGitMethod()
        if (ToolLauncherCatalog.isAiToolType(type) &&
            !AiCliProvisionState.shouldShowAiToolLaunchers(this, method)
        ) {
            alignGlobalMethodToProject()
            Toast.makeText(
                this,
                tr(R.string.main_install_ai_cli_tools_for, method),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (type == "codex" && method == "chroot") {
            Toast.makeText(this, tr(R.string.main_codex_unavailable_in_chroot), Toast.LENGTH_SHORT).show()
            return
        }
        if (type == "shell-root" && method == "chroot") {
            executor.execute {
                val rootOk = RootShell.isRootAvailable()
                mainHandler.post {
                    if (!rootOk) {
                        Toast.makeText(
                            this,
                            tr(R.string.main_root_required_for_rooted_chroot),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        startWorkspaceTerminalTab(type)
                    }
                }
            }
            return
        }
        startWorkspaceTerminalTab(type)
    }

    /** One-shot guest cwd for the next workspace terminal tab (Directory page "Open terminal here"). */
    private var wsTermCwd: String? = null

    private fun startWorkspaceTerminalTab(type: String) {
        if (!reserveTerminalSlot(app = false)) {
            Toast.makeText(this, tr(R.string.main_maximum_10_tabs_allowed), Toast.LENGTH_SHORT).show()
            return
        }
        setTerminalMode(ws = true, chat = false)
        maybeToastHeavyToolLaunch(type)
        val ticket = captureTerminalLaunch(app = false, type)
        // shell-root: login at /root (no project cwd — avoid root-owned files under flux home)
        val workDir = if (type == "shell-root") null else (wsTermCwd.also { wsTermCwd = null } ?: ticket.project)
        val user = LinuxCommandBuilder.sessionUserForType(type)
        if (ticket.method == "chroot") {
            executor.execute { try { RootShell.isRootAvailable() } catch (_: Exception) {} }
        }
        executor.execute {
            val prepared = try {
                terminalLaunchSpec(type, user, workDir, ticket.method)
            } catch (e: Exception) {
                mainHandler.post {
                    if (!releaseTerminalLaunch(ticket)) return@post
                    Toast.makeText(
                        this,
                        e.message?.takeIf { it.isNotBlank() } ?: tr(R.string.main_failed_to_prepare_terminal),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@execute
            }
            mainHandler.post {
                if (!releaseTerminalLaunch(ticket)) return@post
                if (!terminalLaunchStillCurrent(ticket)) return@post
                val (args, envMap) = prepared
                attachWorkspaceSession(type, args, envMap, ticket.method)
            }
        }
    }

    /** Workspace tab client; every workspace session gets its own (callbacks resolve the tab at call time). */
    private fun newWsClient() = NativeCodeTerminalSessionClient(
            context = this,
            terminalViewRef = { if (::workspaceTerminalView.isInitialized) workspaceTerminalView else null },
            activeSessionRef = { workspaceSessions.getOrNull(activeWorkspaceTabIndex) },
            onPreviewUrl = { url -> showPreviewChip(url) },
            onSessionFinishedCallback = { session ->
                mainHandler.post {
                    if (workspaceSessions.contains(session)) {
                        closeWorkspaceTab(workspaceSessions.indexOf(session))
                    } else {
                        for ((pPath, sList) in projectSessionsMap) {
                            val idx = sList.indexOf(session)
                            if (idx >= 0) {
                                sList.removeAt(idx)
                                projectTabNamesMap[pPath]?.let { tList ->
                                    if (idx < tList.size) tList.removeAt(idx)
                                }
                                break
                            }
                        }
                        syncTerminalServices()
                    }
                }
            }
        )

    private fun attachWorkspaceSession(
        type: String,
        args: Array<String>,
        envMap: HashMap<String, String>,
        method: String
    ) {
        val cwd = File(filesDir, "home").absolutePath
        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        val sessionClient = newWsClient().also { TerminalStore.wsClients.add(it) }

        val sessionExec = LinuxCommandBuilder.sessionExec(this, args, method = method)
        val sessionArgs = LinuxCommandBuilder.sessionArgs(this, args, method = method)
        val session = TerminalSession(sessionExec, cwd, sessionArgs, env, 10000, sessionClient)
        workspaceSessions.add(session)
        workspaceTabNames.add(type)
        activeWorkspaceTabIndex = workspaceSessions.size - 1
        saveActiveProjectSessions()
        rebuildWorkspaceTabs()
        switchWorkspaceTab(activeWorkspaceTabIndex)
        updateProjectTerminalService()
    }

    /**
     * Files the workspace tabs under the project they belong to ([workspaceLoadedPath]), never under
     * [activeProjectPath]: that one changes before [openProjectWorkspace] swaps the tabs (R4 B13).
     */
    private fun saveActiveProjectSessions() {
        val path = workspaceLoadedPath
        if (path.isNotEmpty()) {
            projectSessionsMap[path] = ArrayList(workspaceSessions)
            projectTabNamesMap[path] = ArrayList(workspaceTabNames)
            projectActiveTabIndexMap[path] = activeWorkspaceTabIndex
        }
    }

    /** Ends every terminal tab and agent chat of [path] and forgets its tab state. */
    private fun stopProjectSessions(path: String) {
        if (path.isEmpty()) return
        saveActiveProjectSessions()
        projectSessionsMap.remove(path)?.forEach { terminateSessionProcesses(it); it.finishIfRunning() }
        projectTabNamesMap.remove(path)
        projectActiveTabIndexMap.remove(path)
        if (workspaceLoadedPath == path) {
            workspaceSessions.clear()
            workspaceTabNames.clear()
            activeWorkspaceTabIndex = -1
            workspaceLoadedPath = ""
        }
        AgentChats.stopProject(path)
        syncTerminalServices()
    }

    private fun switchWorkspaceTab(index: Int) {
        if (index < 0 || index >= workspaceSessions.size) return
        activeWorkspaceTabIndex = index
        val session = workspaceSessions[index]
        workspaceTerminalView.attachSession(session)
        workspaceTerminalView.onScreenUpdated()
        workspaceTerminalView.requestFocus()
        forceTerminalResize(workspaceTerminalView)
        saveActiveProjectSessions()
        rebuildWorkspaceTabs()
    }

    private fun closeWorkspaceTab(index: Int) {
        if (index < 0 || index >= workspaceSessions.size) return
        val session = workspaceSessions[index]
        terminateSessionProcesses(session)
        session.finishIfRunning()
        workspaceSessions.removeAt(index)
        workspaceTabNames.removeAt(index)
        termTabTitles.remove(session)
        // Closing a tab left of the active one shifts it (strip x on a background tab).
        if (index < activeWorkspaceTabIndex) activeWorkspaceTabIndex--

        if (workspaceSessions.isEmpty()) {
            activeWorkspaceTabIndex = -1
        } else {
            if (activeWorkspaceTabIndex >= workspaceSessions.size) {
                activeWorkspaceTabIndex = workspaceSessions.size - 1
            }
            switchWorkspaceTab(activeWorkspaceTabIndex)
        }
        saveActiveProjectSessions()
        rebuildWorkspaceTabs()
        updateProjectTerminalService()
    }

    /**
     * @param method force isolation; null → project method if [onSelect] null (workspace), else global.
     * @param onSelect null → create workspace tab; non-null → caller (app terminal).
     */
    private fun showNewTerminalDropdown(
        anchorView: View,
        method: String? = null,
        onSelect: ((String) -> Unit)? = null
    ) {
        val m = method
            ?: if (onSelect != null) LinuxCommandBuilder.currentMethod else projectGitMethod()
        val tools = ToolLauncherCatalog.openWithPairs(
            m,
            AiCliProvisionState.shouldShowAiToolLaunchers(this, m)
        )

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
        }

        val popupWindow = PopupWindow(
            popupView,
            dp(200),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }

        for (item in tools) {
            val label = item.first
            val type = item.second

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBg(NC.SURFACE_CONTAINER, Color.TRANSPARENT, 0)

                val iconView = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                        rightMargin = dp(10)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val filename = cliToolAssetFilename(type)
                try {
                    assets.open("images/cli/$filename").use { input ->
                        val bmp = android.graphics.BitmapFactory.decodeStream(input)
                        if (bmp != null) {
                            iconView.setImageBitmap(bmp)
                        } else {
                            iconView.setImageResource(R.drawable.ic_terminal_thick)
                            iconView.setColorFilter(NC.PRIMARY)
                        }
                    }
                } catch (_: Exception) {
                    iconView.setImageResource(R.drawable.ic_terminal_thick)
                    iconView.setColorFilter(NC.PRIMARY)
                }

                val labelTv = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(NC.ON_SURFACE)
                    typeface = Typeface.MONOSPACE
                    // 200dp popup: lock only, no "· PRO" text (would wrap)
                    if (aiToolLocked(type)) markProLocked()
                }

                addView(iconView)
                addView(labelTv)

                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.background = roundedBg(NC.SURFACE_HIGH, NC.PRIMARY, 0)
                            labelTv.setTextColor(NC.PRIMARY)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.background = roundedBg(NC.SURFACE_CONTAINER, Color.TRANSPARENT, 0)
                            labelTv.setTextColor(NC.ON_SURFACE)
                        }
                    }
                    false
                }

                setOnClickListener {
                    popupWindow.dismiss()
                    if (onSelect != null) {
                        onSelect.invoke(type)
                    } else {
                        createWorkspaceTerminalTab(type)
                    }
                }
            }

            popupView.addView(itemLayout)
        }

        popupWindow.showAsDropDown(anchorView, 0, dp(4))
    }

    private fun rebuildWorkspaceTabs() {
        val empty = workspaceSessions.isEmpty()
        if (::workspaceTerminalContainer.isInitialized) {
            workspaceTerminalContainer.visibility = if (empty) View.GONE else View.VISIBLE
        }
        workspaceHubLayout.visibility = if (empty) View.VISIBLE else View.GONE
        refreshWsTabStrip()
    }

    private fun buildProjectWorkspaceLayout() {
        projectWorkspaceContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        projectWorkspaceLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(NC.BG)
        }

        // Title / stats / tool rail: workspace/WorkspaceTopBar (title has priority, stats drop to fit)
        val topBar = WorkspaceTopBar(this) { onBackPressed() }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        workspaceProjectIconIv = topBar.iconView
        workspaceProjectNameTv = topBar.titleView
        topBar.stats.onCustomize = { openTopBarPage() }
        fillToolRail(topBar.rail, workspace = true)
        topBar.rail.addView(buildWsBackToChat(), 0)
        projectWorkspaceLayout.addView(topBar)

        wsTabStrip = buildWsTabStrip()
        // Raw terminal area = tab bar + main area: hidden as one while the chat shows (the tab bar must not leak above it).
        val wsTermStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        projectWorkspaceLayout.addView(wsTermStack)
        wsTermStack.addView(wsTabStrip ?: buildWsTabStrip().also { wsTabStrip = it })

        val mainArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        val centerFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        }

        workspaceTerminalContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
        }

        val termViewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

         workspaceTerminalView = TerminalView(this, null).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        registerForContextMenu(workspaceTerminalView)
        workspaceTerminalView.setTextSize(workspaceFontSize)
        try {
            val tf = Typeface.createFromAsset(assets, "fonts/font.ttf")
            workspaceTerminalView.setTypeface(tf)
        } catch (e: Exception) {}
        
        val workspaceViewClient = NativeCodeTerminalViewClient(
            context = this,
            terminalViewRef = { if (::workspaceTerminalView.isInitialized) workspaceTerminalView else null },
            modState = wsModState,
            onScaleChanged = { scale ->
                val nextSize = (workspaceFontSize * scale).toInt()
                setGlobalTerminalZoom(nextSize)
            }
        )
        workspaceTerminalView.setTerminalViewClient(workspaceViewClient)
        termViewContainer.addView(workspaceTerminalView)
        termViewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            forceTerminalResize(if (::workspaceTerminalView.isInitialized) workspaceTerminalView else null)
        }
        workspaceTerminalContainer.addView(termViewContainer)

        workspaceKeyboardToolbar = buildExtraKeysBar(
            tvRef = { if (::workspaceTerminalView.isInitialized) workspaceTerminalView else null },
            sessionRef = { if (activeWorkspaceTabIndex >= 0 && activeWorkspaceTabIndex < workspaceSessions.size) workspaceSessions[activeWorkspaceTabIndex] else null },
            modState = wsModState,
        ).apply {
            visibility = if (showExtraKeys) View.VISIBLE else View.GONE
        }
        workspaceTerminalContainer.addView(workspaceKeyboardToolbar)

        centerFrame.addView(workspaceTerminalContainer)

        workspaceHubLayout = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
            setBackgroundColor(NC.BG)
            isVerticalScrollBarEnabled = false
        }

        val hubInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Match terminal tool selector padding; section headers live in populateWorkspaceHubTools
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        (workspaceHubLayout as ScrollView).addView(hubInner)

        val hubToolsHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        workspaceHubToolsHost = hubToolsHost
        populateWorkspaceHubTools(hubToolsHost)
        hubInner.addView(hubToolsHost)

        centerFrame.addView(workspaceHubLayout)
        mainArea.addView(centerFrame)

        wsTermStack.addView(mainArea)
        wsTermArea = wsTermStack
        wsChatView = newChatView(ws = true).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) }
        projectWorkspaceLayout.addView(wsChatView)
        applyTerminalMode(ws = true)
        projectWorkspaceContainer.addView(projectWorkspaceLayout)

    }

     private data class StatusCardData(val title: String, val type: String, val desc: String, val color: Int)

    private fun buildProjectSettingsLayout() {
        projectSettingsContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        projectSettingsTopBar = createProjectSubpageTopBar()
        projectSettingsScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))
            setBackgroundColor(NC.BG)
        }
        projectSettingsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        projectSettingsScrollView.addView(projectSettingsLayout)
        col.addView(projectSettingsTopBar)
        col.addView(projectSettingsScrollView)
        projectSettingsContainer.addView(col)
    }

    /** Workspace sub-pages (directory, diff, config, file / diff viewers) share the workspace top bar. */
    private fun createProjectSubpageTopBar(): WorkspaceTopBar {
        val topBar = WorkspaceTopBar(this) { onBackPressed() }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        topBar.stats.onCustomize = { openTopBarPage() }
        fillToolRail(topBar.rail, workspace = true, terminalTools = false)
        updateProjectSubpageTopBar(topBar)
        return topBar
    }

    private fun updateProjectSubpageTopBar(topBar: WorkspaceTopBar) {
        topBar.setTitle(activeProjectName)
        bindWorkspaceProjectIcon(topBar.iconView, getProjects().find { it.path == activeProjectPath }?.icon ?: "")
    }

    private fun bindProjectGitBranchesCard(host: LinearLayout) {
        val path = activeProjectPath
        val method = projectGitMethod()
        host.removeAllViews()
        // Always show full-size loading card while probing (fixes tiny text-only spinner)
        host.visibility = View.VISIBLE
        host.addView(buildGitBranchesLoadingCard())
        host.addView(spacer(16))
        executor.execute {
            val info = GitRepoService.listBranches(this@MainActivity, path, method)
            mainHandler.post {
                if (!host.isAttachedToWindow) return@post
                host.removeAllViews()
                if (!info.isRepo) {
                    host.visibility = View.GONE
                    return@post
                }
                host.visibility = View.VISIBLE
                host.addView(buildProjectGitBranchesCard(info))
                host.addView(spacer(16))
            }
        }
    }

    private fun buildGitBranchesLoadingCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(20), dp(16), dp(20))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_git)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
            })
            addView(TextView(this@MainActivity).apply {
                text = tr(R.string.main_git_branches)
                textSize = 14f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
            })
        })
        card.addView(ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        })
        card.addView(TextView(this).apply {
            text = tr(R.string.main_loading_branches)
            textSize = 13f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })
        card.addView(TextView(this).apply {
            text = tr(R.string.main_reading_local_remote_refs_from)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })
        return card
    }

    /**
     * Display-only branch card: current + expandable dropdown of all local/remote.
     * No checkout (view only).
     */
    private fun buildProjectGitBranchesCard(info: BranchInfo): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        titleRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_git)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
        })
        titleRow.addView(TextView(this).apply {
            text = tr(R.string.main_git_branches)
            textSize = 14f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = tr(R.string.main_caps_view_only)
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        })
        card.addView(titleRow)

        val currentLabel = when {
            info.detached -> tr(R.string.main_detached_head)
            !info.current.isNullOrBlank() -> info.current!!
            else -> "(unknown)"
        }
        card.addView(TextView(this).apply {
            text = tr(R.string.main_current_branch)
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(4))
        })
        card.addView(TextView(this).apply {
            text = currentLabel
            textSize = 14f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.PRIMARY)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        })

        val all = info.allBranches
        val localN = info.localBranches.size
        val remoteN = info.remoteBranches.size
        card.addView(TextView(this).apply {
            text = tr(R.string.main_all_branches_local_remote_tap, localN, remoteN)
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        })

        // Dropdown trigger (read-only expand, no checkout)
        var expanded = false
        val listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            rotation = 90f
        }
        val trigger = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            isClickable = true
            isFocusable = true
        }
        trigger.addView(TextView(this).apply {
            text = tr(R.string.main_switch_branches_tags_view_only)
            textSize = 12f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        trigger.addView(TextView(this).apply {
            text = "${all.size}"
            textSize = 11f
            setTextColor(NC.SURFACE_LOWEST)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.PRIMARY)
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
        })
        trigger.addView(chevron)

        fun rebuildList() {
            listHost.removeAllViews()
            if (all.isEmpty()) {
                listHost.addView(TextView(this).apply {
                    text = tr(R.string.main_no_branches_found_fetch_remotes)
                    textSize = 11f
                    setTextColor(NC.OUTLINE)
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                })
                return
            }
            if (info.localBranches.isNotEmpty()) {
                listHost.addView(TextView(this).apply {
                    text = tr(R.string.main_local)
                    textSize = 9f
                    setTextColor(NC.PRIMARY)
                    typeface = Typeface.MONOSPACE
                    paint.isFakeBoldText = true
                    setPadding(dp(4), dp(4), dp(4), dp(6))
                })
                for (branch in info.localBranches) {
                    listHost.addView(buildBranchDropdownRow(branch, branch == info.current, isRemote = false))
                }
            }
            if (info.remoteBranches.isNotEmpty()) {
                listHost.addView(TextView(this).apply {
                    text = tr(R.string.main_remote)
                    textSize = 9f
                    setTextColor(NC.TERTIARY)
                    typeface = Typeface.MONOSPACE
                    paint.isFakeBoldText = true
                    setPadding(dp(4), dp(10), dp(4), dp(6))
                })
                for (branch in info.remoteBranches) {
                    val isCur = branch == info.current ||
                        branch.endsWith("/${info.current}") ||
                        branch.removePrefix("origin/") == info.current
                    listHost.addView(buildBranchDropdownRow(branch, isCur && info.localBranches.none { it == info.current }, isRemote = true))
                }
            }
            listHost.addView(TextView(this).apply {
                text = tr(R.string.main_checkout_disabled_display_only)
                textSize = 10f
                setTextColor(NC.OUTLINE)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(4))
            })
        }
        rebuildList()

        trigger.setOnClickListener {
            expanded = !expanded
            listHost.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.rotation = if (expanded) 270f else 90f
            trigger.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (expanded) NC.SURFACE_HIGH else NC.SURFACE_LOWEST)
                setStroke(dp(1), if (expanded) NC.PRIMARY else NC.OUTLINE_VAR)
            }
        }

        card.addView(trigger)
        card.addView(listHost)
        return card
    }

    private fun buildBranchDropdownRow(name: String, isCurrent: Boolean, isRemote: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (isCurrent) NC.SURFACE_HIGH else Color.parseColor("#0A0A0A"))
                setStroke(dp(1), if (isCurrent) NC.PRIMARY else NC.OUTLINE_VAR)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(18)).apply { rightMargin = dp(8) }
                setBackgroundColor(
                    when {
                        isCurrent -> NC.PRIMARY
                        isRemote -> NC.TERTIARY
                        else -> NC.OUTLINE_VAR
                    }
                )
            })
            addView(TextView(this@MainActivity).apply {
                text = if (isCurrent) "✓  $name" else "   $name"
                textSize = 12f
                setTextColor(if (isCurrent) NC.PRIMARY else NC.ON_SURFACE)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            if (isCurrent) {
                addView(TextView(this@MainActivity).apply {
                    text = "HEAD"
                    textSize = 9f
                    setTextColor(NC.SURFACE_LOWEST)
                    typeface = Typeface.MONOSPACE
                    paint.isFakeBoldText = true
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(NC.PRIMARY)
                    }
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                })
            } else if (isRemote) {
                addView(TextView(this@MainActivity).apply {
                    text = tr(R.string.main_remote)
                    textSize = 8f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                })
            }
            // View only — tap shows toast, no checkout
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    if (isCurrent) "Already on $name" else "View only — checkout in terminal: git switch …",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openProjectSettings() {
        projectSettingsLayout.removeAllViews()
        updateProjectSubpageTopBar(projectSettingsTopBar)

        val settingsContentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        projectSettingsLayout.addView(settingsContentLayout)

        // Settings page header
        val settingsHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val settingsHeaderTitle = TextView(this).apply {
            text = tr(R.string.main_settings)
            textSize = 18f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val settingsHeaderSub = TextView(this).apply {
            text = tr(R.string.main_caps_project_config)
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        }
        val projMethod = projectGitMethod()
        val settingsMethodBadge = TextView(this).apply {
            text = "CHROOT"
            textSize = 9f
            setTextColor(NC.TERTIARY)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.TERTIARY)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            visibility = if (projMethod == "chroot") View.VISIBLE else View.GONE
        }
        settingsHeaderRow.addView(settingsHeaderTitle)
        settingsHeaderRow.addView(settingsHeaderSub)
        settingsHeaderRow.addView(settingsMethodBadge)
        val settingsDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(16)
            }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        settingsContentLayout.addView(settingsHeaderRow)
        settingsContentLayout.addView(settingsDivider)
        settingsContentLayout.addView(TextView(this).apply {
            text = tr(R.string.main_isolation_project_sticky_not_setting, ProjectPathResolver.methodLabel(projMethod))
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(12))
        })

        // Project Name card (rename)
        val nameCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }
        val nameCardTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val nameCardIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_project_config)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
        }
        val nameCardTitle = TextView(this).apply {
            text = tr(R.string.main_project_name)
            textSize = 14f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
        }
        nameCardTitleRow.addView(nameCardIcon)
        nameCardTitleRow.addView(nameCardTitle)
        nameCard.addView(nameCardTitleRow)
        nameCard.addView(TextView(this).apply {
            text = tr(R.string.main_display_name_only_repo_path)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        })
        val configNameInput = EditText(this).apply {
            setText(activeProjectName)
            hint = tr(R.string.main_project_name_2)
            setHintTextColor(NC.OUTLINE)
            setTextColor(NC.ON_SURFACE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOWEST,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = Color.TRANSPARENT,
                offsetDp = 0,
                cornerRadiusDp = 0
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        nameCard.addView(configNameInput)
        settingsContentLayout.addView(nameCard)

        settingsContentLayout.addView(buildTerminalSettingsCard())
        settingsContentLayout.addView(spacer(16))

        // Git branches card (async; omitted when not a repo)
        val gitBranchesHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            visibility = View.GONE
        }
        settingsContentLayout.addView(gitBranchesHost)
        bindProjectGitBranchesCard(gitBranchesHost)

        // Project Icon card
        val iconCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }
        val iconCardTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val iconCardIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_folder_special)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
        }
        val iconCardTitle = TextView(this).apply {
            text = tr(R.string.main_project_icon)
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        iconCardTitleRow.addView(iconCardIcon)
        iconCardTitleRow.addView(iconCardTitle)
        iconCard.addView(iconCardTitleRow)

        // Preview + controls in a row
        val iconRowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val previewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply { rightMargin = dp(16) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(2), NC.OUTLINE_VAR)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        val iconInputCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }

        fun updatePreview(iconStr: String) {
            previewContainer.removeAllViews()
            if (iconStr.isEmpty()) {
                val iv = ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_folder_special)
                    setColorFilter(NC.PRIMARY)
                    layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
                }
                previewContainer.addView(iv)
            } else if (iconStr.length <= 4 && !iconStr.startsWith("/") && !iconStr.startsWith("http")) {
                val tv = TextView(this@MainActivity).apply {
                    text = iconStr; textSize = 32f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                }
                previewContainer.addView(tv)
            } else {
                val iv = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                }
                previewContainer.addView(iv)
                executor.execute {
                    try {
                        val bitmap = when {
                            iconStr.startsWith("content://") -> {
                                contentResolver.openInputStream(android.net.Uri.parse(iconStr)).use {
                                    android.graphics.BitmapFactory.decodeStream(it)
                                }
                            }
                            iconStr.startsWith("http://") || iconStr.startsWith("https://") -> {
                                java.net.URL(iconStr).openStream().use {
                                    android.graphics.BitmapFactory.decodeStream(it)
                                }
                            }
                            else -> {
                                android.graphics.BitmapFactory.decodeFile(iconStr)
                            }
                        }
                        mainHandler.post {
                            if (bitmap != null) iv.setImageBitmap(bitmap)
                            else {
                                previewContainer.removeAllViews()
                                val defaultIv = ImageView(this@MainActivity).apply {
                                    setImageResource(R.drawable.ic_folder_special)
                                    setColorFilter(NC.PRIMARY)
                                    layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
                                }
                                previewContainer.addView(defaultIv)
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            previewContainer.removeAllViews()
                            val defaultIv = ImageView(this@MainActivity).apply {
                                setImageResource(R.drawable.ic_folder_special)
                                setColorFilter(NC.PRIMARY)
                                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
                            }
                            previewContainer.addView(defaultIv)
                        }
                    }
                }
            }
        }

        val currentProj = getProjects().find { it.path == activeProjectPath }
        val currentIcon = currentProj?.icon ?: ""
        updatePreview(currentIcon)

        val configIconInput = EditText(this).apply {
            setText(currentIcon)
            textSize = 12f
            setTextColor(NC.ON_SURFACE)
            setHintTextColor(NC.ON_SURF_VAR)
            hint = tr(R.string.main_path_url_emoji)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updatePreview(s.toString().trim())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        activeIconInput = configIconInput

        val chooseIconBtn = secondaryButton(tr(R.string.main_browse)) {
            activeIconInput = configIconInput
            projectIconPickerLauncher.launch("image/*")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        iconInputCol.addView(configIconInput)
        iconInputCol.addView(chooseIconBtn)
        iconRowLayout.addView(previewContainer)
        iconRowLayout.addView(iconInputCol)
        iconCard.addView(iconRowLayout)
        settingsContentLayout.addView(iconCard)

        val saveBtn = primaryButton(tr(R.string.main_save_configuration)) {
            val newName = configNameInput.text.toString().trim()
            val newIcon = configIconInput.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(this, tr(R.string.main_project_name_cannot_be_empty), Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            val list = getProjects().toMutableList()
            val idx = list.indexOfFirst { it.path == activeProjectPath }
            if (idx >= 0) {
                val oldProj = list[idx]
                list[idx] = oldProj.copy(name = newName, icon = newIcon)
                saveProjects(list)
                activeProjectName = newName
                getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
                    .putString("active_project_name", activeProjectName)
                    .apply()
                if (::workspaceProjectNameTv.isInitialized) {
                    workspaceProjectNameTv.text = activeProjectName
                }
                if (::projectSettingsTopBar.isInitialized) {
                    updateProjectSubpageTopBar(projectSettingsTopBar)
                }
                if (::projectDirTreeTopBar.isInitialized) {
                    updateProjectSubpageTopBar(projectDirTreeTopBar)
                }
                if (::projectGitDiffTopBar.isInitialized) {
                    updateProjectSubpageTopBar(projectGitDiffTopBar)
                }
                Toast.makeText(this, tr(R.string.main_configuration_saved_successfully), Toast.LENGTH_SHORT).show()
                onBackPressed()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dp(16)
            }
        }
        settingsContentLayout.addView(saveBtn)

        settingsContentLayout.addView(spacer(16))
        val removeBtn = dangerButton(tr(R.string.main_remove_project_2)) {
            if (getProjects().any { it.path == activeProjectPath }) {
                removeProject(activeProjectPath)
                while (pageStack.isNotEmpty() && (
                    pageStack.peek() == ID_PROJECT_WORKSPACE ||
                    pageStack.peek() == ID_PROJECT_SETTINGS ||
                    pageStack.peek() == ID_PROJECT_DIR_TREE ||
                    pageStack.peek() == ID_PROJECT_GIT_DIFF
                )) {
                    pageStack.pop()
                }
                if (pageStack.isEmpty()) {
                    pageStack.push(ID_HOME)
                }
                val nextPage = pageStack.peek()
                navigateToPage(nextPage)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        settingsContentLayout.addView(removeBtn)
    }

    private fun buildProjectDirTreeLayout() {
        projectDirTreeContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        projectDirTreeTopBar = createProjectSubpageTopBar()
        directoryPage = com.zenithblue.nativecode.workspace.DirectoryPage(this, directoryHost).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        col.addView(projectDirTreeTopBar)
        col.addView(directoryPage)
        projectDirTreeContainer.addView(col)
    }

    private val directoryHost = object : com.zenithblue.nativecode.workspace.DirectoryHost {
        override fun root() = activeProjectHostDir()
        override fun openFile(file: File) = showFileViewer(file.absolutePath, ID_PROJECT_DIR_TREE)
        override fun copyText(label: String, text: String) = copyToClipboard(label, text)
        override fun guestPath(file: File) = ProjectPathResolver.toDebianPath(this@MainActivity, file, activeProjectMethod)
        override fun openTerminalHere(dir: File) {
            val guest = guestPath(dir) ?: return Toast.makeText(this@MainActivity, tr(R.string.main_folder_is_outside_the_guest), Toast.LENGTH_SHORT).show()
            navigateToPage(ID_PROJECT_WORKSPACE, false)
            wsTermCwd = guest
            createWorkspaceTerminalTab("shell")
            wsTermCwd = null
        }
        override fun gitChanges(): List<Pair<String, Char>>? {
            val method = projectGitMethod()
            if (!com.zenithblue.nativecode.git.GitRepoService.isGitRepo(this@MainActivity, activeProjectPath, method)) return null
            val r = com.zenithblue.nativecode.git.GitRepoService.loadChanges(this@MainActivity, activeProjectPath, method)
            return (r as? com.zenithblue.nativecode.git.RepoStatusResult.Ok)?.status?.files?.map { it.path to it.status }
        }
        override fun confirmDelete(name: String, isDir: Boolean, onConfirm: () -> Unit) {
            showBrutalistConfirmDialog(
                title = if (isDir) tr(R.string.main_delete_folder) else tr(R.string.main_delete_file),
                message = if (isDir) tr(R.string.main_delete_and_everything_inside_it, name) else tr(R.string.main_delete_this_can_not_be, name),
                confirmLabel = tr(R.string.git_delete),
                destructive = true,
                onConfirm = onConfirm,
            )
        }
    }

    private fun openProjectDirTree() {
        updateProjectSubpageTopBar(projectDirTreeTopBar)
        directoryPage.refresh()
    }

    private fun buildProjectGitDiffLayout() {
        projectGitDiffContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        projectGitDiffTopBar = createProjectSubpageTopBar()
        projectGitHistoryView = com.zenithblue.nativecode.git.ui.GitHistoryView(this, executor) { hash, path ->
            showDiffViewer(path, ID_PROJECT_GIT_DIFF, hash)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            visibility = View.GONE
        }
        projectGitChangesView = com.zenithblue.nativecode.git.ui.GitChangesView(
            this, executor,
            onOpenDiff = { path -> showDiffViewer(path, ID_PROJECT_GIT_DIFF) },
            onOpenFile = { path -> showFileViewer(path, ID_PROJECT_GIT_DIFF) }
        )
        val gitTabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        projectGitTabBtns = listOf(tr(R.string.git_changes), tr(R.string.main_history)).mapIndexed { i, label ->
            TextView(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                minHeight = dp(40)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i == 1) leftMargin = dp(-1) }
                setOnClickListener { projectGitTab = i; applyProjectGitTab() }
            }
        }
        projectGitTabBtns.forEach { gitTabRow.addView(it) }
        projectGitDiffScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))
            setBackgroundColor(NC.BG)
        }
        projectGitDiffLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        projectGitDiffScrollView.addView(projectGitDiffLayout)
        col.addView(projectGitDiffTopBar)
        col.addView(gitTabRow)
        col.addView(projectGitDiffScrollView)
        col.addView(projectGitHistoryView)
        projectGitDiffContainer.addView(col)
    }

    /** CHANGES (working tree diff) vs HISTORY (commit graph) on the project Git page. */
    private fun applyProjectGitTab() {
        projectGitTabBtns.forEachIndexed { i, b ->
            val on = i == projectGitTab
            b.setTextColor(if (on) NC.ON_PRIMARY else NC.ON_SURF_VAR)
            b.background = GradientDrawable().apply {
                setColor(if (on) NC.PRIMARY else NC.SURFACE_LOWEST)
                setStroke(dp(1), if (on) NC.PRIMARY else NC.OUTLINE_VAR)
            }
        }
        projectGitDiffScrollView.visibility = if (projectGitTab == 0) View.VISIBLE else View.GONE
        projectGitHistoryView.visibility = if (projectGitTab == 1) View.VISIBLE else View.GONE
        if (projectGitTab == 1) projectGitHistoryView.show(activeProjectPath, projectGitMethod())
        else refreshGitDiffTree()
    }

    private fun openProjectGitDiff() {
        projectGitDiffLayout.removeAllViews()
        updateProjectSubpageTopBar(projectGitDiffTopBar)

        // Git diff page header
        val gitHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val gitHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gitHeaderTitle = TextView(this).apply {
            text = tr(R.string.main_git_diff)
            textSize = 18f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val gitHeaderSub = TextView(this).apply {
            text = tr(R.string.main_caps_changed_files)
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        }
        val projMethod = projectGitMethod()
        val methodBadge = TextView(this).apply {
            text = "CHROOT"
            textSize = 9f
            setTextColor(NC.TERTIARY)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.TERTIARY)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            visibility = if (projMethod == "chroot") View.VISIBLE else View.GONE
        }
        gitHeaderRow.addView(gitHeaderTitle)
        gitHeaderRow.addView(gitHeaderSub)
        gitHeaderRow.addView(methodBadge)
        val gitDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(8) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        gitHeader.addView(gitHeaderRow)
        gitHeader.addView(gitDivider)
        projectGitDiffLayout.addView(gitHeader)
        (projectGitChangesView.parent as? ViewGroup)?.removeView(projectGitChangesView)
        projectGitDiffLayout.addView(projectGitChangesView)
        applyProjectGitTab()
    }

    private fun setGlobalTerminalZoom(newSize: Int) {
        val clamped = newSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        termFontSize = clamped
        workspaceFontSize = clamped
        scriptFontSize = clamped
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
            .putInt("pref_terminal_zoom", clamped).apply()
        
        if (::terminalView.isInitialized) {
            terminalView.setTextSize(clamped)
        }
        if (::workspaceTerminalView.isInitialized) {
            workspaceTerminalView.setTextSize(clamped)
        }
        if (::scriptInstallTerminalView.isInitialized) {
            scriptInstallTerminalView.setTextSize(clamped)
        }
        if (::mpInstallerTerminalView.isInitialized) {
            mpInstallerTerminalView.setTextSize(clamped)
        }
        forceTerminalResize(if (::terminalView.isInitialized) terminalView else null)
        forceTerminalResize(if (::workspaceTerminalView.isInitialized) workspaceTerminalView else null)
    }

    private fun setExtraKeysEnabled(enabled: Boolean) {
        showExtraKeys = enabled
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
            .putBoolean("pref_show_extra_keys", enabled).apply()
        
        if (::terminalKeyboardToolbar.isInitialized) {
            terminalKeyboardToolbar.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        if (::workspaceKeyboardToolbar.isInitialized) {
            workspaceKeyboardToolbar.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        toolRails.forEach { it.refresh() }
        forceTerminalResize(if (::terminalView.isInitialized) terminalView else null)
        forceTerminalResize(if (::workspaceTerminalView.isInitialized) workspaceTerminalView else null)
    }

    /** One resize per frame. updateSize writes TIOCSWINSZ; no extra SIGWINCH to the tracer pid. */
    private val resizePosted = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<TerminalView, Boolean>()
    )

    private fun forceTerminalResize(tv: TerminalView?) {
        tv ?: return
        synchronized(resizePosted) { if (!resizePosted.add(tv)) return }
        tv.postOnAnimation {
            synchronized(resizePosted) { resizePosted.remove(tv) }
            try {
                if (tv.width <= 0 || tv.height <= 0) return@postOnAnimation
                tv.updateSize()
            } catch (_: Exception) {
            }
        }
    }

    private fun maybeToastHeavyToolLaunch(type: String) {
        if (type != "codex") return
        Toast.makeText(
            this,
            tr(R.string.main_loading_codex_250mb_if_blank),
            Toast.LENGTH_LONG
        ).show()
    }

    /** CLI card/sidebar/dropdown asset under images/cli/. */
    private fun cliToolAssetFilename(type: String): String = when (type) {
        "qwen-code" -> "qwen-code.webp"
        "shell-root" -> "shell.png"
        else -> "$type.png"
    }

    private fun buildTerminalSettingsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 6,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val termTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val termTitleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_terminal_thick)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) }
        }
        val title = TextView(this).apply {
            text = tr(R.string.main_terminal_settings)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        termTitleRow.addView(termTitleIcon)
        termTitleRow.addView(title)
        val sub = TextView(this).apply {
            text = tr(R.string.main_configure_global_terminal_font_zoom)
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(14))
        }
        card.addView(termTitleRow)
        card.addView(sub)

        // 1. Global Terminal Zoom Section
        val zoomHeader = TextView(this).apply {
            text = tr(R.string.main_global_terminal_zoom)
            textSize = 13f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(4))
        }
        val zoomValueTv = TextView(this).apply {
            text = tr(R.string.main_caps_font_size, termFontSize, termFontSize * 100 / 40)
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        }
        
        val zoomControlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val zoomMinusBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_remove_thick)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0
            )
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(2).toFloat()
                        v.translationY = dp(2).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_CONTAINER,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_CONTAINER,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 4,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }
        }

        val zoomPlusBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_add_thick)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0
            )
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(2).toFloat()
                        v.translationY = dp(2).toFloat()
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_CONTAINER,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 2,
                            cornerRadiusDp = 0
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                        v.background = cyberBrutalistBg(
                            fillColor = NC.SURFACE_CONTAINER,
                            strokeColor = NC.OUTLINE_VAR,
                            shadowColor = NC.SHADOW_DARK,
                            offsetDp = 4,
                            cornerRadiusDp = 0
                        )
                    }
                }
                false
            }
        }

        val zoomSeekBar = SeekBar(this).apply {
            max = MAX_FONT_SIZE - MIN_FONT_SIZE
            progress = termFontSize - MIN_FONT_SIZE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        }

        fun updateZoomUI() {
            zoomValueTv.text = tr(R.string.main_caps_font_size, termFontSize, termFontSize * 100 / 40)
            val targetProg = termFontSize - MIN_FONT_SIZE
            if (zoomSeekBar.progress != targetProg) {
                zoomSeekBar.progress = targetProg
            }
        }

        zoomMinusBtn.setOnClickListener {
            setGlobalTerminalZoom(termFontSize - 2)
            updateZoomUI()
        }
        zoomPlusBtn.setOnClickListener {
            setGlobalTerminalZoom(termFontSize + 2)
            updateZoomUI()
        }
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newSize = MIN_FONT_SIZE + progress
                    setGlobalTerminalZoom(newSize)
                    zoomValueTv.text = tr(R.string.main_caps_font_size, newSize, newSize * 100 / 40)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        zoomControlRow.addView(zoomMinusBtn)
        zoomControlRow.addView(zoomSeekBar)
        zoomControlRow.addView(zoomPlusBtn)

        card.addView(zoomHeader)
        card.addView(zoomValueTv)
        card.addView(zoomControlRow)

        // Divider line
        val div = View(this).apply {
            setBackgroundColor(NC.OUTLINE_VAR)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
        }
        card.addView(div)

        // 2. Extra Keyboard Buttons Toggle Section
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val toggleLabelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(12) }
        }
        val toggleTitle = TextView(this).apply {
            text = tr(R.string.main_extra_keys_bar)
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val toggleSub = TextView(this).apply {
            text = tr(R.string.main_esc_tab_sticky_ctrl_alt)
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        }
        toggleLabelLayout.addView(toggleTitle)
        toggleLabelLayout.addView(toggleSub)

        val switchToggle = CustomBrutalistToggle(this, showExtraKeys) { isChecked ->
            setExtraKeysEnabled(isChecked)
        }

        toggleRow.addView(toggleLabelLayout)
        toggleRow.addView(switchToggle)
        card.addView(toggleRow)

        // Item 12 kill switch: off = both terminal surfaces exactly as 1.7.x (switch hidden, terminal only).
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(12) }
                addView(TextView(this@MainActivity).apply {
                    text = tr(R.string.main_chat_view_beta)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = tr(R.string.main_agent_chat_on_the_terminal)
                    textSize = 11f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                })
            })
            addView(CustomBrutalistToggle(this@MainActivity, chatBetaOn()) { on ->
                chatPrefs().edit().putBoolean(ChatLogic.PREF_BETA, on).apply()
                applyTerminalMode(ws = false)
                applyTerminalMode(ws = true)
            })
        })

        // Agent chat: show the model's thinking rows (off by default; hidden rows stay stored).
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(12) }
                addView(TextView(this@MainActivity).apply {
                    text = tr(R.string.main_show_thinking)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = tr(R.string.main_show_thinking_sub)
                    textSize = 11f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                })
            })
            addView(CustomBrutalistToggle(this@MainActivity, chatPrefs().getBoolean(ChatLogic.PREF_THINKING, false)) { on ->
                chatPrefs().edit().putBoolean(ChatLogic.PREF_THINKING, on).apply()
            })
        })

        // 3. Extra keys preset (simple choice; no key editor)
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val prefs = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
        fun paintPresets() {
            val current = KeyPreset.byId(prefs.getString(KeyLayouts.PREF_PRESET, null))
            for (i in 0 until presetRow.childCount) {
                val tv = presetRow.getChildAt(i) as TextView
                val on = tv.tag == current
                tv.setTextColor(if (on) NC.PRIMARY else NC.ON_SURF_VAR)
                tv.background = roundedBg(if (on) (NC.PRIMARY and 0x00FFFFFF) or 0x33000000 else NC.SURFACE, if (on) NC.PRIMARY else NC.BORDER, 0)
                tv.isSelected = on
            }
        }
        KeyPreset.values().forEach { preset ->
            presetRow.addView(TextView(this).apply {
                tag = preset
                text = preset.title.uppercase(Locale.US)
                contentDescription = "Extra keys layout: ${preset.title}"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) }
                setOnClickListener {
                    prefs.edit().putString(KeyLayouts.PREF_PRESET, preset.id).apply()
                    paintPresets()
                    if (::terminalKeyboardToolbar.isInitialized) terminalKeyboardToolbar.reloadPreset()
                    if (::workspaceKeyboardToolbar.isInitialized) workspaceKeyboardToolbar.reloadPreset()
                }
            })
        }
        paintPresets()
        card.addView(presetRow)

        return card
    }

    /** Dedicated "Top bar" page: live preview + stat toggles/reorder (workspace/StatsEditor). */
    private fun buildTopBarPage() {
        topBarScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        topBarScrollView.addView(page)
        page.addView(
            buildSubPageHeader(
                title = tr(R.string.main_top_bar),
                subtitle = tr(R.string.main_caps_phone_stats_in_the_top_bar),
                iconRes = R.drawable.ic_tune_thick,
                pageId = ID_TOPBAR
            ) { badge -> badge.visibility = View.GONE }
        )
        page.addView(StatsEditor.buildPage(this, "my-project") { fillToolRail(it, workspace = true) })
    }

    private fun openTopBarPage() = navigateToPage(ID_TOPBAR)

    /** Registers a Settings sub-page (header + [body]); [onShow] runs every time it is shown. */
    private fun buildExtraPage(id: Int, title: String, subtitle: String, iconRes: Int, body: View, onShow: () -> Unit) {
        val sv = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        sv.addView(page)
        page.addView(buildSubPageHeader(title = title, subtitle = subtitle, iconRes = iconRes, pageId = id) { badge -> badge.visibility = View.GONE })
        page.addView(body)
        extraPages[id] = sv
        extraPageOnShow[id] = onShow
    }

    /** Settings -> SSH (plan 17): OpenSSH install, SSH server (Pro), authorized keys, remote hosts, client keys. */
    private fun buildSshPage() {
        val p = com.zenithblue.nativecode.ssh.ui.SshPage(this, sshHost).also { sshPage = it }
        buildExtraPage(ID_SSH, "SSH", "// SERVER AND REMOTE HOSTS", R.drawable.ic_lucide_server, p.content) { p.refresh() }
    }

    /** Settings -> AI usage (plan 18): per-CLI tokens and cost from ccusage, drawn natively. */
    private fun buildUsagePage() {
        val p = com.zenithblue.nativecode.usage.ui.UsagePage(this, usageHost).also { usagePage = it }
        buildExtraPage(ID_USAGE, tr(R.string.main_ai_usage), "// TOKENS AND COST", R.drawable.ic_lucide_chart, p.content) { p.refresh() }
    }

    private var homeUsageSubtitle: TextView? = null
    private var homeUsageLogos: LinearLayout? = null
    private val homeUsageRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Home AI usage card: today's tokens and cost from the cached report plus the most constrained plan limit
     * (for example "Claude 5h: 62%"). Both reload in the background when older than 10 and 15 minutes.
     */
    private fun refreshHomeUsage() {
        val tv = homeUsageSubtitle ?: return
        if (!homeUsageRefreshing.compareAndSet(false, true)) return
        Thread {
            var logos = emptyList<String>()
            val line = try {
                val repo = com.zenithblue.nativecode.usage.UsageRepository
                val m = com.zenithblue.nativecode.usage.UsageModel
                val lim = com.zenithblue.nativecode.usage.LimitsRepository
                val usage = if (!repo.isInstalled(this)) tr(R.string.main_tokens_and_cost_per_ai_3)
                else {
                    if (repo.cacheAgeMs(this) > 600_000L) repo.load(this)
                    val rep = repo.cached(this)?.only(usageHost::allowed)
                    val t = rep?.let { m.today(it, repo.todayString()) }
                    logos = rep?.let { m.activeAgents(it, repo.todayString()) }.orEmpty()
                    when {
                        rep == null -> "No usage data yet. Tap to read it."
                        t == null -> "Today: no AI usage yet."
                        else -> tr(R.string.main_today_tokens_est, m.fmtTokens(t.first), m.fmtCost(t.second))
                    }
                }
                var l = lim.cached(this)
                if (l.isEmpty() || System.currentTimeMillis() - l.maxOf { it.fetchedAt } > 900_000L) l = lim.refresh(this, usageHost::allowed)
                val worst = com.zenithblue.nativecode.usage.LimitsModel.mostConstrained(l, usageHost::allowed)
                val signed = l.filter { it.state == com.zenithblue.nativecode.usage.LimitState.OK && usageHost.allowed(it.agent) }.map { it.agent }
                logos = lim.AGENTS.filter { it in logos || it in signed }
                if (worst != null) usage + "\n" + worst.first + ": " + worst.second.usedPercent.toInt() + "%" else usage
            } catch (_: Exception) { tr(R.string.main_tokens_and_cost_per_ai_2) }
            homeUsageRefreshing.set(false)
            runOnUiThread {
                homeUsageSubtitle?.text = line
                homeUsageLogos?.let { r -> r.removeAllViews(); if (logos.isNotEmpty()) r.addView(com.zenithblue.nativecode.usage.ui.CliLogos.row(this, logos, 22)) }
            }
        }.start()
    }

    /** `ssh ...` line in a new app terminal tab (Free). Runs through the guest shell, closes the tab when ssh ends cleanly. */
    private fun openSshTab(title: String, command: String) {
        sshTabCommand = command
        sshTabTitle = title
        if (pageStack.isEmpty() || pageStack.peek() != ID_TERMINAL) pageStack.push(ID_TERMINAL)
        navigateToPage(ID_TERMINAL)
        createNewTerminalSession("ssh")
    }

    /** Settings -> Backup (plan 15): backup/restore of the guest home. */
    private fun buildBackupPage() {
        backupScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setBackgroundColor(NC.BG)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        backupScrollView.addView(page)
        page.addView(
            buildSubPageHeader(
                title = tr(R.string.main_backup),
                subtitle = tr(R.string.main_caps_back_up_and_restore),
                iconRes = R.drawable.ic_lucide_archive,
                pageId = ID_BACKUP
            ) { badge -> badge.visibility = View.GONE }
        )
        backupPage = com.zenithblue.nativecode.backup.ui.BackupPage(this, backupHost).also { page.addView(it.content) }
    }

    
    // CustomBrutalistToggle is imported as an alias of the shared ui.BrutalistToggle.

    private class TerminalScanlineDrawable(
        private val bgColor: Int = Color.parseColor("#131313"),
        private val lineSpacingDp: Int = 4,
        private val lineColor: Int = Color.parseColor("#12FFFFFF")
    ) : Drawable() {
        private val bgPaint = Paint().apply { color = bgColor }
        private val linePaint = Paint().apply {
            color = lineColor
            strokeWidth = 1f
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b, bgPaint)

            val displayMetrics = canvas.density
            val density = if (displayMetrics <= 0) 2.5f else displayMetrics.toFloat()
            val spacing = lineSpacingDp * density
            var y = 0f
            while (y < b.height()) {
                canvas.drawLine(0f, y, b.width().toFloat(), y, linePaint)
                y += spacing
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class CircularProgressView(context: Context) : View(context) {
        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 100f)
                postInvalidate()
            }
        var arcColor: Int = Color.parseColor("#C8B6FF")
            set(value) {
                field = value
                postInvalidate()
            }
        var trackColor: Int = Color.parseColor("#222222")
            set(value) {
                field = value
                postInvalidate()
            }

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val rectF = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val strokeWidthPx = 7f * density
            trackPaint.strokeWidth = strokeWidthPx
            trackPaint.color = trackColor

            progressPaint.strokeWidth = strokeWidthPx
            progressPaint.color = arcColor

            val inset = strokeWidthPx / 2f + 2f * density
            rectF.set(inset, inset, width - inset, height - inset)

            canvas.drawOval(rectF, trackPaint)

            val sweep = (progress / 100f) * 360f
            if (sweep > 0f) {
                canvas.drawArc(rectF, -90f, sweep, false, progressPaint)
            }
        }
    }
}
