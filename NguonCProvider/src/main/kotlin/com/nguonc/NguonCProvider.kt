package com.nguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URI
import java.net.URLEncoder

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Nguồn C"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/api/films/phim-moi-cap-nhat?page=" to "Phim Mới Cập Nhật",
        "$mainUrl/api/films/danh-sach/phim-bo?page=" to "Phim Bộ",
        "$mainUrl/api/films/danh-sach/phim-le?page=" to "Phim Lẻ",
        "$mainUrl/api/films/danh-sach/tv-shows?page=" to "TV Shows",
        "$mainUrl/api/films/danh-sach/hoat-hinh?page=" to "Hoạt Hình",
        "$mainUrl/api/films/quoc-gia/han-quoc?page=" to "Phim Hàn Quốc",
        "$mainUrl/api/films/quoc-gia/trung-quoc?page=" to "Phim Trung Quốc",
        "$mainUrl/api/films/quoc-gia/au-my?page=" to "Phim Âu Mỹ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("${request.data}$page").parsedSafe<ListResponse>()
        val items = response?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun FilmItem.toSearchResponse(): SearchResponse? {
        val slug = this.slug ?: return null
        val title = this.name ?: return null
        val epCount = this.currentEpisode?.let {
            Regex("""\d+""").find(it)?.value?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, "$mainUrl/api/film/$slug", TvType.TvSeries) {
            this.posterUrl = this@toSearchResponse.thumbUrl ?: this@toSearchResponse.posterUrl
            addSub(epCount)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/films/search?keyword=$encoded&page=1"
        val response = app.get(url).parsedSafe<ListResponse>()
        return response?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // Hàm chuẩn hóa tên tập ("Tập 01", "Tap 1", "1" -> "1") để khớp chính xác giữa các server
    private fun normalizeEpKey(rawName: String?): String {
        if (rawName.isNullOrBlank()) return "full"
        val num = Regex("""\d+""").find(rawName)?.value?.toIntOrNull()
        return num?.toString() ?: rawName.lowercase().trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/api/film/$slug"
        val movie = app.get(apiUrl).parsedSafe<DetailResponse>()?.movie ?: return null

        val episodeMap = linkedMapOf<String, MutableList<SourceItem>>()
        val displayNames = linkedMapOf<String, String>()

        // 1. Nạp danh sách tập từ API Nguồn C
        movie.episodes?.forEach { server ->
            val sName = "Nguồn C (${server.serverName ?: "Server"})"
            val epList = server.items ?: server.serverData ?: emptyList()
            epList.forEach { ep ->
                val rawName = ep.name ?: ep.slug ?: "Full"
                val key = normalizeEpKey(rawName)
                if (!displayNames.containsKey(key)) {
                    displayNames[key] = if (rawName.lowercase().contains("tập") || rawName.lowercase().contains("full")) {
                        rawName
                    } else {
                        "Tập $rawName"
                    }
                }
                val list = episodeMap.getOrPut(key) { mutableListOf() }
                list.add(
                    SourceItem(
                        serverName = sName,
                        m3u8 = ep.m3u8 ?: ep.linkM3u8,
                        embed = ep.embed ?: ep.linkEmbed
                    )
                )
            }
        }

        // 2. Nạp bổ sung luồng .m3u8 trực tiếp từ KKPhim / OPhim có cùng slug (hoặc tìm theo tên phim) để chống trường hợp streamc.xyz chặn Cloudflare
        try {
            var kkDetail = app.get("https://phimapi.com/phim/$slug").parsedSafe<KKDetailResponse>()
            if (kkDetail?.episodes.isNullOrEmpty() && !movie.name.isNullOrBlank()) {
                val searchUrl = "https://phimapi.com/v1/api/tim-kiem?keyword=${URLEncoder.encode(movie.name, "UTF-8")}&limit=5"
                val searchRes = app.get(searchUrl).parsedSafe<KKSearchWrapper>()
                val matchedSlug = searchRes?.data?.items?.firstOrNull()?.slug
                if (!matchedSlug.isNullOrBlank()) {
                    kkDetail = app.get("https://phimapi.com/phim/$matchedSlug").parsedSafe<KKDetailResponse>()
                }
            }

            kkDetail?.episodes?.forEach { server ->
                val sName = "Server Dự Phòng (${server.serverName ?: "HLS"})"
                server.serverData?.forEach { ep ->
                    val rawName = ep.name ?: ep.slug ?: "Full"
                    val key = normalizeEpKey(rawName)
                    if (!displayNames.containsKey(key)) {
                        displayNames[key] = if (rawName.lowercase().contains("tập") || rawName.lowercase().contains("full")) {
                            rawName
                        } else {
                            "Tập $rawName"
                        }
                    }
                    val list = episodeMap.getOrPut(key) { mutableListOf() }
                    if (!ep.linkM3u8.isNullOrBlank()) {
                        // Đưa luồng .m3u8 trực tiếp lên đầu danh sách để phát tức thì
                        list.add(
                            0,
                            SourceItem(
                                serverName = sName,
                                m3u8 = ep.linkM3u8,
                                embed = ep.linkEmbed
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val episodes = episodeMap.entries.mapIndexed { index, entry ->
            val key = entry.key
            val epLabel = displayNames[key] ?: "Tập ${index + 1}"
            // Thêm tiền tố "http://nguonc.local/" để hàm fixUrl của CloudStream không làm hỏng chuỗi JSON
            val payload = "http://nguonc.local/" + entry.value.toJson()
            newEpisode(payload) {
                this.name = epLabel
                this.episode = key.toIntOrNull() ?: (index + 1)
            }
        }

        val cleanPlot = movie.description?.replace(Regex("<.*?>"), "")?.trim()

        return if (episodes.size > 1 || (movie.totalEpisodes ?: 1) > 1) {
            newTvSeriesLoadResponse(movie.name ?: "", apiUrl, TvType.TvSeries, episodes) {
                this.posterUrl = movie.thumbUrl ?: movie.posterUrl
                this.backgroundPosterUrl = movie.posterUrl ?: movie.thumbUrl
                this.plot = cleanPlot
            }
        } else {
            newMovieLoadResponse(movie.name ?: "", apiUrl, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = movie.thumbUrl ?: movie.posterUrl
                this.backgroundPosterUrl = movie.posterUrl ?: movie.thumbUrl
                this.plot = cleanPlot
            }
        }
    }

    private suspend fun emitStreamLink(
        callback: (ExtractorLink) -> Unit,
        sourceName: String,
        rawUrl: String,
        refererUrl: String
    ) {
        val cleanUrl = rawUrl.replace("\\/", "/").trim()
        if (!cleanUrl.startsWith("http")) return

        val origin = try {
            val uri = URI(refererUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            mainUrl
        }

        val isM3u8 = cleanUrl.contains(".m3u8") || !cleanUrl.contains(".mp4")
        callback.invoke(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = cleanUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = refererUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "Referer" to refererUrl,
                    "Origin" to origin,
                    "User-Agent" to userAgent
                )
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanJson = if (data.contains("[")) {
            "[" + data.substringAfter("[")
        } else {
            return false
        }

        val sources = try {
            parseJson<List<SourceItem>>(cleanJson)
        } catch (e: Exception) {
            emptyList()
        }

        sources.forEach { source ->
            val sName = source.serverName ?: "Nguồn C"

            // 1. Phát ngay lập tức nếu có sẵn luồng .m3u8 trực tiếp
            if (!source.m3u8.isNullOrBlank()) {
                emitStreamLink(callback, sName, source.m3u8, "$mainUrl/")
            }

            // 2. Xử lý link embed (VD: https://embed2.streamc.xyz/embed.php?hash=...)
            val embedUrl = source.embed?.replace("\\/", "/")?.trim()
            if (!embedUrl.isNullOrBlank() && source.m3u8.isNullOrBlank()) {
                var resolvedSuccess = false
                val embedOrigin = try {
                    val uri = URI(embedUrl)
                    "${uri.scheme}://${uri.host}"
                } catch (e: Exception) {
                    "https://embed2.streamc.xyz"
                }

                // 2.1: Thử lấy qua HTTP kèm đầy đủ Sec-Fetch Headers chuẩn trình duyệt và Referer của phim.nguonc.com
                try {
                    val browserHeaders = mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Referer" to "$mainUrl/",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "cross-site",
                        "Upgrade-Insecure-Requests" to "1"
                    )

                    val html = app.get(embedUrl, headers = browserHeaders).text
                    if (!html.contains("You have been blocked")) {
                        val m3u8Match = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*)""").find(html)?.groupValues?.get(1)
                            ?: Regex("""["']?(?:file|source|url)["']?\s*:\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)

                        if (m3u8Match != null) {
                            emitStreamLink(callback, sName, m3u8Match, "$embedOrigin/")
                            resolvedSuccess = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 2.2: Dùng WebView thật của Android để vượt Cloudflare challenge và chặn bắt link .m3u8/.mp4
                if (!resolvedSuccess) {
                    try {
                        val resolver = WebViewResolver(
                            interceptUrl = Regex("""(\.m3u8|\.mp4|master\.txt|playlist\.m3u8|/hls/|get\.php)"""),
                            additionalUrls = listOf(Regex("""(\.m3u8|\.mp4)""")),
                            useOkhttp = false,
                            timeout = 20_000L
                        )
                        val result = app.get(
                            embedUrl,
                            referer = "$mainUrl/",
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "$mainUrl/"
                            ),
                            interceptor = resolver
                        )
                        val intercepted = result.url
                        if (intercepted.isNotBlank() && intercepted != embedUrl && !intercepted.contains("embed.php")) {
                            emitStreamLink(callback, sName, intercepted, "$embedOrigin/")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return true
    }

    // Các Data Class của Nguồn C
    data class ListResponse(@JsonProperty("items") val items: List<FilmItem>? = null)

    data class FilmItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("current_episode") val currentEpisode: String? = null
    )

    data class DetailResponse(@JsonProperty("movie") val movie: MovieDetail? = null)

    data class MovieDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
        @JsonProperty("episodes") val episodes: List<ServerGroup>? = null
    )

    data class ServerGroup(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("items") val items: List<EpisodeItem>? = null,
        @JsonProperty("server_data") val serverData: List<EpisodeItem>? = null
    )

    data class EpisodeItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("link_embed") val linkEmbed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("link_m3u8") val linkM3u8: String? = null
    )

    // Các Data Class hỗ trợ lấy link .m3u8 dự phòng trực tiếp
    data class KKDetailResponse(
        @JsonProperty("episodes") val episodes: List<ServerGroup>? = null
    )

    data class KKSearchWrapper(
        @JsonProperty("data") val data: ListResponse? = null
    )

    data class SourceItem(
        @JsonProperty("serverName") val serverName: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("embed") val embed: String? = null
    )
}