package org.blindrss.android.cast
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL

data class DlnaDevice(val name: String, val controlUrl: String, val location: String)

// Minimal UPnP AVTransport casting: SSDP discover MediaRenderer, then SOAP play.
object Dlna {
    private val http = OkHttpClient()
    suspend fun discover(ctx: Context): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("blindrss-dlna")?.apply { setReferenceCounted(false); acquire() }
        val found = mutableMapOf<String, DlnaDevice>()
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = 3000
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                val addr = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(msg.toByteArray(), msg.length, addr, 1900))
                val buf = ByteArray(4096); val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 3500) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { socket.receive(pkt) } catch (_: Exception) { break }
                    val resp = String(pkt.data, 0, pkt.length)
                    val loc = Regex("LOCATION:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(resp)?.groupValues?.get(1) ?: continue
                    if (!found.containsKey(loc)) describe(loc)?.let { found[loc] = it }
                }
            }
        } catch (_: Exception) {} finally { try { lock?.release() } catch (_: Exception) {} }
        found.values.toList()
    }
    private fun describe(location: String): DlnaDevice? {
        return try {
            val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() ?: "" }
            val name = Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)?.groupValues?.get(1) ?: "DLNA device"
            val services = Regex("<service>([\\s\\S]*?)</service>").findAll(xml).toList()
            var control: String? = null
            for (s in services) { if (s.groupValues[1].contains("AVTransport")) { control = Regex("<controlURL>([^<]+)</controlURL>").find(s.groupValues[1])?.groupValues?.get(1); break } }
            if (control == null) return null
            DlnaDevice(name, URL(URL(location), control).toString(), location)
        } catch (_: Exception) { null }
    }
    private fun soap(controlUrl: String, action: String, args: String): Boolean {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$args</u:$action></s:Body></s:Envelope>"""
        return try { http.newCall(Request.Builder().url(controlUrl)
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .post(body.toRequestBody("text/xml".toMediaType())).build()).execute().use { it.isSuccessful } } catch (_: Exception) { false }
    }
    suspend fun play(device: DlnaDevice, mediaUrl: String, title: String): Boolean = withContext(Dispatchers.IO) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val meta = esc("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>${esc(title)}</dc:title><upnp:class>object.item.audioItem</upnp:class><res protocolInfo="http-get:*:audio/mpeg:*">${esc(mediaUrl)}</res></item></DIDL-Lite>""")
        soap(device.controlUrl, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${esc(mediaUrl)}</CurrentURI><CurrentURIMetaData>$meta</CurrentURIMetaData>") &&
            soap(device.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }
    suspend fun stop(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) { soap(device.controlUrl, "Stop", "<InstanceID>0</InstanceID>") }
}
