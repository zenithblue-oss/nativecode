package com.zenithblue.nativecode.ssh.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.editor.showThemed
import com.zenithblue.nativecode.ssh.RemoteHosts
import com.zenithblue.nativecode.ssh.SshHost
import com.zenithblue.nativecode.ssh.SshModel
import com.zenithblue.nativecode.ssh.SshServerController
import com.zenithblue.nativecode.ssh.SshState
import java.util.concurrent.Executors

/** What the page needs from the activity. */
internal interface SshHostApi {
    /** True when the SSH server may be started; shows the Upgrade sheet and returns false for Free users. */
    fun serverAllowed(): Boolean
    fun isPro(): Boolean
    /** Opens a terminal tab that runs [command] (an `ssh ...` line) and titles it [title]. */
    fun connect(title: String, command: String)
    fun openDocument(onResult: (Uri?) -> Unit)
    fun requestNotifications()
}

/**
 * SSH page (plan 17): an OpenSSH install banner, the SSH server (Pro), authorized keys, saved remote hosts and the
 * client keypairs (Free). Private keys are never shown, read or logged; only public keys reach the screen.
 */
internal class SshPage(private val ctx: Context, private val host: SshHostApi) {
    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var busy = false
    private val onServer: () -> Unit = { ui.post { renderServer() } }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(ctx).apply {
        textSize = size
        setTextColor(color)
        if (bold) paint.isFakeBoldText = true
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun button(label: String, primary: Boolean, danger: Boolean = false) = TextView(ctx).apply {
        text = label
        textSize = 13f
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        val fg = if (primary) Color.parseColor("#0A0A0A") else if (danger) NC.ERROR else NC.PRIMARY
        setTextColor(fg)
        background = GradientDrawable().apply {
            setColor(if (primary) NC.PRIMARY_CON else Color.TRANSPARENT)
            setStroke(dp(1), if (primary) NC.PRIMARY_CON else if (danger) NC.ERROR else NC.OUTLINE_VAR)
        }
        minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        isFocusable = true
    }

    private fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun clip(label: String, value: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    // Banner
    private val bannerCard = card()
    private val bannerTv = text(12f, NC.ON_SURFACE)
    private val installBtn = button("INSTALL OPENSSH", true)

    // Server
    private val serverCard = card()
    private val statusTv = text(14f, NC.PRIMARY, bold = true, mono = true)
    private val addrTv = text(13f, NC.ON_SURFACE, mono = true).apply { setTextIsSelectable(true) }
    private val copyCmdBtn = button("COPY COMMAND", false)
    private val fpTv = text(11f, NC.ON_SURF_VAR, mono = true).apply { setTextIsSelectable(true) }
    private val errTv = text(12f, NC.ERROR)
    private val logTv = text(10f, NC.ON_SURF_VAR, mono = true)
    private val toggleBtn = button("START SERVER", true)
    private val keepCb = CheckBox(ctx).apply {
        text = "Keep running when Wi-Fi is lost"
        setTextColor(NC.ON_SURF_VAR)
        textSize = 12f
        minHeight = dp(48)
    }

    // Keys / hosts / client keys
    private val authList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val keyInput = EditText(ctx).apply {
        hint = "ssh-ed25519 AAAA... name"
        setHintTextColor(NC.OUTLINE)
        setTextColor(NC.ON_SURFACE)
        textSize = 12f
        typeface = Typeface.MONOSPACE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 2
        background = GradientDrawable().apply { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.OUTLINE_VAR) }
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }
    private val hostsList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val myKeysList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { SshServerController.addListener(onServer) }
            override fun onViewDetachedFromWindow(v: View) { SshServerController.removeListener(onServer) }
        })

        bannerCard.addView(text(15f, NC.TERTIARY, bold = true, mono = true).apply { text = "OPENSSH NOT INSTALLED" })
        bannerTv.text = "The SSH server, the ssh client and key generation need OpenSSH in your Linux. It is installed with apk (a few MB, needs internet)."
        bannerTv.setPadding(0, dp(4), 0, dp(12))
        bannerCard.addView(bannerTv)
        bannerCard.addView(installBtn, lp())
        content.addView(bannerCard)
        installBtn.setOnClickListener { install() }

        serverCard.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = "SSH SERVER  ·  PRO" })
        serverCard.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = "Log in to this phone's Linux from another computer on the same Wi-Fi. Key login only: password login is off. Off by default and never started at boot."
            setPadding(0, dp(4), 0, dp(12))
        })
        serverCard.addView(statusTv)
        serverCard.addView(addrTv, lp(8))
        serverCard.addView(copyCmdBtn, lp(8))
        serverCard.addView(fpTv, lp(10))
        serverCard.addView(errTv, lp(8))
        serverCard.addView(logTv, lp(8))
        serverCard.addView(keepCb, lp(8))
        serverCard.addView(toggleBtn, lp(8))
        content.addView(serverCard)
        toggleBtn.setOnClickListener { toggle() }
        copyCmdBtn.setOnClickListener { addrTv.text?.toString()?.takeIf { it.startsWith("ssh ") }?.let { clip("ssh command", it) } }
        keepCb.setOnCheckedChangeListener { _, on -> SshServerController.setKeepRunning(ctx, on) }

        val auth = card()
        auth.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = "AUTHORIZED KEYS" })
        auth.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = "Only these public keys can log in (~/.ssh/authorized_keys). Paste the contents of the other computer's id_ed25519.pub or import the file. Private keys are refused."
            setPadding(0, dp(4), 0, dp(8))
        })
        auth.addView(authList)
        auth.addView(keyInput, lp(10))
        val authBtns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val addKey = button("ADD KEY", true)
        val importKey = button("IMPORT FILE", false)
        authBtns.addView(addKey, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        authBtns.addView(importKey, LinearLayout.LayoutParams(0, -2, 1f))
        auth.addView(authBtns, lp(8))
        content.addView(auth)
        addKey.setOnClickListener { addAuthorized(keyInput.text.toString()) }
        importKey.setOnClickListener { importKeyFile() }

        val hosts = card()
        hosts.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = "REMOTE HOSTS" })
        hosts.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = "Saved SSH destinations. CONNECT opens a terminal tab running ssh. No passwords are stored: type it in the tab, or use a key."
            setPadding(0, dp(4), 0, dp(8))
        })
        hosts.addView(hostsList)
        val addHost = button("ADD HOST", false)
        hosts.addView(addHost, lp(8))
        content.addView(hosts)
        addHost.setOnClickListener { editHost(null) }

        val keys = card()
        keys.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = "MY KEYS" })
        keys.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = "ed25519 keypairs in ~/.ssh. Copy the public key to the remote's authorized_keys. The private key never leaves this phone."
            setPadding(0, dp(4), 0, dp(8))
        })
        keys.addView(myKeysList)
        val gen = button("GENERATE ED25519 KEY", false)
        keys.addView(gen, lp(8))
        content.addView(keys)
        gen.setOnClickListener { generateKey() }
    }

    /** Page shown. */
    fun refresh() {
        renderAll()
    }

    private fun renderAll() { renderServer(); renderAuthorized(); renderHosts(); renderMyKeys() }

    // region server

    private fun renderServer() {
        val installed = SshServerController.isInstalled(ctx)
        bannerCard.visibility = if (installed) View.GONE else View.VISIBLE
        installBtn.isEnabled = !busy
        installBtn.alpha = if (busy) 0.5f else 1f
        val st = SshServerController.status
        val ip = st.address ?: SshServerController.lanAddress()
        statusTv.text = when (st.state) {
            SshState.STOPPED -> "STOPPED"
            SshState.STARTING -> "STARTING..."
            SshState.RUNNING -> "RUNNING on port ${SshModel.PORT}"
            SshState.ERROR -> "FAILED"
        }
        statusTv.setTextColor(if (st.state == SshState.ERROR) NC.ERROR else if (st.state == SshState.RUNNING) NC.PRIMARY else NC.ON_SURF_VAR)
        addrTv.text = if (ip != null) SshModel.connectCommand(ip) else "No Wi-Fi address. Connect to Wi-Fi or turn on the hotspot."
        copyCmdBtn.visibility = if (ip != null) View.VISIBLE else View.GONE
        val fp = SshServerController.hostFingerprint(ctx)
        fpTv.text = if (fp != null) "Host key (ed25519)\n$fp" else "Host key is created on the first start."
        errTv.text = st.message.orEmpty()
        errTv.visibility = if (st.message.isNullOrBlank()) View.GONE else View.VISIBLE
        val tail = if (st.state == SshState.STOPPED) emptyList() else SshServerController.log().takeLast(6)
        logTv.text = tail.joinToString("\n")
        logTv.visibility = if (tail.isEmpty()) View.GONE else View.VISIBLE
        val running = st.state == SshState.RUNNING || st.state == SshState.STARTING
        toggleBtn.text = if (running) "STOP SERVER" else "START SERVER"
        toggleBtn.isEnabled = installed && !busy && (running || ip != null)
        toggleBtn.alpha = if (toggleBtn.isEnabled) 1f else 0.5f
        keepCb.setOnCheckedChangeListener(null)
        keepCb.isChecked = SshServerController.keepRunning(ctx)
        keepCb.setOnCheckedChangeListener { _, on -> SshServerController.setKeepRunning(ctx, on) }
    }

    private fun toggle() {
        val st = SshServerController.status.state
        if (st == SshState.RUNNING || st == SshState.STARTING) { SshServerController.stop(ctx); return }
        if (!host.serverAllowed()) return
        val go = {
            host.requestNotifications()
            SshServerController.start(ctx)
        }
        if (SshServerController.warned(ctx)) go()
        else AlertDialog.Builder(ctx)
            .setTitle("Open an SSH port?")
            .setMessage("This opens port ${SshModel.PORT} on your current Wi-Fi network. Only computers holding a key you add under Authorized keys can log in; passwords are refused. Do not use it on a network you do not trust. A notification shows while it runs.")
            .setPositiveButton("START") { _, _ -> SshServerController.setWarned(ctx); go() }
            .setNegativeButton("CANCEL", null)
            .create().showThemed(ctx)
    }

    private fun install() {
        if (busy) return
        busy = true
        installBtn.text = "INSTALLING..."
        renderServer()
        bg.execute {
            val err = SshServerController.install(ctx)
            ui.post {
                busy = false
                installBtn.text = "INSTALL OPENSSH"
                if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                renderAll()
            }
        }
    }

    // endregion

    // region authorized keys

    private fun renderAuthorized() {
        authList.removeAllViews()
        val keys = SshServerController.authorizedKeys(ctx)
        if (keys.isEmpty()) {
            authList.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = "No keys yet. Nobody can log in." })
            return
        }
        for (k in keys) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(13f, NC.ON_SURFACE, bold = true, mono = true).apply { text = k.comment.ifEmpty { "(no name)" } })
            info.addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = "${k.type}\n${k.fingerprint}" })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val rm = button("REMOVE", false, danger = true)
            rm.contentDescription = "Remove key ${k.comment}"
            rm.setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Remove this key?")
                    .setMessage("${k.comment.ifEmpty { k.type }}\n${k.fingerprint}\n\nThat computer can no longer log in.")
                    .setPositiveButton("REMOVE") { _, _ -> SshServerController.removeAuthorizedKey(ctx, k.fingerprint); renderAuthorized() }
                    .setNegativeButton("CANCEL", null)
                    .create().showThemed(ctx, setOf(AlertDialog.BUTTON_POSITIVE))
            }
            row.addView(rm, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            authList.addView(row)
        }
    }

    private fun addAuthorized(raw: String) {
        if (!host.serverAllowed()) return
        val err = SshServerController.addAuthorizedKey(ctx, raw)
        if (err != null) { Toast.makeText(ctx, err, Toast.LENGTH_LONG).show(); return }
        keyInput.setText("")
        renderAuthorized()
    }

    private fun importKeyFile() {
        if (!host.serverAllowed()) return
        host.openDocument { uri ->
            if (uri == null) return@openDocument
            bg.execute {
                val text = try {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
                        val buf = CharArray(SshModel.MAX_KEY_LINE + 1)
                        String(buf, 0, r.read(buf).coerceAtLeast(0))
                    }
                } catch (_: Exception) { null }
                ui.post {
                    if (text == null) Toast.makeText(ctx, "Could not read that file.", Toast.LENGTH_LONG).show()
                    else addAuthorized(text)
                }
            }
        }
    }

    // endregion

    // region remote hosts

    private fun renderHosts() {
        hostsList.removeAllViews()
        val hosts = RemoteHosts.load(ctx)
        if (hosts.isEmpty()) hostsList.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = "No saved hosts." })
        for (h in hosts) {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            box.addView(text(14f, NC.ON_SURFACE, bold = true, mono = true).apply { text = h.name })
            box.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply {
                text = h.target + if (h.keyFile.isNotEmpty()) "  key: ${h.keyFile}" else ""
            })
            val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val connect = button("CONNECT", true)
            val edit = button("EDIT", false)
            val del = button("DELETE", false, danger = true)
            btns.addView(connect, LinearLayout.LayoutParams(0, -2, 1.3f).apply { rightMargin = dp(6) })
            btns.addView(edit, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            btns.addView(del, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(btns, lp(6))
            connect.setOnClickListener { host.connect(h.name, RemoteHosts.command(h)) }
            edit.setOnClickListener { editHost(h) }
            del.setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("Delete ${h.name}?").setMessage("Only the saved entry is removed.")
                    .setPositiveButton("DELETE") { _, _ -> RemoteHosts.save(ctx, RemoteHosts.load(ctx).filterNot { it.id == h.id }); renderHosts() }
                    .setNegativeButton("CANCEL", null)
                    .create().showThemed(ctx, setOf(AlertDialog.BUTTON_POSITIVE))
            }
            hostsList.addView(box)
            hostsList.addView(View(ctx).apply { setBackgroundColor(NC.OUTLINE_VAR) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun field(label: String, value: String, number: Boolean = false): Pair<LinearLayout, EditText> {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        box.addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = label })
        val et = EditText(ctx).apply {
            setText(value)
            setTextColor(NC.ON_SURFACE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minHeight = dp(48)
        }
        box.addView(et)
        return box to et
    }

    private fun editHost(existing: SshHost?) {
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val (nameBox, name) = field("NAME", existing?.name.orEmpty())
        val (userBox, user) = field("USER", existing?.user ?: "")
        val (hostBox, hostEt) = field("HOST OR IP", existing?.host.orEmpty())
        val (portBox, port) = field("PORT", (existing?.port ?: 22).toString(), number = true)
        val options = listOf("") + SshServerController.clientKeys(ctx)
        var pick = options.indexOf(existing?.keyFile ?: "").coerceAtLeast(0)
        val keyBtn = button("", false)
        fun label() { keyBtn.text = "KEY: " + options[pick].ifEmpty { "ssh default" } }
        label()
        keyBtn.setOnClickListener { pick = (pick + 1) % options.size; label() }
        listOf(nameBox, userBox, hostBox, portBox).forEach { form.addView(it) }
        form.addView(keyBtn, lp(10))
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "Add host" else "Edit host")
            .setView(android.widget.ScrollView(ctx).apply { addView(form) })
            .setPositiveButton("SAVE", null)
            .setNegativeButton("CANCEL", null)
            .create().showThemed(ctx)
        for (e in listOf(name, user, hostEt, port)) e.setTextColor(NC.ON_SURFACE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val h = SshHost(existing?.id ?: RemoteHosts.newId(), name.text.toString().trim(), user.text.toString().trim(),
                hostEt.text.toString().trim(), port.text.toString().toIntOrNull() ?: 0, options[pick])
            val err = RemoteHosts.validate(h)
            if (err != null) { Toast.makeText(ctx, err, Toast.LENGTH_LONG).show(); return@setOnClickListener }
            RemoteHosts.save(ctx, RemoteHosts.upsert(RemoteHosts.load(ctx), h))
            dialog.dismiss()
            renderHosts()
        }
    }

    // endregion

    // region client keys

    private fun renderMyKeys() {
        myKeysList.removeAllViews()
        val keys = SshServerController.clientKeys(ctx)
        if (keys.isEmpty()) myKeysList.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = "No keys yet." })
        for (name in keys) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val pub = SshServerController.publicKeyText(ctx, name)?.let { SshModel.parsePublicKey(it) }
            val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(13f, NC.ON_SURFACE, bold = true, mono = true).apply { text = name })
            info.addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = pub?.fingerprint ?: "unreadable" })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val copy = button("COPY PUBLIC KEY", false)
            copy.setOnClickListener {
                val t = SshServerController.publicKeyText(ctx, name)
                if (t != null && SshModel.parsePublicKey(t) != null) clip("public key", t)
                else Toast.makeText(ctx, "Could not read the public key.", Toast.LENGTH_LONG).show()
            }
            row.addView(copy, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            myKeysList.addView(row)
        }
    }

    private fun generateKey() {
        if (!SshServerController.isInstalled(ctx)) { Toast.makeText(ctx, "Install OpenSSH first.", Toast.LENGTH_LONG).show(); return }
        val existing = SshServerController.clientKeys(ctx).toSet()
        val suggested = if ("id_ed25519" !in existing) "id_ed25519" else generateSequence(2) { it + 1 }.map { "id_ed25519_$it" }.first { it !in existing }
        val (box, name) = field("KEY NAME", suggested)
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(box) }
        AlertDialog.Builder(ctx).setTitle("Generate key").setView(form)
            .setPositiveButton("GENERATE") { _, _ ->
                val n = name.text.toString().trim()
                bg.execute {
                    val err = SshServerController.generateClientKey(ctx, n)
                    ui.post {
                        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                        renderMyKeys()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .create().showThemed(ctx)
        name.setTextColor(NC.ON_SURFACE)
    }

    // endregion
}
