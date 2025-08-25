package com.dramaid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Dramaid : MainAPI() {
    override var mainUrl = "https://dramaid.icu"
    override var name = "Dramaid"
    override val supportedTypes = setOf(TvType.AsianDrama)
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select("div.post-item").mapNotNull {
            val title = it.selectFirst("h2.post-title a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h2.post-title a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            newHomePageList(
                title = title,
                link = href,
                posterUrl = poster
            )
        }
        return newHomePageResponse(
            list = listOf(
                HomePageList("Terbaru", items)
            )
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: name
        val poster = doc.selectFirst("div.thumb img")?.attr("src")
        val description = doc.selectFirst("div.entry-content p")?.text()

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.AsianDrama,
            posterUrl = poster,
            plot = description
        ) {
            addEpisodes(DubStatus.Subbed, listOf(
                Episode(
                    data = url,
                    name = "Episode 1",
                    season = 1,
                    episode = 1
                )
            ))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val playerUrl = doc.selectFirst("iframe")?.attr("src") ?: return false

        // Contoh: ambil link HLS / MP4 dari iframe
        val m3u8Url = extractHlsUrl(playerUrl) ?: return false

        newExtractorLink(
            source = name,
            name = "Dramaid Server",
            url = m3u8Url
        ) {
            // Jika perlu referer, tambahkan di sini
            referer = mainUrl
            isM3u8 = true
        }.also { callback(it) }

        return true
    }

    private suspend fun extractHlsUrl(iframeUrl: String): String? {
        val iframeDoc = app.get(iframeUrl).document
        return iframeDoc.selectFirst("source")?.attr("src")
    }
}
