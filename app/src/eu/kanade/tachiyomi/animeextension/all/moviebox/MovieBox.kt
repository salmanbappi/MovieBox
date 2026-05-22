package eu.kanade.tachiyomi.animeextension.all.moviebox

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
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.util.TimeZone
import java.util.UUID

class MovieBox : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "MovieBox"
    override val baseUrl = "https://moviebox.ph"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 3508466391484419848L

    private val apiBaseUrl = "https://h5-api.aoneroom.com"
    private val mobileApiBaseUrl = "https://h5-api.aoneroom.com"
    private val playApiBaseUrl = "https://netfilm.world"

    private val secretKeyDefault = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
    private val secretKeyAlt = "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

    private val blockedKeywords = listOf(
        "mma", "ufc", "wrestling", "boxing", "kickboxing", "muay thai", "rizin", "nfc", "highlights",
        "esports", "e-sports", "gaming", "gameplay", "pubg", "free fire", "dota", "league of legends", "valorant", "fifa", "fc 24", "roblox", "minecraft",
        "dj mix", "mixtape", "mashup", "remix", "song", "lyrics", "audio porn", "massage", "therapist",
    )

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json")
        .add("X-Request-Lang", "en")
        .add("X-Source", "MB_Website")

    private fun getMobileHeaders(url: String, method: String = "GET", body: String? = null): Headers {
        val timestamp = System.currentTimeMillis()
        val contentType = if (method == "POST") "application/json; charset=utf-8" else "application/json"
        return Headers.Builder()
            .add("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("accept", "application/json")
            .add("content-type", contentType)
            .add("connection", "keep-alive")
            .add("x-client-token", generateXClientToken(timestamp))
            .add("x-tr-signature", generateXTrSignature(method, "application/json", contentType, url, body, timestamp = timestamp))
            .add("x-client-info", getClientInfo())
            .add("x-client-status", "0")
            .build()
    }

    private fun getClientInfo(): String {
        return JsonObject(mapOf(
            "package_name" to kotlinx.serialization.json.JsonPrimitive("com.community.oneroom"),
            "version_name" to kotlinx.serialization.json.JsonPrimitive("3.0.13.0325.03"),
            "version_code" to kotlinx.serialization.json.JsonPrimitive(50020088),
            "os" to kotlinx.serialization.json.JsonPrimitive("android"),
            "os_version" to kotlinx.serialization.json.JsonPrimitive("13"),
            "install_ch" to kotlinx.serialization.json.JsonPrimitive("ps"),
            "device_id" to kotlinx.serialization.json.JsonPrimitive(deviceId),
            "install_store" to kotlinx.serialization.json.JsonPrimitive("ps"),
            "gaid" to kotlinx.serialization.json.JsonPrimitive(gaid),
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
        val reversed = tsStr.reversed()
        val hash = reversed.md5()
        return "$tsStr,$hash"
    }

    private val deviceId by lazy { UUID.randomUUID().toString().replace("-", "") }
    private val gaid by lazy { UUID.randomUUID().toString() }

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
        val uri = url.toHttpUrlOrNull() ?: throw Exception("Invalid URL: $url")
        val path = uri.encodedPath
        
        val query = if (uri.querySize > 0) {
            uri.queryParameterNames.sorted().joinToString("&") { name ->
                uri.queryParameterValues(name).joinToString("&") { value ->
                    "$name=$value"
                }
            }
        } else ""
        
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            trimmed.md5()
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

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        timestamp: Long
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secretB64 = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = Base64.decode(secretB64, Base64.DEFAULT)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)

        return "$timestamp|2|$signatureB64"
    }

    private fun getToken(): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val reversed = timestamp.reversed()
        val md5 = reversed.md5()
        return "$timestamp,$md5"
    }

    // Popular: High-Quality Trending API
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$mobileApiBaseUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=$page&perPage=18"
        return GET(url, getMobileHeaders(url))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseSubjectListPage(response)
    }

    // Latest: Disabled
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")

    // Search & Filters
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val rankingFilter = filters.find { it is RankingFilter } as? RankingFilter
        if (query.isEmpty() && rankingFilter != null && rankingFilter.state > 0) {
            val rankingId = rankingFilter.toId()
            val url = "$mobileApiBaseUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=$rankingId&page=$page&perPage=18"
            return GET(url, getMobileHeaders(url))
        }

        if (query.isNotBlank()) {
            val url = "$mobileApiBaseUrl/wefeed-mobile-bff/subject-api/search/v2"
            val bodyData = JsonObject(
                mapOf(
                    "keyword" to kotlinx.serialization.json.JsonPrimitive(query),
                    "page" to kotlinx.serialization.json.JsonPrimitive(page),
                    "perPage" to kotlinx.serialization.json.JsonPrimitive(18),
                ),
            ).toString()
            val body = bodyData.toRequestBody("application/json; charset=utf-8".toMediaType())
            return POST(url, getMobileHeaders(url, "POST", bodyData), body)
        }

        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val languageFilter = filters.find { it is LanguageFilter } as? LanguageFilter
        val minRateFilter = filters.find { it is MinRateFilter } as? MinRateFilter
        val sortFilter = filters.find { it is SortFilter } as? SortFilter
        val genreFilter = filters.find { it is GenreFilter } as? GenreFilter
        val yearFilter = filters.find { it is YearFilter } as? YearFilter
        val countryFilter = filters.find { it is CountryFilter } as? CountryFilter
        
        val bodyMap = mutableMapOf<String, JsonElement>(
            "keyword" to kotlinx.serialization.json.JsonPrimitive(query),
            "page" to kotlinx.serialization.json.JsonPrimitive(page),
            "perPage" to kotlinx.serialization.json.JsonPrimitive(18)
        )

        if (typeFilter != null && typeFilter.state > 0) {
            val typeId = typeFilter.toId()
            if (typeId == "ANIMATION") {
                bodyMap["channelId"] = kotlinx.serialization.json.JsonPrimitive(2)
                bodyMap["genre"] = kotlinx.serialization.json.JsonPrimitive("Animation")
            } else {
                bodyMap["channelId"] = kotlinx.serialization.json.JsonPrimitive(typeId.toInt())
            }
        }
        if (languageFilter != null && languageFilter.state > 0) {
            bodyMap["classify"] = kotlinx.serialization.json.JsonPrimitive(languageFilter.toId())
        }
        if (minRateFilter != null && minRateFilter.state > 0) {
            bodyMap["rate"] = kotlinx.serialization.json.JsonPrimitive(minRateFilter.toId())
        }
        if (sortFilter != null && sortFilter.state > 0) {
            bodyMap["sort"] = kotlinx.serialization.json.JsonPrimitive(sortFilter.toId())
        }
        if (genreFilter != null && genreFilter.state > 0) {
            bodyMap["genre"] = kotlinx.serialization.json.JsonPrimitive(genreFilter.toId())
        }
        if (yearFilter != null && yearFilter.state > 0) {
            bodyMap["year"] = kotlinx.serialization.json.JsonPrimitive(yearFilter.toId())
        }
        if (countryFilter != null && countryFilter.state > 0) {
            bodyMap["country"] = kotlinx.serialization.json.JsonPrimitive(countryFilter.toId())
        }

        val url = "$mobileApiBaseUrl/wefeed-mobile-bff/subject-api/filter"
        val bodyData = JsonObject(bodyMap).toString()
        val body = bodyData.toRequestBody("application/json".toMediaType())
        return POST(url, getMobileHeaders(url, "POST", bodyData), body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseSubjectListPage(response)
    }

    // Details
    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.split("/").last()
        val url = "$mobileApiBaseUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        return GET(url, getMobileHeaders(url))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val bodyString = response.body.string()
        val jsonRes = json.parseToJsonElement(bodyString).jsonObject
        val data = jsonRes["data"]?.jsonObject ?: throw Exception("Details not found")
        val subject = data["subject"]?.jsonObject ?: throw Exception("Subject not found")

        return SAnime.create().apply {
            title = subject["title"]?.jsonPrimitive?.content ?: ""
            description = subject["description"]?.jsonPrimitive?.content
            genre = subject["genre"]?.jsonPrimitive?.content
            author = subject["countryName"]?.jsonPrimitive?.content
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val bodyString = response.body.string()
        val jsonRes = json.parseToJsonElement(bodyString).jsonObject
        val data = jsonRes["data"]?.jsonObject ?: return emptyList()
        val subject = data["subject"]?.jsonObject ?: return emptyList()
        val subjectId = subject["subjectId"]?.jsonPrimitive?.content ?: return emptyList()
        
        // Fetch seasons from dedicated mobile endpoint for 100% accuracy
        val seasonsUrl = "$mobileApiBaseUrl/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
        val seasonsRequest = GET(seasonsUrl, getMobileHeaders(seasonsUrl))
        val seasonsResponse = client.newCall(seasonsRequest).execute().use { it.body.string() }
        val seasonsJson = json.parseToJsonElement(seasonsResponse).jsonObject
        val seasons = seasonsJson["data"]?.jsonObject?.get("seasons")?.jsonArray

        if (!seasons.isNullOrEmpty()) {
            val episodes = mutableListOf<SEpisode>()
            seasons.forEach { seasonEl ->
                val season = seasonEl.jsonObject
                val seNum = season["se"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                val allEpRaw = season["allEp"]?.jsonPrimitive?.content.orEmpty()
                val totalEp = if (allEpRaw.isNotBlank()) allEpRaw.split(",").size else season["maxEp"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

                for (i in 1..totalEp) {
                    episodes.add(SEpisode.create().apply {
                        name = "Season $seNum - Episode $i"
                        episode_number = i.toFloat()
                        url = "$subjectId&se=$seNum&ep=$i"
                        date_upload = System.currentTimeMillis()
                    })
                }
            }
            if (episodes.isNotEmpty()) return episodes.reversed()
        }

        return listOf(
            SEpisode.create().apply {
                name = "Full Movie"
                url = "$subjectId&se=0&ep=0"
                date_upload = System.currentTimeMillis()
            },
        )
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.split("&")
        val subjectId = parts[0]
        val se = parts.find { it.startsWith("se=") }?.split("=")?.get(1) ?: "0"
        val ep = parts.find { it.startsWith("ep=") }?.split("=")?.get(1) ?: "0"
        
        val url = "$mobileApiBaseUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$se&ep=$ep"
        return GET(url, getMobileHeaders(url))
    }

    override fun videoListParse(response: Response): List<Video> {
        val bodyString = response.body.string()
        val jsonRes = json.parseToJsonElement(bodyString).jsonObject
        val data = jsonRes["data"]?.jsonObject ?: return emptyList()
        
        // Use mobile app referer for stream consistency
        val referer = "https://h5.aoneroom.com/"
        
        return parseVideoItems(data, referer)
    }

    override fun List<Video>.sort(): List<Video> {
        return this.sortedByDescending { 
            when {
                it.quality.contains("1080") -> 3
                it.quality.contains("720") -> 2
                it.quality.contains("480") -> 1
                else -> 0
            }
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        TypeFilter(),
        LanguageFilter(),
        GenreFilter(),
        YearFilter(),
        CountryFilter(),
        AnimeFilter.Separator(),
        RankingFilter()
    )

    private class SortFilter : AnimeFilter.Select<String>("Sort", arrayOf("Default", "ForYou", "Hottest", "Rating", "Latest")) {
        fun toId() = when (state) {
            1 -> "ForYou"
            2 -> "Hottest"
            3 -> "Rating"
            4 -> "Latest"
            else -> ""
        }
    }

    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf(
        "All", "Movie", "TV Series", "Animated Series", "Short TV"
    )) {
        fun toId() = when (state) {
            1 -> "4" // Movie
            2 -> "2" // TV Series
            3 -> "ANIMATION"
            4 -> "1" // Short TV
            else -> ""
        }
    }

    private class LanguageFilter : AnimeFilter.Select<String>("Language/Dub", arrayOf(
        "All", "English Dub", "Hindi Dub", "Bangla dub", "French Dub", "Urdu Dub", "Tamil Dub", "Telugu Dub", "Punjabi Dub", "Malayalam Dub", "Kannada Dub", "Arabic Dub", "Arabic Sub", "Tagalog Dub", "Indonesian Dub", "Russian Dub", "Kurdish Sub", "Spanish Dub", "Spanish Sub", "SpanishLatam Dub"
    )) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class MinRateFilter : AnimeFilter.Select<String>("Minimum Rating", arrayOf(
        "All", "9+", "8+", "7+", "6+", "5+"
    )) {
        fun toId() = if (state == 0) "" else values[state].removeSuffix("+")
    }

    private class GenreFilter : AnimeFilter.Select<String>("Genre", arrayOf(
        "All", "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "Film-Noir", "Game-Show", "History", "Horror", "Music", "Musical", "Mystery", "News", "Reality-TV", "Romance", "Sci-Fi", "Short", "Sport", "Talk-Show", "Thriller", "War", "Western", "Other"
    )) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class YearFilter : AnimeFilter.Select<String>("Year", arrayOf("All") + (2026 downTo 2020).map { it.toString() }.toTypedArray() + arrayOf("2010s", "2000s", "1990s", "1980s", "Other")) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class CountryFilter : AnimeFilter.Select<String>("Country", arrayOf(
        "All", "United States", "United Kingdom", "Korea", "Japan", "Bangladesh", "China", "Egypt", "France", "Germany", "India", "Indonesia", "Iraq", "Italy", "Ivory Coast", "Kenya", "Lebanon", "Mexico", "Morocco", "Nigeria", "Pakistan", "Philippines", "Russia", "Saudi Arabia", "South Africa", "Spain", "Syria", "Thailand", "Malaysia", "Turkey", "Other"
    )) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class RankingFilter : AnimeFilter.Select<String>("Ranking List (Search only)", arrayOf(
        "None",
        "Trending Now",
        "Cinema",
        "Bollywood",
        "Hollywood",
        "South Indian",
        "Hot Short TV",
        "Trending Bengali Movies",
        "Trending Bengali TV",
        "Asian",
        "Top Series This Week",
        "Anime",
        "Korean Drama",
        "Chinese Drama",
        "Indian Drama",
        "Reality-TV",
        "Western TV",
        "Turkish Drama"
    )) {
        fun toId() = when (state) {
            1 -> "8610422883619422240"
            2 -> "5692654647815587592"
            3 -> "414907768299210008"
            4 -> "8019599703232971616"
            5 -> "3859721901924910512"
            6 -> "5740267679764693592"
            7 -> "5837669637445565960"
            8 -> "735765054104261208"
            9 -> "5429170738815291968"
            10 -> "5606549574572819920"
            11 -> "8434602210994128512"
            12 -> "7878715743607948784"
            13 -> "8788126208987989488"
            14 -> "4903182713986896328"
            15 -> "1255898847918934600"
            16 -> "3910636007619709856"
            17 -> "5177200225164885656"
            else -> ""
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    private fun parseVideoItems(data: JsonObject, referer: String): List<Video> {
        val videos = mutableListOf<Video>()
        val subtitleTracks = fetchSubtitleTracks(data, referer)
        val videoHeaders = Headers.headersOf(
            "Referer", referer,
            "Origin", playApiBaseUrl,
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
        data["hls"]?.jsonArray?.forEach { element ->
            val obj = element.jsonObject
            val rawUrl = obj["url"]?.jsonPrimitive?.content ?: return@forEach
            val url = rawUrl.normalizeVideoUrl()
            val quality = (obj["resolutions"]?.jsonPrimitive?.content ?: "Auto") + "P (HLS)"
            videos.add(Video(url, quality, url, headers = videoHeaders, subtitleTracks = subtitleTracks))
        }
        data["streams"]?.jsonArray?.forEach { element ->
            val obj = element.jsonObject
            val rawUrl = obj["url"]?.jsonPrimitive?.content ?: return@forEach
            val url = rawUrl.normalizeVideoUrl()
            val quality = (obj["resolutions"]?.jsonPrimitive?.content ?: "Unknown") + "P"
            videos.add(Video(url, quality, url, headers = videoHeaders, subtitleTracks = subtitleTracks))
        }
        return videos
    }

    private fun fetchSubtitleTracks(data: JsonObject, referer: String): List<Track> {
        val streams = data["streams"]?.jsonArray.orEmpty()
        val streamId = streams.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""
        
        val subjectId = runCatching {
            java.net.URL(referer).query
                ?.split("&")
                ?.mapNotNull {
                    val p = it.split("=")
                    if (p.size == 2 && p[0] == "id") p[1] else null
                }?.firstOrNull()
        }.getOrNull() ?: ""

        if (streamId.isBlank() || subjectId.isBlank()) return emptyList()

        val url = "$mobileApiBaseUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
        
        return runCatching {
            val req = GET(url, getMobileHeaders(url))
            val body = client.newCall(req).execute().use { it.body.string() }
            val captions = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("extCaptions")?.jsonArray.orEmpty()
            captions.mapNotNull { cap ->
                val obj = cap.jsonObject
                val capUrl = obj["url"]?.jsonPrimitive?.content.orEmpty().normalizeVideoUrl()
                if (capUrl.isBlank()) return@mapNotNull null
                val lang = obj["lanName"]?.jsonPrimitive?.content
                    ?: obj["lan"]?.jsonPrimitive?.content
                    ?: "Unknown"
                Track(capUrl, lang)
            }
        }.getOrDefault(emptyList())
    }

    private fun String.normalizeVideoUrl(): String {
        val value = trim()
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("https:") -> value.replaceFirst("https:", "https://")
            value.startsWith("http:") -> value.replaceFirst("http:", "http://")
            else -> value
        }
    }

    private fun parseSubjectListPage(response: Response): AnimesPage {
        val bodyString = response.body.string()
        val jsonRes = json.parseToJsonElement(bodyString).jsonObject
        val data = jsonRes["data"]?.jsonObject ?: return AnimesPage(emptyList(), false)
        
        // Handle both 'subjectList' and 'results' (for search v2)
        val rawItems = data["subjectList"]?.jsonArray 
            ?: data["items"]?.jsonArray 
            ?: data["results"]?.jsonArray?.mapNotNull { it.jsonObject["subjects"]?.jsonArray }?.flatten()
            ?: return AnimesPage(emptyList(), false)

        val items = rawItems as List<JsonElement>

        val animes = items.mapNotNull { item ->
            val obj = item.jsonObject
            val subject = if (obj.containsKey("subject")) obj["subject"]?.jsonObject else obj
            if (subject == null || !isAllowedSubject(subject)) return@mapNotNull null

            val id = subject["subjectId"]?.jsonPrimitive?.content 
                ?: subject["id"]?.jsonPrimitive?.content 
                ?: subject["detailPath"]?.jsonPrimitive?.content 
                ?: return@mapNotNull null

            SAnime.create().apply {
                title = subject["title"]?.jsonPrimitive?.content ?: ""
                url = "/movies/$id"
                thumbnail_url = subject["cover"]?.jsonObject?.get("url")?.jsonPrimitive?.content
            }
        }

        val hasMore = data["pager"]?.jsonObject?.get("hasMore")?.jsonPrimitive?.boolean ?: (animes.size >= 12)
        return AnimesPage(animes, hasMore)
    }

    private fun isAllowedSubject(subject: JsonObject): Boolean {
        val subjectType = subject["subjectType"]?.jsonPrimitive?.content?.toIntOrNull()
        // Strictly allow only real content types: 
        // 4: Movie, 2: TV Series, 1: Short/Web Series
        if (subjectType !in listOf(1, 2, 4)) return false

        val text = buildString {
            append(subject["title"]?.jsonPrimitive?.content.orEmpty())
            append(' ')
            append(subject["genre"]?.jsonPrimitive?.content.orEmpty())
            append(' ')
            append(subject["classify"]?.jsonPrimitive?.content.orEmpty())
            append(' ')
            append(subject["description"]?.jsonPrimitive?.content.orEmpty())
        }.lowercase()

        return blockedKeywords.none { text.contains(it) }
    }

    private inner class NuxtResolver(val data: JsonArray) {
        fun resolve(element: JsonElement?): JsonElement? {
            if (element == null) return null
            if (element is kotlinx.serialization.json.JsonPrimitive && !element.isString) {
                val idx = element.content.toIntOrNull() ?: return element
                return if (idx >= 0 && idx < data.size) data[idx] else element
            }
            return element
        }

        fun resolve(idx: Int): JsonElement? {
            return if (idx >= 0 && idx < data.size) data[idx] else null
        }

        fun resolveIdx(element: JsonElement?): Int? {
            if (element == null) return null
            if (element is kotlinx.serialization.json.JsonPrimitive && !element.isString) {
                return element.content.toIntOrNull()
            }
            return null
        }

        fun resolveObject(element: JsonElement?): JsonObject? {
            val resolved = resolve(element)
            return if (resolved is JsonObject) resolved.jsonObject else null
        }

        fun resolveObject(idx: Int): JsonObject? {
            val resolved = resolve(idx)
            return if (resolved is JsonObject) resolved.jsonObject else null
        }

        fun getString(element: JsonElement?): String? {
            val resolved = resolve(element)
            return if (resolved is kotlinx.serialization.json.JsonPrimitive) resolved.content else null
        }

        fun getInt(element: JsonElement?): Int? {
            val resolved = resolve(element)
            return resolved?.jsonPrimitive?.content?.toIntOrNull()
        }

        fun findSubject(): JsonObject? {
            if (data.size < 2) return null
            
            data.forEach { element ->
                if (element is JsonObject) {
                    element.keys.filter { it.startsWith("$") }.forEach { key ->
                        val bffIdx = element[key]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                        val bffData = resolve(bffIdx)
                        if (bffData is JsonObject) {
                            val bffObj = bffData.jsonObject
                            if (bffObj.containsKey("subject")) {
                                return resolveObject(bffObj["subject"])
                            }
                            if (bffObj.containsKey("data")) {
                                val innerData = resolveObject(bffObj["data"])
                                if (innerData != null && innerData.containsKey("subject")) {
                                    return resolveObject(innerData["subject"])
                                }
                            }
                        }
                    }
                }
            }
            return null
        }
    }
}
