package eu.kanade.tachiyomi.animeextension.all.moviebox

import android.app.Application
import android.content.SharedPreferences
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.util.TimeZone

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
        "https://netfilm.world",
        "https://h5-api.aoneroom.com"
    )
    
    private val apiSources = listOf(
        "mb_call_hola",
        "MB_Website"
    )

    private val playApiBaseUrl = "https://netfilm.world"

    private val blockedKeywords = listOf(
        "mma", "ufc", "wrestling", "boxing", "kickboxing", "muay thai", "rizin", "nfc", "highlights",
        "esports", "e-sports", "gaming", "gameplay", "pubg", "free fire", "dota", "league of legends", "valorant", "fifa", "fc 24", "roblox", "minecraft",
        "dj mix", "mixtape", "mashup", "remix", "song", "lyrics", "audio porn", "massage", "therapist",
    )

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://moviebox.ph/")
        .add("Origin", "https://moviebox.ph")
        .add("Accept", "application/json")
        .add("X-Request-Lang", "en")

    private fun getApiHeaders(source: String = ""): Headers {
        val preferredSource = source.ifBlank { preferences.getString(PREF_SOURCE_KEY, "mb_call_hola") ?: "mb_call_hola" }
        return headersBuilder()
            .set("X-Source", preferredSource)
            .add("X-Client-Token", getToken())
            .add("X-Client-Info", """{"timezone":"${TimeZone.getDefault().id}"}""")
            .build()
    }

    private fun getToken(): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val reversed = timestamp.reversed()
        val md5 = reversed.md5()
        return "$timestamp,$md5"
    }

    private fun String.md5(): String {
        return MessageDigest.getInstance("MD5").digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun getPreferredHost(): String {
        return preferences.getString(PREF_HOST_KEY, apiHosts[0]) ?: apiHosts[0]
    }

    private fun safeGetJson(urlPath: String, isPost: Boolean = false, bodyData: String? = null): JsonElement {
        var lastError: Exception? = null
        
        for (host in apiHosts) {
            for (source in apiSources) {
                val url = host + urlPath
                val request = if (isPost) {
                    val body = bodyData.orEmpty().toRequestBody("application/json; charset=utf-8".toMediaType())
                    POST(url, getApiHeaders(source = source), body)
                } else {
                    GET(url, getApiHeaders(source = source))
                }
                
                try {
                    val response = client.newCall(request).execute()
                    val body = response.body.string()
                    
                    if (body.contains("<html", ignoreCase = true) || body.contains("<!DOCTYPE", ignoreCase = true)) {
                        continue
                    }
                    
                    return json.parseToJsonElement(body)
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
            }
        }
        
        throw lastError ?: Exception("The server returned an HTML page or was unreachable on all verified mirrors. This usually happens if your network/ISP is blocking the site. Try changing the 'API Host' in extension settings or use a VPN.")
    }

    // Popular: High-Quality Trending API
    override fun popularAnimeRequest(page: Int): Request {
        val url = "${getPreferredHost()}/wefeed-h5api-bff/subject/trending?page=$page&perPage=18"
        return GET(url, getApiHeaders())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val jsonRes = if (body.contains("<html", ignoreCase = true)) {
            val url = response.request.url.toString().substringAfter(".com").substringAfter(".world")
            safeGetJson(url)
        } else json.parseToJsonElement(body)
        
        val data = jsonRes.jsonObject["data"]?.jsonObject ?: return AnimesPage(emptyList(), false)
        return parseSubjectListPage(data)
    }

    // Latest: Disabled
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")

    // Search & Filters
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val rankingFilter = filters.find { it is RankingFilter } as? RankingFilter
        if (query.isEmpty() && rankingFilter != null && rankingFilter.state > 0) {
            val rankingId = rankingFilter.toId()
            val url = "${getPreferredHost()}/wefeed-h5api-bff/ranking-list/content?id=$rankingId&page=$page&perPage=18"
            return GET(url, getApiHeaders())
        }

        val url = if (query.isNotBlank()) "${getPreferredHost()}/wefeed-h5api-bff/subject/search" else "${getPreferredHost()}/wefeed-h5api-bff/subject/filter"
        
        val bodyMap = mutableMapOf<String, JsonElement>(
            "keyword" to kotlinx.serialization.json.JsonPrimitive(query),
            "page" to kotlinx.serialization.json.JsonPrimitive(page),
            "perPage" to kotlinx.serialization.json.JsonPrimitive(18)
        )

        if (query.isEmpty()) {
            val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
            val languageFilter = filters.find { it is LanguageFilter } as? LanguageFilter
            val minRateFilter = filters.find { it is MinRateFilter } as? MinRateFilter
            val sortFilter = filters.find { it is SortFilter } as? SortFilter
            val genreFilter = filters.find { it is GenreFilter } as? GenreFilter
            val yearFilter = filters.find { it is YearFilter } as? YearFilter
            val countryFilter = filters.find { it is CountryFilter } as? CountryFilter

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
        }

        val bodyData = JsonObject(bodyMap).toString()
        val body = bodyData.toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(url, getApiHeaders(), body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val jsonRes = if (body.contains("<html", ignoreCase = true)) {
            val url = response.request.url.toString().substringAfter(".com").substringAfter(".world")
            // Re-executing POST is complex in failover, throw clear error
            throw Exception("Received HTML. Please change 'API Host' in extension settings.")
        } else json.parseToJsonElement(body)
        
        val data = jsonRes.jsonObject["data"]?.jsonObject ?: return AnimesPage(emptyList(), false)
        return parseSubjectListPage(data)
    }

    // Details
    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.split("/").last()
        val param = if (id.all { it.isDigit() }) "subjectId" else "detailPath"
        val url = "${getPreferredHost()}/wefeed-h5api-bff/detail?$param=$id"
        return GET(url, getApiHeaders())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.body.string()
        val jsonRes = if (body.contains("<html", ignoreCase = true)) {
            val id = response.request.url.queryParameter("subjectId") ?: response.request.url.queryParameter("detailPath").orEmpty()
            val param = if (id.all { it.isDigit() }) "subjectId" else "detailPath"
            safeGetJson("/wefeed-h5api-bff/detail?$param=$id")
        } else json.parseToJsonElement(body)
        
        val data = jsonRes.jsonObject["data"]?.jsonObject ?: throw Exception("Details not found")
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
        val body = response.body.string()
        
        // Comprehensive host & source failover for episode listing
        val jsonRes = if (body.contains("<html", ignoreCase = true) || !body.startsWith("{")) {
            val id = response.request.url.queryParameter("subjectId") 
                ?: response.request.url.queryParameter("detailPath").orEmpty()
            val param = if (id.all { it.isDigit() }) "subjectId" else "detailPath"
            safeGetJson("/wefeed-h5api-bff/detail?$param=$id")
        } else json.parseToJsonElement(body)
        
        val data = jsonRes.jsonObject["data"]?.jsonObject ?: return emptyList()
        val subject = data["subject"]?.jsonObject ?: return emptyList()
        val subjectId = subject["subjectId"]?.jsonPrimitive?.content ?: return emptyList()
        val detailPath = subject["detailPath"]?.jsonPrimitive?.content ?: subjectId
        val subjectType = subject["subjectType"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        
        // Seasons can be in 'resource' or root 'data' depending on API version
        val resource = data["resource"]?.jsonObject ?: data
        val seasons = resource["seasons"]?.jsonArray

        if (!seasons.isNullOrEmpty()) {
            val episodes = mutableListOf<SEpisode>()
            seasons.forEach { seasonEl ->
                val season = seasonEl.jsonObject
                val seNum = season["se"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                val allEpRaw = season["allEp"]?.jsonPrimitive?.content.orEmpty()
                val totalEp = if (allEpRaw.isNotBlank()) {
                    allEpRaw.split(",").filter { it.isNotBlank() }.size
                } else {
                    season["maxEp"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                }

                for (i in 1..totalEp) {
                    episodes.add(SEpisode.create().apply {
                        name = "Season $seNum - Episode $i"
                        episode_number = i.toFloat()
                        url = "$subjectId&se=$seNum&ep=$i&detailPath=$detailPath"
                        date_upload = System.currentTimeMillis()
                    })
                }
            }
            if (episodes.isNotEmpty()) return episodes.reversed()
        }

        // Fallback for Movies or single-episode Series
        return listOf(
            SEpisode.create().apply {
                name = "Full Movie"
                url = "$subjectId&se=0&ep=0&detailPath=$detailPath"
                date_upload = System.currentTimeMillis()
            },
        )
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.split("&")
        val subjectId = parts[0]
        val se = parts.find { it.startsWith("se=") }?.split("=")?.get(1) ?: "0"
        val ep = parts.find { it.startsWith("ep=") }?.split("=")?.get(1) ?: "0"
        val detailPath = parts.find { it.startsWith("detailPath=") }?.split("=")?.get(1) ?: ""
        
        val url = "${getPreferredHost()}/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath"
        return GET(url, getApiHeaders())
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body.string()
        val jsonRes = if (body.contains("<html", ignoreCase = true)) {
            val url = response.request.url.toString().substringAfter(".com").substringAfter(".world")
            safeGetJson(url)
        } else json.parseToJsonElement(body)
        
        val data = jsonRes.jsonObject["data"]?.jsonObject ?: return emptyList()
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

    private fun parseSubjectListPage(data: JsonObject): AnimesPage {
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
        val title = subject["title"]?.jsonPrimitive?.content?.lowercase() ?: ""
        return blockedKeywords.none { title.contains(it) }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hostPref = ListPreference(screen.context).apply {
            key = PREF_HOST_KEY
            title = "API Host"
            entries = arrayOf("Netfilm (Mirror)", "Aoneroom (Official)")
            entryValues = apiHosts.toTypedArray()
            setDefaultValue(apiHosts[0])
            summary = "%s"
        }
        val sourcePref = ListPreference(screen.context).apply {
            key = PREF_SOURCE_KEY
            title = "Traffic Source"
            entries = arrayOf("Stealth (Bypass)", "Website (Standard)")
            entryValues = apiSources.toTypedArray()
            setDefaultValue(apiSources[0])
            summary = "%s"
        }
        screen.addPreference(hostPref)
        screen.addPreference(sourcePref)
    }

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
            val quality = (obj["resolutions"]?.jsonPrimitive?.content ?: "Auto") + "P (MP4)"
            videos.add(Video(url, quality, url, headers = videoHeaders, subtitleTracks = subtitleTracks))
        }
        return videos
    }

    private fun fetchSubtitleTracks(data: JsonObject, referer: String): List<Track> {
        val streams = data["streams"]?.jsonArray.orEmpty()
        val streamId = streams.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""
        
        val parts = java.net.URL(referer).query?.split("&").orEmpty()
        val subjectId = parts.find { it.startsWith("id=") }?.split("=")?.get(1) 
            ?: parts.find { it.startsWith("subjectId=") }?.split("=")?.get(1)
            ?: ""

        if (streamId.isBlank() || subjectId.isBlank()) return emptyList()

        val url = "${getPreferredHost()}/wefeed-h5api-bff/subject/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
        
        return runCatching {
            val req = GET(url, getApiHeaders())
            val body = client.newCall(req).execute().body.string()
            val jsonRes = json.parseToJsonElement(body).jsonObject
            val captions = jsonRes["data"]?.jsonObject?.get("extCaptions")?.jsonArray.orEmpty()
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
        val value = this
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("https:") -> value.replaceFirst("https:", "https://")
            value.startsWith("http:") -> value.replaceFirst("http:", "http://")
            else -> value
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        TypeFilter(),
        LanguageFilter(),
        MinRateFilter(),
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

    companion object {
        private const val PREF_HOST_KEY = "api_host"
        private const val PREF_SOURCE_KEY = "api_source"
    }
}