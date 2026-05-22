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
    override val supportsLatest = true
    override val id: Long = 3508466391484419848L

    private val apiBaseUrl = "https://h5-api.aoneroom.com"
    private val playApiBaseUrl = "https://netfilm.world"
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

    private fun getToken(): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val reversed = timestamp.reversed()
        val md5 = reversed.md5()
        return "$timestamp,$md5"
    }

    private fun String.md5(): String {
        return MessageDigest.getInstance("MD5")
            .digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    // Popular: High-Quality Trending API
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiBaseUrl/wefeed-h5api-bff/subject/trending?page=$page&perPage=18"
        return GET(url, headersBuilder().add("X-Client-Token", getToken()).build())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseSubjectListPage(response)
    }

    // Latest: Filtered Latest (High perPage to bypass noise)
    override fun latestUpdatesRequest(page: Int): Request {
        val body = """{"page": $page, "perPage": 60, "sort": "Latest"}""".toRequestBody("application/json".toMediaType())
        return POST("$apiBaseUrl/wefeed-h5api-bff/subject/filter", headersBuilder().add("X-Client-Token", getToken()).build(), body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseSubjectListPage(response)
    }

    // Search & Filters
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val rankingFilter = filters.find { it is RankingFilter } as? RankingFilter
        if (query.isEmpty() && rankingFilter != null && rankingFilter.state > 0) {
            val rankingId = rankingFilter.toId()
            val url = "$apiBaseUrl/wefeed-h5api-bff/ranking-list/content?id=$rankingId&page=$page&perPage=18"
            return GET(url, headersBuilder().add("X-Client-Token", getToken()).build())
        }

        if (query.isNotBlank()) {
            // Website native keyword search endpoint.
            val body = JsonObject(
                mapOf(
                    "keyword" to kotlinx.serialization.json.JsonPrimitive(query),
                    "page" to kotlinx.serialization.json.JsonPrimitive(page),
                    "perPage" to kotlinx.serialization.json.JsonPrimitive(18),
                ),
            ).toString().toRequestBody("application/json".toMediaType())
            return POST(
                "$apiBaseUrl/wefeed-h5api-bff/subject/search",
                headersBuilder().add("X-Client-Token", getToken()).build(),
                body,
            )
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

        val body = JsonObject(bodyMap).toString().toRequestBody("application/json".toMediaType())
        return POST("$apiBaseUrl/wefeed-h5api-bff/subject/filter", headersBuilder().add("X-Client-Token", getToken()).build(), body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseSubjectListPage(response)
    }

    // Details
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script[id=__NUXT_DATA__]")?.data()
            ?: throw Exception("Could not find Nuxt data")
        
        val nuxtData = json.parseToJsonElement(scriptData).jsonArray
        val resolver = NuxtResolver(nuxtData)
        val subject = resolver.findSubject() ?: throw Exception("Subject not found in Nuxt data")
        
        return SAnime.create().apply {
            title = resolver.getString(subject["title"]) ?: ""
            description = resolver.getString(subject["description"])
            genre = resolver.getString(subject["genre"])
            author = resolver.getString(subject["countryName"])
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val detailPath = java.net.URL(response.request.url.toString()).path.split("/").lastOrNull { it.isNotEmpty() }
            ?: return emptyList()
        val detailData = fetchDetailData(detailPath) ?: return emptyList()
        val subject = detailData["subject"]?.jsonObject ?: return emptyList()
        val subjectId = subject["subjectId"]?.jsonPrimitive?.content ?: return emptyList()
        val subjectType = subject["subjectType"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val seasons = detailData["resource"]?.jsonObject?.get("seasons")?.jsonArray

        if (subjectType != 1 && !seasons.isNullOrEmpty()) {
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
                        url = "$subjectId&se=$seNum&ep=$i&detailPath=$detailPath"
                        date_upload = System.currentTimeMillis()
                    })
                }
            }
            if (episodes.isNotEmpty()) return episodes.reversed()
        }

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
        
        val url = "$playApiBaseUrl/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath"
        val referer = "$playApiBaseUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&detailSe=$se&detailEp=$ep&lang=en"
        val playHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Accept", "application/json")
            .add("Origin", playApiBaseUrl)
            .add("Referer", referer)
            .add("X-Source", "mb_call_hola")
            .add("X-Client-Info", """{"timezone":"${TimeZone.getDefault().id}"}""")
            .build()
        return GET(url, playHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val bodyString = response.body.string()
        val jsonRes = json.parseToJsonElement(bodyString).jsonObject
        val data = jsonRes["data"]?.jsonObject ?: return emptyList()
        val requestReferer = response.request.header("Referer") ?: "$playApiBaseUrl/"
        
        if (data["limited"]?.jsonPrimitive?.boolean == true) {
            throw Exception("Limited Content: Open Website to Unlock")
        }

        val videos = parseVideoItems(data, requestReferer)
        if (videos.isNotEmpty()) return videos

        // Fallback: try legacy API host before giving up.
        val requestUrl = response.request.url
        val legacyUrl = "$apiBaseUrl/wefeed-h5api-bff/subject/play?" + requestUrl.query.orEmpty()
        val legacyRequest = GET(legacyUrl, headersBuilder()
            .add("X-Client-Token", getToken())
            .add("X-Client-Info", """{"timezone":"${TimeZone.getDefault().id}"}""")
            .build())
        val legacyBody = client.newCall(legacyRequest).execute().use { it.body.string() }
        val legacyData = json.parseToJsonElement(legacyBody).jsonObject["data"]?.jsonObject ?: return emptyList()
        return parseVideoItems(legacyData, requestReferer)
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

    private fun fetchDetailData(detailPath: String): JsonObject? {
        val url = "$apiBaseUrl/wefeed-h5api-bff/detail?detailPath=$detailPath"
        val request = GET(url, headersBuilder()
            .add("X-Client-Token", getToken())
            .add("X-Client-Info", """{"timezone":"${TimeZone.getDefault().id}"}""")
            .build())
        return client.newCall(request).execute().use { res ->
            val body = res.body.string()
            json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
        }
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
            val quality = (obj["resolutions"]?.jsonPrimitive?.content ?: "Unknown") + "P"
            videos.add(Video(url, quality, url, headers = videoHeaders, subtitleTracks = subtitleTracks))
        }
        return videos
    }

    private fun fetchSubtitleTracks(data: JsonObject, referer: String): List<Track> {
        val hls = data["hls"]?.jsonArray.orEmpty()
        val streams = data["streams"]?.jsonArray.orEmpty()
        val (format, id) = when {
            hls.isNotEmpty() -> "HLS" to (hls.first().jsonObject["id"]?.jsonPrimitive?.content ?: "")
            streams.isNotEmpty() -> "MP4" to (streams.first().jsonObject["id"]?.jsonPrimitive?.content ?: "")
            else -> "" to ""
        }
        if (format.isBlank() || id.isBlank()) return emptyList()

        val refererUrl = runCatching { java.net.URL(referer) }.getOrNull() ?: return emptyList()
        val detailPath = refererUrl.path.split("/").lastOrNull { it.isNotBlank() } ?: return emptyList()
        val subjectId = refererUrl.query
            ?.split("&")
            ?.mapNotNull {
                val p = it.split("=")
                if (p.size == 2 && p[0] == "id") p[1] else null
            }?.firstOrNull().orEmpty()
        if (subjectId.isBlank()) return emptyList()

        val captionUrl = "$playApiBaseUrl/wefeed-h5api-bff/subject/caption?format=$format&id=$id&subjectId=$subjectId&detailPath=$detailPath"
        val captionHeaders = Headers.Builder()
            .add("Accept", "application/json")
            .add("Origin", playApiBaseUrl)
            .add("Referer", referer)
            .add("X-Client-Info", """{"timezone":"${TimeZone.getDefault().id}"}""")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        return runCatching {
            val req = GET(captionUrl, captionHeaders)
            val body = client.newCall(req).execute().use { it.body.string() }
            val captions = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("captions")?.jsonArray.orEmpty()
            captions.mapNotNull { cap ->
                val obj = cap.jsonObject
                val url = obj["url"]?.jsonPrimitive?.content.orEmpty().normalizeVideoUrl()
                if (url.isBlank()) return@mapNotNull null
                val lang = obj["lanName"]?.jsonPrimitive?.content
                    ?: obj["lan"]?.jsonPrimitive?.content
                    ?: "Unknown"
                Track(url, lang)
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
        val items = data["subjectList"]?.jsonArray ?: data["items"]?.jsonArray ?: return AnimesPage(emptyList(), false)

        val animes = items.mapNotNull { item ->
            val obj = item.jsonObject
            val subject = if (obj.containsKey("subject")) obj["subject"]?.jsonObject else obj
            if (subject == null || !isAllowedSubject(subject)) return@mapNotNull null

            val detailPath = subject["detailPath"]?.jsonPrimitive?.content.orEmpty()
            if (detailPath.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                title = subject["title"]?.jsonPrimitive?.content ?: ""
                url = "/movies/$detailPath"
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
