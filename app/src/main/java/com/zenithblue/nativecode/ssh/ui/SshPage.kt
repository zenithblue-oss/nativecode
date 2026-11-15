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
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** What the page needs from the activity. */
internal interface SshHostApi {
    /** True when the SSH server may be started; shows the Upgrade sheet and returns false for Free users. */
    fun serverAllowed(): Boolean
    fun isPro(): Boolean
    /** Opens a terminal tab that runs [command] (an `ssh ...` line) and titles it [title]. */
    fun connect(title: String, command: String)
    fun openDocument(onResult: (Uri?) -> Unit)
    fun requestNotifications()
    /** Opens Settings > Components, where the Linux runtime update (OpenSSH, ccusage) is applied. */
    fun openComponents()
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
        Toast.makeText(ctx, tr(R.string.chat_copied), Toast.LENGTH_SHORT).show()
    }

    // Banner
    private val bannerCard = card()
    private val bannerTv = text(12f, NC.ON_SURFACE)
    private val installBtn = button(tr(R.string.common_open_components), true)

    // Server
    private val serverCard = card()
    private val statusTv = text(14f, NC.PRIMARY, bold = true, mono = true)
    private val addrTv = text(13f, NC.ON_SURFACE, mono = true).apply { setTextIsSelectable(true) }
    private val copyCmdBtn = button(tr(R.string.ssh_copy_command), false)
    private val fpTv = text(11f, NC.ON_SURF_VAR, mono = true).apply { setTextIsSelectable(true) }
    private val errTv = text(12f, NC.ERROR)
    private val logTv = text(10f, NC.ON_SURF_VAR, mono = true)
    private val toggleBtn = button(tr(R.string.ssh_start_server), true)
    private val keepCb = CheckBox(ctx).apply {
        text = tr(R.string.ssh_keep_running_when_wi_fi)
        setTextColor(NC.ON_SURF_VAR)
        textSize = 12f
        minHeight = dp(48)
    }

    // Keys / hosts / client keys
    private val authList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val keyInput = EditText(ctx).apply {
        hint = tr(R.string.ssh_ssh_ed25519_aaaa_name)
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

        bannerCard.addView(text(15f, NC.TERTIARY, bold = true, mono = true).apply { text = tr(R.string.ssh_openssh_not_installed) })
        // OpenSSH ships in the Linux runtime update (Components); the app never installs packages itself.
        bannerTv.text = tr(R.string.ssh_openssh_in_runtime_update)
        bannerTv.setPadding(0, dp(4), 0, dp(12))
        bannerCard.addView(bannerTv)
        bannerCard.addView(installBtn, lp())
        content.addView(bannerCard)
        installBtn.setOnClickListener { host.openComponents() }

        serverCard.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = tr(R.string.ssh_ssh_server_pro) })
        serverCard.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = tr(R.string.ssh_log_in_to_this_phone)
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
        auth.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = tr(R.string.ssh_authorized_keys) })
        auth.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = tr(R.string.ssh_only_these_public_keys_can)
            setPadding(0, dp(4), 0, dp(8))
        })
        auth.addView(authList)
        auth.addView(keyInput, lp(10))
        val authBtns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val addKey = button(tr(R.string.ssh_add_key), true)
        val importKey = button(tr(R.string.ssh_import_file), false)
        authBtns.addView(addKey, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        authBtns.addView(importKey, LinearLayout.LayoutParams(0, -2, 1f))
        auth.addView(authBtns, lp(8))
        content.addView(auth)
        addKey.setOnClickListener { addAuthorized(keyInput.text.toString()) }
        importKey.setOnClickListener { importKeyFile() }

        val hosts = card()
        hosts.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = tr(R.string.ssh_remote_hosts) })
        hosts.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = tr(R.string.ssh_saved_ssh_destinations_connect_opens)
            setPadding(0, dp(4), 0, dp(8))
        })
        hosts.addView(hostsList)
        val addHost = button(tr(R.string.ssh_add_host), false)
        hosts.addView(addHost, lp(8))
        content.addView(hosts)
        addHost.setOnClickListener { editHost(null) }

        val keys = card()
        keys.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = tr(R.string.ssh_my_keys) })
        keys.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = tr(R.string.ssh_ed25519_keypairs_in_ssh_copy)
            setPadding(0, dp(4), 0, dp(8))
        })
        keys.addView(myKeysList)
        val gen = button(tr(R.string.ssh_generate_ed25519_key), false)
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
            SshState.STOPPED -> tr(R.string.ssh_stopped)
            SshState.STARTING -> tr(R.string.ssh_starting_2)
            SshState.RUNNING -> "RUNNING on port ${SshModel.PORT}"
            SshState.ERROR -> tr(R.string.ssh_failed)
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
        toggleBtn.text = if (running) tr(R.string.ssh_stop_server) else tr(R.string.ssh_start_server)
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
            .setTitle(tr(R.string.ssh_open_an_ssh_port))
            .setMessage(tr(R.string.ssh_this_opens_port_on_your, SshModel.PORT))
            .setPositiveButton(tr(R.string.ssh_start)) { _, _ -> SshServerController.setWarned(ctx); go() }
            .setNegativeButton(tr(R.string.x11_cancel), null)
            .create().showThemed(ctx)
    }

    // endregion

    // region authorized keys

    private fun renderAuthorized() {
        authList.removeAllViews()
        val keys = SshServerController.authorizedKeys(ctx)
        if (keys.isEmpty()) {
            authList.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = tr(R.string.ssh_no_keys_yet_nobody_can) })
            return
        }
        for (k in keys) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(13f, NC.ON_SURFACE, bold = true, mono = true).apply { text = k.comment.ifEmpty { "(no name)" } })
            info.addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = "${k.type}\n${k.fingerprint}" })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val rm = button(tr(R.string.ssh_remove), false, danger = true)
            rm.contentDescription = "Remove key ${k.comment}"
            rm.setOnClickListener {
                AlertDialog.Builder(ctx).setTitle(tr(R.string.ssh_remove_this_key))
                    .setMessage("${k.comment.ifEmpty { k.type }}\n${k.fingerprint}\n\nThat computer can no longer log in.")
                    .setPositiveButton(tr(R.string.ssh_remove)) { _, _ -> SshServerController.removeAuthorizedKey(ctx, k.fingerprint); renderAuthorized() }
                    .setNegativeButton(tr(R.string.x11_cancel), null)
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
        if (hosts.isEmpty()) hostsList.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = tr(R.string.ssh_no_saved_hosts) })
        for (h in hosts) {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            box.addView(text(14f, NC.ON_SURFACE, bold = true, mono = true).apply { text = h.name })
            box.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply {
                text = h.target + if (h.keyFile.isNotEmpty()) "  key: ${h.keyFile}" else ""
            })
            val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val connect = button(tr(R.string.ssh_connect), true)
            val edit = button(tr(R.string.ssh_edit), false)
            val del = button(tr(R.string.git_delete), false, danger = true)
            btns.addView(connect, LinearLayout.LayoutParams(0, -2, 1.3f).apply { rightMargin = dp(6) })
            btns.addView(edit, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            btns.addView(del, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(btns, lp(6))
            connect.setOnClickListener { host.connect(h.name, RemoteHosts.command(h)) }
            edit.setOnClickListener { editHost(h) }
            del.setOnClickListener {
                AlertDialog.Builder(ctx).setTitle(tr(R.string.ssh_delete, h.name)).setMessage(tr(R.string.ssh_only_the_saved_entry_is))
                    .setPositiveButton(tr(R.string.git_delete)) { _, _ -> RemoteHosts.save(ctx, RemoteHosts.load(ctx).filterNot { it.id == h.id }); renderHosts() }
                    .setNegativeButton(tr(R.string.x11_cancel), null)
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
        val (nameBox, name) = field(tr(R.string.ssh_name), existing?.name.orEmpty())
        val (userBox, user) = field(tr(R.string.ssh_user), existing?.user ?: "")
        val (hostBox, hostEt) = field(tr(R.string.ssh_host_or_ip), existing?.host.orEmpty())
        val (portBox, port) = field(tr(R.string.ssh_port), (existing?.port ?: 22).toString(), number = true)
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
            .setPositiveButton(tr(R.string.x11_save), null)
            .setNegativeButton(tr(R.string.x11_cancel), null)
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
        if (keys.isEmpty()) myKeysList.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = tr(R.string.ssh_no_keys_yet) })
        for (name in keys) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val pub = SshServerController.publicKeyText(ctx, name)?.let { SshModel.parsePublicKey(it) }
            val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(13f, NC.ON_SURFACE, bold = true, mono = true).apply { text = name })
            info.addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = pub?.fingerprint ?: "unreadable" })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val copy = button(tr(R.string.ssh_copy_public_key), false)
            copy.setOnClickListener {
                val t = SshServerController.publicKeyText(ctx, name)
                if (t != null && SshModel.parsePublicKey(t) != null) clip("public key", t)
                else Toast.makeText(ctx, tr(R.string.ssh_could_not_read_the_public), Toast.LENGTH_LONG).show()
            }
            row.addView(copy, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            myKeysList.addView(row)
        }
    }

    private fun generateKey() {
        if (!SshServerController.isInstalled(ctx)) { Toast.makeText(ctx, tr(R.string.ssh_openssh_in_runtime_update), Toast.LENGTH_LONG).show(); return }
        val existing = SshServerController.clientKeys(ctx).toSet()
        val suggested = if ("id_ed25519" !in existing) "id_ed25519" else generateSequence(2) { it + 1 }.map { "id_ed25519_$it" }.first { it !in existing }
        val (box, name) = field(tr(R.string.ssh_key_name), suggested)
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(box) }
        AlertDialog.Builder(ctx).setTitle(tr(R.string.ssh_generate_key)).setView(form)
            .setPositiveButton(tr(R.string.ssh_generate)) { _, _ ->
                val n = name.text.toString().trim()
                bg.execute {
                    val err = SshServerController.generateClientKey(ctx, n)
                    ui.post {
                        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                        renderMyKeys()
                    }
                }
            }
            .setNegativeButton(tr(R.string.x11_cancel), null)
            .create().showThemed(ctx)
        name.setTextColor(NC.ON_SURFACE)
    }

    // endregion
}
