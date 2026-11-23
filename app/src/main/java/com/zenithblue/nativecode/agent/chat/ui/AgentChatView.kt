package com.zenithblue.nativecode.agent.chat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import com.zenithblue.nativecode.cliauth.AuthState
import com.zenithblue.nativecode.cliauth.CliCredentialProbe
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import java.util.concurrent.Executor
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** One thread-list row. [draft] = nothing sent yet (no run, no message): hidden from the list, reused by the next new thread. */
data class ThreadRow(val thread: ChatThread, val status: ThreadStatus, val lastRun: Run?, val add: Int, val del: Int, val preview: String? = null, val draft: Boolean = false)

/** The open thread: store rows (newest 200 + pages loaded with "Load earlier"). */
data class ThreadData(
    val thread: ChatThread,
    val items: List<TurnItem>,
    val runs: List<Run>,
    val requests: List<ChatRequest>,
    val hasMore: Boolean,
) {
    /** Same thread with its composer draft dropped (the message was just sent). */
    fun withoutDraft() = copy(thread = thread.copy(draftJson = null))
}

sealed class Route {
    data object List : Route()
    data class Chat(val id: String) : Route()
    data class Diff(val id: String) : Route()
    data class Settings(val id: String) : Route()
    data class Bridge(val id: String) : Route()
}

/**
 * Chat surface of one page (GitHistoryView pattern): TERMINAL tab ([workspace] false, every thread, grouped)
 * or the workspace page ([workspace] true, one project's threads). A FrameLayout + ComposeView that stays
 * attached across page switches; state comes from [backend] (store + live snapshots).
 */
@SuppressLint("ViewConstructor")
class AgentChatView(
    context: Context,
    private val io: Executor,
    private val workspace: Boolean,
    internal val host: ChatHost,
    internal val backend: ChatBackend,
    /** AgentChats.visibleThreadId sink (notification suppression). */
    private val onVisibleThread: (String?) -> Unit,
) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())

    /** Bridge card: open a host file in the in-app viewer (this surface is where Back returns to). */
    internal fun openHostFile(path: String) = host.openFile(path, workspace)

    internal val stack = mutableStateListOf<Route>(Route.List)
    internal val rows = mutableStateOf<List<ThreadRow>>(emptyList())
    internal val loaded = mutableStateOf(false)
    internal val open = mutableStateOf<ThreadData?>(null)
    internal val live = mutableStateOf<LiveState?>(null)
    /** Empty-state pane: the draft thread shown in place of the list until its first send (see [promoteDraft]). */
    internal val draftId = mutableStateOf<String?>(null)
    /** A refused first send from the empty-state pane (thread id, draft): the chat route that replaced the pane takes it back. */
    internal val refusedDraft = mutableStateOf<Pair<String, String?>?>(null)
    internal val now = mutableStateOf(System.currentTimeMillis())

    /** Workspace project (guest path); null on the TERMINAL tab. */
    internal var projectPath: String? = null
        private set
    private val pageShownState = mutableStateOf(false)
    private val pageShown get() = pageShownState.value
    private val chatPosition = mutableStateOf(true)
    private var unObserve: (() -> Unit)? = null
    private var approvals = emptySet<String>()

    /**
     * A landscape host LinearLayout (baseline-aligned, weighted child) measures us with an UNSPECIFIED width;
     * Compose turns that into an infinite max width and every horizontalScroll throws. Cap it at the given size.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun cap(spec: Int, fallback: Int) = if (MeasureSpec.getMode(spec) != MeasureSpec.UNSPECIFIED) spec
            else MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(spec).takeIf { it > 0 } ?: fallback, MeasureSpec.AT_MOST)
        val dm = resources.displayMetrics
        super.onMeasure(cap(widthMeasureSpec, dm.widthPixels), cap(heightMeasureSpec, dm.heightPixels))
    }

    private val storeListener: (Set<String>) -> Unit = { ids ->
        main.post {
            reloadList()
            open.value?.thread?.id?.let { if (it in ids) reloadThread(it) }
        }
    }

    init {
        addView(ComposeView(context).apply {
            setContent {
                // Every link in assistant markdown routes here (never a raw Intent: a file: URI crashes with FileUriExposedException).
                val links = remember {
                    object : UriHandler {
                        override fun openUri(uri: String) = host.openLink(uri, open.value?.thread?.cwd ?: projectPath ?: HOME, workspace)
                    }
                }
                val resolver = remember<(String) -> java.io.File?> { { raw -> host.resolveFile(raw, open.value?.thread?.cwd ?: projectPath ?: HOME) } }
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides (context as ComponentActivity), LocalUriHandler provides links, LocalFileResolver provides resolver) {
                    ChatTheme {
                        BackHandler(enabled = chatPosition.value && pageShownState.value && stack.size > 1) { pop() }
                        // No imePadding: MA pads contentFrame by the IME inset (the composer rises with it).
                        Box(Modifier.fillMaxSize().background(C.bg)) {
                            Crossfade(stack.last(), animationSpec = tween(160), label = "route") { r ->
                                when (r) {
                                    Route.List -> ThreadListScreen(this@AgentChatView)
                                    is Route.Chat -> ChatScreen(this@AgentChatView, r.id)
                                    is Route.Diff -> DiffScreen(this@AgentChatView, r.id)
                                    is Route.Settings -> ThreadSettingsScreen(this@AgentChatView, r.id)
                                    is Route.Bridge -> BridgeHistoryScreen(this@AgentChatView, r.id)
                                }
                            }
                        }
                    }
                }
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backend.addListener(storeListener)
        reloadList()
    }

    override fun onDetachedFromWindow() {
        backend.removeListener(storeListener)
        unObserve?.invoke(); unObserve = null
        super.onDetachedFromWindow()
    }

    internal val isWorkspace get() = workspace

    /** Workspace project switch: only the scope changes; other projects' chats keep running. */
    fun setScope(path: String?) {
        if (path == projectPath) return
        projectPath = path
        draftId.value = null
        if (stack.size > 1) { stack.clear(); stack += Route.List; closeThread() }
        rows.value = emptyList(); loaded.value = false
        reloadList()
    }

    /** MA: page visible + Chat position. Drives visibleThreadId and the Terminal-position snackbar. */
    fun setShown(page: Boolean, chat: Boolean) {
        pageShownState.value = page; chatPosition.value = chat
        publishVisible()
        if (page && chat) open.value?.thread?.id?.let { backend.markVisitedAsync(it) }
    }

    /** Deep link (notification): open [threadId] on top of the list. */
    fun openThread(threadId: String) {
        stack.clear(); stack += Route.List; stack += Route.Chat(threadId)
        showThread(threadId)
    }

    internal fun push(r: Route) {
        stack += r
        if (r is Route.Chat) showThread(r.id)
    }

    internal fun pop() {
        if (stack.size <= 1) return
        val gone = stack.removeAt(stack.lastIndex)
        if (gone is Route.Chat) closeThread()
        (stack.last() as? Route.Chat)?.let { showThread(it.id) }
    }

    fun onBack(): Boolean = if (stack.size > 1) { pop(); true } else false

    private fun showThread(id: String) {
        if (open.value?.thread?.id != id) { open.value = null; live.value = backend.live(id) }
        unObserve?.invoke()
        unObserve = backend.observeLive(id) { main.post { if (open.value?.thread?.id == id || open.value == null) live.value = backend.live(id) } }
        reloadThread(id)
        backend.markVisitedAsync(id)
        publishVisible(id)
    }

    private fun closeThread() {
        unObserve?.invoke(); unObserve = null
        open.value = null; live.value = null
        publishVisible()
    }

    private fun publishVisible(id: String? = (stack.lastOrNull() as? Route.Chat)?.id) =
        onVisibleThread(if (pageShown && chatPosition.value) id else null)

    private fun ChatBackend.markVisitedAsync(id: String) = io.execute { runCatching { markVisited(id) } }

    internal fun bg(work: () -> Unit) = io.execute { runCatching(work) }
    internal fun ui(work: () -> Unit) { main.post(work) }

    internal fun reloadList() {
        val scope = projectPath
        if (workspace && scope == null) return
        io.execute {
            val list = runCatching {
                backend.threads(scope, all = !workspace).map { t -> ChatLogic.rowOrBare(t) {
                    val runs = backend.runs(t.id)
                    val last = runs.lastOrNull { it.status != com.zenithblue.nativecode.agent.chat.core.RunStatus.QUEUED }
                    val stat = last?.diffStat.orEmpty()
                    val recent = backend.items(t.id, 8)
                    val preview = recent.lastOrNull { it.payload is ItemPayload.AssistantMessage || it.payload is ItemPayload.UserMessage }?.payload?.let { p ->
                        ((p as? ItemPayload.AssistantMessage)?.text ?: com.zenithblue.nativecode.agent.chat.core.SkillBlocks.plain((p as ItemPayload.UserMessage).text)).lineSequence().firstOrNull { it.isNotBlank() }
                            ?.trim()?.trimStart('#', '>', '-', '*', ' ')?.take(200)?.let { Md.inline(it).text }
                    }?.takeUnless { it.startsWith(t.title.take(40)) } // the first prompt is already the title
                    ThreadRow(t, ChatLogic.status(t, last, backend.openRequests(t.id)), last, stat.sumOf { it.add }, stat.sumOf { it.del }, preview, draft = runs.isEmpty() && recent.isEmpty())
                } }
            }.getOrDefault(emptyList())
            main.post {
                if (scope != projectPath) return@post
                val nowApproval = list.filter { it.status == ThreadStatus.APPROVAL }.map { it.thread.id }.toSet()
                val fresh = nowApproval - approvals
                approvals = nowApproval
                if (fresh.isNotEmpty() && pageShown && !chatPosition.value) {
                    val r = list.first { it.thread.id in fresh }
                    host.approvalWhileInTerminal(r.thread.title) { host.showChat(workspace); openThread(r.thread.id) }
                }
                rows.value = list; loaded.value = true
            }
        }
    }

    internal fun reloadThread(id: String) {
        val older = open.value?.takeIf { it.thread.id == id }?.let { d -> d.items.size > 200 } ?: false
        val keep = if (older) open.value!!.items else emptyList()
        io.execute {
            val data = runCatching {
                val t = backend.thread(id) ?: return@runCatching null
                val latest = backend.items(id, 200)
                val ids = latest.map { it.id }.toHashSet()
                val merged = keep.filter { it.id !in ids && it.ordinal < (latest.firstOrNull()?.ordinal ?: Int.MAX_VALUE) } + latest
                ThreadData(t, merged, backend.runs(id), backend.openRequests(id), hasMore = (merged.firstOrNull()?.ordinal ?: 0) > 0 && latest.size >= 200 || older)
            }.getOrNull()
            main.post { if ((stack.lastOrNull() as? Route.Chat)?.id == id || stack.any { it is Route.Chat && it.id == id } || draftId.value == id) open.value = data }
        }
    }

    internal fun loadEarlier() {
        val d = open.value ?: return
        val first = d.items.firstOrNull()?.ordinal ?: return
        io.execute {
            val page = runCatching { backend.items(d.thread.id, 200, first) }.getOrDefault(emptyList())
            main.post {
                val cur = open.value ?: return@post
                if (cur.thread.id == d.thread.id) open.value = cur.copy(items = page + cur.items, hasMore = page.size >= 200)
            }
        }
    }

    /** Error row Retry / agent-stopped Restart: resend that run's prompt (else the last one). */
    internal fun retry(t: ChatThread, runId: String?) {
        val users = open.value?.items.orEmpty().mapNotNull { i -> (i.payload as? ItemPayload.UserMessage)?.let { i.runId to it } }
        val msg = (users.lastOrNull { it.first == runId } ?: users.lastOrNull())?.second ?: return
        bg { backend.send(t.id, msg.text, msg.attachments, steer = false) }
    }

    /**
     * New thread, no provider question: this scope's unsent draft is reused, else one starts on [ChatLogic.defaultTool] (the provider
     * of the scope's latest thread, so the choice persists). Opens its chat, or only loads it for the empty-state pane ([openChat] false).
     */
    internal fun newThread(openChat: Boolean = true) {
        val project = projectPath
        val mine = rows.value.filter { it.thread.projectPath == project }
        val reuse = mine.firstOrNull { it.draft }?.thread?.id
        val last = mine.filter { !it.draft }.maxByOrNull { it.thread.updatedAt }?.thread?.tool
        val tools = CliToolCatalog.forMethod(LinuxCommandBuilder.currentMethod).filter { it.chat != null }
        val usable = tools.filter { host.canUseTool(it.terminalType) }.map { it.terminalType }.toSet() // host calls stay on main
        val home = host.guestHome()
        io.execute {
            val id = reuse ?: runCatching {
                val tool = ChatLogic.defaultTool(last, tools.map { it.terminalType }, { it in usable }) { tt ->
                    CliCredentialProbe.probe(tools.first { it.terminalType == tt }.id, home).state == AuthState.SIGNED_IN
                }
                backend.createThread(project, project ?: HOME, tool).id
            }.getOrNull()
            main.post {
                if (id == null) {
                    android.widget.Toast.makeText(context, tr(R.string.chat_could_not_create_the_thread), android.widget.Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (openChat) push(Route.Chat(id)) else { draftId.value = id; showThread(id) }
                reloadList()
            }
        }
    }
    /** First send from the empty-state pane: the pane's draft becomes a normal thread and opens as one. */
    internal fun promoteDraft(id: String) {
        if (draftId.value != id) return
        draftId.value = null
        // The chat route builds its composer from this thread's draft (open copy, or the reload that follows the push), which
        // still holds the text just sent: drop it in both. ponytail: one tiny store write on main so the reload cannot race it.
        open.value = open.value?.let { if (it.thread.id == id) it.withoutDraft() else it }
        runCatching { backend.setDraft(id, null) }
        push(Route.Chat(id))
    }

    /** `/side` / `/btw`: fork into a side-chat thread and open it on top (t3code opens it as a panel tab). */
    internal fun sideChat(parentId: String, prompt: String) {
        io.execute {
            val t = runCatching { backend.sideChat(parentId, prompt) }.onFailure { android.util.Log.w("NcChat", "side chat failed", it) }.getOrNull()
            main.post {
                if (t == null) {
                    android.widget.Toast.makeText(context, tr(R.string.chat_could_not_start_a_side), android.widget.Toast.LENGTH_SHORT).show()
                    return@post
                }
                reloadList(); push(Route.Chat(t.id))
            }
        }
    }

    companion object {
        const val HOME = "/home/flux"
    }
}
