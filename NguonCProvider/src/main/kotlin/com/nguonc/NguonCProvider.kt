package com.nguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
        return newAnimeSearchResponse(title, "$mainUrl/api/film/$slug", TvType.TvSeries) {
            this.posterUrl = this@toSearchResponse.thumbUrl ?: this@toSearchResponse.posterUrl
            addSub(this@toSearchResponse.currentEpisode)
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
                this.episode = epName.filter { it.isDigit() }.toIntOrNull() ?: (index + 1)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = parseJson<List<SourceItem>>(data)

        sources.forEach { source ->
            val sName = "Nguồn C - ${source.serverName ?: "Default"}"

            // TẦNG 1: Link .m3u8 trực tiếp
            if (!source.m3u8.isNullOrBlank()) {
                M3u8Helper.generateM3u8(
                    source = sName,
                    streamUrl = source.m3u8,
                    referer = "$mainUrl/"
                ).forEach(callback)
                return@forEach
            }

            // TẦNG 2: Bóc tách từ link embed
            val embedUrl = source.embed
            if (!embedUrl.isNullOrBlank()) {
                var found = false
                try {
                    val embedResponse = app.get(embedUrl, referer = "$mainUrl/")
                    val html = embedResponse.text

                    val m3u8Match = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").find(html)?.groupValues?.get(1)
                    if (m3u8Match != null) {
                        val cleanUrl = m3u8Match.replace("\\/", "/")
                        M3u8Helper.generateM3u8(
                            source = sName,
                            streamUrl = cleanUrl,
                            referer = embedUrl
                        ).forEach(callback)
                        found = true
                    }

                    if (!found && embedUrl.contains("embed.php")) {
                        val getPhpUrl = embedUrl.replace("embed.php", "get.php")
                        val getPhpText = app.get(
                            getPhpUrl,
                            referer = embedUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        ).text

                        val subMatch = Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*)""").find(getPhpText)?.groupValues?.get(1)
                        if (subMatch != null) {
                            val cleanUrl = subMatch.replace("\\/", "/")
                            val isM3u8Link = cleanUrl.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = sName,
                                    name = sName,
                                    url = cleanUrl,
                                    type = if (isM3u8Link) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // TẦNG 3: Dùng WebViewResolver ngầm
                if (!found) {
                    try {
                        val resolver = WebViewResolver(
                            interceptUrl = Regex("""\.(m3u8|mp4)(\?.*)?$"""),
                            additionalUrls = listOf(Regex("""get\.php\?hash=""")),
                            useOkhttp = false,
                            timeout = 15_000L
                        )
                        val resolved = app.get(
                            embedUrl,
                            referer = "$mainUrl/",
                            interceptor = resolver
                        )
                        val interceptedUrl = resolved.url
                        if (interceptedUrl.contains(".m3u8") || interceptedUrl.contains(".mp4")) {
                            val isM3u8Link = interceptedUrl.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = sName,
                                    name = sName,
                                    url = interceptedUrl,
                                    type = if (isM3u8Link) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
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
        val serverName: String? = null,
        val m3u8: String? = null,
        val embed: String? = null
    )
}