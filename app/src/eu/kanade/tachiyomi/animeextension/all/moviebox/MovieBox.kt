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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
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

    private fun getApiHeaders(url: String, method: String = "GET", body: String? = null, token: String? = null): Headers {
        val timestamp = System.currentTimeMillis()
        val contentType = if (method == "POST") "application/json; charset=utf-8" else "application/json"
        
        return Headers.Builder()
            .add("user-agent", "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; Samsung; Build/BP22.250325.006; Cronet/133.0.6876.3)")
            .add("accept", "application/json")
            .add("content-type", contentType)
            .add("connection", "keep-alive")
            .add("x-client-token", generateXClientToken(timestamp))
            .add("x-tr-signature", generateXTrSignature(method, "application/json", contentType, url, body, timestamp = timestamp))
            .add("x-client-info", getClientInfo())
            .add("x-client-status", "0")
            .add("x-play-mode", "2")
            .apply {
                if (!token.isNullOrBlank()) {
                    add("Authorization", "Bearer $token")
                }
            }
            .build()
    }

    private fun getClientInfo(): String {
        return JsonObject(mapOf(
            "package_name" to kotlinx.serialization.json.JsonPrimitive("com.community.mbox.in"),
            "version_name" to kotlinx.serialization.json.JsonPrimitive("3.0.03.0529.03"),
            "version_code" to kotlinx.serialization.json.JsonPrimitive(50020042),
            "os" to kotlinx.serialization.json.JsonPrimitive("android"),
            "os_version" to kotlinx.serialization.json.JsonPrimitive("16"),
            "device_id" to kotlinx.serialization.json.JsonPrimitive(deviceId),
            "install_store" to kotlinx.serialization.json.JsonPrimitive("ps"),
            "gaid" to kotlinx.serialization.json.JsonPrimitive("d7578036d13336cc"),
            "brand" to kotlinx.serialization.json.JsonPrimitive("Samsung"),
            "model" to kotlinx.serialization.json.JsonPrimitive("SM-S918B"),
            "system_language" to kotlinx.serialization.json.JsonPrimitive("en"),
            "net" to kotlinx.serialization.json.JsonPrimitive("NETWORK_WIFI"),
            "region" to kotlinx.serialization.json.JsonPrimitive("IN"),
            "timezone" to kotlinx.serialization.json.JsonPrimitive("Asia/Calcutta"),
            "sp_code" to kotlinx.serialization.json.JsonPrimitive(""),
        )).toString()
    }

    private fun generateXClientToken(timestamp: Long): String {
        val tsStr = timestamp.toString()
        val hash = tsStr.reversed().md5()
        return "$tsStr,$hash"
    }

    private val deviceId by lazy { UUID.randomUUID().toString().replace("-", "") }

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
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "$timestamp\n" +
                "$bodyHash\n" +
                canonicalUrl
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

    private fun safeGetJsonWithHeaders(urlPath: String, isPost: Boolean = false, bodyData: String? = null, token: String? = null): Pair<JsonElement, Headers>? {
        for (host in apiHosts) {
            val url = host + urlPath
            val request = if (isPost) {
                val body = bodyData.orEmpty().toRequestBody("application/json; charset=utf-8".toMediaType())
                POST(url, getApiHeaders(url, "POST", bodyData, token = token), body)
            } else {
                GET(url, getApiHeaders(url, token = token))
            }
            try {
                val response = client.newCall(request).execute()
                val body = response.body.string().trim()
                if (body.isEmpty() || body.contains("<html", ignoreCase = true) || !body.startsWith("{")) continue
                return Pair(json.parseToJsonElement(body), response.headers)
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    // Popular
    override fun popularAnimeRequest(page: Int): Request {
        val url = "${getPreferredHost()}/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=$page&perPage=18"
        return GET(url, getApiHeaders(url))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val jsonRes = if (body.contains("<html", ignoreCase = true) || !body.startsWith("{")) {
            safeGetJsonWithHeaders(response.request.url.toString().substringAfter(".com").substringAfter(".world"))?.first
        } else json.parseToJsonElement(body)
        
        val data = jsonRes?.obj?.get("data")?.obj ?: return AnimesPage(emptyList(), false)
        return parseSubjectListPage(data)
    }

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "${getPreferredHost()}/wefeed-mobile-bff/subject-api/search/v2"
            val bodyData = """{"page":$page,"perPage":20,"keyword":"$query"}"""
            val body = bodyData.toRequestBody("application/json; charset=utf-8".toMediaType())
            return POST(url, getApiHeaders(url, "POST", bodyData), body)
        }
        val url = "${getPreferredHost()}/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=$page&perPage=18"
        return GET(url, getApiHeaders(url))
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val jsonRes = if (body.contains("<html", ignoreCase = true) || !body.startsWith("{")) {
             safeGetJsonWithHeaders("/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=18")?.first
        } else json.parseToJsonElement(body)
        
        val data = jsonRes?.obj?.get("data")?.obj ?: return AnimesPage(emptyList(), false)
        return parseSubjectListPage(data)
    }

    // Details
    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.split("/").last().split("|").first()
        val param = if (id.all { it.isDigit() }) "subjectId" else "detailPath"
        val url = "${getPreferredHost()}/wefeed-mobile-bff/subject-api/get?$param=$id"
        return GET(url, getApiHeaders(url))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val xUser = response.header("x-user")
        var token = xUser?.let { runCatching { json.parseToJsonElement(it).obj?.get("token")?.str }.getOrNull() }
        
        val body = response.body.string()
        val jsonRes = if (body.contains("<html", ignoreCase = true) || !body.startsWith("{")) {
            val id = response.request.url.queryParameter("subjectId") ?: response.request.url.queryParameter("detailPath").orEmpty()
            val param = if (id.all { it.isDigit() }) "subjectId" else "detailPath"
            val result = safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/get?$param=$id")
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

    override fun episodeListParse(response: Response): List<SEpisode> {
        val xUser = response.header("x-user")
        val headerToken = xUser?.let { runCatching { json.parseToJsonElement(it).obj?.get("token")?.str }.getOrNull() }
        val urlParts = response.request.url.toString().split("|")
        val token = headerToken ?: (if (urlParts.size > 1) urlParts[1] else null)
        
        val body = response.body.string()
        val jsonRes = if (body.contains("<html", ignoreCase = true) || !body.startsWith("{")) {
            val id = response.request.url.queryParameter("subjectId") ?: response.request.url.queryParameter("detailPath").orEmpty()
            val param = if (id.all { it.isDigit() }) "subjectId" else "detailPath"
            safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/get?$param=$id", token = token)?.first
        } else json.parseToJsonElement(body)
        
        val data = jsonRes?.obj?.get("data")?.obj ?: return emptyList()
        val subjectId = data["subject"]?.obj?.get("subjectId")?.str 
            ?: data["subjectId"]?.str ?: return emptyList()
        val detailPath = data["subject"]?.obj?.get("detailPath")?.str ?: subjectId
        
        val allIds = mutableListOf(subjectId)
        val dubs = data["subject"]?.obj?.get("dubs")?.arr ?: data["dubs"]?.arr
        dubs?.forEach { it.obj?.get("subjectId")?.str?.let { sid -> if (sid !in allIds) allIds.add(sid) } }

        val episodes = mutableListOf<SEpisode>()
        val seasonsMap = mutableMapOf<Int, MutableSet<Int>>()

        for (sid in allIds) {
            val currentSeasons = data["resource"]?.obj?.get("seasons")?.arr ?: data["seasons"]?.arr
            val seasonsToUse = if (sid == subjectId && currentSeasons != null) currentSeasons else {
                val seasonsUrl = "/wefeed-mobile-bff/subject-api/season-info?subjectId=$sid"
                val seasonsRes = safeGetJsonWithHeaders(seasonsUrl, token = token)?.first
                seasonsRes?.obj?.get("data")?.obj?.get("seasons")?.arr
            }
            
            seasonsToUse?.forEach { seasonEl ->
                val season = seasonEl.obj ?: return@forEach
                val seNum = season["se"]?.str?.toIntOrNull() ?: 1
                val maxEp = season["maxEp"]?.str?.toIntOrNull() ?: 1
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
                    url = "se=$seNum&ep=$epNum&ids=$idsString&detailPath=$detailPath" + if (!token.isNullOrBlank()) "|$token" else ""
                    date_upload = System.currentTimeMillis()
                })
            }
        }

        return if (episodes.isNotEmpty()) episodes.reversed() else listOf(
            SEpisode.create().apply {
                name = "Play Movie"
                episode_number = 1f
                url = "se=0&ep=0&ids=$idsString&detailPath=$detailPath" + if (!token.isNullOrBlank()) "|$token" else ""
                date_upload = System.currentTimeMillis()
            }
        )
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl, headersBuilder().build())
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.header("X-Tachiyomi-Episode-Url") ?: response.request.url.toString()
        val epParts = episodeUrl.split("|")
        val token = if (epParts.size > 1) epParts[1] else null
        val params = epParts[0].split("&")
        val se = params.find { it.startsWith("se=") }?.substringAfter("=") ?: "0"
        val ep = params.find { it.startsWith("ep=") }?.substringAfter("=") ?: "0"
        val idsString = params.find { it.startsWith("ids=") }?.substringAfter("=") ?: ""
        val subjectIds = idsString.split(",").filter { it.isNotBlank() }

        val videos = mutableListOf<Video>()
        for (sid in subjectIds) {
            val playUrl = "/wefeed-mobile-bff/subject-api/play-info?subjectId=$sid&se=$se&ep=$ep"
            val jsonRes = safeGetJsonWithHeaders(playUrl, token = token)?.first ?: continue
            val data = jsonRes.obj?.get("data")?.obj ?: continue
            
            data["streams"]?.arr?.forEach { stream ->
                val obj = stream.obj ?: return@forEach
                val url = obj["url"]?.str ?: return@forEach
                val quality = (obj["resolutions"]?.str ?: "Auto") + "P"
                val signCookie = obj["signCookie"]?.str
                val headers = Headers.Builder()
                    .add("Referer", "https://h5.aoneroom.com/")
                    .add("User-Agent", "Mozilla/5.0")
                    .apply { if (!signCookie.isNullOrBlank()) add("Cookie", signCookie) }
                    .build()
                videos.add(Video(url, quality, url, headers = headers))
            }
        }
        return videos
    }

    private fun parseSubjectListPage(data: JsonObject): AnimesPage {
        val items = data["subjectList"]?.arr ?: data["items"]?.arr ?: data["subjects"]?.arr
            ?: data["results"]?.arr?.mapNotNull { it.obj?.get("subjects")?.arr }?.flatten()
            ?: return AnimesPage(emptyList(), false)

        val animes = (items as List<JsonElement>).mapNotNull { item ->
            val obj = item.obj ?: return@mapNotNull null
            val subject = if (obj.containsKey("subject")) obj["subject"]?.obj else obj
            if (subject == null) return@mapNotNull null
            val id = subject["subjectId"]?.str ?: return@mapNotNull null
            SAnime.create().apply {
                title = subject["title"]?.str ?: ""
                url = "/movies/$id"
                thumbnail_url = subject["cover"]?.obj?.get("url")?.str
            }
        }
        return AnimesPage(animes, data["pager"]?.obj?.get("hasMore")?.jsonPrimitive?.booleanOrNull ?: (animes.size >= 12))
    }

    private val JsonElement?.obj get() = this as? JsonObject
    private val JsonElement?.arr get() = this as? JsonArray
    private val JsonElement?.str get() = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private val JsonElement?.bool get() = (this as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_HOST_KEY
            title = "API Host"
            entries = arrayOf("Official (Aoneroom)", "Mirror (Netfilm)", "H5 API")
            entryValues = apiHosts.toTypedArray()
            setDefaultValue(apiHosts[0])
            summary = "%s"
        }.also { screen.addPreference(it) }
    }

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(SortFilter(), TypeFilter(), RankingFilter())

    private class SortFilter : AnimeFilter.Select<String>("Sort", arrayOf("Default", "Hottest", "Rating", "Latest")) {
        fun toId() = when (state) { 1 -> "Hottest"; 2 -> "Rating"; 3 -> "Latest"; else -> "" }
    }
    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Movie", "TV Series")) {
        fun toId() = when (state) { 1 -> "4"; 2 -> "2"; else -> "" }
    }
    private class RankingFilter : AnimeFilter.Select<String>("Ranking", arrayOf("None", "Trending", "Bollywood")) {
        fun toId() = when (state) { 1 -> "4516404531735022304"; 2 -> "414907768299210008"; else -> "" }
    }

    companion object {
        private const val PREF_HOST_KEY = "api_host"
    }
}