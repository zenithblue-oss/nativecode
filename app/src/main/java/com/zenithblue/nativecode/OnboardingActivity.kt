package com.zenithblue.nativecode

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import android.system.Os
import com.zenithblue.nativecode.cliauth.AiCliProvisionState
import com.zenithblue.nativecode.onboarding.OnboardingIo
import com.zenithblue.nativecode.onboarding.OnboardingUiKit
import com.zenithblue.nativecode.onboarding.OnboardingUiKit.CustomBrutalistToggle
import com.zenithblue.nativecode.cliauth.CliToolsInstaller
import com.zenithblue.nativecode.cliauth.InstallPlanCatalog
import com.zenithblue.nativecode.terminal.GpuAccelDetector
import com.zenithblue.nativecode.terminal.HostCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.TermuxHostPaths
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.min

class OnboardingActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var pageContainer: FrameLayout

    // 0 privacy → 1 intro → 2 slideshow → 3 requirements → 4 notifications → 5 plan → 6 setup → 7 complete
    private var currentPageIndex = 0

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── Shared setup status state ──────────────────────────────────────────────
    private var isDebianBaseSetupStarted = false
    private var enableDebianCustomization = true
    private var enableXfceGui = true
    /** C6: AI CLI suite opt-in (default OFF). Step H only when true. */
    private var enableAiCliInstall = false
    /** Complete-page truth: SKIPPED | READY | PARTIAL */
    private var aiCliSetupOutcome = "SKIPPED"

    // ── Linux isolation method selected on page 4 ─────────────────────────────
    // "proot" (default, rootless) or "chroot" (requires KernelSU/Magisk root)
    private var selectedIsolationMethod = "proot"

    // page 4 (Full Environment Setup: base + AI CLIs) — progress-first UI
    private lateinit var setupPercentText: TextView
    private lateinit var setupPhaseMetaText: TextView
    private lateinit var setupElapsedText: TextView
    private lateinit var setupStepTitleText: TextView
    private lateinit var setupDetailText: TextView
    private lateinit var setupProgressTrack: FrameLayout
    private lateinit var setupProgressFill: View
    private lateinit var baseLogText: TextView
    private lateinit var baseLogScroll: ScrollView
    private lateinit var setupLogPanel: View
    private lateinit var setupLogToggleBtn: LinearLayout
    private lateinit var setupLogToggleLabel: TextView
    private lateinit var baseNextBtn: View

    private var setupLogVisible = false
    private val setupLogBuffer = StringBuilder()
    private var setupOverallPercent = 0
    private var setupPhaseIndex = 0
    private var setupPhaseFraction = 0f
    private var setupPhases: List<SetupPhase> = emptyList()
    private var setupFailed = false
    private var setupFinished = false
    private var setupFgsStarted = false
    private var setupFgsGeneration = 0

    /** Displayed progress 0–100f (animated toward [setupOverallPercent]). */
    private var setupAnimatedPercent = 0f
    private var progressBarAnimator: ValueAnimator? = null
    private var percentTextAnimator: ValueAnimator? = null
    private var progressPulseAnimator: ObjectAnimator? = null
    private var percentPunchAnimator: ObjectAnimator? = null
    private var setupDisplayedPercentInt = 0

    // Elapsed timer (starts with install, freezes on success/fail)
    private var setupElapsedStartMs = 0L
    private var setupElapsedFrozenMs = 0L
    private var setupElapsedRunning = false
    private val setupElapsedTicker = object : Runnable {
        override fun run() {
            if (!setupElapsedRunning) return
            refreshSetupElapsedUi()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private data class SetupPhase(val id: String, val label: String, val weight: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install/upgrade: stage scripts before any onboarding shell work
        AppUpgrade.runIfNeeded(this)

        val isForced = intent.getBooleanExtra("force_onboarding", false)
        val setupComplete = File(filesDir, "setup_complete").exists() ||
                getSharedPreferences("nativecode_prefs", MODE_PRIVATE).getBoolean("onboarding_completed", false)

        if (!isForced && setupComplete) {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        // Make window status/navigation bar transparent for immersive dark layout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        rootLayout = FrameLayout(this).apply {
            background = roundedBg(NC.BG, NC.BG, 0)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        pageContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        rootLayout.addView(pageContainer)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, statusBars.top, 0, navBars.bottom)
            insets
        }

        setContentView(rootLayout)

        deployScripts()

        // Optional deep-link from Settings → Chroot install
        intent.getStringExtra("preferred_isolation")?.let { pref ->
            if (pref == "chroot" || pref == "proot") selectedIsolationMethod = pref
        }
        val privacyOk = getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
            .getBoolean("privacy_accepted", false)
        var startPage = intent.getIntExtra("target_page", if (privacyOk) 1 else 0)
        // 0 = privacy; force privacy first until accepted
        if (!privacyOk) startPage = 0
        else if (startPage == 0) startPage = 1
        showPage(startPage)

        // Jump straight into Environment Setup (page 6) and run full install chain
        if (intent.getBooleanExtra("auto_start_setup", false) && startPage == 6 && privacyOk) {
            if (!isDebianBaseSetupStarted) {
                isDebianBaseSetupStarted = true
                runDebianBaseSetup()
            }
        }
    }

    private fun showPage(index: Int) {
        currentPageIndex = index
        pageContainer.removeAllViews()

        val view = when (index) {
            0 -> buildPrivacyPage()
            1 -> buildIntroPage()
            2 -> buildSlideshowPage()
            3 -> buildRequirementsPage()
            4 -> buildNotificationPermissionPage()
            5 -> buildInstallPlanPage()
            6 -> buildDebianBasePage()
            7, 8 -> buildCompletePage() // 8 = legacy deep-link after plan insert
            else -> buildIntroPage()
        }

        view.alpha = 0f
        pageContainer.addView(view)
        view.animate().alpha(1f).setDuration(250).start()
    }

    // ── Page 0: Privacy (local-only / open-source) ────────────────────────────
    private fun buildPrivacyPage(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            isFillViewport = true
            setBackgroundColor(NC.BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        val headerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield_thick)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                bottomMargin = dp(12)
                gravity = Gravity.START
            }
        }
        root.addView(headerIcon)

        val title = TextView(this).apply {
            text = "PRIVACY"
            textSize = 28f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.01f
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = "// OPEN SOURCE · LOCAL-FIRST DEVELOPER ENVIRONMENT"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(16))
        }
        root.addView(sub)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(16) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        root.addView(divider)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }

        fun bullet(label: String, body: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(14))
                addView(TextView(this@OnboardingActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(NC.PRIMARY)
                    typeface = Typeface.MONOSPACE
                })
                addView(TextView(this@OnboardingActivity).apply {
                    text = body
                    textSize = 14f
                    setTextColor(Color.parseColor("#FAFAFA"))
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(0f, 1.25f)
                })
            }
        }

        card.addView(
            bullet(
                "01 · NO NATIVECODE CLOUD",
                "NativeCode is open source. We do not operate a backend that collects your personal data, analytics, or telemetry."
            )
        )
        card.addView(
            bullet(
                "02 · YOUR DATA STAYS LOCAL",
                "Files, projects, packages, and credentials live on this device inside your Debian proot or chroot distro — not on our servers."
            )
        )
        card.addView(
            bullet(
                "03 · OPTIONAL NETWORK",
                "You may choose GitHub login, AI CLIs, or package downloads. Those talks go to those third parties only when you start them — never silently by NativeCode cloud."
            )
        )
        card.addView(
            bullet(
                "04 · YOU CONTROL DELETION",
                "Clear app storage, uninstall, or delete guest files to remove local data. Manage third-party accounts with each vendor."
            )
        )
        root.addView(card)

        val policyLink = TextView(this).apply {
            text = "Full policy on zenithblue.dev ↗"
            textSize = 13f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(20))
            setOnClickListener {
                val url = "https://zenith-blue.web.app/nativecode/privacy"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this@OnboardingActivity, "No browser available", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(policyLink)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        root.addView(spacer)

        root.addView(
            primaryButton("I Understand — Continue", R.drawable.ic_arrow_right) {
                getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("privacy_accepted", true)
                    .apply()
                showPage(1)
            }
        )

        scroll.addView(root)
        return scroll
    }

    // ── Page 1: Brand Intro ───────────────────────────────────────────────────
    private fun buildIntroPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        val topSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        root.addView(topSpacer)

        // Center sharp Cyber-Brutalist container for logo
        val logoCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.BORDER,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply {
                bottomMargin = dp(32)
            }
        }
        val logoIv = ImageView(this).apply {
            setImageResource(R.drawable.logo_highres)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(130))
        }
        logoCard.addView(logoIv)
        root.addView(logoCard)

        val nameTv = TextView(this).apply {
            text = "NativeCode"
            textSize = 38f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = -0.01f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(nameTv)

        val taglineTv = TextView(this).apply {
            text = "Portable Linux & AI Developer Environment"
            textSize = 14f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(36))
        }
        root.addView(taglineTv)

        // Feature tag pills row (sharp Cyber-Brutalist design)
        val tagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(48))
        }
        tagRow.addView(featureBadge(R.drawable.ic_terminal, "Debian Trixie"))
        tagRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        tagRow.addView(featureBadge(R.drawable.ic_smart_toy, "AI & LLMs"))
        tagRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        tagRow.addView(featureBadge(R.drawable.ic_laptop, "Dev Suite"))
        root.addView(tagRow)

        val botSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        root.addView(botSpacer)

        val btn = primaryButton("Get Started", R.drawable.ic_arrow_right) {
            showPage(2) // slideshow
        }
        root.addView(btn)

        return root
    }

    // ── Page 2: Feature Slideshow ─────────────────────────────────────────────
    private fun buildSlideshowPage(): View {
        val shadowOff = 6
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Tightened from 16dp: reclaims vertical space so the hero image can dominate
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        // Slim mono label replaces the 20sp smallHeader() used on other pages — that header
        // alone cost ~36dp (20sp text + 16dp bottom pad) of hero space for little benefit here.
        root.addView(TextView(this).apply {
            text = "CORE CAPABILITIES"
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        })

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = dp(4)
                // Bottom gap leaves room for card's 6dp extrusion + breathing room
                bottomMargin = dp(4 + shadowOff)
            }
            clipChildren = false
            clipToPadding = false
        }
        root.addView(contentFrame)

        val slides = listOf(
            SlideData(
                category = "WORKSPACE",
                title = "Project Management",
                iconResId = R.drawable.ic_folder,
                desc = "Multi-repo project tree manager with quick creation, workspace switching, and file organization.",
                type = PreviewType.PROJECT_TREE
            ),
            SlideData(
                category = "AI ENGINE",
                title = "AI Tools & LLM Harness",
                iconResId = R.drawable.ic_smart_toy,
                desc = "Run Claude Code, Codex, Aider, and OpenCode AI CLI tools directly inside your native terminal environment.",
                type = PreviewType.AI_CLI
            ),
            SlideData(
                category = "RUNTIMES",
                title = "Development Environment",
                iconResId = R.drawable.ic_laptop,
                desc = "Preconfigured Node.js 26 LTS, Python 3.12, GCC, and package managers ready for instant execution.",
                type = PreviewType.DEV_SUITE
            ),
            SlideData(
                category = "DESKTOP",
                title = "XFCE4 Graphic Server",
                iconResId = R.drawable.ic_display,
                desc = "Integrated Termux-X11 display server support to launch full Linux XFCE desktop GUIs on Android.",
                type = PreviewType.XFCE_GUI
            ),
            SlideData(
                category = "DEBIAN",
                title = "Debian 13 Linux",
                iconResId = R.drawable.ic_terminal,
                desc = "Native glibc Debian 13 PRoot environment with apt package manager and full Linux userland.",
                type = PreviewType.DEBIAN_ENV
            ),
            SlideData(
                category = "CONTROL",
                title = "Git Diff & Version Control",
                iconResId = R.drawable.ic_git,
                desc = "Native Git integration with real-time status tracking, visual diff inspection, and branch switching.",
                type = PreviewType.GIT_DIFF
            )
        )

        var activeSlide = 0

        fun slideImageRes(type: PreviewType): Int = when (type) {
            PreviewType.PROJECT_TREE -> R.drawable.img_slide_workspace
            PreviewType.AI_CLI -> R.drawable.img_slide_ai
            PreviewType.DEV_SUITE -> R.drawable.img_slide_dev
            PreviewType.XFCE_GUI -> R.drawable.img_slide_xfce
            PreviewType.DEBIAN_ENV -> R.drawable.img_slide_debian
            PreviewType.MARKETPLACE -> R.drawable.img_slide_marketplace
            PreviewType.GIT_DIFF -> R.drawable.img_slide_git
        }

        fun renderSlideCard(slideIdx: Int): View {
            val slide = slides[slideIdx]
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                clipChildren = false
                clipToPadding = false
            }

            // Dedicated centered hero card sized to SVG aspect ratio (1132 / 1657)
            val heroCard = object : FrameLayout(this@OnboardingActivity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    val h = measuredHeight
                    val maxW = MeasureSpec.getSize(widthMeasureSpec)
                    val shadowPx = dp(shadowOff)
                    val contentH = (h - shadowPx).coerceAtLeast(1)
                    val contentW = (contentH * (1132f / 1657f)).toInt()
                    val targetW = minOf(maxW, contentW + shadowPx)
                    setMeasuredDimension(targetW, h)
                    val childWSpec = MeasureSpec.makeMeasureSpec(targetW - shadowPx, MeasureSpec.EXACTLY)
                    val childHSpec = MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY)
                    measureChildren(childWSpec, childHSpec)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, 0, 1f).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(10)
                }
                background = cyberBrutalistBg(
                    fillColor = Color.parseColor("#121212"),
                    strokeColor = NC.BORDER,
                    shadowColor = NC.SURFACE_BRIGHT,
                    offsetDp = shadowOff,
                    cornerRadiusDp = 0,
                    rightFaceColor = NC.OUTLINE_VAR
                )
                setPadding(0, 0, dp(shadowOff), dp(shadowOff))
                clipChildren = true
                clipToPadding = true
            }

            val iv = ImageView(this).apply {
                setImageResource(slideImageRes(slide.type))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            }
            heroCard.addView(iv)
            container.addView(heroCard)

            // Meta card below hero
            val meta = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                background = cyberBrutalistBg(
                    fillColor = NC.SURFACE_CONTAINER,
                    strokeColor = NC.BORDER,
                    shadowColor = NC.SURFACE_BRIGHT,
                    offsetDp = shadowOff,
                    cornerRadiusDp = 0,
                    rightFaceColor = NC.OUTLINE_VAR
                )
                setPadding(dp(14), dp(10), dp(14 + shadowOff), dp(10 + shadowOff))
            }

            val tagTv = TextView(this).apply {
                text = "[ ${slide.category} — ${String.format("%02d", slideIdx + 1)} / ${String.format("%02d", slides.size)} ]"
                textSize = 13f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(6))
            }
            meta.addView(tagTv)

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            val iconIv = ImageView(this).apply {
                setImageResource(slide.iconResId)
                setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    rightMargin = dp(10)
                }
            }
            val titleTv = TextView(this).apply {
                text = slide.title
                textSize = 20f
                setTextColor(NC.ON_SURFACE)
                typeface = Typeface.DEFAULT_BOLD
            }
            headerRow.addView(iconIv)
            headerRow.addView(titleTv)
            meta.addView(headerRow)

            val descTv = TextView(this).apply {
                text = slide.desc
                textSize = 15f
                setTextColor(NC.ON_SURF_VAR)
                setLineSpacing(0f, 1.35f)
            }
            meta.addView(descTv)

            container.addView(meta)
            return container
        }

        contentFrame.addView(renderSlideCard(0))

        // Progress Dots Row — sharp rectangles (no radius)
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
        }
        val dotViews = mutableListOf<View>()

        fun updateDots(selectedIdx: Int) {
            for (i in dotViews.indices) {
                val isSelected = (i == selectedIdx)
                val widthDp = if (isSelected) 28 else 8
                val params = dotViews[i].layoutParams as LinearLayout.LayoutParams
                params.width = dp(widthDp)
                params.height = dp(8)
                dotViews[i].layoutParams = params
                dotViews[i].background = roundedBg(
                    if (isSelected) NC.PRIMARY else NC.SURFACE_HIGHEST,
                    if (isSelected) NC.PRIMARY else NC.BORDER_VAR,
                    0
                )
            }
        }

        fun switchSlide(newIdx: Int) {
            if (newIdx == activeSlide) return
            activeSlide = newIdx
            val currentView = if (contentFrame.childCount > 0) contentFrame.getChildAt(0) else null
            val nextView = renderSlideCard(newIdx)
            nextView.alpha = 0f

            if (currentView != null) {
                currentView.animate().alpha(0f).setDuration(160).withEndAction {
                    contentFrame.removeAllViews()
                    contentFrame.addView(nextView)
                    nextView.animate().alpha(1f).setDuration(200).start()
                }.start()
            } else {
                contentFrame.addView(nextView)
                nextView.animate().alpha(1f).setDuration(200).start()
            }
            updateDots(activeSlide)
        }

        for (i in slides.indices) {
            val isSelected = (i == 0)
            val dot = View(this).apply {
                val widthDp = if (isSelected) 28 else 8
                layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(8)).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
                background = roundedBg(
                    if (isSelected) NC.PRIMARY else NC.SURFACE_HIGHEST,
                    if (isSelected) NC.PRIMARY else NC.BORDER_VAR,
                    0
                )
                isClickable = true
                setOnClickListener { switchSlide(i) }
            }
            dotsRow.addView(dot)
            dotViews.add(dot)
        }
        root.addView(dotsRow)

        // Manual swipe only (no auto-advance)
        var downX = 0f
        contentFrame.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - downX
                    if (deltaX < -90) {
                        switchSlide((activeSlide + 1) % slides.size)
                    } else if (deltaX > 90) {
                        switchSlide(if (activeSlide > 0) activeSlide - 1 else slides.size - 1)
                    }
                    true
                }
                else -> false
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Reserve shadow extrusion under buttons
            setPadding(0, 0, 0, dp(shadowOff))
        }
        val prevBtn = secondaryButton("Back") {
            if (activeSlide > 0) {
                switchSlide(activeSlide - 1)
            } else {
                showPage(1) // intro
            }
        }
        prevBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }

        val nextBtn = primaryButton("Continue", R.drawable.ic_arrow_right) {
            if (activeSlide < slides.size - 1) {
                switchSlide(activeSlide + 1)
            } else {
                showPage(3) // requirements
            }
        }
        nextBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)

        btnRow.addView(prevBtn)
        btnRow.addView(nextBtn)
        root.addView(btnRow)

        return root
    }

    private data class SlideData(
        val category: String,
        val title: String,
        val iconResId: Int,
        val desc: String,
        val type: PreviewType
    )

    private enum class PreviewType {
        PROJECT_TREE,
        AI_CLI,
        DEV_SUITE,
        XFCE_GUI,
        DEBIAN_ENV,
        MARKETPLACE,
        GIT_DIFF
    }


    // ── Page 3: Requirements (device probe dashboard) ─────────────────────────
    private fun buildRequirementsPage(): View {
        val shadowOff = 6
        val snap = DeviceRequirements.evaluate(this)
        val byId = snap.checks.associateBy { it.id }
        val storage = byId["storage"]
        val ram = byId["ram"]
        val swap = byId["swap"]
        val cpu = byId["cpu"]

        val passN = snap.checks.count { it.status == DeviceRequirements.Status.PASS }
        val warnN = snap.checks.count {
            it.status == DeviceRequirements.Status.WARN || it.status == DeviceRequirements.Status.UNKNOWN
        }
        val failN = snap.checks.count { it.status == DeviceRequirements.Status.FAIL }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        // ── Probe header ──────────────────────────────────────────────────────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val headerLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        headerLeft.addView(TextView(this).apply {
            text = "DEVICE PROBE"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
        })
        headerLeft.addView(TextView(this).apply {
            text = "Hardware gate before install"
            textSize = 18f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        })
        headerRow.addView(headerLeft)

        // Score chip: PASS.WARN.FAIL lattice (compact)
        val scoreChip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            background = roundedBg(NC.SURFACE_LOWEST, NC.BORDER, 0)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        scoreChip.addView(TextView(this).apply {
            text = "$passN / ${snap.checks.size}"
            textSize = 14f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        })
        scoreChip.addView(TextView(this).apply {
            text = buildString {
                append("OK $passN")
                if (warnN > 0) append(" · WARN $warnN")
                if (failN > 0) append(" · FAIL $failN")
            }
            textSize = 8f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        })
        headerRow.addView(scoreChip)
        root.addView(headerRow)

        root.addView(TextView(this).apply {
            text = "Storage is a hard gate. RAM / swap / SoC are soft signals."
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(12))
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(shadowOff + 8))
        }

        // ── STORAGE hard-gate hero ────────────────────────────────────────────
        if (storage != null) {
            list.addView(reqStorageHeroCard(storage, snap, shadowOff).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(12)
                }
            })
        }

        // ── RAM + SWAP twin tiles (equal height) ──────────────────────────────
        val twin = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dp(12)
            }
        }
        val twinTiles = mutableListOf<View>()
        if (ram != null) {
            val t = reqMetricTile(ram, snap.ramTotalMb, DeviceRequirements.MIN_RAM_MB, shadowOff)
            t.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
            twin.addView(t)
            twinTiles.add(t)
        }
        if (swap != null) {
            val t = reqMetricTile(swap, snap.swapTotalMb, DeviceRequirements.MIN_SWAP_MB, shadowOff)
            t.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            twin.addView(t)
            twinTiles.add(t)
        }
        // Match heights after first measure so longer detail text doesn't make swap taller
        if (twinTiles.size >= 2) {
            twin.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View?, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    val maxH = twinTiles.maxOf { it.height }
                    if (maxH <= 0) return
                    var changed = false
                    for (tile in twinTiles) {
                        val lp = tile.layoutParams as LinearLayout.LayoutParams
                        if (lp.height != maxH) {
                            lp.height = maxH
                            tile.layoutParams = lp
                            changed = true
                        }
                    }
                    if (changed) twin.removeOnLayoutChangeListener(this)
                }
            })
        }
        list.addView(twin)

        // ── SILICON SoC panel with brand logo ─────────────────────────────────
        if (cpu != null) {
            list.addView(reqSocCard(cpu, snap.socLabel, shadowOff).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(12)
                }
            })
        }

        // ── Verdict strip ─────────────────────────────────────────────────────
        list.addView(reqVerdictBanner(snap).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })

        scroll.addView(list)
        root.addView(scroll)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(shadowOff))
        }
        val prevBtn = secondaryButton("Back") { showPage(2) }
        prevBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }

        val nextLabel = if (snap.hardBlocked) "Storage required" else "Continue"
        val nextBtn = primaryButton(nextLabel, if (snap.hardBlocked) null else R.drawable.ic_arrow_right) {
            if (!snap.hardBlocked) {
                selectedIsolationMethod = "proot"
                showPage(4) // Notifications permission page
            }
        }
        nextBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        if (snap.hardBlocked) {
            nextBtn.isEnabled = false
            nextBtn.alpha = 0.4f
            nextBtn.isClickable = false
        }
        btnRow.addView(prevBtn)
        btnRow.addView(nextBtn)
        root.addView(btnRow)

        return root
    }

    private fun reqStatusAccent(status: DeviceRequirements.Status): Int = when (status) {
        DeviceRequirements.Status.PASS -> NC.PRIMARY
        DeviceRequirements.Status.FAIL -> NC.ERROR
        DeviceRequirements.Status.WARN -> NC.TERTIARY
        DeviceRequirements.Status.UNKNOWN -> NC.SECONDARY
    }

    private fun reqStatusBadge(status: DeviceRequirements.Status): TextView {
        val (bg, fg, label) = when (status) {
            DeviceRequirements.Status.PASS -> Triple(NC.PRIMARY_CON, NC.ON_PRIMARY_CON, "PASS")
            DeviceRequirements.Status.FAIL -> Triple(NC.ERROR_CON, NC.ON_ERROR_CON, "FAIL")
            DeviceRequirements.Status.WARN -> Triple(NC.SURFACE_HIGHEST, NC.TERTIARY, "WARN")
            DeviceRequirements.Status.UNKNOWN -> Triple(NC.SURFACE_HIGHEST, NC.SECONDARY, "????")
        }
        return textBadge(label, bg, fg)
    }

    /** Segmented bar 0f–1f fill via layout weights. */
    private fun reqMeterBar(fraction: Float, fill: Int, track: Int = NC.SURFACE_HIGHEST): View {
        val f = fraction.coerceIn(0f, 1f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(8))
            weightSum = 1f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(track)
                setStroke(dp(1), NC.BORDER)
            }
            if (f > 0f) {
                addView(View(this@OnboardingActivity).apply {
                    setBackgroundColor(fill)
                    layoutParams = LinearLayout.LayoutParams(0, MATCH, f)
                })
            }
            val rest = 1f - f
            if (rest > 0.001f) {
                addView(View(this@OnboardingActivity).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    layoutParams = LinearLayout.LayoutParams(0, MATCH, rest)
                })
            }
        }
    }

    private fun reqStorageHeroCard(
        check: DeviceRequirements.CheckResult,
        snap: DeviceRequirements.Snapshot,
        shadowOff: Int
    ): View {
        val accent = reqStatusAccent(check.status)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = accent,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = shadowOff,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(14), dp(14), dp(14 + shadowOff), dp(14 + shadowOff))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "01  STORAGE"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        top.addView(textBadge("HARD GATE", NC.SURFACE_HIGHEST, NC.ON_SURF_VAR).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }
        })
        top.addView(reqStatusBadge(check.status))
        card.addView(top)

        card.addView(TextView(this).apply {
            text = check.measured
            textSize = 28f
            setTextColor(accent)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, dp(2))
        })

        val totalLabel = if (snap.totalStorageBytes > 0) {
            DeviceRequirements.formatBytes(snap.totalStorageBytes) + " total"
        } else {
            "internal storage"
        }
        card.addView(TextView(this).apply {
            text = "need ${check.requirement}  ·  $totalLabel"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        })

        val frac = if (snap.totalStorageBytes > 0) {
            (snap.freeStorageBytes.toDouble() / snap.totalStorageBytes.toDouble()).toFloat()
        } else {
            if (check.status == DeviceRequirements.Status.PASS) 0.72f else 0.18f
        }
        card.addView(reqMeterBar(frac, accent))

        card.addView(TextView(this).apply {
            text = check.detail
            textSize = 12f
            setTextColor(NC.ON_SURFACE)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(10), 0, 0)
        })
        return card
    }

    private fun reqMetricTile(
        check: DeviceRequirements.CheckResult,
        measuredMb: Long,
        minMb: Long,
        shadowOff: Int
    ): View {
        val accent = reqStatusAccent(check.status)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = accent,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = shadowOff,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(12), dp(12), dp(12 + shadowOff), dp(12 + shadowOff))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = check.title.uppercase()
            textSize = 10f
            letterSpacing = 0.06f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        top.addView(reqStatusBadge(check.status))
        card.addView(top)

        // Big value: prefer short number
        val big = when {
            measuredMb >= 1024 -> String.format(java.util.Locale.US, "%.1f GB", measuredMb / 1024.0)
            measuredMb > 0 -> "$measuredMb MB"
            else -> "—"
        }
        card.addView(TextView(this).apply {
            text = big
            textSize = 20f
            setTextColor(accent)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(2))
        })

        card.addView(TextView(this).apply {
            text = "soft · need >${minMb / 1000} GB"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        })

        val frac = if (measuredMb > 0 && minMb > 0) {
            (measuredMb.toFloat() / (minMb * 1.35f)).coerceIn(0f, 1f)
        } else 0.05f
        card.addView(reqMeterBar(frac, accent))

        // Fixed 2-line detail slot so RAM/Swap cards share the same text budget
        card.addView(TextView(this).apply {
            text = check.detail
            textSize = 11f
            setTextColor(NC.ON_SURFACE)
            setLineSpacing(0f, 1.2f)
            maxLines = 2
            minLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        })
        return card
    }

    private fun reqSocLogoRes(label: String): Int? {
        val t = label.lowercase()
        return when {
            t.contains("snapdragon") || t.contains("qualcomm") ||
                Regex("""\bsm\d{4}\b""").containsMatchIn(t) -> R.drawable.logo_soc_snapdragon
            t.contains("dimensity") || t.contains("mediatek") || t.contains("helio") ||
                t.contains("mtk") || Regex("""\bmt\d{4}\b""").containsMatchIn(t) ->
                R.drawable.logo_soc_mediatek
            t.contains("exynos") || t.contains("samsung") && t.contains("s5e") ->
                R.drawable.logo_soc_exynos
            t.contains("tensor") -> R.drawable.logo_soc_tensor
            else -> null
        }
    }

    private fun reqSocCard(
        check: DeviceRequirements.CheckResult,
        socLabel: String,
        shadowOff: Int
    ): View {
        val accent = reqStatusAccent(check.status)
        val logoRes = reqSocLogoRes(socLabel.ifBlank { check.measured })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = accent,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = shadowOff,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(14), dp(14), dp(14 + shadowOff), dp(14 + shadowOff))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "04  SILICON"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        top.addView(textBadge("SOFT", NC.SURFACE_HIGHEST, NC.ON_SURF_VAR).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }
        })
        top.addView(reqStatusBadge(check.status))
        card.addView(top)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        // Logo plate
        val logoPlate = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                rightMargin = dp(14)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(dp(1), NC.BORDER)
            }
        }
        if (logoRes != null) {
            logoPlate.addView(ImageView(this).apply {
                setImageResource(logoRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            })
        } else {
            logoPlate.addView(TextView(this).apply {
                text = "SoC"
                textSize = 14f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            })
        }
        body.addView(logoPlate)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = check.measured
            textSize = 16f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        })
        textCol.addView(TextView(this).apply {
            text = "floor  ${check.requirement}"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        })
        textCol.addView(TextView(this).apply {
            text = check.detail
            textSize = 12f
            setTextColor(NC.ON_SURFACE)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(6), 0, 0)
        })
        body.addView(textCol)
        card.addView(body)

        // Vendor strip under body
        val vendor = when {
            logoRes == R.drawable.logo_soc_snapdragon -> "QUALCOMM  ·  SNAPDRAGON"
            logoRes == R.drawable.logo_soc_mediatek -> "MEDIATEK  ·  DIMENSITY"
            logoRes == R.drawable.logo_soc_exynos -> "SAMSUNG  ·  EXYNOS"
            logoRes == R.drawable.logo_soc_tensor -> "GOOGLE  ·  TENSOR"
            else -> "VENDOR  ·  UNIDENTIFIED"
        }
        card.addView(TextView(this).apply {
            text = vendor
            textSize = 10f
            letterSpacing = 0.1f
            setTextColor(accent)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        })
        return card
    }

    private fun reqVerdictBanner(snap: DeviceRequirements.Snapshot): View {
        data class Verdict(val label: String, val detail: String, val stroke: Int, val fg: Int, val tagBg: Int, val tagFg: Int)
        val v = when {
            snap.hardBlocked -> Verdict(
                "BLOCKED",
                "Free more than 10 GB storage to continue setup.",
                NC.ERROR,
                NC.ERROR,
                NC.ERROR_CON,
                NC.ON_ERROR_CON
            )
            snap.hasSoftWarnings -> Verdict(
                "SOFT WARN",
                "You can continue — performance may stutter below recommended RAM / swap / SoC.",
                NC.TERTIARY_CON,
                NC.TERTIARY,
                NC.TERTIARY_CON,
                NC.ON_TERTIARY
            )
            else -> Verdict(
                "ALL CLEAR",
                "Checks look good. Ready for method selection.",
                NC.PRIMARY,
                NC.PRIMARY,
                NC.PRIMARY_CON,
                NC.ON_PRIMARY_CON
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(NC.SURFACE_LOWEST, v.stroke, 0)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        row.addView(TextView(this).apply {
            text = v.label
            textSize = 11f
            setTextColor(v.tagFg)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(v.tagBg)
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) }
        })
        row.addView(TextView(this).apply {
            text = v.detail
            textSize = 12f
            setTextColor(v.fg)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        return row
    }

    // ── Page 4: Notification Permission (for background setup FGS & service status) ───
    private fun buildNotificationPermissionPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(NC.BG)
        }

        root.addView(smallHeader("Notifications", R.drawable.ic_stat_terminal))

        val spacer1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) }
        root.addView(spacer1)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat_terminal)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                bottomMargin = dp(14)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        card.addView(icon)

        val title = TextView(this).apply {
            text = "FOREGROUND SERVICE STATUS"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(title)

        val desc = TextView(this).apply {
            text = "NativeCode runs long tasks (package installation, rootfs decompression, desktop sessions) in an Android Foreground Service. Notification permission allows you to monitor real-time setup progress and terminal background tasks without Android killing the process."
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(16))
        }
        card.addView(desc)

        val grantBtn = TextView(this).apply {
            val granted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            text = if (granted) "PERMISSION GRANTED" else "GRANT PERMISSION"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(NC.ON_PRIMARY)
            background = cyberBrutalistBg(
                fillColor = NC.PRIMARY_CON,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 4,
                cornerRadiusDp = 0
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33) {
                    NativeCodeFgs.ensurePostNotificationsPermission(this@OnboardingActivity)
                } else {
                    Toast.makeText(this@OnboardingActivity, "Notification permission already granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
        card.addView(grantBtn)
        root.addView(card)

        val spacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) }
        root.addView(spacer2)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val prevBtn = secondaryButton("Back") { showPage(3) }
        prevBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        val nextBtn = primaryButton("Continue", R.drawable.ic_arrow_right) {
            showPage(5)
        }
        nextBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        btnRow.addView(prevBtn); btnRow.addView(nextBtn)
        root.addView(btnRow)

        return root
    }

    // ── Page 5: Install plan + consent (C6) ────────────────────────────────────
    private fun buildInstallPlanPage(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            isFillViewport = true
            setBackgroundColor(NC.BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        root.addView(smallHeader("Install Plan", R.drawable.ic_storage))
        root.addView(TextView(this).apply {
            text = "// GUEST DEBIAN ONLY · NOT A HOST APK UPDATE · REVIEW BEFORE RUN"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(12))
        })

        fun sectionCard(
            title: String,
            badge: String,
            lines: List<InstallPlanCatalog.InventoryLine>,
            bodyExtra: View? = null
        ): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cyberBrutalistBg(
                    fillColor = NC.SURFACE_CONTAINER,
                    strokeColor = NC.BORDER,
                    shadowColor = NC.SURFACE_BRIGHT,
                    offsetDp = 6,
                    cornerRadiusDp = 0,
                    rightFaceColor = NC.OUTLINE_VAR
                )
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            head.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(NC.ON_SURFACE)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            head.addView(textBadge(badge, NC.PRIMARY_CON, NC.ON_PRIMARY_CON))
            card.addView(head)
            lines.forEach { line ->
                card.addView(TextView(this).apply {
                    text = "· ${line.label}  —  ${line.detail}"
                    textSize = 11f
                    setTextColor(NC.ON_SURF_VAR)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
            bodyExtra?.let { card.addView(it) }
            return card
        }

        // A — Base (required)
        root.addView(
            sectionCard(
                "A · BASE ENVIRONMENT",
                "REQUIRED",
                InstallPlanCatalog.baseInventory(selectedIsolationMethod)
            )
        )

        // B — Customization toggle
        val customExtra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        customExtra.addView(TextView(this).apply {
            text = "Include shell/font customization"
            textSize = 12f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        customExtra.addView(
            CustomBrutalistToggle(this, enableDebianCustomization) { checked ->
                enableDebianCustomization = checked
            }
        )
        root.addView(
            sectionCard(
                "B · CUSTOMIZATION",
                "OPTIONAL",
                InstallPlanCatalog.customizationInventory(),
                customExtra
            )
        )

        // C — Graphical Desktop (XFCE4) toggle
        val guiExtra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        guiExtra.addView(TextView(this).apply {
            text = "Install XFCE4 graphical desktop"
            textSize = 12f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        guiExtra.addView(
            CustomBrutalistToggle(this, enableXfceGui) { checked ->
                enableXfceGui = checked
            }
        )
        root.addView(
            sectionCard(
                "C · GRAPHICAL DESKTOP",
                "OPTIONAL",
                InstallPlanCatalog.guiInventory(),
                guiExtra
            )
        )

        // D — AI CLI tools (default OFF)
        val aiTableHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (enableAiCliInstall) View.VISIBLE else View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        InstallPlanCatalog.aiCliInventory().forEach { line ->
            aiTableHost.addView(TextView(this).apply {
                text = "· ${line.label}  —  ${line.detail}"
                textSize = 11f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        val aiExtra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val aiToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        aiToggleRow.addView(TextView(this).apply {
            text = "Install AI CLI tools"
            textSize = 12f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        aiToggleRow.addView(
            CustomBrutalistToggle(this, enableAiCliInstall) { checked ->
                enableAiCliInstall = checked
                aiTableHost.visibility = if (checked) View.VISIBLE else View.GONE
            }
        )
        aiExtra.addView(aiToggleRow)
        aiExtra.addView(TextView(this).apply {
            text = "Default OFF. Third-party curl/npm installers run inside guest only. Skip = Debian shells only; install later in Settings → AI CLI tools."
            textSize = 11f
            setTextColor(NC.OUTLINE)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1.2f)
        })
        aiExtra.addView(TextView(this).apply {
            text = "AI outputs are from third-party vendors. Report: Settings → AI Safety after setup."
            textSize = 11f
            setTextColor(NC.OUTLINE)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1.2f)
        })
        aiExtra.addView(aiTableHost)
        root.addView(
            sectionCard(
                "D · AI CLI TOOLS",
                "DEFAULT OFF",
                emptyList(),
                aiExtra
            )
        )

        // Consent checkbox
        var planAccepted = false
        val consentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_LOW,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 4,
                cornerRadiusDp = 0
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        val check = CheckBox(this).apply {
            isChecked = false
            buttonTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
        }
        val consentTv = TextView(this).apply {
            text = "I reviewed this plan. Guest install may use network and third-party scripts when AI is enabled."
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) }
        }
        consentRow.addView(check)
        consentRow.addView(consentTv)
        root.addView(consentRow)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val prevBtn = secondaryButton("Back") { showPage(4) } // notifications permission
        prevBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        val startBtn = primaryButton("I understand — start install", R.drawable.ic_arrow_right) {
            if (!planAccepted) {
                Toast.makeText(this, "Check the consent box to continue", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            AiCliProvisionState.setPlanAccepted(this, true)
            AiCliProvisionState.setEnableAiCliInstall(this, enableAiCliInstall)
            AiCliProvisionState.setEnableCustomization(this, enableDebianCustomization)
            AiCliProvisionState.setEnableGui(this, enableXfceGui)
            if (!enableAiCliInstall) {
                AiCliProvisionState.markAiCliProvisioned(this, selectedIsolationMethod, false)
                aiCliSetupOutcome = "SKIPPED"
            }
            showPage(6)
            if (!isDebianBaseSetupStarted) {
                isDebianBaseSetupStarted = true
                runDebianBaseSetup()
            }
        }.apply {
            isEnabled = false
            alpha = 0.45f
        }
        startBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.4f)
        check.setOnCheckedChangeListener { _, isChecked ->
            planAccepted = isChecked
            startBtn.isEnabled = isChecked
            startBtn.alpha = if (isChecked) 1f else 0.45f
        }
        consentRow.setOnClickListener { check.isChecked = !check.isChecked }
        btnRow.addView(prevBtn)
        btnRow.addView(startBtn)
        root.addView(btnRow)

        scroll.addView(root)
        return scroll
    }

    // ── Page 6: Full Environment Setup (progress-first; logs on demand) ────────
    private fun buildDebianBasePage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        root.addView(smallHeader("Environment Setup", R.drawable.ic_storage))

        // Center cluster: progress + log controls vertically in remaining space
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        // Progress card (Cyber-Brutalist sharp 0px) — text centered
        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.BORDER,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(24), dp(28), dp(24), dp(28))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        }

        setupPhaseMetaText = TextView(this).apply {
            text = setupPhaseMetaLabel()
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        progressCard.addView(setupPhaseMetaText)

        setupElapsedText = TextView(this).apply {
            text = formatSetupElapsedLabel()
            textSize = 12f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setPadding(0, 0, 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            contentDescription = "Elapsed install time"
        }
        progressCard.addView(setupElapsedText)

        setupPercentText = TextView(this).apply {
            text = "${setupOverallPercent}%"
            textSize = 48f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
            setPadding(0, 0, 0, dp(20))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            contentDescription = "Setup progress $setupOverallPercent percent"
        }
        progressCard.addView(setupPercentText)

        setupProgressTrack = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_HIGH)
                setStroke(dp(1), NC.BORDER)
                cornerRadius = 0f
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(16)).apply { bottomMargin = dp(20) }
            clipChildren = true
        }
        // Full-width fill; progress driven by scaleX from left (pivotX=0)
        setupProgressFill = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.PRIMARY_CON)
                cornerRadius = 0f
            }
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            pivotX = 0f
            scaleX = (setupAnimatedPercent / 100f).coerceIn(0f, 1f)
            alpha = 1f
        }
        setupProgressTrack.addView(setupProgressFill)
        progressCard.addView(setupProgressTrack)

        setupStepTitleText = TextView(this).apply {
            text = if (setupFinished) {
                "Full environment setup complete"
            } else if (setupFailed) {
                "Setup failed — open log for details"
            } else if (isDebianBaseSetupStarted) {
                currentSetupPhase()?.label ?: "Installing environment…"
            } else {
                "Initializing full environment setup (base + AI CLIs)…"
            }
            textSize = 14f
            setTextColor(if (setupFailed) NC.ERROR else NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        progressCard.addView(setupStepTitleText)

        setupDetailText = TextView(this).apply {
            text = if (setupFinished) "Ready to continue" else "Step progress updates as install runs"
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        progressCard.addView(setupDetailText)
        center.addView(progressCard)

        // Log toggle (secondary, full width) — logs hidden by default
        setupLogToggleLabel = TextView(this).apply {
            text = if (setupLogVisible) "Hide setup log" else "View setup log"
            textSize = 14f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        setupLogToggleBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.PRIMARY_CON,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
            isClickable = true
            isFocusable = true
            contentDescription = "Toggle setup log visibility"
            setOnClickListener { toggleSetupLogPanel() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(4).toFloat()
                        v.translationY = dp(4).toFloat()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                    }
                }
                false
            }
        }
        val logIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_terminal)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) }
        }
        setupLogToggleBtn.addView(logIcon)
        setupLogToggleBtn.addView(setupLogToggleLabel)
        center.addView(setupLogToggleBtn)

        // Log panel (GONE by default) — max height so center cluster stays balanced
        val consoleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.LOGBG,
                strokeColor = NC.BORDER,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(240))
            visibility = if (setupLogVisible) View.VISIBLE else View.GONE
        }
        setupLogPanel = consoleCard
        val consoleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(NC.SURFACE_CONTAINER, NC.BORDER, 0)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val consoleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_terminal)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) }
        }
        val consoleTitle = TextView(this).apply {
            text = "[ SETUP LOG ]"
            textSize = 11f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val consoleClose = TextView(this).apply {
            text = "HIDE"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setOnClickListener { setSetupLogVisible(false) }
        }
        consoleHeader.addView(consoleIcon)
        consoleHeader.addView(consoleTitle)
        consoleHeader.addView(consoleClose)
        consoleCard.addView(consoleHeader)

        baseLogScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }
        baseLogText = TextView(this).apply {
            text = setupLogBuffer.toString()
            textSize = 11f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setLineSpacing(dp(2).toFloat(), 1.2f)
        }
        baseLogScroll.addView(baseLogText)
        consoleCard.addView(baseLogScroll)
        center.addView(consoleCard)

        root.addView(center)

        // Next pinned to bottom
        baseNextBtn = primaryButton("Next: Complete Setup", R.drawable.ic_arrow_right) {
            showPage(7) // complete
        }
        if (setupFinished) {
            baseNextBtn.isEnabled = true
            baseNextBtn.alpha = 1f
        } else {
            baseNextBtn.isEnabled = false
            baseNextBtn.alpha = 0.45f
        }
        root.addView(baseNextBtn)

        // Restore bar width after layout
        applySetupProgressUi()

        return root
    }

    private fun toggleSetupLogPanel() {
        setSetupLogVisible(!setupLogVisible)
    }

    private fun setSetupLogVisible(visible: Boolean) {
        setupLogVisible = visible
        if (::setupLogPanel.isInitialized) {
            setupLogPanel.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (::setupLogToggleLabel.isInitialized) {
            setupLogToggleLabel.text = if (visible) "Hide setup log" else "View setup log"
        }
        if (visible && ::baseLogScroll.isInitialized) {
            baseLogScroll.post { baseLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun prootSetupPhases(): List<SetupPhase> {
        val phases = mutableListOf(
            SetupPhase("A", "Preparing directories…", 3),
            SetupPhase("B", "Extracting bootstrap assets…", 12),
            SetupPhase("C", "Deploying host configs…", 5),
            SetupPhase("D", "Initializing host environment…", 12),
            SetupPhase("E", "Provisioning Debian guest…", 25),
            SetupPhase("F", "Configuring hardware acceleration…", 10)
        )
        if (enableXfceGui) {
            phases.add(SetupPhase("GUI", "Installing XFCE4 desktop…", 20))
        }
        if (enableDebianCustomization) {
            phases.add(SetupPhase("G", "Customizing guest environment…", 8))
        }
        if (enableAiCliInstall) {
            phases.add(SetupPhase("H", "Installing AI CLI tools…", 15))
        }
        return phases
    }

    private fun chrootSetupPhases(): List<SetupPhase> {
        val phases = mutableListOf(
            SetupPhase("R0", "Checking root access…", 2),
            SetupPhase("R1", "Installing Debian chroot base…", 30),
            SetupPhase("E", "Provisioning Debian packages…", 15),
            SetupPhase("F", "Configuring hardware acceleration…", 10)
        )
        if (enableXfceGui) {
            phases.add(SetupPhase("GUI", "Installing XFCE4 desktop…", 20))
        }
        if (enableDebianCustomization) {
            phases.add(SetupPhase("G", "Customizing guest environment…", 8))
        }
        if (enableAiCliInstall) {
            phases.add(SetupPhase("H", "Installing AI CLI tools…", 15))
        }
        return phases
    }

    private fun currentSetupPhase(): SetupPhase? =
        setupPhases.getOrNull(setupPhaseIndex)

    private fun setupPhaseMetaLabel(): String {
        if (setupPhases.isEmpty()) return "SETUP"
        val step = (setupPhaseIndex + 1).coerceAtMost(setupPhases.size)
        return "STEP $step / ${setupPhases.size}"
    }

    private fun beginSetupPhases(method: String) {
        setupFailed = false
        setupFinished = false
        setupPhases = if (method == "chroot") chrootSetupPhases() else prootSetupPhases()
        setupPhaseIndex = 0
        setupPhaseFraction = 0f
        setupOverallPercent = 0
        setupAnimatedPercent = 0f
        setupDisplayedPercentInt = 0
        cancelSetupProgressAnimators()
        startSetupElapsedTimer()
        mainHandler.post {
            applySetupProgressUi(animate = false)
            startProgressPulse()
        }
    }

    private fun startSetupElapsedTimer() {
        setupElapsedStartMs = System.currentTimeMillis()
        setupElapsedFrozenMs = 0L
        setupElapsedRunning = true
        mainHandler.removeCallbacks(setupElapsedTicker)
        mainHandler.post {
            refreshSetupElapsedUi()
            mainHandler.postDelayed(setupElapsedTicker, 1000L)
        }
    }

    private fun stopSetupElapsedTimer() {
        if (setupElapsedRunning && setupElapsedStartMs > 0L) {
            setupElapsedFrozenMs = System.currentTimeMillis() - setupElapsedStartMs
        }
        setupElapsedRunning = false
        mainHandler.removeCallbacks(setupElapsedTicker)
        mainHandler.post { refreshSetupElapsedUi() }
    }

    private fun currentSetupElapsedMs(): Long {
        return when {
            setupElapsedRunning && setupElapsedStartMs > 0L ->
                System.currentTimeMillis() - setupElapsedStartMs
            setupElapsedFrozenMs > 0L -> setupElapsedFrozenMs
            else -> 0L
        }
    }

    private fun formatSetupElapsed(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun formatSetupElapsedLabel(): String {
        val t = formatSetupElapsed(currentSetupElapsedMs())
        return when {
            setupFinished -> "ELAPSED  $t  ·  DONE"
            setupFailed -> "ELAPSED  $t  ·  STOPPED"
            setupElapsedRunning || setupElapsedStartMs > 0L -> "ELAPSED  $t"
            else -> "ELAPSED  00:00"
        }
    }

    private fun refreshSetupElapsedUi() {
        if (!::setupElapsedText.isInitialized) return
        setupElapsedText.text = formatSetupElapsedLabel()
        setupElapsedText.setTextColor(
            when {
                setupFinished -> NC.PRIMARY
                setupFailed -> NC.ERROR
                else -> NC.PRIMARY
            }
        )
    }

    /** Enter a named phase; completed weight = sum of all prior phase weights. */
    private fun enterSetupPhase(phaseId: String, detail: String? = null) {
        val idx = setupPhases.indexOfFirst { it.id == phaseId }
        if (idx < 0) return
        setupPhaseIndex = idx
        setupPhaseFraction = 0.05f
        val phase = setupPhases[idx]
        updateSetupFgs(phase.label)
        mainHandler.post {
            if (::setupStepTitleText.isInitialized) {
                setupStepTitleText.text = phase.label
                setupStepTitleText.setTextColor(NC.ON_SURFACE)
            }
            if (::setupDetailText.isInitialized) {
                setupDetailText.text = detail
                    ?: "Step ${idx + 1} of ${setupPhases.size}"
            }
            recomputeSetupPercent(capAt99 = true)
            applySetupProgressUi()
        }
    }

    private fun setSetupPhaseFraction(fraction: Float, detail: String? = null) {
        setupPhaseFraction = fraction.coerceIn(0f, 1f)
        mainHandler.post {
            if (detail != null && ::setupDetailText.isInitialized) {
                setupDetailText.text = detail
            }
            recomputeSetupPercent(capAt99 = true)
            applySetupProgressUi()
        }
    }

    private fun recomputeSetupPercent(capAt99: Boolean) {
        if (setupFinished) {
            setupOverallPercent = 100
            return
        }
        if (setupPhases.isEmpty()) {
            setupOverallPercent = 0
            return
        }
        val completed = setupPhases.take(setupPhaseIndex).sumOf { it.weight }
        val curW = setupPhases.getOrNull(setupPhaseIndex)?.weight ?: 0
        val raw = completed + curW * setupPhaseFraction
        val total = setupPhases.sumOf { it.weight }.coerceAtLeast(1)
        val pct = ((raw * 100f) / total).toInt()
        setupOverallPercent = if (capAt99) min(99, pct) else pct.coerceIn(0, 100)
    }

    private fun finishSetupProgressSuccess() {
        setupFinished = true
        setupFailed = false
        setupOverallPercent = 100
        setupPhaseFraction = 1f
        stopSetupElapsedTimer()
        stopSetupFgs()
        mainHandler.post {
            stopProgressPulse()
            if (::setupStepTitleText.isInitialized) {
                setupStepTitleText.text = "Full environment setup complete"
                setupStepTitleText.setTextColor(NC.PRIMARY)
            }
            if (::setupDetailText.isInitialized) {
                setupDetailText.text = "Ready to continue"
            }
            applySetupProgressUi(animate = true, onBarSettled = { punchPercentSuccess() })
            if (::baseNextBtn.isInitialized) {
                baseNextBtn.isEnabled = true
                baseNextBtn.alpha = 1f
            }
        }
    }

    private fun failSetupProgress(message: String) {
        setupFailed = true
        stopSetupElapsedTimer()
        stopSetupFgs()
        mainHandler.post {
            stopProgressPulse()
            if (::setupStepTitleText.isInitialized) {
                setupStepTitleText.text = message
                setupStepTitleText.setTextColor(NC.ERROR)
            }
            if (::setupDetailText.isInitialized) {
                setupDetailText.text = "Open setup log for details"
            }
            applySetupProgressUi(animate = true)
        }
    }

    private fun cancelSetupProgressAnimators() {
        progressBarAnimator?.cancel()
        progressBarAnimator = null
        percentTextAnimator?.cancel()
        percentTextAnimator = null
        percentPunchAnimator?.cancel()
        percentPunchAnimator = null
        stopProgressPulse()
    }

    private fun startProgressPulse() {
        if (!::setupProgressFill.isInitialized) return
        if (setupFinished || setupFailed) return
        stopProgressPulse()
        // Soft terminal-green breathe while work runs (not blur — opacity only)
        progressPulseAnimator = ObjectAnimator.ofFloat(setupProgressFill, View.ALPHA, 1f, 0.72f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopProgressPulse() {
        progressPulseAnimator?.cancel()
        progressPulseAnimator = null
        if (::setupProgressFill.isInitialized) {
            setupProgressFill.alpha = 1f
        }
    }

    private fun punchPercentSuccess() {
        if (!::setupPercentText.isInitialized) return
        percentPunchAnimator?.cancel()
        setupPercentText.scaleX = 1f
        setupPercentText.scaleY = 1f
        percentPunchAnimator = ObjectAnimator.ofFloat(setupPercentText, View.SCALE_X, 1f, 1.12f, 1f).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.4f)
            start()
        }
        ObjectAnimator.ofFloat(setupPercentText, View.SCALE_Y, 1f, 1.12f, 1f).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.4f)
            start()
        }
        // Brief fill flash
        if (::setupProgressFill.isInitialized) {
            ObjectAnimator.ofFloat(setupProgressFill, View.ALPHA, 1f, 0.55f, 1f).apply {
                duration = 280
                start()
            }
        }
    }

    /**
     * Apply progress UI. When [animate], bar width (scaleX) and % label ease toward target.
     * Mechanical decelerate curve — matches cyber-brutalist “hardware” feel.
     */
    private fun applySetupProgressUi(
        animate: Boolean = true,
        onBarSettled: (() -> Unit)? = null
    ) {
        if (!::setupPercentText.isInitialized) return
        setupPercentText.contentDescription = "Setup progress $setupOverallPercent percent"
        if (::setupPhaseMetaText.isInitialized) {
            setupPhaseMetaText.text = setupPhaseMetaLabel()
        }

        val target = setupOverallPercent.toFloat().coerceIn(0f, 100f)

        if (!::setupProgressFill.isInitialized) {
            setupPercentText.text = "${setupOverallPercent}%"
            setupDisplayedPercentInt = setupOverallPercent
            setupAnimatedPercent = target
            onBarSettled?.invoke()
            return
        }

        // Ensure scale pivots from left edge of track after layout
        setupProgressTrack.post {
            if (!::setupProgressFill.isInitialized) return@post
            setupProgressFill.pivotX = 0f
            setupProgressFill.pivotY = setupProgressFill.height / 2f
        }

        if (!animate || kotlin.math.abs(target - setupAnimatedPercent) < 0.15f) {
            progressBarAnimator?.cancel()
            percentTextAnimator?.cancel()
            setupAnimatedPercent = target
            setupDisplayedPercentInt = setupOverallPercent
            setupProgressFill.scaleX = (target / 100f).coerceIn(0.001f, 1f).let {
                if (target <= 0f) 0f else it
            }
            setupPercentText.text = "${setupOverallPercent}%"
            if (!setupFinished && !setupFailed) startProgressPulse()
            onBarSettled?.invoke()
            return
        }

        progressBarAnimator?.cancel()
        percentTextAnimator?.cancel()

        val fromBar = setupAnimatedPercent
        val durationMs = (280L + (kotlin.math.abs(target - fromBar) * 8f).toLong()).coerceIn(280L, 720L)

        progressBarAnimator = ValueAnimator.ofFloat(fromBar, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val v = va.animatedValue as Float
                setupAnimatedPercent = v
                if (::setupProgressFill.isInitialized) {
                    setupProgressFill.scaleX = if (v <= 0f) 0f else (v / 100f).coerceIn(0.001f, 1f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setupAnimatedPercent = target
                    if (::setupProgressFill.isInitialized) {
                        setupProgressFill.scaleX = if (target <= 0f) 0f else (target / 100f).coerceIn(0.001f, 1f)
                    }
                    if (!setupFinished && !setupFailed) startProgressPulse()
                    onBarSettled?.invoke()
                }
            })
            start()
        }

        val fromPct = setupDisplayedPercentInt
        val toPct = setupOverallPercent
        percentTextAnimator = ValueAnimator.ofInt(fromPct, toPct).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { va ->
                val n = va.animatedValue as Int
                setupDisplayedPercentInt = n
                if (::setupPercentText.isInitialized) {
                    setupPercentText.text = "$n%"
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setupDisplayedPercentInt = toPct
                    if (::setupPercentText.isInitialized) {
                        setupPercentText.text = "$toPct%"
                    }
                }
            })
            start()
        }

        // Keep pulse alive during progress (restart if cancelled by alpha anim)
        if (!setupFinished && !setupFailed) startProgressPulse()
    }

    override fun onDestroy() {
        setupElapsedRunning = false
        mainHandler.removeCallbacks(setupElapsedTicker)
        cancelSetupProgressAnimators()
        // Keep FGS if install is still running so the process is not killed.
        if (setupFinished || setupFailed || !isDebianBaseSetupStarted) {
            stopSetupFgs()
        }
        super.onDestroy()
    }

    private fun startSetupFgs(detail: String? = null) {
        NativeCodeFgs.ensurePostNotificationsPermission(this)
        val gen = ++setupFgsGeneration
        setupFgsStarted = true
        mainHandler.post { applySetupFgs(gen, detail) }
    }

    private fun updateSetupFgs(detail: String) {
        if (!setupFgsStarted || setupFinished || setupFailed) return
        val gen = setupFgsGeneration
        mainHandler.post { applySetupFgs(gen, detail) }
    }

    private fun applySetupFgs(gen: Int, detail: String?) {
        if (gen != setupFgsGeneration || !setupFgsStarted || setupFinished || setupFailed) return
        NativeCodeFgs.acquire(
            this,
            NativeCodeFgs.OWNER_INSTALL,
            BackgroundService.installIntent(this, detail)
        )
    }

    private fun stopSetupFgs() {
        setupFgsGeneration++
        setupFgsStarted = false
        val release = { NativeCodeFgs.release(this, NativeCodeFgs.OWNER_INSTALL) }
        if (Looper.myLooper() == Looper.getMainLooper()) release()
        else mainHandler.post(release)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NativeCodeFgs.REQ_POST_NOTIFICATIONS) return
        if (!setupFgsStarted || setupFinished || setupFailed) return
        applySetupFgs(setupFgsGeneration, currentSetupPhase()?.label)
    }

    private fun runDebianBaseSetup() {
        startSetupFgs(
            "Installing Debian Linux — setup scripts running"
        )
        // ── CHROOT path (KernelSU / Magisk root required) ────────────────────
        if (selectedIsolationMethod == "chroot") {
            beginSetupPhases("chroot")
            enterSetupPhase("R0", "Verifying KernelSU / Magisk root…")
            updateBaseStatus("[CHROOT] Checking root access...")
            executor.execute {
                val rootOk = RootShell.isRootAvailable()
                if (!rootOk) {
                    updateBaseStatus("[CHROOT] ERROR: Root not available. Grant superuser to NativeCode in KernelSU/Magisk manager, then retry.")
                    failSetupProgress("Root not available — grant superuser, then retry")
                    return@execute
                }
                enterSetupPhase("R1", "Running setup_alpaquita_chroot.sh…")
                updateBaseStatus("[CHROOT] Root confirmed. Running setup_alpaquita_chroot.sh...")
                RootShell.executeScriptAsset(
                    context = this,
                    assetName = "scripts/chroot/setup_alpaquita_chroot.sh",
                    onLine = { line -> appendSetupLog(line) },
                    onDone = { code ->
                        // Proceed if exit 0 OR base marker present (false exit 1 from am start, etc.)
                        val installed = ProjectPathResolver.isChrootInstalled()
                        if (code != 0 && installed) {
                            updateBaseStatus(
                                "[CHROOT] Base exit $code but .flux_configured present — continuing guest setup..."
                            )
                        }
                        if (code == 0 || installed) {
                            setSetupPhaseFraction(1f, "Base chroot installed")
                            updateBaseStatus("[CHROOT] Base complete — starting guest chain (E→H)...")
                            // Step E: Provision Alpaquita packages (runs inside chroot as root)
                            enterSetupPhase("E", "Installing guest packages…")
                            updateBaseStatus("[CHROOT] E. Provisioning Alpaquita packages...")
                            val familyScript = if (assets.list("scripts")?.contains("setup_alpaquita_family.sh") == true)
                                "setup_alpaquita_family.sh" else "setup_alpine_family.sh"
                            copyAndRunInChroot(
                                assetName = "scripts/$familyScript",
                                scriptName = familyScript,
                                cmd = "sh /tmp/$familyScript"
                            ) { codeE ->
                                if (codeE != 0) {
                                    updateBaseStatus("[CHROOT] Distro family setup failed (exit $codeE).")
                                    failSetupProgress("Distro package setup failed (exit $codeE)")
                                    return@copyAndRunInChroot
                                }
                                setSetupPhaseFraction(1f)
                                // Step F: Hardware acceleration (Adreno→turnip, else virgl)
                                val gpuDetect = GpuAccelDetector.detect()
                                enterSetupPhase(
                                    "F",
                                    "${gpuDetect.mode} · ${gpuDetect.vendorHint}"
                                )
                                updateBaseStatus(
                                    "[CHROOT] F. Configuring Hardware Acceleration " +
                                        "(${gpuDetect.mode}, vendor=${gpuDetect.vendorHint})..."
                                )
                                getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
                                    .putString("flux_gpu", gpuDetect.mode)
                                    .putString("flux_gpu_vendor", gpuDetect.vendorHint)
                                    .apply()
                                copyAndRunInChroot(
                                    assetName = "scripts/setup_hw_accel_debian.sh",
                                    scriptName = "setup_hw_accel_debian.sh",
                                    cmd = "env FLUX_GPU=${gpuDetect.mode} bash /tmp/setup_hw_accel_debian.sh"
                                ) { codeF ->
                                    if (codeF != 0) {
                                        updateBaseStatus("[CHROOT] HW accel setup failed (exit $codeF). Continuing...")
                                    }
                                    setSetupPhaseFraction(1f)
                                    fun afterChrootCustom() {
                                        maybeRunCliToolsSetupChroot { finishChrootBaseSetup() }
                                    }
                                    fun runChrootCustomization() {
                                        if (enableDebianCustomization) {
                                            enterSetupPhase("G", "Applying shell polish…")
                                            updateBaseStatus("[CHROOT] G. Customizing Guest Environment...")
                                            copyAndRunInChroot(
                                                assetName = "scripts/setup_customization_alpine.sh",
                                                scriptName = "setup_customization_alpine.sh",
                                                cmd = "sh /tmp/setup_customization_alpine.sh"
                                            ) { codeG ->
                                                if (codeG != 0) {
                                                    updateBaseStatus("[CHROOT] Customization failed (exit $codeG). Continuing...")
                                                }
                                                setSetupPhaseFraction(1f)
                                                afterChrootCustom()
                                            }
                                        } else {
                                            updateBaseStatus("[CHROOT] G. Skipping Guest Customization (Toggle Off)...")
                                            afterChrootCustom()
                                        }
                                    }
                                    if (enableXfceGui) {
                                        enterSetupPhase("GUI", "Installing XFCE4 desktop…")
                                        updateBaseStatus("[CHROOT] GUI. Provisioning XFCE4 Desktop...")
                                        copyAndRunInChroot(
                                            assetName = "scripts/setup_xfce4.sh",
                                            scriptName = "setup_xfce4.sh",
                                            cmd = "env FLUX_THEME=dark bash /tmp/setup_xfce4.sh"
                                        ) { codeGui ->
                                            if (codeGui != 0) {
                                                updateBaseStatus("[CHROOT] XFCE4 setup failed (exit $codeGui). Continuing...")
                                            }
                                            setSetupPhaseFraction(1f)
                                            runChrootCustomization()
                                        }
                                    } else {
                                        updateBaseStatus("[CHROOT] GUI. Skipping XFCE4 Desktop (Toggle Off)...")
                                        runChrootCustomization()
                                    }
                                }
                            }
                        } else {
                            updateBaseStatus("[CHROOT] Setup failed with exit code $code. Check logs above.")
                            failSetupProgress("Chroot setup failed (exit $code)")
                        }
                    }
                )
            }
            return
        }

        // ── PROOT path (default, rootless) ────────────────────────────────────
        beginSetupPhases("proot")
        executor.execute {
            try {
                enterSetupPhase("A", "Creating prefix directories…")
                updateBaseStatus("A. Preparing Directories...")
                val usrDir = File(filesDir, "usr")
                val tmpDir = File(usrDir, "tmp")
                val etcDir = File(usrDir, "etc")
                val varDir = File(usrDir, "var")
                val homeDir = File(filesDir, "home")
                tmpDir.mkdirs(); etcDir.mkdirs(); homeDir.mkdirs()
                File(varDir, "log/apt").mkdirs()
                File(varDir, "lib/dpkg").mkdirs()

                enterSetupPhase("B", "Unpacking bootstrap.tar…")
                updateBaseStatus("B. Extracting Bootstrap Assets...")
                val tarFile = File(filesDir, "bootstrap.tar")
                if (!tarFile.exists()) {
                    copyAssetWithProgress("bootstrap.tar", tarFile) { frac, detail ->
                        setSetupPhaseFraction(frac * 0.5f, detail)
                    }
                } else {
                    setSetupPhaseFraction(0.5f, "bootstrap.tar ready")
                }

                val tarBin = if (File("/system/bin/tar").canExecute()) "/system/bin/tar" else "tar"
                var tarExit = -1
                try {
                    val tarProcess = Runtime.getRuntime().exec(
                        arrayOf(tarBin, "-xf", tarFile.absolutePath, "-C", filesDir.absolutePath)
                    )
                    val err = try { tarProcess.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
                    try { tarProcess.inputStream.close() } catch (_: Exception) {}
                    tarExit = tarProcess.waitFor()
                    if (err.isNotBlank()) appendSetupLog(">>> tar stderr: ${err.take(2000)}\n")
                    appendSetupLog(">>> tar exit=$tarExit\n")
                } catch (e: Exception) {
                    appendSetupLog(">>> tar exec failed: ${e.message}\n")
                }
                val bashProbe = File(filesDir, "usr/bin/bash")
                if (tarExit != 0 || !bashProbe.isFile) {
                    // Fallback to stream extraction if shell tar command fails
                    appendSetupLog(">>> fallback extractTarStream (tarExit=$tarExit bash=${bashProbe.exists()})\n")
                    assets.open("bootstrap.tar").use { input ->
                        extractTarStream(input, filesDir)
                    }
                }
                setSetupPhaseFraction(1f, "Bootstrap extracted")
                if (tarFile.exists()) tarFile.delete()

                val nested = File(filesDir, "data/data/com.zenithblue.nativecode/files")
                if (nested.exists()) {
                    moveDirectoryContents(nested, filesDir)
                    File(filesDir, "data").deleteRecursively()
                }

                val loginInitPy = File(filesDir, "usr/lib/python3.14/site-packages/proot_distro/commands/login/__init__.py")
                if (loginInitPy.exists()) {
                    var content = loginInitPy.readText()
                    content = content.replace(
                        "\"PROOT_NO_SECCOMP\", \"PROOT_VERBOSE\"",
                        "\"PROOT_NO_SECCOMP\", \"PROOT_VERBOSE\", \"PROOT_LOADER\""
                    )
                    loginInitPy.writeText(content)
                }

                // SSOT: rewrite residual com.termux paths in bootstrap text files
                TermuxHostPaths.applyPackageToExtractedPrefix(filesDir)

                File(varDir, "log/apt").mkdirs(); File(varDir, "lib/dpkg").mkdirs(); tmpDir.mkdirs()

                enterSetupPhase("C", "Scripts + DNS…")
                updateBaseStatus("C. Deploying Host Configs...")
                deployScripts()
                File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
                setSetupPhaseFraction(1f)

                enterSetupPhase("D", "Running setup_termux.sh…")
                updateBaseStatus("D. Initializing Host Environment...")
                // Always re-validate host deps after bootstrap; clear stale marker
                HostCommandBuilder.clearSetupMarker(this@OnboardingActivity)
                val bash = TermuxHostPaths.libBash(this@OnboardingActivity).absolutePath
                check(
                    runShellCommand(
                        arrayOf(bash, File(homeDir, "setup_termux.sh").absolutePath),
                        forceHostSetup = true
                    ) == 0
                ) { "Host setup failed" }
                setSetupPhaseFraction(1f)

                enterSetupPhase("E", "Alpaquita rootfs + packages…")
                updateBaseStatus("E. Provisioning Alpaquita Guest Container...")
                val outZst = File(homeDir, "alpaquita_rootfs.tar.zst")
                val outGz = File(homeDir, "alpine_rootfs.tar.gz")
                val outTar = File(homeDir, "alpine_rootfs.tar")
                val hasAlpaquitaAsset = assets.list("rootfs")?.contains("alpaquita_rootfs.tar.zst") == true
                val distroCandidate = if (hasAlpaquitaAsset || outZst.exists()) "alpaquita" else "alpine"

                if (distroCandidate == "alpaquita") {
                    if (!outZst.isFile || outZst.length() < 2L * 1024L * 1024L) {
                        assets.open("rootfs/alpaquita_rootfs.tar.zst").use { input ->
                            FileOutputStream(outZst).use { output ->
                                input.copyTo(output, 1024 * 1024)
                            }
                        }
                        Log.i("Onboarding", "Explicitly deployed alpaquita rootfs: ${outZst.absolutePath} (${outZst.length()} bytes)")
                    }
                    val outZstSha = File(homeDir, "alpaquita_rootfs.tar.zst.sha256")
                    if (assets.list("rootfs")?.contains("alpaquita_rootfs.tar.zst.sha256") == true) {
                        assets.open("rootfs/alpaquita_rootfs.tar.zst.sha256").use { input ->
                            FileOutputStream(outZstSha).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } else {
                    val hasAlpineRootfs = (outGz.isFile && outGz.length() > 2L * 1024L * 1024L) ||
                        (outTar.isFile && outTar.length() > 2L * 1024L * 1024L)
                    if (!hasAlpineRootfs) {
                        val assetName = if (assets.list("rootfs")?.contains("alpine_rootfs.tar.gz") == true)
                            "rootfs/alpine_rootfs.tar.gz" else "rootfs/alpine_rootfs.tar"
                        val target = if (assetName.endsWith(".tar.gz")) outGz else outTar
                        assets.open(assetName).use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output, 1024 * 1024)
                            }
                        }
                        Log.i("Onboarding", "Explicitly deployed alpine fallback rootfs: ${target.absolutePath} (${target.length()} bytes)")
                    }
                }

                val familyScript = if (distroCandidate == "alpaquita" && assets.list("scripts")?.contains("setup_alpaquita_family.sh") == true)
                    "setup_alpaquita_family.sh" else "setup_alpine_family.sh"
                val debBytes = assets.open("scripts/$familyScript").use { it.readBytes() }
                val debPayload = Base64.encodeToString(debBytes, Base64.NO_WRAP)
                check(runShellCommand(arrayOf(bash, File(homeDir, "flux_install.sh").absolutePath, distroCandidate, debPayload)) == 0) { "Guest install failed for $distroCandidate" }
                setSetupPhaseFraction(1f)

                val gpuDetect = GpuAccelDetector.detect()
                enterSetupPhase("F", "${gpuDetect.mode} · ${gpuDetect.vendorHint}")
                updateBaseStatus(
                    "F. Configuring Hardware Accel " +
                        "(${gpuDetect.mode}, vendor=${gpuDetect.vendorHint})..."
                )
                getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit()
                    .putString("flux_gpu", gpuDetect.mode)
                    .putString("flux_gpu_vendor", gpuDetect.vendorHint)
                    .apply()
                val hwScript = File(File(usrDir, "tmp"), "setup_hw_accel_debian.sh")
                assets.open("scripts/setup_hw_accel_debian.sh").use { input -> FileOutputStream(hwScript).use { input.copyTo(it) } }
                hwScript.setExecutable(true)
                check(runShellCommand(arrayOf(
                    TermuxHostPaths.BIN + "/python",
                    TermuxHostPaths.PROOT_DISTRO,
                    "login", distroCandidate, "--shared-tmp", "--",
                    "env", "FLUX_GPU=${gpuDetect.mode}", "bash", "/tmp/setup_hw_accel_debian.sh"
                )) == 0) { "GPU setup failed" }
                setSetupPhaseFraction(1f)

                if (enableXfceGui) {
                    enterSetupPhase("GUI", "Installing XFCE4 desktop…")
                    updateBaseStatus("GUI. Provisioning XFCE4 Desktop...")
                    val guiScript = File(File(usrDir, "tmp"), "setup_xfce4.sh")
                    assets.open("scripts/setup_xfce4.sh").use { input -> FileOutputStream(guiScript).use { input.copyTo(it) } }
                    guiScript.setExecutable(true)
                    check(runShellCommand(arrayOf(
                        TermuxHostPaths.BIN + "/python",
                        TermuxHostPaths.PROOT_DISTRO,
                        "login", distroCandidate, "--shared-tmp", "--",
                        "env", "FLUX_THEME=dark", "bash", "/tmp/setup_xfce4.sh"
                    )) == 0) { "XFCE4 setup failed" }
                    setSetupPhaseFraction(1f)
                } else {
                    updateBaseStatus("GUI. Skipping XFCE4 Desktop (Toggle Off)...")
                }

                if (enableDebianCustomization) {
                    enterSetupPhase("G", "Applying shell polish…")
                    updateBaseStatus("G. Customizing Guest Environment...")
                    val customScript = File(File(usrDir, "tmp"), "setup_customization_alpine.sh")
                    assets.open("scripts/setup_customization_alpine.sh").use { input -> FileOutputStream(customScript).use { input.copyTo(it) } }
                    customScript.setExecutable(true)
                    check(runShellCommand(arrayOf(
                        TermuxHostPaths.BIN + "/python",
                        TermuxHostPaths.PROOT_DISTRO,
                        "login", distroCandidate, "--shared-tmp", "--",
                        "sh", "/tmp/setup_customization_alpine.sh"
                    )) == 0) { "Customization failed" }
                    setSetupPhaseFraction(1f)
                } else {
                    updateBaseStatus("G. Skipping Guest Customization (Toggle Off)...")
                }

                // Step H: AI CLI tools only if opted in on plan page (default OFF)
                maybeRunCliToolsSetupProot()

                // Persist linux_method = proot
                getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
                    .edit().putString("linux_method", "proot").apply()
                updateBaseStatus("Full Environment Setup Successful!")
                finishSetupProgressSuccess()
            } catch (e: Exception) {
                Log.e("Onboarding", "Debian Base Setup failed", e)
                updateBaseStatus("Error: ${e.message}")
                failSetupProgress("Error: ${e.message}")
            }
        }
    }

    /** Copy asset to file with approximate byte progress for setup UI. */
    private fun copyAssetWithProgress(
        assetName: String,
        dest: File,
        onProgress: (fraction: Float, detail: String) -> Unit
    ) = OnboardingIo.copyAssetWithProgress(assets, assetName, dest, onProgress)

    private fun formatBytes(bytes: Long): String = OnboardingIo.formatBytes(bytes)

    private fun moveDirectoryContents(source: File, destination: File) =
        OnboardingIo.moveDirectoryContents(source, destination)

    private fun extractTarStream(inputStream: InputStream, targetDir: File) =
        OnboardingIo.extractTarStream(inputStream, targetDir, ::appendSetupLog)

    private fun skipBytes(inputStream: InputStream, count: Long) =
        OnboardingIo.skipBytes(inputStream, count)

    private fun updateBaseStatus(msg: String) {
        // Phase title stays under enterSetupPhase / failSetupProgress.
        // All status strings still go into the on-demand log.
        appendSetupLog(">>> $msg\n")
    }

    private fun appendSetupLog(chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(setupLogBuffer) {
            setupLogBuffer.append(chunk)
            // Cap ~200k chars to avoid OOM on huge apt streams
            val max = 200_000
            if (setupLogBuffer.length > max) {
                setupLogBuffer.delete(0, setupLogBuffer.length - max)
            }
        }
        mainHandler.post {
            if (!::baseLogText.isInitialized) return@post
            baseLogText.append(chunk)
            // Keep TextView from growing without bound if buffer was trimmed
            val text = baseLogText.text
            if (text != null && text.length > 220_000) {
                baseLogText.text = text.subSequence(text.length - 200_000, text.length)
            }
            if (setupLogVisible && ::baseLogScroll.isInitialized) {
                baseLogScroll.post { baseLogScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // ── AI CLI tools (gated; shared CliToolsInstaller) ─────────────────────────

    private fun maybeRunCliToolsSetupProot() {
        if (!enableAiCliInstall) {
            updateBaseStatus("H. Skipping AI CLI tools (not selected on install plan).")
            AiCliProvisionState.markAiCliProvisioned(this, "proot", false)
            aiCliSetupOutcome = "SKIPPED"
            return
        }
        enterSetupPhase("H", "NVM, Node, opencode, codex…")
        updateBaseStatus("H. Installing AI CLI tools (NVM, Node, opencode, codex, claude, …)...")
        val cliCode = CliToolsInstaller.runProotSync(this) { chunk -> appendSetupLog(chunk) }
        if (cliCode == 0) {
            updateBaseStatus("H. AI CLI tools provisioned successfully.")
            AiCliProvisionState.markAiCliProvisioned(this, "proot", true)
            aiCliSetupOutcome = "READY"
        } else {
            updateBaseStatus(
                "H. AI CLI tools finished with exit $cliCode " +
                    "(continuing; install later via Settings → AI CLI tools)."
            )
            AiCliProvisionState.markAiCliProvisioned(this, "proot", false)
            aiCliSetupOutcome = "PARTIAL"
        }
        setSetupPhaseFraction(1f)
    }

    private fun maybeRunCliToolsSetupChroot(onDone: () -> Unit) {
        if (!enableAiCliInstall) {
            updateBaseStatus("[CHROOT] H. Skipping AI CLI tools (not selected on install plan).")
            AiCliProvisionState.markAiCliProvisioned(this, "chroot", false)
            aiCliSetupOutcome = "SKIPPED"
            onDone()
            return
        }
        enterSetupPhase("H", "NVM, Node, opencode, codex…")
        updateBaseStatus("[CHROOT] H. Installing AI CLI tools (NVM, Node, opencode, codex, claude, …)...")
        CliToolsInstaller.runChrootAsync(
            ctx = this,
            onLine = { chunk -> appendSetupLog(chunk) },
            onDone = { code ->
                if (code == 0) {
                    updateBaseStatus("[CHROOT] H. AI CLI tools provisioned successfully.")
                    AiCliProvisionState.markAiCliProvisioned(this, "chroot", true)
                    aiCliSetupOutcome = "READY"
                } else {
                    updateBaseStatus(
                        "[CHROOT] H. AI CLI tools finished with exit $code " +
                            "(continuing; install later via Settings → AI CLI tools)."
                    )
                    AiCliProvisionState.markAiCliProvisioned(this, "chroot", false)
                    aiCliSetupOutcome = "PARTIAL"
                }
                setSetupPhaseFraction(1f)
                onDone()
            }
        )
    }

    // ── Page 7: Complete ──────────────────────────────────────────────────────
    private fun buildCompletePage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        val spacer1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) }
        root.addView(spacer1)

        val logoCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.PRIMARY,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(168)).apply {
                bottomMargin = dp(24)
            }
        }
        val logoIv = ImageView(this).apply {
            setImageResource(R.drawable.logo_highres)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(128), dp(128))
            contentDescription = "NativeCode logo"
        }
        logoCard.addView(logoIv)
        root.addView(logoCard)

        val title = TextView(this).apply {
            text = "Setup Successful!"
            textSize = 28f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(title)

        val subtitleText = when (aiCliSetupOutcome) {
            "READY" -> "Linux container & AI CLI tools provisioned"
            "PARTIAL" -> "Linux container ready · AI tools incomplete"
            else -> "Linux container ready · AI tools skipped"
        }
        val subtitle = TextView(this).apply {
            text = subtitleText
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(subtitle)

        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cyberBrutalistBg(
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.BORDER,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        }

        val summaryHeader = TextView(this).apply {
            text = "[ PROVISIONED COMPONENTS ]"
            textSize = 11f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
            setPadding(0, 0, 0, dp(14))
        }
        summaryCard.addView(summaryHeader)

        summaryCard.addView(summaryRow(R.drawable.ic_terminal, "Guest OS", "Debian 13 (Trixie)", "READY"))
        summaryCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(10)) })

        val runtimeBadge = when (aiCliSetupOutcome) {
            "READY" -> "READY"
            "PARTIAL" -> "PARTIAL"
            else -> "SKIPPED"
        }
        val runtimeDetail = when (aiCliSetupOutcome) {
            "READY" -> "Node.js / NVM"
            "PARTIAL" -> "Install incomplete"
            else -> "Not installed (optional)"
        }
        summaryCard.addView(summaryRow(R.drawable.ic_laptop, "Dev Runtime", runtimeDetail, runtimeBadge))
        summaryCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(10)) })

        val aiDetail = when (aiCliSetupOutcome) {
            "READY" -> "CLI suite installed"
            "PARTIAL" -> "Retry in Settings → AI CLI tools"
            else -> "Off — shells only"
        }
        summaryCard.addView(summaryRow(R.drawable.ic_smart_toy, "AI Tools", aiDetail, runtimeBadge))
        root.addView(summaryCard)

        val spacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) }
        root.addView(spacer2)

        File(filesDir, "setup_complete").createNewFile()
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE).edit().putBoolean("onboarding_completed", true).apply()

        val btn = primaryButton("Launch Environment", R.drawable.ic_arrow_right) {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
        root.addView(btn)

        return root
    }

    private fun summaryRow(iconRes: Int, labelStr: String, detailStr: String, badgeStr: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) }
        }
        val labelTv = TextView(this).apply {
            text = "$labelStr: "
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
        }
        val detailTv = TextView(this).apply {
            text = detailStr
            textSize = 12f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val badge = textBadge(badgeStr, NC.PRIMARY_CON, NC.ON_PRIMARY_CON)

        row.addView(icon)
        row.addView(labelTv)
        row.addView(detailTv)
        row.addView(badge)
        return row
    }

    // ── Helper execution scripts ──────────────────────────────────────────────

    /** Persist linux_method=chroot, unlock Next button after all chroot setup steps finish. */
    private fun finishChrootBaseSetup() {
        getSharedPreferences("nativecode_prefs", MODE_PRIVATE)
            .edit().putString("linux_method", "chroot").apply()
        updateBaseStatus("[CHROOT] Full environment setup complete! linux_method=chroot saved.")
        finishSetupProgressSuccess()
    }

    /**
     * Copy an asset script into the chroot /tmp, make it executable, then run it
     * inside the chroot as root. [onDone] is called on the main thread with the exit code.
     */
    private fun copyAndRunInChroot(
        assetName: String,
        scriptName: String,
        cmd: String,
        onDone: (Int) -> Unit
    ) {
        executor.execute {
            // 1. Write asset into app-private staging dir (app has write access here)
            val stageDir = File(filesDir, "staged_scripts").also { it.mkdirs() }
            val staged = File(stageDir, scriptName)
            try {
                assets.open(assetName).use { input ->
                    java.io.FileOutputStream(staged).use { input.copyTo(it) }
                }
                staged.setReadable(true, false)
            } catch (e: Exception) {
                Log.e("Onboarding", "copyAndRunInChroot stage failed: ${e.message}")
                updateBaseStatus("[CHROOT] Error staging $scriptName: ${e.message}")
                mainHandler.post { onDone(-1) }
                return@execute
            }

            // 2. As root: mkdir chroot/tmp, cp staged script in, chmod +x, then run
            val chrootTmpPath = "/data/local/tmp/chrootAlpine/tmp"
            val copyCmd = "mkdir -p $chrootTmpPath && cp ${staged.absolutePath} $chrootTmpPath/$scriptName && chmod +x $chrootTmpPath/$scriptName"
            val copyCode = RootShell.executeSync(copyCmd)
            if (copyCode != 0) {
                updateBaseStatus("[CHROOT] Error copying $scriptName into chroot (exit $copyCode).")
                mainHandler.post { onDone(-1) }
                return@execute
            }

            // 3. Run inside chroot (stage SSOT helper from assets if needed)
            RootShell.executeInChroot(
                cmd = cmd,
                user = "root",
                onLine = { line -> appendSetupLog(line + "\n") },
                onDone = onDone,
                context = this
            )
        }
    }

    private fun deployScripts() {
        try {
            // SSOT host env + residual bootstrap path rewrite (safe if no usr yet)
            TermuxHostPaths.applyPackageToExtractedPrefix(filesDir)
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
                "setup_cli_tools.sh",
                "setup_alpaquita_family.sh",
                "setup_alpine_family.sh",
                "setup_hw_accel_debian.sh",
                "setup_xfce4.sh",
                "setup_customization_alpine.sh",
                "setup_alpaquita_chroot.sh",
                "setup_alpine_chroot.sh",
                "setup_debian13_chroot.sh",
                "uninstall_alpine_chroot.sh",
                "uninstall_debian13_chroot.sh",
                "nativecode_chroot.sh"
            )
            for (script in scripts) {
                val assetPath = when {
                    script == "setup_alpaquita_chroot.sh" ||
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
                    Log.w("Onboarding", "Script $assetPath not found in assets", e)
                }
            }
            // Deploy font.ttf
            val termuxDir = File(homeDir, ".termux").also { it.mkdirs() }
            val fontOut = File(termuxDir, "font.ttf")
            assets.open("fonts/font.ttf").use { input -> FileOutputStream(fontOut).use { input.copyTo(it) } }
            // Pinned Alpine rootfs for proot-distro install ./file --name alpine
            deployRootfsArchive(homeDir)
            // Bundled XFCE theme/icon/cursor/wallpaper archives → shared tmp
            // (guest sees them at /tmp/flux_xfce_assets via --shared-tmp).
            deployXfceAssets()
        } catch (e: Exception) {
            Log.e("Onboarding", "Failed to deploy scripts", e)
        }
    }

    /** Asset name → filename the guest scripts look up in /tmp/flux_xfce_assets. */
    private val xfceBundledAssets = mapOf(
        "Space-transparency.tar.xz" to "Space-transparency.tar.xz",
        "Space-light.tar.xz" to "Space-light.tar.xz",
        "01-Vimix-cursors.tar.xz" to "01-Vimix-cursors.tar.xz",
        "02-Vimix-white-cursors.tar.xz" to "02-Vimix-white-cursors.tar.xz",
        "papirus-dark-only.tar" to "papirus-dark-only.tar",
        "fluxlinux-dark.png" to "fluxlinux-dark.png",
        "fluxlinux-light.png" to "fluxlinux-light.png"
    )

    private fun openXfceAsset(name: String): InputStream {
        return try {
            assets.open("xfce/$name")
        } catch (_: Exception) {
            // AGP may store .tar.gz assets gunzipped as .tar
            assets.open("xfce/${name.removeSuffix(".gz")}")
        }
    }

    private fun deployXfceAssets() {
        val destDir = File(File(filesDir, "usr/tmp"), "flux_xfce_assets").also { it.mkdirs() }
        for ((assetName, guestName) in xfceBundledAssets) {
            try {
                val out = File(destDir, guestName)
                if (out.isFile && out.length() > 0) continue
                openXfceAsset(assetName).use { input ->
                    FileOutputStream(out).use { input.copyTo(it) }
                }
                Log.i("Onboarding", "Deployed xfce asset: ${out.absolutePath} (${out.length()} bytes)")
            } catch (e: Exception) {
                Log.e("Onboarding", "Failed to deploy xfce asset $assetName", e)
            }
        }
    }

    /** Copy assets/rootfs/alpaquita_rootfs.tar.zst → $HOME by streaming (skip if already present & large). */
    private fun deployRootfsArchive(homeDir: File) {
        val outZst = File(homeDir, "alpaquita_rootfs.tar.zst")
        val outGz = File(homeDir, "alpine_rootfs.tar.gz")
        val outTar = File(homeDir, "alpine_rootfs.tar")
        val hasAlpaquitaAsset = assets.list("rootfs")?.contains("alpaquita_rootfs.tar.zst") == true

        if (hasAlpaquitaAsset) {
            val outZstSha = File(homeDir, "alpaquita_rootfs.tar.zst.sha256")
            if (assets.list("rootfs")?.contains("alpaquita_rootfs.tar.zst.sha256") == true) {
                try {
                    assets.open("rootfs/alpaquita_rootfs.tar.zst.sha256").use { input ->
                        FileOutputStream(outZstSha).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Onboarding", "Failed to deploy Alpaquita sha256", e)
                }
            }
            if (outZst.isFile && outZst.length() > 2L * 1024L * 1024L) {
                Log.i("Onboarding", "Alpaquita rootfs already deployed: ${outZst.absolutePath}")
                return
            }
            try {
                assets.open("rootfs/alpaquita_rootfs.tar.zst").use { input ->
                    FileOutputStream(outZst).use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                Log.i("Onboarding", "Streamed Alpaquita rootfs: ${outZst.absolutePath} (${outZst.length()} bytes)")
            } catch (e: Exception) {
                Log.e("Onboarding", "Failed to stream Alpaquita rootfs archive from assets", e)
            }
        } else {
            if ((outGz.isFile && outGz.length() > 2L * 1024L * 1024L) ||
                (outTar.isFile && outTar.length() > 2L * 1024L * 1024L)) {
                Log.i("Onboarding", "Alpine fallback rootfs already deployed")
                return
            }
            try {
                val assetName = if (assets.list("rootfs")?.contains("alpine_rootfs.tar.gz") == true)
                    "rootfs/alpine_rootfs.tar.gz" else "rootfs/alpine_rootfs.tar"
                val target = if (assetName.endsWith(".tar.gz")) outGz else outTar
                assets.open(assetName).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                Log.i("Onboarding", "Streamed Alpine fallback rootfs: ${target.absolutePath} (${target.length()} bytes)")
            } catch (e: Exception) {
                Log.e("Onboarding", "Failed to stream Alpine fallback rootfs archive from assets", e)
            }
        }
    }

    private fun runShellCommand(
        cmd: Array<String>,
        forceHostSetup: Boolean = false
    ): Int {
        val exe = cmd.firstOrNull() ?: ""
        val underAppData = exe.startsWith("/data/data/") || exe.startsWith("/data/app/") || exe.startsWith("/data/user/")
        val adjusted = if (underAppData && exe != "/system/bin/linker64" && !exe.startsWith("/system/"))
            arrayOf("/system/bin/linker64") + cmd else cmd
        val pb = ProcessBuilder(*adjusted)
        HostCommandBuilder.applyTo(this, pb, forceHostSetup = forceHostSetup)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val buf = ByteArray(1024)
        val stream = proc.inputStream
        var read: Int
        while (stream.read(buf).also { read = it } != -1) {
            appendSetupLog(String(buf, 0, read))
        }
        return proc.waitFor()
    }

    // ── Helper UI components ──────────────────────────────────────────────────

    private fun smallHeader(title: String, iconRes: Int = R.drawable.ic_terminal): LinearLayout =
        OnboardingUiKit.smallHeader(this, title, iconRes)

    private fun primaryButton(label: String, iconRes: Int? = null, onClick: () -> Unit): LinearLayout =
        OnboardingUiKit.primaryButton(this, label, iconRes, onClick)

    private fun secondaryButton(label: String, onClick: () -> Unit): LinearLayout =
        OnboardingUiKit.secondaryButton(this, label, onClick)

    private fun featureBadge(iconRes: Int, textStr: String): LinearLayout =
        OnboardingUiKit.featureBadge(this, iconRes, textStr)

    private fun cyberBrutalistBg(
        fillColor: Int,
        strokeColor: Int = NC.BORDER,
        shadowColor: Int = NC.SURFACE_BRIGHT,
        offsetDp: Int = 6,
        cornerRadiusDp: Int = 0,
        rightFaceColor: Int = NC.OUTLINE_VAR
    ): LayerDrawable = OnboardingUiKit.cyberBrutalistBg(
        this, fillColor, strokeColor, shadowColor, offsetDp, cornerRadiusDp, rightFaceColor
    )

    private fun dp(v: Int) = OnboardingUiKit.dp(this, v)
    private fun roundedBg(fill: Int, stroke: Int, r: Int) = OnboardingUiKit.roundedBg(this, fill, stroke, r)
    private fun textBadge(text: String, bg: Int, fg: Int) = OnboardingUiKit.textBadge(this, text, bg, fg)

    private fun pulseView(v: View) = OnboardingUiKit.pulseView(v)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
