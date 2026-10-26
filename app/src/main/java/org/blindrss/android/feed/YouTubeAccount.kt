package org.blindrss.android.feed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// YouTube account sign-in, following the approach audited in SmartTube /
// MediaServiceCore (both MIT; this is an independent implementation of the
// same documented protocol, no code copied):
//  - The OAuth client constants are NOT Brandon's and NOT shipped: they are
//    the official YouTube TV client's own, scraped at runtime from the TV
//    client's base.js exactly as MediaServiceCore's ClientData does (first
//    occurrence of its two documented regex patterns).
//  - Sign-in is Google's OAuth2 device-code flow for limited-input devices:
//    POST oauth2.googleapis.com/device/code, the user enters the code at the
//    verification URL, the app polls oauth2.googleapis.com/token.
//  - With the access token, Innertube browse (FEwhat_to_watch /
//    FEsubscriptions) serves recommendations and the subscriptions shelf
//    with an Authorization: Bearer header.
object YouTubeAccount {
    const val SCOPE = "https://www.googleapis.com/auth/youtube https://www.googleapis.com/auth/youtube-paid-content"
    private const val TV_UA = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36"
    private val http = OkHttpClient()

    data class ClientConstants(val id: String, val secret: String)
    data class DeviceCode(val userCode: String, val deviceCode: String, val verificationUrl: String, val intervalSec: Int, val expiresInSec: Int)
    data class Tokens(val accessToken: String, val refreshToken: String)

    // MediaServiceCore ClientData patterns (first occurrence is the TV one).
    private val ID_RE = Regex("""clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[$\w]+:"\w+"""")
    private val SECRET_RE = Regex("""clientId:"[-\w]+\.apps\.googleusercontent\.com",\n?[$\w]+:"(\w+)"""")

    fun parseClientConstants(js: String): ClientConstants? {
        val id = ID_RE.find(js)?.groupValues?.get(1) ?: return null
        val secret = SECRET_RE.find(js)?.groupValues?.get(1) ?: return null
        return ClientConstants(id, secret)
    }
    // Candidate script URLs in the youtube.com/tv page (player + m=base).
    fun scriptCandidates(tvHtml: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""player_url":"([^"]+)"""").find(tvHtml)?.groupValues?.get(1)?.replace("\\/", "/")?.let { out += "https://www.youtube.com$it" }
        for (m in Regex("""https://www\.youtube\.com/s/player/[^\s"']+""").findAll(tvHtml)) out += m.value.replace("\\/", "/")
        return out.distinct()
    }
    fun baseVariant(url: String): String = url.replace("/m=main", "/m=base").let {
        if ("m=base" in it || "m=main" in it) it else it.replace("tv-player-ias.vflset", "tv-player-ias.vflset") }

    suspend fun fetchClientConstants(): ClientConstants = withContext(Dispatchers.IO) {
        val tv = get("https://www.youtube.com/tv?hl=en", TV_UA)
        val candidates = scriptCandidates(tv)
        for (u in candidates + candidates.map { baseVariant(it) }) {
            val js = try { get(u, TV_UA) } catch (_: Exception) { continue }
            parseClientConstants(js)?.let { return@withContext it }
            // The player js names its base module; follow one hop.
            for (m in Regex("""https://www\.youtube\.com/s/player/[^\s"']*m=base[^\s"']*""").findAll(js)) {
                val deep = try { get(m.value, TV_UA) } catch (_: Exception) { continue }
                parseClientConstants(deep)?.let { return@withContext it }
            }
        }
        throw java.io.IOException("Could not read the YouTube TV client constants")
    }

    fun parseDeviceCode(json: String): DeviceCode { val o = JSONObject(json)
        return DeviceCode(o.optString("user_code"), o.optString("device_code"),
            o.optString("verification_url").ifEmpty { "https://www.google.com/device" },
            o.optInt("interval", 5), o.optInt("expires_in", 1800)) }
    // Token endpoint answer: tokens, "pending" while the user has not
    // finished, or any other error string.
    fun parseTokenResponse(json: String): Pair<Tokens?, String> { val o = JSONObject(json)
        val err = o.optString("error")
        if (err == "authorization_pending" || err == "slow_down") return null to "pending"
        if (err.isNotEmpty()) return null to err
        val access = o.optString("access_token"); if (access.isEmpty()) return null to "empty"
        return Tokens(access, o.optString("refresh_token")) to "" }

    suspend fun requestDeviceCode(c: ClientConstants): DeviceCode = withContext(Dispatchers.IO) {
        parseDeviceCode(postForm("https://oauth2.googleapis.com/device/code",
            mapOf("client_id" to c.id, "scope" to SCOPE))) }
    suspend fun pollToken(c: ClientConstants, deviceCode: String): Pair<Tokens?, String> = withContext(Dispatchers.IO) {
        parseTokenResponse(postForm("https://oauth2.googleapis.com/token",
            mapOf("client_id" to c.id, "client_secret" to c.secret, "device_code" to deviceCode,
                "grant_type" to "http://oauth.net/grant_type/device/1.0"))) }
    suspend fun refresh(c: ClientConstants, refreshToken: String): Tokens = withContext(Dispatchers.IO) {
        val (t, err) = parseTokenResponse(postForm("https://oauth2.googleapis.com/token",
            mapOf("client_id" to c.id, "client_secret" to c.secret, "grant_type" to "refresh_token", "refresh_token" to refreshToken)))
        t?.copy(refreshToken = t.refreshToken.ifEmpty { refreshToken }) ?: throw java.io.IOException("YouTube sign-in expired ($err). Sign in again.") }

    // Innertube browse with the account token. browseId FEwhat_to_watch is
    // the home/recommendations feed; FEsubscriptions the subscriptions shelf.
    suspend fun browse(accessToken: String, browseId: String): List<VideoResult> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "TVHTML5").put("clientVersion", "7.20251020.21.00").put("hl", "en").put("gl", "US")))
            .put("browseId", browseId).toString()
        val req = Request.Builder().url("https://www.youtube.com/youtubei/v1/browse?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")
            .header("Authorization", "Bearer $accessToken").header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} from YouTube"); parseBrowse(r.body?.string() ?: "") }
    }
    // Walk the browse JSON collecting video renderers (home + shelves).
    fun parseBrowse(json: String): List<VideoResult> {
        val out = mutableListOf<VideoResult>(); val seen = mutableSetOf<String>()
        fun text(o: JSONObject?): String { if (o == null) return ""
            o.optString("simpleText").ifEmpty { null }?.let { return it }
            val runs = o.optJSONArray("runs"); val sb = StringBuilder()
            if (runs != null) for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
            return sb.toString() }
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val vr = node.optJSONObject("videoRenderer") ?: node.optJSONObject("compactVideoRenderer")
                        ?: node.optJSONObject("gridVideoRenderer")
                    if (vr != null) {
                        val videoId = vr.optString("videoId")
                        if (videoId.isNotBlank() && seen.add(videoId)) {
                            val byline = vr.optJSONObject("shortBylineText") ?: vr.optJSONObject("longBylineText")
                            var channelId = ""; var channelName = text(byline)
                            val runs = byline?.optJSONArray("runs")
                            if (runs != null && runs.length() > 0) { val ep = runs.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                                channelId = ep?.optString("browseId") ?: "" }
                            out += VideoResult(text(vr.optJSONObject("title")), "https://www.youtube.com/watch?v=$videoId", "YouTube",
                                channelName, 0, 0, 0, if (channelId.isNotBlank()) "https://www.youtube.com/channel/$channelId" else "")
                        }
                    }
                    val keys = node.keys(); while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        try { walk(JSONObject(json)) } catch (_: Exception) { }
        return out
    }

    private fun get(url: String, ua: String): String {
        http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url"); return r.body?.string() ?: "" } }
    private fun postForm(url: String, fields: Map<String, String>): String {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            // The token endpoint answers 4xx while pending; the body still
            // carries the machine-readable error, so return it for parsing
            // unless it is not JSON at all.
            if (!r.isSuccessful && !text.trimStart().startsWith("{")) throw java.io.IOException("HTTP ${r.code} for $url")
            return text } }
}
