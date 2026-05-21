package eu.kanade.tachiyomi.animeextension.all.moviebox

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
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
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

class MovieBox : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "MovieBox"
    override val baseUrl = "https://moviebox.ph"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 3508466391484419848L

    private val apiBaseUrl = "https://h5-api.aoneroom.com"

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json")
        .add("X-Request-Lang", "en")

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

    // Popular: Trending Now
    override fun popularAnimeRequest(page: Int): Request {
        val body = """{"page": $page, "perPage": 18, "sort": "POPULAR"}""".toRequestBody("application/json".toMediaType())
        return POST("$apiBaseUrl/wefeed-h5api-bff/subject/filter", headersBuilder().add("X-Client-Token", getToken()).build(), body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val bodyString = response.body.string()
        val data = json.parseToJsonElement(bodyString).jsonObject["data"]?.jsonObject ?: return AnimesPage(emptyList(), false)
        val items = data["items"]?.jsonArray ?: data["subjectList"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        
        val animes = items.map { item ->
            val obj = item.jsonObject
            SAnime.create().apply {
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                url = "/detail/" + (obj["detailPath"]?.jsonPrimitive?.content ?: "")
                thumbnail_url = obj["cover"]?.jsonObject?.get("url")?.jsonPrimitive?.content
            }
        }
        val hasMore = data["pager"]?.jsonObject?.get("hasMore")?.jsonPrimitive?.boolean 
            ?: (items.size >= 12)
        return AnimesPage(animes, hasMore)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val body = """{"page": $page, "perPage": 18, "sort": "LATEST"}""".toRequestBody("application/json".toMediaType())
        return POST("$apiBaseUrl/wefeed-h5api-bff/subject/filter", headersBuilder().add("X-Client-Token", getToken()).build(), body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // Search & Filters
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val rankingFilter = filters.find { it is RankingFilter } as? RankingFilter
        if (query.isEmpty() && rankingFilter != null && rankingFilter.state > 0) {
            val rankingId = rankingFilter.toId()
            val url = "$apiBaseUrl/wefeed-h5api-bff/ranking-list/content?id=$rankingId&page=$page&perPage=18"
            return GET(url, headersBuilder().add("X-Client-Token", getToken()).build())
        }

        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
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
            bodyMap["classify"] = kotlinx.serialization.json.JsonPrimitive(typeFilter.toId())
        }
        if (sortFilter != null) {
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

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

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
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script[id=__NUXT_DATA__]")?.data()
            ?: return emptyList()
        
        val nuxtData = json.parseToJsonElement(scriptData).jsonArray
        val resolver = NuxtResolver(nuxtData)
        
        val subject = resolver.findSubject() ?: return emptyList()
        
        val resourceIdx = subject["resource"]?.jsonPrimitive?.content?.toIntOrNull()
        if (resourceIdx != null) {
            val resourceElement = resolver.resolve(resourceIdx)
            if (resourceElement is JsonObject) {
                val resource = resourceElement.jsonObject
                val seasonsIdx = resource["seasons"]?.jsonPrimitive?.content?.toIntOrNull()
                if (seasonsIdx != null) {
                    val seasonsElement = resolver.resolve(seasonsIdx)
                    if (seasonsElement is JsonArray) {
                        val seasons = seasonsElement.jsonArray
                        val episodes = mutableListOf<SEpisode>()
                        
                        seasons.forEachIndexed { sIdx, sRef ->
                            val seasonIdx = if (sRef is kotlinx.serialization.json.JsonPrimitive) sRef.jsonPrimitive.content.toIntOrNull() else null
                            if (seasonIdx == null) return@forEachIndexed
                            
                            val seasonElement = resolver.resolve(seasonIdx)
                            if (seasonElement is JsonObject) {
                                val season = seasonElement.jsonObject
                                val allEp = resolver.getInt(season["allEp"]) ?: 1
                                val seName = resolver.getString(season["se"]) ?: "S${sIdx + 1}"
                                
                                for (i in 1..allEp) {
                                    episodes.add(SEpisode.create().apply {
                                        name = "$seName - Episode $i"
                                        episode_number = i.toFloat()
                                        url = response.request.url.toString()
                                        date_upload = System.currentTimeMillis()
                                    })
                                }
                            }
                        }
                        return episodes.reversed()
                    }
                }
            }
        }

        return listOf(SEpisode.create().apply {
            name = "Movie"
            url = response.request.url.toString()
            date_upload = System.currentTimeMillis()
        })
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script[id=__NUXT_DATA__]")?.data()
            ?: return emptyList()
        
        val nuxtData = json.parseToJsonElement(scriptData).jsonArray
        val videos = mutableListOf<Video>()
        
        nuxtData.forEach { element ->
            try {
                if (element is kotlinx.serialization.json.JsonPrimitive) {
                    val str = element.jsonPrimitive.content
                    if (str.startsWith("https://macdn.aoneroom.com/media/")) {
                        val quality = when {
                            str.contains("-sd.") -> "480p"
                            str.contains("-hd.") -> "720p"
                            else -> "1080p"
                        }
                        videos.add(Video(str, quality, str))
                    }
                }
            } catch (e: Exception) {}
        }
        
        return videos
    }

    override fun List<Video>.sort(): List<Video> {
        return this.sortedByDescending { 
            when {
                it.quality.contains("1080p") -> 3
                it.quality.contains("720p") -> 2
                it.quality.contains("480p") -> 1
                else -> 0
            }
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
        YearFilter(),
        CountryFilter(),
        AnimeFilter.Separator(),
        RankingFilter()
    )

    private class SortFilter : AnimeFilter.Select<String>("Sort", arrayOf("Popular", "Latest", "IMDb Rating")) {
        fun toId() = when (state) {
            1 -> "LATEST"
            2 -> "IMDB_RATING"
            else -> "POPULAR"
        }
    }

    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Movie", "TV Series", "Animation Shows", "Bengali dub", "Hindi dub")) {
        fun toId() = when (state) {
            1 -> "Movie"
            2 -> "TV Series"
            3 -> "Animation Shows"
            4 -> "Bengali dub"
            5 -> "Hindi dub"
            else -> "All"
        }
    }

    private class GenreFilter : AnimeFilter.Select<String>("Genre", arrayOf(
        "All", "Action", "Adventure", "Comedy", "Crime", "Drama", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller", "War", "Western", "Animation", "Biography", "Documentary", "Family", "History", "Music", "Musical", "Sport"
    )) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class YearFilter : AnimeFilter.Select<String>("Year", arrayOf("All") + (2026 downTo 1990).map { it.toString() }.toTypedArray()) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class CountryFilter : AnimeFilter.Select<String>("Country", arrayOf("All", "United States", "India", "China", "Korea", "Japan", "United Kingdom", "France", "Germany", "Canada", "Spain", "Italy", "Turkey")) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class RankingFilter : AnimeFilter.Select<String>("Ranking List (Search only)", arrayOf(
        "None",
        "Trending Now",
        "Cinema",
        "Bollywood",
        "Hollywood",
        "South Indian",
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
            6 -> "5837669637445565960"
            7 -> "735765054104261208"
            8 -> "5429170738815291968"
            9 -> "5606549574572819920"
            10 -> "8434602210994128512"
            11 -> "7878715743607948784"
            12 -> "8788126208987989488"
            13 -> "4903182713986896328"
            14 -> "1255898847918934600"
            15 -> "3910636007619709856"
            16 -> "5177200225164885656"
            else -> ""
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    private inner class NuxtResolver(val data: JsonArray) {
        fun resolve(index: Int): JsonElement = data[index]
        
        fun getString(element: JsonElement?): String? {
            if (element == null) return null
            val idx = element.jsonPrimitive.content.toIntOrNull() ?: return element.jsonPrimitive.content
            return if (idx >= 0 && idx < data.size && data[idx] is kotlinx.serialization.json.JsonPrimitive) {
                data[idx].jsonPrimitive.content
            } else null
        }

        fun getInt(element: JsonElement?): Int? {
            return element?.jsonPrimitive?.content?.toIntOrNull()
        }

        fun findSubject(): JsonObject? {
            if (data.size < 2) return null
            
            // Nuxt data usually has the actual state inside an object at an early index
            // We search for anything that looks like BFF response ($...)
            
            data.forEach { element ->
                if (element is JsonObject) {
                    element.keys.filter { it.startsWith("$") }.forEach { key ->
                        val bffIdx = element[key]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                        val bffData = if (bffIdx >= 0 && bffIdx < data.size) resolve(bffIdx) else null
                        if (bffData is JsonObject) {
                            val bffObj = bffData.jsonObject
                            if (bffObj.containsKey("subject")) {
                                val subIdx = bffObj["subject"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                                val sub = resolve(subIdx)
                                if (sub is JsonObject) return sub.jsonObject
                            }
                            if (bffObj.containsKey("data")) {
                                val innerDataIdx = bffObj["data"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                                val innerData = resolve(innerDataIdx)
                                if (innerData is JsonObject) {
                                    val innerObj = innerData.jsonObject
                                    if (innerObj.containsKey("subject")) {
                                        val subIdx = innerObj["subject"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                                        val sub = resolve(subIdx)
                                        if (sub is JsonObject) return sub.jsonObject
                                    }
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
