package com.tv.ball.feature.f062

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 一条投屏会话的当前快照。
 *
 * 会话本身就是状态机：投屏建立时是 [State.IDLE]，Play/Restart 之后进 [State.PLAYING]，
 * Pause 进 [State.PAUSED]，Stop 回到 [State.IDLE]。界面每次拿到的都是引擎返回的**新快照**，
 * 不原地改对象——这样渲染和网络结果永远同源，不会出现"按钮说在播、设备其实停了"的错觉。
 *
 * @param device     设备显示名（只用于界面与日志，协议里不参与）
 * @param controlUrl AVTransport 的 controlURL，POST SOAP 就打这个地址
 * @param volume     最近一次确认过的音量（0~100），由 RenderingControl 的 GetVolume / SetVolume 回填
 * @param state      状态机当前态
 * @param relTimeMs  最近一次确认过的进度（毫秒）
 * @param lastAction 最近一次下发的动作名，界面提示"已执行 Play"用
 * @param lastSummary 最近一次 SOAP 往返的一行摘要
 */
data class Session(
    val device: String,
    val controlUrl: String,
    val volume: Int,
    val state: State = State.IDLE,
    val relTimeMs: Long = 0L,
    val lastAction: String = "",
    val lastSummary: String = ""
) {
    /** 投屏建立之后只在这三态之间流转，没有第四种"未知"。 */
    enum class State {
        IDLE,
        PLAYING,
        PAUSED
    }

    /** 音量夹到 0~100：有些设备回 200 之外的值，界面不该显示成 137。 */
    fun withVolume(value: Int): Session = copy(volume = value.coerceIn(F062Engine.MIN_VOLUME, F062Engine.MAX_VOLUME))
}

/**
 * 一次 SOAP 往返的结果。
 *
 * @param action     动作名，例如 Play / Seek / SetVolume
 * @param endpoint   实际 POST 的地址（RenderingControl 的地址由 AVTransport 的地址推导，见 [F062Engine.endpointFor]）
 * @param soapAction 发出去的 SOAPAction 头，排查兼容性问题时最常要看的就是它
 * @param ok         HTTP 2xx 且响应体里没有 UPnP fault
 * @param httpCode   状态码；连接层就失败时为 0
 * @param ms         本次往返耗时
 * @param body       响应体原文（折叠面板展示，超长截断）
 * @param faultCode  响应体 `<errorCode>` 里的 UPnP 错误码
 * @param faultText  响应体 `<errorDescription>` 里的原文描述
 * @param error      收敛后的人话失败原因；成功为 null
 */
data class SoapReply(
    val action: String,
    val endpoint: String,
    val soapAction: String,
    val ok: Boolean,
    val httpCode: Int,
    val ms: Long,
    val body: String,
    val faultCode: Int? = null,
    val faultText: String? = null,
    val error: String? = null
) {
    /** 一行摘要：`Play · HTTP 200 · 38 ms` / `Seek 失败 · HTTP 500 · UPnP 701（Transition not available）`。 */
    val brief: String
        get() {
            val head = if (ok) action else "$action 失败"
            val code = if (httpCode > 0) "HTTP $httpCode" else "连接层"
            val fault = faultCode?.let { upnp ->
                " · UPnP $upnp" + (faultText?.takeIf { it.isNotBlank() }?.let { text -> "（$text）" } ?: "")
            }.orEmpty()
            return "$head · $code$fault · $ms ms"
        }

    /** 折叠面板里的完整回执：请求行 + 状态 + 响应体（截断）。 */
    val detail: String
        get() = buildString {
            append("POST ").append(endpoint).append('\n')
            append("SOAPAction: ").append(soapAction).append('\n')
            append(if (httpCode > 0) "HTTP $httpCode" else "连接层失败")
            append(" · ").append(ms).append(" ms")
            val reason = error
            if (reason != null) append('\n').append("错误：").append(reason)
            if (body.isNotBlank()) append('\n').append(trimBody(body))
        }
}

/**
 * 一次控制动作的最终结果。
 *
 * @param ok      动作是否被设备接受
 * @param session 动作之后的新会话快照（失败时也要返回：界面靠它把失败后的真实态显示出来）
 * @param trace   本次往返的完整回执文本，喂给可折叠的"最近一次 SOAP 响应"
 * @param error   失败原因；成功为 null
 * @param note    部分成功的说明（例如"已开始播放，但回到开头失败"）；成功且完整时为 null
 */
data class Outcome(
    val ok: Boolean,
    val session: Session,
    val trace: String,
    val error: String? = null,
    val note: String? = null
)

/**
 * 投屏会话控制引擎：直接对设备的 AVTransport / RenderingControl 服务发 SOAP。
 *
 * 为什么自己拼 SOAP 而不复用 [com.tv.ball.domain.dlna.DLNAManager]：那个类只覆盖"投屏这一刻"
 * （SetAVTransportURI + Play），拿不到返回值、也无法回读状态；而"投屏会话控制"要的是
 * **双向**——发命令 + 解析回执，还要能查询设备当前到底在播还是在暂停。
 *
 * 协议约束（UPnP AVTransport:1）：
 * 1. 所有动作都是 `POST controlURL`，头带 `Content-Type: text/xml; charset=utf-8` 和
 *    `SOAPAction: "urn:schemas-upnp-org:service:AVTransport:1#<动作>"`（**引号是规范的一部分**）；
 * 2. 请求体是 `<s:Envelope><s:Body><u:动作>...</u:动作></s:Body></s:Envelope>`；
 * 3. 失败时 HTTP 可能是 200 也可能是 500，真正的错误码在 `<errorCode>` 里——所以判断成功
 *    不能只看状态码，必须把 fault 一起看（[SoapReply.ok]）；
 * 4. 音量不属于 AVTransport，走同一台设备的 RenderingControl 服务，controlURL 由
 *    `/AVTransport/control` 换成 `/RenderingControl/control`（[endpointFor]）。
 *
 * 错误收敛：超时 / 拒绝连接 / DNS / 非法地址 / HTTP 状态码 / UPnP 错误码，最后都收敛成
 * [SoapReply.error] 里的一句话，界面只负责显示，不再二次判断。
 *
 * 全部是阻塞调用，调用方必须放在 Dispatchers.IO 上跑；引擎自身无状态（会话由调用方持有并传入），
 * 因此多线程下只共享一个 OkHttpClient，天然安全。
 */
class F062Engine(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    // ===== 传输控制（AVTransport） =====

    /** 播放。 */
    fun play(current: Session): Outcome =
        transport(current, ACTION_PLAY, playBody()) { it.copy(state = Session.State.PLAYING) }

    /**
     * 从头重播。
     *
     * UPnP 没有 Restart 这个动作，标准做法是 `Seek(REL_TIME, 00:00:00)` + `Play`。
     * 两个请求串行发：有的设备在 STOPPED 态不接受 Seek（回 701），但 Play 仍然有效，
     * 所以以 Play 的结果判成功，Seek 失败只作为 [Outcome.note] 提示，不把整件事判死。
     */
    fun restart(current: Session): Outcome {
        val seek = call(current, SERVICE_AVT, ACTION_SEEK, seekBody(0L))
        val play = call(current, SERVICE_AVT, ACTION_PLAY, playBody())
        val replies = listOf(seek, play)
        val note = if (play.ok && !seek.ok) {
            "已从头播放，但回到 0 秒失败：" + (seek.error ?: "设备拒绝")
        } else {
            null
        }
        return finish(replies, current, play.ok, note = note) {
            it.copy(
                state = Session.State.PLAYING,
                // Seek 没成功就别假装进度归零了，保留旧值并在 note 里说明
                relTimeMs = if (seek.ok) 0L else it.relTimeMs
            )
        }
    }

    /** 暂停。 */
    fun pause(current: Session): Outcome =
        transport(current, ACTION_PAUSE, instanceBody()) { it.copy(state = Session.State.PAUSED) }

    /** 停止，进度一并归零。 */
    fun stop(current: Session): Outcome =
        transport(current, ACTION_STOP, instanceBody()) {
            it.copy(state = Session.State.IDLE, relTimeMs = 0L)
        }

    /**
     * 进度跳转。
     *
     * 单位固定 `REL_TIME`（相对开头的绝对时间），目标格式 `HH:MM:SS`——这是绝大多数
     * 电视盒子 / 投影仪唯一认的 Seek 模式；`ABS_TIME` 与 `TRACK_NR` 设备支持度差得多。
     */
    fun seek(current: Session, targetMs: Long): Outcome {
        val target = targetMs.coerceAtLeast(0L)
        return transport(current, ACTION_SEEK, seekBody(target)) { it.copy(relTimeMs = target) }
    }

    // ===== 音量（RenderingControl） =====

    /** 设置音量，越界值先夹到 0~100 再下发，免得设备直接回 402。 */
    fun setVolume(current: Session, volume: Int): Outcome {
        val target = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        return control(current, ACTION_SET_VOLUME, setVolumeBody(target)) { it.copy(volume = target) }
    }

    /**
     * 回读设备：TransportInfo（播放态）+ PositionInfo（进度）+ GetVolume（音量）。
     *
     * 三个请求都要发，但彼此独立：任意一个成功就更新对应字段，全挂才算失败。
     * 投屏设备的状态经常被遥控器从另一端改掉，只信本地状态机迟早会和设备对不上，
     * 所以界面要有一个"刷新"动作走这条路径。
     */
    fun refresh(current: Session): Outcome {
        val info = call(current, SERVICE_AVT, ACTION_GET_INFO, instanceBody())
        val position = call(current, SERVICE_AVT, ACTION_GET_POSITION, instanceBody())
        val volume = call(current, SERVICE_RCS, ACTION_GET_VOLUME, volumeBody())
        val replies = listOf(info, position, volume)

        val nextState = if (info.ok) stateOf(tagOf(info.body, "CurrentTransportState")) else null
        val nextRelTime = if (position.ok) relTimeOf(tagOf(position.body, "RelTime")) else null
        val nextVolume = if (volume.ok) volumeOf(volume.body) else null

        val missing = buildList {
            if (!info.ok) add("播放态")
            if (!position.ok) add("进度")
            if (!volume.ok) add("音量")
        }
        val ok = missing.size < replies.size
        val note = if (ok && missing.isNotEmpty()) "部分信息没读到：" + missing.joinToString("、") else null

        return finish(replies, current, ok, note = note) { base ->
            base.copy(
                state = nextState ?: base.state,
                relTimeMs = nextRelTime ?: base.relTimeMs,
                volume = nextVolume ?: base.volume
            )
        }
    }

    // ===== 动作组装 =====

    private fun transport(
        current: Session,
        action: String,
        inner: String,
        apply: (Session) -> Session
    ): Outcome {
        val reply = call(current, SERVICE_AVT, action, inner)
        return finish(listOf(reply), current, reply.ok) { apply(it) }
    }

    private fun control(
        current: Session,
        action: String,
        inner: String,
        apply: (Session) -> Session
    ): Outcome {
        val reply = call(current, SERVICE_RCS, action, inner)
        return finish(listOf(reply), current, reply.ok) { apply(it) }
    }

    /**
     * 把若干次往返收敛成一个 [Outcome]。
     *
     * 无论成功失败都更新 `lastAction` / `lastSummary`：失败时界面正需要看到"刚才那发到底回了什么"。
     */
    private fun finish(
        replies: List<SoapReply>,
        current: Session,
        ok: Boolean,
        note: String? = null,
        apply: (Session) -> Session
    ): Outcome {
        val base = current.copy(
            lastAction = replies.lastOrNull()?.action ?: current.lastAction,
            lastSummary = replies.joinToString("  |  ") { it.brief }
        )
        val failure = replies.firstOrNull { !it.ok }
        return Outcome(
            ok = ok,
            session = if (ok) apply(base) else base,
            trace = replies.joinToString("\n\n") { it.detail },
            error = if (ok) null else (failure?.error ?: "设备没有返回成功响应"),
            note = if (ok) note else null
        )
    }

    // ===== 网络 =====

    private fun call(session: Session, service: String, action: String, inner: String): SoapReply {
        val endpoint = endpointFor(service, session.controlUrl)
        val soapAction = "\"urn:schemas-upnp-org:service:$service:1#$action\""
        val started = clock()
        return try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", soapAction)
                .header("User-Agent", USER_AGENT)
                .post(envelope(service, action, inner).toRequestBody(XML_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val faultCode = faultCodeOf(body)
                val faultText = faultTextOf(body)
                // 关键：UPnP 的失败可能带着 HTTP 200 回来，必须同时看 fault
                val accepted = response.isSuccessful && faultCode == null
                SoapReply(
                    action = action,
                    endpoint = endpoint,
                    soapAction = soapAction,
                    ok = accepted,
                    httpCode = response.code,
                    ms = clock() - started,
                    body = body,
                    faultCode = faultCode,
                    faultText = faultText,
                    error = if (accepted) null else describeFailure(response.code, faultCode, faultText)
                )
            }
        } catch (e: Exception) {
            SoapReply(
                action = action,
                endpoint = endpoint,
                soapAction = soapAction,
                ok = false,
                httpCode = 0,
                ms = clock() - started,
                body = "",
                error = describeThrowable(e)
            )
        }
    }

    // ===== SOAP 报文 =====

    private fun envelope(service: String, action: String, inner: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">
      $inner
    </u:$action>
  </s:Body>
</s:Envelope>"""

    private fun instanceBody(): String = "<InstanceID>$INSTANCE_ID</InstanceID>"

    private fun playBody(): String =
        "<InstanceID>$INSTANCE_ID</InstanceID>\n      <Speed>1</Speed>"

    private fun seekBody(targetMs: Long): String =
        "<InstanceID>$INSTANCE_ID</InstanceID>\n" +
            "      <Unit>$SEEK_UNIT</Unit>\n" +
            "      <Target>${formatRelTime(targetMs)}</Target>"

    private fun volumeBody(): String =
        "<InstanceID>$INSTANCE_ID</InstanceID>\n      <Channel>$CHANNEL</Channel>"

    private fun setVolumeBody(volume: Int): String =
        "<InstanceID>$INSTANCE_ID</InstanceID>\n" +
            "      <Channel>$CHANNEL</Channel>\n" +
            "      <DesiredVolume>$volume</DesiredVolume>"

    // ===== 工具 =====

    /** 错误收敛：把 HTTP 状态码 / UPnP 错误码收敛成一句话。 */
    private fun describeFailure(httpCode: Int, faultCode: Int?, faultText: String?): String = when {
        faultCode != null -> {
            val hint = faultText?.takeIf { it.isNotBlank() } ?: upnpFaultHint(faultCode)
            "UPnP $faultCode：$hint"
        }
        httpCode == 401 -> "设备要求认证（HTTP 401）"
        httpCode == 404 -> "控制地址不对（HTTP 404），确认结尾是 /AVTransport/control"
        httpCode in 500..599 -> "设备内部错误（HTTP $httpCode）"
        else -> "HTTP $httpCode"
    }

    /** 错误收敛：连接层异常的原始信息对用户没意义，逐个翻译。 */
    private fun describeThrowable(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "设备 $TIMEOUT_SECONDS 秒内没有响应"
        is UnknownHostException -> "找不到设备地址，检查 IP 是否在同一网段"
        is ConnectException -> "设备拒绝连接，可能已关机或端口不对"
        is IllegalArgumentException -> "控制地址不合法"
        is IOException -> "网络异常：${brief(e)}"
        else -> brief(e)
    }

    private fun brief(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return if (message != null) "${e.javaClass.simpleName}: $message" else e.javaClass.simpleName
    }

    companion object {
        /** AVTransport 服务名，直接拼进 SOAPAction 与默认 controlURL。 */
        const val SERVICE_AVT: String = "AVTransport"

        /** RenderingControl 服务名，音量走它。 */
        const val SERVICE_RCS: String = "RenderingControl"

        /** 界面默认回填的控制地址：标准 49152 端口 + 标准路径，换成实际设备的即可。 */
        const val DEFAULT_CONTROL_URL: String = "http://192.168.1.10:49152/AVTransport/control"

        /** 音量下限。 */
        const val MIN_VOLUME: Int = 0

        /** 音量上限。 */
        const val MAX_VOLUME: Int = 100

        /** 一次 ± 调多少。 */
        const val VOLUME_STEP: Int = 5

        /** 单次 SOAP 往返的超时：投屏设备在局域网内，5 秒没回就是真挂了。 */
        const val TIMEOUT_SECONDS: Long = 5

        private const val INSTANCE_ID = "0"
        private const val CHANNEL = "Master"
        private const val SEEK_UNIT = "REL_TIME"

        private const val ACTION_PLAY = "Play"
        private const val ACTION_PAUSE = "Pause"
        private const val ACTION_STOP = "Stop"
        private const val ACTION_SEEK = "Seek"
        private const val ACTION_SET_VOLUME = "SetVolume"
        private const val ACTION_GET_VOLUME = "GetVolume"
        private const val ACTION_GET_INFO = "GetTransportInfo"
        private const val ACTION_GET_POSITION = "GetPositionInfo"

        private const val USER_AGENT = "TVPlayer/1.0 UPnP/1.0 DLNADOC/1.50"

        /**
         * 服务名 → 实际打的地址。
         *
         * 音量和播放态不在同一个服务上，但同一台设备上两个 controlURL 只差中间那段，
         * 所以让用户只填一个（AVTransport 的）就够了；填的要是别的路径就原样用，
         * 让设备自己回 404/501，比在这里瞎猜更诚实。
         */
        fun endpointFor(service: String, controlUrl: String): String {
            val url = controlUrl.trim()
            if (service != SERVICE_RCS) return url
            return if (url.contains("/AVTransport/control", ignoreCase = true)) {
                url.replace("/AVTransport/control", "/RenderingControl/control", ignoreCase = true)
            } else {
                url
            }
        }

        /** 毫秒 → `HH:MM:SS`（Seek 的 Target 只能是这个格式）。 */
        fun formatRelTime(ms: Long): String {
            val totalSeconds = ms.coerceAtLeast(0L) / 1000L
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }

        /**
         * `HH:MM:SS` / `MM:SS` → 毫秒；格式不对返回 null（界面据此提示，不发请求）。
         *
         * 分秒必须小于 60：用户手滑写成 `00:99:00` 时宁可直接拦下来，也不要让设备回 711。
         */
        fun parseRelTime(text: String?): Long? {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val parts = raw.split(':')
            if (parts.size != 2 && parts.size != 3) return null
            val numbers = parts.map { it.toLongOrNull() ?: return null }
            if (numbers.any { it < 0L }) return null
            val seconds = numbers.last()
            if (seconds >= 60L) return null
            return when (numbers.size) {
                2 -> (numbers[0] * 60L + seconds) * 1000L
                else -> {
                    if (numbers[1] >= 60L) return null
                    (numbers[0] * 3600L + numbers[1] * 60L + seconds) * 1000L
                }
            }
        }

        /** UPnP 标准错误码 → 人话。 */
        fun upnpFaultHint(code: Int): String = when (code) {
            401 -> "设备不认识这个动作"
            402 -> "参数不合法"
            501 -> "设备执行失败"
            701 -> "当前状态不允许这个切换"
            702 -> "设备上没有可操作的媒体"
            704, 710, 711 -> "跳转目标不被接受"
            714 -> "媒体类型不支持"
            715 -> "设备忙"
            716 -> "资源不存在"
            718 -> "InstanceID 无效"
            else -> "设备拒绝"
        }

        /** `CurrentTransportState` → 三态；不认识的态返回 null，保留本地判断。 */
        fun stateOf(tag: String?): Session.State? = when (tag?.trim()?.uppercase(Locale.US)) {
            "PLAYING" -> Session.State.PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> Session.State.PAUSED
            "STOPPED", "NO_MEDIA_PRESENT" -> Session.State.IDLE
            else -> null
        }

        /** `CurrentVolume` → 0~100；没有或不是数字返回 null。 */
        fun volumeOf(body: String): Int? =
            tagOf(body, "CurrentVolume")?.toIntOrNull()?.coerceIn(MIN_VOLUME, MAX_VOLUME)

        /**
         * 控制专用客户端。
         *
         * 不跟随重定向（SOAP 被 302 到登录页说明地址填错了，跟着走只会拿到一份 HTML 让人困惑）、
         * 不自动重试（一次点击发两发 Play，设备可能真的播两遍）。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** SOAP 请求体的媒体类型。 */
private val XML_TYPE = "text/xml; charset=utf-8".toMediaType()

/** 回执原文在折叠面板里最多留这么多字符。 */
private const val BODY_LIMIT = 1500

/** `<errorCode>701</errorCode>`。 */
private val FAULT_CODE_RE = Regex("<errorCode>\\s*(-?\\d+)\\s*</errorCode>", RegexOption.IGNORE_CASE)

/** `<errorDescription>Transition not available</errorDescription>`。 */
private val FAULT_TEXT_RE = Regex("<errorDescription>\\s*([^<]*?)\\s*</errorDescription>", RegexOption.IGNORE_CASE)

private fun faultCodeOf(body: String): Int? =
    FAULT_CODE_RE.find(body)?.groupValues?.get(1)?.toIntOrNull()

private fun faultTextOf(body: String): String? =
    FAULT_TEXT_RE.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 从 SOAP 响应里取一个标签的值，容忍命名空间前缀（`<CurrentTransportState>` 与
 * `<u:CurrentTransportState>` 都能取到），也容忍 `NOT_IMPLEMENTED` 这类非数字值。
 */
private fun tagOf(body: String, name: String): String? =
    Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>\\s*([^<]*?)\\s*</(?:[A-Za-z0-9_]+:)?$name\\s*>")
        .find(body)?.groupValues?.get(1)

/** `RelTime`（`00:01:23`）→ 毫秒；`NOT_IMPLEMENTED` 之类返回 null。 */
private fun relTimeOf(text: String?): Long? {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val parts = raw.split(':')
    if (parts.size != 2 && parts.size != 3) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    return when (numbers.size) {
        2 -> (numbers[0] * 60L + numbers[1]) * 1000L
        else -> (numbers[0] * 3600L + numbers[1] * 60L + numbers[2]) * 1000L
    }
}

/** 回执原文截断：折叠面板是给人扫一眼的，不是日志。 */
private fun trimBody(body: String): String {
    val normalized = body.replace("\r\n", "\n").trim()
    return if (normalized.length <= BODY_LIMIT) {
        normalized
    } else {
        normalized.take(BODY_LIMIT) + "\n… 已截断，共 ${normalized.length} 字符"
    }
}
