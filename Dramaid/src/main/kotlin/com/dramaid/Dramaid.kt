package com.dramaid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Base64

class Dramaid : MainAPI() {
    override var mainUrl = "https://dramaid.nl"
    override var name = "DramaId"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama)

    companion object {
        private fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        private fun getType(t: String?): TvType {
            return when {
                t?.contains("Movie", true) == true -> TvType.Movie
                t?.contains("Anime", true) == true -> TvType.Anime
                else -> TvType.AsianDrama
            }
        }
    }

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Drama Terbaru",
        "&order=latest" to "Baru Ditambahkan",
        "&status=&type=&order=popular" to "Drama Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/series/?page=$page${request.data}").document
        val home = document.select("article[itemscope=itemscope]").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperDramaLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            Regex("$mainUrl/(.+)-ep.+").find(uri)?.groupValues?.getOrNull(1)
                ?.let { "$mainUrl/series/$it" } ?: uri
        } else uri
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperDramaLink(this.selectFirst("a.tip")?.attr("href") ?: return null)
        val title = this.selectFirst("h2[itemprop=headline]")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.select("img:last-child").attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article[itemscope=itemscope]").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(document.select("div.thumb img:last-child").attr("src"))
        val tags = document.select(".genxed > a").map { it.text() }
        val type = document.selectFirst(".info-content .spe span:contains(Tipe:)")?.ownText()
        val year = Regex("\\d{4}").find(
            document.selectFirst(".info-content > .spe > span > time")?.text()?.trim().orEmpty()
        )?.groupValues?.firstOrNull()?.toIntOrNull()
        val status = getStatus(
            document.select(".info-content > .spe > span:nth-child(1)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".entry-content > p").text().trim()

        val episodes = document.select(".eplister > ul > li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(a.attr("href"))
            val epTitle = li.selectFirst("a > .epl-title")?.text() ?: a.text()
            val epNum = Regex("""(?:Episode|Eps)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) {
                name = epTitle
                episode = epNum
            }
        }.reversed()

        val recommendations = document
            .select(".listupd > article[itemscope=itemscope]")
            .mapNotNull { it.toSearchResult() }

        return newTvSeriesLoadResponse(
            title, url, getType(type), episodes = episodes
        ) {
            posterUrl = poster
            this.year = year
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private data class Sources(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("default") val default: Boolean?
    )

    private data class Tracks(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val type: String,
        @JsonProperty("default") val default: Boolean?
    )

    private suspend fun invokeDriveSource(
        url: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val server = app.get(url).document.selectFirst(".picasa")?.nextElementSibling()?.data()
            ?: return

        val source = server.substringAfter("sources: [", "").substringBefore("],")
        val trackers = server.substringAfter("tracks:[", "").substringBefore("],")
            .replace("//language", "")
            .replace("file", "\"file\"")
            .replace("label", "\"label\"")
            .replace("kind", "\"kind\"")

        if (source.isNotBlank()) {
            tryParseJson<List<Sources>>("[$source]")?.forEach { s ->
                sourceCallback.invoke(
                    newExtractorLink(
                        source = "Drive",
                        name = name,
                        url = fixUrl(s.file)
                    ) {
                        referer = "https://motonews.club/"
                        quality = getQualityFromName(s.label)
                        isM3u8 = s.type.equals("hls", true)
                    }
                )
            }
        }

        if (trackers.isNotBlank()) {
            tryParseJson<List<Tracks>>("[$trackers]")?.forEach { t ->
                subCallback.invoke(
                    SubtitleFile(t.label, t.file)
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val sources = document.select(".mobius > .mirror > option").mapNotNull { opt ->
            val raw = opt.attr("value")
            val decoded = runCatching {
                String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
            }.getOrNull() ?: return@mapNotNull null
            val src = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src").orEmpty()
            if (src.isBlank()) null else fixUrl(src)
        }

        for (src in sources) {
            val processed = src.replace("https://ndrama.xyz", "https://www.fembed.com")
            if (processed.contains("motonews")) {
                invokeDriveSource(processed, subtitleCallback, callback)
            } else {
                loadExtractor(processed, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
}
