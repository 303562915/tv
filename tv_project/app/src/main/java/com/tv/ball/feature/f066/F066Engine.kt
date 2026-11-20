package com.tv.ball.feature.f066

import android.os.Handler
import android.os.Looper
import com.tv.ball.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 遥控指令的种类。
 *
 * 线上一律用 [wire] 里的全大写名字，不用枚举名，这样服务端换个语言实现也不用跟着改协议；
 * 解析时不分大小写，手写的调试脚本 `{"cmd":"play"}` 也能收。
 */
enum class CommandKind(val wire: String, val labelRes: Int) {

    /** 播放。 */
    PLAY("PLAY", R.string.f066_cmd_play),

    /** 暂停。 */
    PAUSE("PAUSE", R.string.f066_cmd_pause),

    /** 下一集。 */
    NEXT("NEXT", R.string.f066_cmd_next),

    /** 上一集。 */
    PREV("PREV", R.string.f066_cmd_prev),

    /** 进度跳转，[Command.value] 是毫秒。 */
    SEEK("SEEK", R.string.f066_cmd_seek),

    /** 音量，[Command.value] 是 0..100。 */
    VOLUME("VOLUME", R.string.f066_cmd_volume);

    companion object {

        /** 兼容服务端写成 "command" / 小写 / 带空格的情况。 */
        fun fromWire(raw: String?): CommandKind? {
            if (raw.isNullOrBlank()) return null
            val name = raw.trim()
            return values().firstOrNull { it.wire.equals(name, ignoreCase = true) }
        }
    }
}

/**
 * 一条遥控指令。
 *
 * [value] 只对 SEEK / VOLUME 有意义，其余指令为 null——不要用 0 当"没有值"，
 * 因为音量 0 是合法的静音，进度 0 是合法的从头发。
 */
data class Command(val kind: CommandKind, val value: Int? = null)

/**
 * 「远程遥控通道」的协议层。
 *
 * 只做三件事，不做连接：
 * 1. [encode] / [parse]：手机上按的按钮 → 一行 JSON → 电视端的播放器；
 * 2. 输入框里手打的进度 / 音量 → 毫秒 / 整数，格式不对就返回 null，界面据此提示；
 * 3. [newServer]：按地址造一条 [RemoteControlServer]，地址为空时那条连接会走本地回环。
 *
 * JSON 形状固定为 `{"cmd":"PLAY","ts":1730000000000}`，带值的是
 * `{"cmd":"SEEK","value":125000,"ts":...}`。ts 是手机本地时间，只用来在电视端日志里
 * 对齐时序，不参与任何判断——两台设备没必要对时。
 */
object F066Engine {

    /** 地址输入框的初始值：留空就是本地回环演示。 */
    const val DEFAULT_URL = "ws://192.168.1.10:8765/remote"

    /** 重连上限：断线后最多再试 3 次，之后状态落到 CLOSED，交给用户手动点连接。 */
    const val MAX_ATTEMPTS = 3

    /** 指数退避基数（毫秒）：400 → 800 → 1600。 */
    const val BACKOFF_BASE_MS = 400L

    const val MIN_VOLUME = 0
    const val MAX_VOLUME = 100

    /** 音量步进：± 一次动这么多。 */
    const val VOLUME_STEP = 5

    /** SEEK / VOLUME 之外，多带一个 value 也算噪声，encode 会忽略它。 */
    private val VALUE_KINDS = setOf(CommandKind.SEEK, CommandKind.VOLUME)

    /**
     * 一行 JSON → 指令。
     *
     * 返回 null 的三种情况：空串、不是 JSON、cmd 字段缺失或不是六种之一。
     * 值的类型不较真：字符串 "12" 也能收，收不到就退化成不带值的指令，日志里看得出来。
     */
    fun parse(json: String?): Command? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(json)
            val kind = CommandKind.fromWire(obj.optString("cmd").ifBlank { obj.optString("command") })
                ?: return null
            if (kind !in VALUE_KINDS) return Command(kind)
            val raw = obj.opt("value")
            val value = when (raw) {
                null, JSONObject.NULL -> null
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
            Command(kind, value)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 指令 → 一行 JSON。
     *
     * 值只在 SEEK / VOLUME 上带，进度顺手夹到非负，音量夹到 0..100——
     * 让越界的输入在手机上就被修正，而不是让电视端去猜 130 的音量是什么意思。
     */
    fun encode(command: Command): String {
        val obj = JSONObject()
        obj.put("cmd", command.kind.wire)
        if (command.kind in VALUE_KINDS) {
            val value = command.value ?: 0
            obj.put(
                "value",
                when (command.kind) {
                    CommandKind.VOLUME -> value.coerceIn(MIN_VOLUME, MAX_VOLUME)
                    else -> value.coerceAtLeast(0)
                }
            )
        }
        obj.put("ts", System.currentTimeMillis())
        return obj.toString()
    }

    /**
     * 进度输入框 → 毫秒。
     *
     * 认三种写法：`mm:ss`、`hh:mm:ss`、纯数字（秒，可带小数）。
     * 空串 / 负号 / 冒号段不是数字 / 超过三段都算非法，返回 null 让界面直接提示，
     * 不白发一条电视端必然忽略的 SEEK。
     */
    fun parseSeek(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.contains(':')) {
            val parts = text.split(':')
            if (parts.isEmpty() || parts.size > 3) return null
            var total = 0L
            for (part in parts) {
                val unit = part.trim().toLongOrNull() ?: return null
                if (unit < 0) return null
                total = total * 60 + unit
            }
            return (total * 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val seconds = text.toDoubleOrNull() ?: return null
        if (seconds < 0 || seconds > Int.MAX_VALUE / 1000.0) return null
        return (seconds * 1000.0).toInt()
    }

    /** 音量输入框 → 0..100；越界夹紧而不是报错，静音 0 和满格 100 都要能用。 */
    fun parseVolume(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val value = text.toIntOrNull() ?: return null
        return value.coerceIn(MIN_VOLUME, MAX_VOLUME)
    }

    /** 毫秒 → `mm:ss`，日志和状态行里给人看的那一份。 */
    fun formatSeek(ms: Int?): String {
        if (ms == null || ms < 0) return "--:--"
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /** 指令 + 值拼成一句人话，日志里比光秃秃的 SEEK 好认。 */
    fun describe(command: Command): String = when (command.kind) {
        CommandKind.SEEK -> command.kind.wire + " " + formatSeek(command.value)
        CommandKind.VOLUME -> command.kind.wire + " " + (command.value ?: 0)
        else -> command.kind.wire
    }

    /** 按地址造一条连接；[RemoteControlServer] 自己判断空地址走回环还是真连。 */
    fun newServer(url: String): RemoteControlServer = RemoteControlServer(url)
}

/**
 * 一条 WebSocket 指令通道。
 *
 * 状态只有三档：[State.CONNECTING] / [State.OPEN] / [State.CLOSED]，和 WebSocket 的
 * onOpen / onFailure / onClosed 一一对应。掉线不立刻放弃：按 [F066Engine.BACKOFF_BASE_MS]
 * 做指数退避重连，最多 [F066Engine.MAX_ATTEMPTS] 次，用完才落 CLOSED——
 * 投屏场景里电视休眠、Wi-Fi 抖动都很常见，一次失败就要求人重新点连接太蠢。
 *
 * 地址为空时进 **回环演示**：不建任何 socket，[send] 把 JSON 编出来再原样解析一遍，
 * 立刻当作"收到指令"回给自己。这样没有服务端也能把六种指令、日志、送达状态全看一遍，
 * 而且走的是同一条 encode / parse 路径，协议有问题照样暴露。
 *
 * 所有回调都回到主线程（[MAIN]），界面里不用再套 runOnUiThread。
 */
class RemoteControlServer(private val url: String) {

    enum class State {
        /** 正在建连或正在退避等待下一次重连。 */
        CONNECTING,

        /** 通道可用，可以发指令。 */
        OPEN,

        /** 断了且不再重试；也可能是用户主动断开后的稳定态。 */
        CLOSED
    }

    /** 当前状态；[State.CLOSED] 是初始值，也是主动断开后的终态。 */
    @Volatile
    var state: State = State.CLOSED
        private set

    /** 地址为空的回环模式：没有 socket，自己发自己收。 */
    private val loopback: Boolean = url.isBlank()

    private var socket: WebSocket? = null
    private var commandCallback: ((Command) -> Unit)? = null
    private var stateCallback: ((State, String) -> Unit)? = null
    private var reconnectTask: Runnable? = null
    private var attempts = 0
    private var closedByUser = false

    /** 收到的指令（回环模式下就是刚发出去的那条）。 */
    fun onCommand(cb: (Command) -> Unit) {
        commandCallback = cb
    }

    /** 状态变化 + 一句原因，原因要能直接进日志。 */
    fun onState(cb: (State, String) -> Unit) {
        stateCallback = cb
    }

    /** 开始连接：回环模式 150ms 后进 OPEN，真实地址直接建 socket。 */
    fun connect() {
        closedByUser = false
        attempts = 0
        cancelReconnect()
        if (loopback) {
            enterLoopback()
            return
        }
        openSocket()
    }

    /**
     * 主动断开。
     *
     * 先立 closedByUser 再关 socket：OkHttp 的 onClosed / onFailure 是异步回来的，
     * 不先落这个标记，用户点"断开"会被自己触发的回调当成掉线，反而开始重连。
     */
    fun disconnect() {
        closedByUser = true
        cancelReconnect()
        socket?.close(NORMAL_CLOSE, "client closed")
        socket = null
        setState(State.CLOSED, "已断开")
    }

    /** 发一条指令；返回是否真的交给了通道。回环模式下永远 true（自己收）。 */
    fun send(command: Command): Boolean {
        if (loopback) {
            val wire = F066Engine.encode(command)
            val echoed = F066Engine.parse(wire)
            if (echoed == null) {
                setState(State.CLOSED, "回环自检失败：协议编解码不一致")
                return false
            }
            postCommand(echoed)
            return true
        }
        val ws = socket
        if (state != State.OPEN || ws == null) return false
        return ws.send(F066Engine.encode(command))
    }

    /** 退出页面时调：断开且不再回调，避免 Activity 销毁后还被 onState 摸一下。 */
    fun release() {
        closedByUser = true
        cancelReconnect()
        socket?.cancel()
        socket = null
        commandCallback = null
        stateCallback = null
        state = State.CLOSED
    }

    // ===== 内部 =====

    private fun enterLoopback() {
        setState(State.CONNECTING, "本地回环模式：无服务端，自己发自己收")
        val task = Runnable {
            reconnectTask = null
            setState(State.OPEN, "回环通道就绪")
        }
        reconnectTask = task
        MAIN.postDelayed(task, LOOPBACK_DELAY_MS)
    }

    private fun openSocket() {
        cancelReconnect()
        socket?.cancel()
        setState(State.CONNECTING, "连接 " + displayUrl())
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            // 地址不是合法的 ws:// 或 wss://，重试也没用，直接落 CLOSED
            setState(State.CLOSED, "地址不合法：" + url)
            return
        }
        socket = CLIENT.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
            setState(State.OPEN, "通道已建立")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val command = F066Engine.parse(text)
            if (command == null) {
                setState(state, "收到无法识别的报文，已忽略")
                return
            }
            postCommand(command)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            val reason = if (code == null) {
                t.message ?: t.javaClass.simpleName
            } else {
                "HTTP " + code + " / " + (t.message ?: t.javaClass.simpleName)
            }
            onDropped(reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDropped("服务端关闭 " + code + (if (reason.isBlank()) "" else " " + reason))
        }
    }

    /** 掉线处理：用户主动断的就不再重连；否则指数退避，次数用完落 CLOSED。 */
    private fun onDropped(reason: String) {
        socket = null
        if (closedByUser) {
            setState(State.CLOSED, reason)
            return
        }
        if (attempts >= F066Engine.MAX_ATTEMPTS) {
            setState(State.CLOSED, reason + "；重连 " + F066Engine.MAX_ATTEMPTS + " 次未成功")
            return
        }
        attempts += 1
        val delay = F066Engine.BACKOFF_BASE_MS * (1L shl (attempts - 1))
        setState(State.CONNECTING, reason + "；" + delay + "ms 后第 " + attempts + " 次重连")
        val task = Runnable {
            reconnectTask = null
            if (!closedByUser) openSocket()
        }
        reconnectTask = task
        MAIN.postDelayed(task, delay)
    }

    private fun cancelReconnect() {
        reconnectTask?.let { MAIN.removeCallbacks(it) }
        reconnectTask = null
    }

    private fun setState(next: State, reason: String) {
        state = next
        MAIN.post { stateCallback?.invoke(next, reason) }
    }

    private fun postCommand(command: Command) {
        MAIN.post { commandCallback?.invoke(command) }
    }

    /** 日志里显示的地址：回环模式给个人话，正常地址原样。 */
    private fun displayUrl(): String = url.trim().ifEmpty { LOOPBACK_LABEL }

    companion object {

        private const val NORMAL_CLOSE = 1000

        /** 回环模式下"建连"的假延时，让 CONNECTING 这一档在界面里看得见。 */
        private const val LOOPBACK_DELAY_MS = 150L

        private const val LOOPBACK_LABEL = "本地回环（无服务端）"

        private val MAIN = Handler(Looper.getMainLooper())

        /**
         * 所有连接共用一个 OkHttpClient。
         *
         * readTimeout 必须置 0：WebSocket 是长连接，OkHttp 的读超时只管握手后的 socket
         * 读空闲，设成 10s 会让一条安静了十秒的通道莫名其妙被判死。
         * pingInterval 15s 负责让 NAT / 路由器别把空闲映射回收掉。
         * retryOnConnectionFailure 关掉：重连节奏由我们自己按退避控制。
         */
        private val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}