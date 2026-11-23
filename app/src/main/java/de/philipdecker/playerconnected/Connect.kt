package de.philipdecker.playerconnected

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Ein UPnP-Dienst eines Geräts: vollständiger Typ (mit Version) und Steuer-Adresse */
data class Svc(val type: String, val url: String)

/** Ein Wiedergabeziel im Heimnetz: Sonos (Gruppe als ein Eintrag) oder UPnP/DLNA-Renderer */
data class Target(
    val id: String,
    val name: String,
    val sub: String,
    val sonos: Boolean,
    val host: String,
    val avt: Svc,
    val rc: Svc?,
    /** Sonos: Lautstärke der ganzen Gruppe */
    val grc: Svc?,
    val group: Int = 1,
    /** Minimal Library am Computer (Fernsteuerung) – pcOutput = Ausgabe dort (local = Lautsprecher des PCs) */
    val pc: Boolean = false,
    val pcOutput: String = "",
)

/**
 * Streaming ins Heimnetz. Nur lokale Adressen, nichts geht ins Internet.
 * Die Oberfläche setzt [wanted]; der Wiedergabedienst schaltet um und meldet über [active] zurück.
 */
object Connect {
    val devices = MutableStateFlow<List<Target>>(emptyList())
    val searching = MutableStateFlow(false)
    /** Gewünschtes Ziel (null = dieses Handy) */
    val wanted = MutableStateFlow<Target?>(null)
    /** Tatsächlich verbundenes Ziel */
    val active = MutableStateFlow<Target?>(null)
    /** Kurzer Hinweis als String-Ressource (z. B. Verbindung verloren) */
    val notice = MutableStateFlow<Int?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun select(t: Target?) { notice.value = null; wanted.value = t }

    fun search(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            searching.value = true
            try {
                val found = withContext(Dispatchers.IO) { try { discover(app) } catch (_: Throwable) { emptyList() } }
                // Das verbundene Gerät bleibt sichtbar, auch wenn es diesmal nicht geantwortet hat
                val act = active.value
                devices.value = if (act != null && found.none { it.id == act.id }) found + act else found
            } finally { searching.value = false }
        }
    }

    // ───────── Suche (SSDP) ─────────

    private const val MR = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val ZP = "urn:schemas-upnp-org:device:ZonePlayer:1"

    private suspend fun discover(ctx: Context): List<Target> {
        val locations = ssdp(ctx)
        val descs = coroutineScope { locations.map { async(Dispatchers.IO) { describe(it) } }.awaitAll() }
            .filterNotNull().distinctBy { it.udn }
        val sonos = descs.filter { it.sonos }
        val out = ArrayList<Target>()
        if (sonos.isNotEmpty()) out += sonosGroups(sonos) ?: sonos.mapNotNull { it.toTarget() }
        out += descs.filter { !it.sonos }.mapNotNull { it.toTarget() }
        return out.distinctBy { it.id }.sortedWith(compareBy(Natural) { it.name })
    }

    /** M-SEARCH über jede WLAN-/LAN-Schnittstelle, sammelt die LOCATION-Adressen ~3 s lang */
    private fun ssdp(ctx: Context): Set<String> {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try { wifi?.createMulticastLock("connect")?.apply { setReferenceCounted(false); acquire() } } catch (_: Throwable) { null }
        val sockets = ArrayList<MulticastSocket>()
        val found = LinkedHashSet<String>()
        try {
            for ((ni, addr) in lanAddresses()) {
                try {
                    sockets += MulticastSocket(InetSocketAddress(addr, 0)).apply {
                        networkInterface = ni
                        timeToLive = 4
                        soTimeout = 120
                    }
                } catch (_: Exception) { }
            }
            if (sockets.isEmpty()) sockets += MulticastSocket(0).apply { soTimeout = 120 }
            val group = InetSocketAddress("239.255.255.250", 1900)
            fun send() {
                for (s in sockets) for (st in listOf(MR, ZP)) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    try { s.send(DatagramPacket(msg, msg.size, group)) } catch (_: Exception) { }
                }
            }
            send()
            val start = System.currentTimeMillis()
            var resent = false
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() - start < 3200) {
                if (!resent && System.currentTimeMillis() - start > 900) { send(); resent = true }
                for (s in sockets) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                        text.lineSequence().firstOrNull { it.startsWith("location:", true) }
                            ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http://") }
                            ?.let { found += it }
                    } catch (_: SocketTimeoutException) { } catch (_: Exception) { }
                }
            }
        } finally {
            sockets.forEach { try { it.close() } catch (_: Exception) { } }
            try { lock?.release() } catch (_: Throwable) { }
        }
        return found
    }

    /** IPv4-Adressen im Heimnetz (WLAN, LAN, Hotspot) */
    private fun lanAddresses(): List<Pair<NetworkInterface, Inet4Address>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { ni to it } }
    } catch (_: Exception) { emptyList() }

    // ───────── Gerätebeschreibung ─────────

    private class Desc(
        val udn: String, val name: String, val room: String?, val maker: String, val model: String,
        val host: String, val services: Map<String, Svc>, val location: String,
    ) {
        val sonos get() = maker.contains("Sonos", ignoreCase = true)
        fun toTarget(): Target? {
            val avt = services["AVTransport"] ?: return null
            val label = room?.takeIf { it.isNotBlank() } ?: name
            val sub = listOf(maker.removeSuffix(", Inc.").removeSuffix(" Inc."), model).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            return Target(udn, label, sub, sonos, host, avt, services["RenderingControl"], services["GroupRenderingControl"])
        }
    }

    private val descCache = HashMap<String, Desc>()

    private fun describe(location: String): Desc? {
        synchronized(descCache) { descCache[location]?.let { return it } }
        return try {
            val doc = Upnp.xml(Upnp.get(location))
            val base = doc.getElementsByTagName("URLBase").item(0)?.textContent?.trim()?.takeIf { it.startsWith("http") } ?: location
            val root = doc.getElementsByTagName("device").item(0) as? Element ?: return null
            val services = HashMap<String, Svc>()
            val list = doc.getElementsByTagName("service")
            for (i in 0 until list.length) {
                val s = list.item(i) as? Element ?: continue
                val type = Upnp.child(s, "serviceType") ?: continue
                val ctrl = Upnp.child(s, "controlURL") ?: continue
                val key = type.substringBeforeLast(':').substringAfterLast(':')
                services.putIfAbsent(key, Svc(type, URL(URL(base), ctrl).toString()))
            }
            // Name des Geräts mit AVTransport (bei Sonos das eingebettete MediaRenderer-Gerät)
            val devs = doc.getElementsByTagName("device")
            var name = Upnp.child(root, "friendlyName") ?: "?"
            for (i in 0 until devs.length) {
                val d = devs.item(i) as? Element ?: continue
                if (Upnp.child(d, "deviceType")?.contains("MediaRenderer") == true) { name = Upnp.child(d, "friendlyName") ?: name; break }
            }
            Desc(
                udn = (Upnp.child(root, "UDN") ?: location).removePrefix("uuid:"),
                name = name,
                room = Upnp.child(root, "roomName"),
                maker = Upnp.child(root, "manufacturer") ?: "",
                model = Upnp.child(root, "modelName") ?: "",
                host = URL(location).host,
                services = services,
                location = location,
            ).also { synchronized(descCache) { descCache[location] = it } }
        } catch (_: Exception) { null }
    }

    /** Sonos: je Gruppe ein Eintrag (Koordinator steuert), unsichtbare Mitglieder (Surround, Sub) ausgeblendet */
    private fun sonosGroups(sonos: List<Desc>): List<Target>? {
        val zgt = sonos.firstNotNullOfOrNull { it.services["ZoneGroupTopology"] } ?: return null
        val state = try { Upnp.value(Upnp.soap(zgt, "GetZoneGroupState", emptyList()), "ZoneGroupState") } catch (_: Exception) { null } ?: return null
        val doc = try { Upnp.xml(state) } catch (_: Exception) { return null }
        val groups = doc.getElementsByTagName("ZoneGroup")
        val out = ArrayList<Target>()
        for (i in 0 until groups.length) {
            val g = groups.item(i) as? Element ?: continue
            val coord = g.getAttribute("Coordinator")
            val members = (0 until g.childNodes.length).mapNotNull { g.childNodes.item(it) as? Element }
                .filter { it.tagName == "ZoneGroupMember" && it.getAttribute("Invisible") != "1" }
            val head = members.firstOrNull { it.getAttribute("UUID") == coord } ?: continue
            val desc = sonos.firstOrNull { it.udn.startsWith(coord) }
                ?: head.getAttribute("Location").takeIf { it.isNotEmpty() }?.let { describe(it) }
                ?: continue
            val base = desc.toTarget() ?: continue
            val headName = head.getAttribute("ZoneName").ifEmpty { base.name }
            val others = members.filter { it !== head }.map { it.getAttribute("ZoneName") }.filter { it.isNotEmpty() && it != headName }.distinct()
            out += base.copy(
                id = coord,
                name = if (others.isEmpty()) headName else "$headName + ${others.size}",
                sub = if (others.isEmpty()) "Sonos · ${desc.model}" else "Sonos · " + (listOf(headName) + others).joinToString(", "),
                group = 1 + others.size,
            )
        }
        return out.takeIf { it.isNotEmpty() }
    }
}

/** Kleine UPnP-Hilfen: SOAP-Aufrufe, XML, Zeitformat */
object Upnp {
    fun get(url: String, timeout: Int = 3000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    fun soap(svc: Svc, action: String, args: List<Pair<String, String>>, timeout: Int = 5000): String {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(svc.type).append("\">")
            for ((k, v) in args) append('<').append(k).append('>').append(esc(v)).append("</").append(k).append('>')
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }.toByteArray(Charsets.UTF_8)
        val c = URL(svc.url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"${svc.type}#$action\"")
        c.setFixedLengthStreamingMode(body.size)
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = try { c.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw IOException("$action: HTTP $code ${err?.let { value(it, "errorCode") } ?: ""}")
            }
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    fun value(xml: String, tag: String): String? =
        Regex("<(?:[A-Za-z0-9]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9]+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.let { unesc(it) }

    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun unesc(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun xml(s: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
        f.isNamespaceAware = false
        return f.newDocumentBuilder().parse(InputSource(StringReader(s.trim().removePrefix("﻿"))))
    }

    /** Text des direkten Kind-Elements */
    fun child(e: Element, tag: String): String? {
        val n = e.childNodes
        for (i in 0 until n.length) {
            val c = n.item(i) as? Element ?: continue
            if (c.tagName == tag || c.tagName.endsWith(":$tag")) return c.textContent?.trim()
        }
        return null
    }

    /** ms → H:MM:SS */
    fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    /** H:MM:SS(.mmm) → ms; -1 wenn unbekannt */
    fun parseTime(t: String?): Long {
        if (t.isNullOrBlank()) return -1
        val p = t.trim().split(':')
        if (p.size != 3) return -1
        val h = p[0].toLongOrNull() ?: return -1
        val m = p[1].toLongOrNull() ?: return -1
        val sec = p[2].toDoubleOrNull() ?: return -1
        return h * 3_600_000 + m * 60_000 + (sec * 1000).toLong()
    }
}
