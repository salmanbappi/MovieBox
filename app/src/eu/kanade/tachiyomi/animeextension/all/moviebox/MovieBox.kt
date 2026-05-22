package eu.kanade.tachiyomi.animeextension.all.moviebox

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MovieBox : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "MovieBox"
    override val baseUrl = "https://moviebox.ph"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 3508466391484419848L

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val apiHosts = listOf(
        "https://api3.aoneroom.com",
        "https://netfilm.world",
        "https://h5-api.aoneroom.com"
    )

    private val secretKeyDefault = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://moviebox.ph/")
        .add("Origin", "https://moviebox.ph")
        .add("Accept", "application/json")

    private fun getApiHeaders(url: String, method: String = "GET", body: String? = null, token: String? = null, isDetails: Boolean = false, isPlayback: Boolean = false): Headers {
        val timestamp = System.currentTimeMillis()
        val contentType = if (method == "POST") "application/json; charset=utf-8" else "application/json"
        
        return Headers.Builder()
            .add("user-agent", "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001; Cronet/145.0.7582.0)")
            .add("accept", "application/json")
            .add("content-type", contentType)
            .add("connection", "keep-alive")
            .add("x-client-token", generateXClientToken(timestamp))
            .add("x-tr-signature", generateXTrSignature(method, "application/json", contentType, url, body, timestamp = timestamp))
            .add("x-client-info", getClientInfo())
            .add("x-client-status", "0")
            .apply {
                if (isDetails) add("x-play-mode", "2")
                if (isPlayback) add("x-play-mode", "1")
                if (!token.isNullOrBlank()) {
                    add("Authorization", "Bearer $token")
                }
            }
            .build()
    }

    private fun getClientInfo(): String {
        return JsonObject(mapOf(
            "package_name" to kotlinx.serialization.json.JsonPrimitive("com.community.oneroom"),
            "version_name" to kotlinx.serialization.json.JsonPrimitive("3.0.13.0325.03"),
            "version_code" to kotlinx.serialization.json.JsonPrimitive(50020088),
            "os" to kotlinx.serialization.json.JsonPrimitive("android"),
            "os_version" to kotlinx.serialization.json.JsonPrimitive("13"),
            "device_id" to kotlinx.serialization.json.JsonPrimitive(deviceId),
            "install_store" to kotlinx.serialization.json.JsonPrimitive("ps"),
            "gaid" to kotlinx.serialization.json.JsonPrimitive("1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d"),
            "brand" to kotlinx.serialization.json.JsonPrimitive("Samsung"),
            "model" to kotlinx.serialization.json.JsonPrimitive("SM-S918B"),
            "system_language" to kotlinx.serialization.json.JsonPrimitive("en"),
            "net" to kotlinx.serialization.json.JsonPrimitive("NETWORK_WIFI"),
            "region" to kotlinx.serialization.json.JsonPrimitive("US"),
            "timezone" to kotlinx.serialization.json.JsonPrimitive("Asia/Calcutta"),
            "sp_code" to kotlinx.serialization.json.JsonPrimitive(""),
            "X-Play-Mode" to kotlinx.serialization.json.JsonPrimitive("1"),
            "X-Idle-Data" to kotlinx.serialization.json.JsonPrimitive("1"),
            "X-Family-Mode" to kotlinx.serialization.json.JsonPrimitive("0"),
            "X-Content-Mode" to kotlinx.serialization.json.JsonPrimitive("0"),
        )).toString()
    }

    private fun generateXClientToken(timestamp: Long): String {
        val tsStr = timestamp.toString()
        val hash = tsStr.reversed().md5()
        return "$tsStr,$hash"
    }

    private val deviceId by lazy { 
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    private fun String.md5(): String = toByteArray().md5()

    private fun ByteArray.md5(): String {
        return MessageDigest.getInstance("MD5").digest(this)
            .joinToString("") { "%02x".format(it) }
    }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        
        val query = if (!parsed.queryParameterNames.isNullOrEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value"
                }
            }
        } else ""
        
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5ByteArray(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
    }

    private fun md5ByteArray(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        timestamp: Long
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        
        // Correct double-decode logic
        val secretStr = String(Base64.decode(secretKeyDefault, Base64.DEFAULT))
        val secretBytes = Base64.decode(secretStr, Base64.DEFAULT)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)

        return "$timestamp|2|$signatureB64"
    }

    private fun getPreferredHost(): String {
        return preferences.getString(PREF_HOST_KEY, apiHosts[0]) ?: apiHosts[0]
    }

    private fun safeGetJsonWithHeaders(urlPath: String, isPost: Boolean = false, bodyData: String? = null, token: String? = null, isDetails: Boolean = false, isPlayback: Boolean = false): Pair<JsonElement, Headers>? {
        for (host in apiHosts) {
            val adaptivePath = if (host.contains("api3")) {
                urlPath.replace("/wefeed-h5api-bff/detail", "/wefeed-mobile-bff/subject-api/get")
            } else {
                urlPath.replace("/wefeed-mobile-bff/subject-api/get", "/wefeed-h5api-bff/detail")
            }
            
            val url = host + adaptivePath
            val request = if (isPost) {
                val body = bodyData.orEmpty().toRequestBody("application/json; charset=utf-8".toMediaType())
                POST(url, getApiHeaders(url, "POST", bodyData, token = token, isDetails = isDetails, isPlayback = isPlayback), body)
            } else {
                GET(url, getApiHeaders(url, token = token, isDetails = isDetails, isPlayback = isPlayback))
            }
            try {
                val response = client.newCall(request).execute()
                val body = response.body.string().trim()
                if (body.isEmpty() || body.contains("<html", ignoreCase = true) || !body.startsWith("{")) continue
                val jsonRes = json.parseToJsonElement(body)
                if (jsonRes.obj?.get("code")?.jsonPrimitive?.intOrNull != 0) continue
                return Pair(jsonRes, response.headers)
            } catch (e: Exception) { continue }
        }
        return null
    }

    // Popular
    override fun popularAnimeRequest(page: Int): Request {
        val host = getPreferredHost()
        val path = if (host.contains("api3")) "/wefeed-mobile-bff/tab/ranking-list" else "/wefeed-h5api-bff/ranking-list/content"
        val url = "$host$path?tabId=0&categoryType=4516404531735022304&page=$page&perPage=18"
        return GET(url, getApiHeaders(url))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val jsonRes = if (!body.trim().startsWith("{")) {
            safeGetJsonWithHeaders("/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=18")?.first
        } else json.parseToJsonElement(body)
        val data = jsonRes?.obj?.get("data")?.obj ?: return AnimesPage(emptyList(), false)
        return parseSubjectListPage(data)
    }

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val host = getPreferredHost()
            val path = if (host.contains("api3")) "/wefeed-mobile-bff/subject-api/search/v2" else "/wefeed-h5api-bff/subject/search"
            val url = "$host$path"
            val bodyData = """{"page":$page,"perPage":20,"keyword":"$query"}"""
            val body = bodyData.toRequestBody("application/json; charset=utf-8".toMediaType())
            return POST(url, getApiHeaders(url, "POST", bodyData), body)
        }
        return popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // Details
    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("/").substringBefore("|")
        val host = getPreferredHost()
        val path = if (host.contains("api3")) "/wefeed-mobile-bff/subject-api/get" else "/wefeed-h5api-bff/detail"
        val param = if (id.all { it.isDigit() }) "subjectId" else "detailPath"
        val url = "$host$path?$param=$id"
        return GET(url, getApiHeaders(url, isDetails = true))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val xUser = response.header("x-user")
        var token = xUser?.let { runCatching { json.parseToJsonElement(it).obj?.get("token")?.str }.getOrNull() }
        
        val body = response.body.string()
        val jsonRes = if (!body.trim().startsWith("{")) {
            val id = response.request.url.queryParameter("subjectId") ?: response.request.url.toString().substringAfterLast("/")
            val result = safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/get?subjectId=$id", isDetails = true)
            token = result?.second?.get("x-user")?.let { runCatching { json.parseToJsonElement(it).obj?.get("token")?.str }.getOrNull() }
            result?.first
        } else json.parseToJsonElement(body)
        
        val data = jsonRes?.obj?.get("data")?.obj ?: throw Exception("Details not found")
        val subject = data["subject"]?.obj ?: data

        return SAnime.create().apply {
            title = subject["title"]?.str ?: ""
            description = subject["description"]?.str
            genre = subject["genre"]?.str
            author = subject["countryName"]?.str
            url = subject["subjectId"]?.str?.let { "/movies/$it" } ?: url
            if (!token.isNullOrBlank()) url += "|$token"
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val xUser = response.header("x-user")
        val headerToken = xUser?.let { runCatching { json.parseToJsonElement(it).obj?.get("token")?.str }.getOrNull() }
        val urlParts = response.request.url.toString().split("|")
        val token = headerToken ?: (if (urlParts.size > 1) urlParts[1] else null)
        
        val body = response.body.string()
        val jsonRes = if (!body.trim().startsWith("{")) {
            val id = response.request.url.queryParameter("subjectId") ?: response.request.url.toString().substringAfterLast("/")
            safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/get?subjectId=$id", token = token, isDetails = true)?.first
        } else json.parseToJsonElement(body)
        
        val data = jsonRes?.obj?.get("data")?.obj ?: return emptyList()
        val mainSubjectId = data["subject"]?.obj?.get("subjectId")?.str 
            ?: data["subjectId"]?.str ?: return emptyList()
        val detailPath = data["subject"]?.obj?.get("detailPath")?.str ?: mainSubjectId
        
        val allIds = mutableListOf(mainSubjectId)
        val dubs = data["subject"]?.obj?.get("dubs")?.arr ?: data["dubs"]?.arr
        dubs?.forEach { it.obj?.get("subjectId")?.str?.let { sid -> if (sid !in allIds) allIds.add(sid) } }

        val episodes = mutableListOf<SEpisode>()
        val seasonsMap = mutableMapOf<Int, MutableSet<Int>>()

        for (sid in allIds) {
            val seasonsUrl = "/wefeed-mobile-bff/subject-api/season-info?subjectId=$sid"
            val seasonsRes = safeGetJsonWithHeaders(seasonsUrl, token = token, isDetails = true)?.first
            val seasonsData = seasonsRes?.obj?.get("data")?.obj ?: seasonsRes?.obj ?: data
            val resource = seasonsData["resource"]?.obj ?: seasonsData
            val seasons = resource["seasons"]?.arr ?: seasonsData["seasons"]?.arr

            seasons?.forEach { seasonEl ->
                val season = seasonEl.obj ?: return@forEach
                val seNum = season["se"]?.jsonPrimitive?.intOrNull ?: 1
                val maxEp = season["maxEp"]?.jsonPrimitive?.intOrNull ?: 1
                val epSet = seasonsMap.getOrPut(seNum) { mutableSetOf() }
                for (i in 1..maxEp) epSet.add(i)
            }
        }

        val idsString = allIds.joinToString(",")
        seasonsMap.forEach { (seNum, epSet) ->
            epSet.sorted().forEach { epNum ->
                episodes.add(SEpisode.create().apply {
                    name = "Season $seNum - Episode $epNum"
                    episode_number = epNum.toFloat()
                    url = "$seNum|$epNum|$idsString|$detailPath" + if (!token.isNullOrBlank()) "|$token" else ""
                    date_upload = System.currentTimeMillis()
                })
            }
        }

        return if (episodes.isNotEmpty()) episodes.reversed() else listOf(
            SEpisode.create().apply {
                name = "Play Movie"
                episode_number = 1f
                url = "0|0|$mainSubjectId|$detailPath" + if (!token.isNullOrBlank()) "|$token" else ""
                date_upload = System.currentTimeMillis()
            }
        )
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl, headersBuilder().build())

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.header("X-Tachiyomi-Episode-Url") ?: response.request.url.toString()
        val parts = episodeUrl.split("|")
        if (parts.size < 4) return emptyList()
        
        val se = parts[0]; val ep = parts[1]; val subjectIds = parts[2].split(","); val token = if (parts.size > 4) parts[4] else null

        val videos = mutableListOf<Video>()
        for (sid in subjectIds) {
            val playUrl = "/wefeed-mobile-bff/subject-api/play-info?subjectId=$sid&se=$se&ep=$ep"
            val jsonRes = safeGetJsonWithHeaders(playUrl, token = token, isPlayback = true)?.first ?: continue
            jsonRes.obj?.get("data")?.obj?.get("streams")?.arr?.forEach { stream ->
                val obj = stream.obj ?: return@forEach
                val url = obj["url"]?.str ?: return@forEach
                val quality = (obj["resolutions"]?.str ?: "Auto") + "P"
                val signCookie = obj["signCookie"]?.str
                val headers = Headers.Builder()
                    .add("Referer", "https://h5.aoneroom.com/")
                    .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .apply { if (!signCookie.isNullOrBlank()) add("Cookie", signCookie) }
                    .build()
                videos.add(Video(url, quality, url, headers = headers))
            }
        }
        return videos
    }

    private fun parseSubjectListPage(data: JsonObject): AnimesPage {
        val items = data["subjectList"]?.arr ?: data["items"]?.arr ?: data["subjects"]?.arr ?: data["results"]?.arr?.mapNotNull { it.obj?.get("subjects")?.arr }?.flatten() ?: return AnimesPage(emptyList(), false)
        val animes = (items as List<JsonElement>).mapNotNull { item ->
            val obj = item.obj ?: return@mapNotNull null
            val subject = if (obj.containsKey("subject")) obj["subject"]?.obj else obj
            if (subject == null) return@mapNotNull null
            val id = subject["subjectId"]?.str ?: return@mapNotNull null
            SAnime.create().apply { title = subject["title"]?.str ?: ""; url = "/movies/$id"; thumbnail_url = subject["cover"]?.obj?.get("url")?.str }
        }
        return AnimesPage(animes, data["pager"]?.obj?.get("hasMore")?.bool ?: (animes.size >= 12))
    }

    private val JsonElement?.obj get() = this as? JsonObject
    private val JsonElement?.arr get() = this as? JsonArray
    private val JsonElement?.str get() = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private val JsonElement?.bool get() = (this as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply { key = PREF_HOST_KEY; title = "API Host"; entries = arrayOf("Official (Aoneroom)", "Mirror (Netfilm)", "H5 API"); entryValues = apiHosts.toTypedArray(); setDefaultValue(apiHosts[0]); summary = "%s" }.also { screen.addPreference(it) }
    }
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(SortFilter(), TypeFilter(), RankingFilter())
    private class SortFilter : AnimeFilter.Select<String>("Sort", arrayOf("Default", "Hottest", "Rating", "Latest")) { fun toId() = when (state) { 1 -> "Hottest"; 2 -> "Rating"; 3 -> "Latest"; else -> "" } }
    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Movie", "TV Series")) { fun toId() = when (state) { 1 -> "4"; 2 -> "2"; else -> "" } }
    private class RankingFilter : AnimeFilter.Select<String>("Ranking", arrayOf("None", "Trending", "Bollywood")) { fun toId() = when (state) { 1 -> "4516404531735022304"; 2 -> "414907768299210008"; else -> "" } }
    companion object { private const val PREF_HOST_KEY = "api_host" }
}