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

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/api/film/$slug"
        val movie = app.get(apiUrl).parsedSafe<DetailResponse>()?.movie ?: return null

        val episodeMap = linkedMapOf<String, MutableList<SourceItem>>()

        movie.episodes?.forEach { server ->
            val sName = server.serverName ?: "Server"
            server.items?.forEach { ep ->
                val epName = ep.name ?: "Full"
                val list = episodeMap.getOrPut(epName) { mutableListOf() }
                list.add(
                    SourceItem(
                        serverName = sName,
                        m3u8 = ep.m3u8,
                        embed = ep.embed
                    )
                )
            }
        }

        val episodes = episodeMap.entries.mapIndexed { index, entry ->
            val epName = entry.key
            val sourcesJson = entry.value.toJson()
            newEpisode(sourcesJson) {
                this.name = if (epName.lowercase().contains("tập") || epName.lowercase().contains("full")) {
                    epName
                } else {
                    "Tập $epName"
                }
                this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull() ?: (index + 1)
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
            newMovieLoadResponse(movie.name ?: "", apiUrl, TvType.Movie, episodes.firstOrNull()?.data ?: "[]") {
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
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
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
        // QUAN TRỌNG: Cắt bỏ phần mainUrl ("https://phim.nguonc.com/") mà hàm fixUrl của CloudStream tự động gắn vào trước chuỗi JSON
        val cleanJson = if (data.contains("[")) {
            "[" + data.substringAfter("[")
        } else {
            data
        }

        val sources = try {
            parseJson<List<SourceItem>>(cleanJson)
        } catch (e: Exception) {
            emptyList()
        }

        sources.forEach { source ->
            val sName = "Nguồn C - ${source.serverName ?: "Default"}"

            // TẦNG 1: Nếu API trả sẵn link .m3u8 trực tiếp
            if (!source.m3u8.isNullOrBlank()) {
                emitStreamLink(callback, sName, source.m3u8, "$mainUrl/")
            }

            // TẦNG 2: Bóc tách từ link embed (VD: https://embed.streamc.xyz/embed.php?hash=...)
            val embedUrl = source.embed?.replace("\\/", "/")?.trim()
            if (!embedUrl.isNullOrBlank()) {
                var found = false
                try {
                    val embedOrigin = try {
                        val uri = URI(embedUrl)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) {
                        "https://embed.streamc.xyz"
                    }

                    val embedResponse = app.get(
                        embedUrl,
                        referer = "$mainUrl/",
                        headers = mapOf(
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        )
                    )
                    val html = embedResponse.text

                    // 2.1: Tìm trực tiếp link .m3u8 / .mp4 hoặc biến file: "..." trong mã HTML của trang embed
                    val directMatch = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*)""").find(html)?.groupValues?.get(1)
                        ?: Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)

                    if (directMatch != null) {
                        emitStreamLink(callback, sName, directMatch, embedUrl)
                        found = true
                    }

                    // 2.2: Cơ chế riêng của streamc.xyz (gọi sang get.php?hash=... hoặc ajax)
                    if (!found && embedUrl.contains("hash=")) {
                        val hash = embedUrl.substringAfter("hash=").substringBefore("&")
                        val candidateUrls = listOf(
                            embedUrl.replace("embed.php", "get.php"),
                            "$embedOrigin/get.php?hash=$hash",
                            "$embedOrigin/ajax.php?hash=$hash"
                        ).distinct()

                        for (apiEndpoint in candidateUrls) {
                            val apiText = app.get(
                                apiEndpoint,
                                referer = embedUrl,
                                headers = mapOf(
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Origin" to embedOrigin,
                                    "Referer" to embedUrl
                                )
                            ).text

                            val subMatch = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|txt)[^\s"'<>\\]*)""").find(apiText)?.groupValues?.get(1)
                                ?: Regex("""["']?(?:file|url|source)["']?\s*:\s*["'](https?://[^"']+)["']""").find(apiText)?.groupValues?.get(1)

                            if (subMatch != null) {
                                emitStreamLink(callback, sName, subMatch, embedUrl)
                                found = true
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // TẦNG 3: Dùng WebViewResolver mở ngầm trang embed để bắt gói tin video thực tế
                if (!found) {
                    try {
                        val resolver = WebViewResolver(
                            interceptUrl = Regex("""(\.m3u8|\.mp4|master\.txt|index\.m3u8|playlist\.m3u8|/hls/)"""),
                            additionalUrls = listOf(Regex("""(\.m3u8|\.mp4|get\.php)""")),
                            useOkhttp = false,
                            timeout = 15_000L
                        )
                        val resolved = app.get(
                            embedUrl,
                            referer = "$mainUrl/",
                            interceptor = resolver
                        )
                        val interceptedUrl = resolved.url
                        if (interceptedUrl.isNotBlank() && interceptedUrl != embedUrl) {
                            emitStreamLink(callback, sName, interceptedUrl, embedUrl)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return true
    }

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
        @JsonProperty("items") val items: List<EpisodeItem>? = null
    )

    data class EpisodeItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )

    data class SourceItem(
        @JsonProperty("serverName") val serverName: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("embed") val embed: String? = null
    )
}