package com.tv.ball.domain.dlna

import java.net.URL
import java.util.Locale

/**
 * 设备描述 XML 的解析：从 `LOCATION` 指向的 device description 里，把
 * `urn:schemas-upnp-org:service:AVTransport:1` 对应的 **真正 controlURL** 抠出来。
 *
 * 为什么必须要这一步：
 * 原来的实现用 `location.replace("/description.xml", "/AVTransport/control")` 猜控制地址，
 * 而各家固件的 controlURL 长得完全不一样（`/upnp/control/AVTransport1`、`/AVTransport/ctrl`、
 * `/ctl/AVTransport`、`/MediaRenderer/AVTransport/control` …），猜错就是 404/500，
 * 表现就是那句「投屏失败，请重试」。
 *
 * 解析只处理 `<service>` 块，不建 DOM（设备描述经常不规范，正则更耐受）。
 */
object DLNAServiceParser {

    /** 投屏用的服务类型；`AVTransport:2` 的服务也接受，老设备可能只报版本 1。 */
    private val AV_TRANSPORT_SERVICE_PREFIX = "urn:schemas-upnp-org:service:avtransport:"

    private val SERVICE_BLOCK_RE = Regex(
        "<service>(.*?)</service>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val SERVICE_TYPE_RE = Regex("<serviceType>(.*?)</serviceType>", RegexOption.IGNORE_CASE)
    private val CONTROL_URL_RE = Regex("<controlURL>(.*?)</controlURL>", RegexOption.IGNORE_CASE)
    private val FRIENDLY_NAME_RE = Regex(
        "<friendlyName>(.*?)</friendlyName>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val MODEL_NAME_RE = Regex(
        "<modelName>(.*?)</modelName>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 解析结果：控制地址已拼成绝对 URL。 */
    data class Description(
        val friendlyName: String,
        val modelName: String,
        val avTransportControlUrl: String
    )

    /**
     * 解析设备描述 XML。
     *
     * [location] 是这份 XML 自己的地址，用来把相对的 `controlURL` 拼成绝对地址；
     * 有些设备直接给绝对地址，那就原样用。
     *
     * 找不到 AVTransport 服务时 `avTransportControlUrl` 为空串——调用方据此给出可读的失败原因，
     * 而不是拿一个猜的地址去 POST。
     */
    fun parse(xml: String, location: String): Description {
        val friendlyName = unescape(friendlyNameOf(xml))
        val modelName = unescape(modelNameOf(xml))
        val controlUrl = findAvTransportControlUrl(xml, location)
        return Description(friendlyName, modelName, controlUrl)
    }

    /** 从描述里取 `friendlyName`，抠不到返回空串。 */
    fun friendlyNameOf(xml: String): String =
        FRIENDLY_NAME_RE.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun modelNameOf(xml: String): String =
        MODEL_NAME_RE.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    /**
     * 在描述里找 AVTransport 服务的 controlURL，并转成绝对地址。
     *
     * 退回策略：匹配不到 `<service>` 块时，再全局找一次 `AVTransport` 附近的 `controlURL`，
     * 因为个别设备的描述 XML 把 `<service>` 写得不闭合，正则切不出完整块。
     */
    private fun findAvTransportControlUrl(xml: String, location: String): String {
        for (block in SERVICE_BLOCK_RE.findAll(xml)) {
            val body = block.groupValues[1]
            val type = SERVICE_TYPE_RE.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (!type.lowercase(Locale.ROOT).startsWith(AV_TRANSPORT_SERVICE_PREFIX)) continue
            val raw = unescape(CONTROL_URL_RE.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty())
            if (raw.isNotEmpty()) return absolutize(raw, location)
        }
        return ""
    }

    /** 相对 controlURL 拼成绝对地址；绝对地址原样返回。 */
    fun absolutize(controlUrl: String, location: String): String {
        val trimmed = controlUrl.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return try {
            URL(URL(location), trimmed).toString()
        } catch (t: Throwable) {
            trimmed
        }
    }

    /**
     * 设备描述里的 XML 实体还原；`&amp;` 必须最后替换，否则 `&amp;lt;` 会被解两次。
     */
    fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}