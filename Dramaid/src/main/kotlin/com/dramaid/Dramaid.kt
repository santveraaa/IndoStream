package com.dramaid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addEpisodes
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

open class Dramaid : MainAPI() {
    override var mainUrl = "https://www.dramaid.site"
    override var name = "Dramaid"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = doc.select("div.item").mapNotNull {
            val title = it.selectFirst("h2")?.text() ?: return@mapNotNull null
            val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = it.selectFirst("img")?.attr("src")
            HomePageListItem(
                name = title,
                url = link,
                posterUrl = poster
            )
        }
        return newHomePageResponse(
            listOf(HomePageList("Terbaru", items))
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val description = doc.selectFirst(".desc")?.text()
        val episodes = doc.select("ul.episode-list li a").map {
            newEpisode(
                name = it.text(),
                data = fixUrl(it.attr("href"))
            )
        }

        return TvSeriesLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.AsianDrama,
            posterUrl = poster,
            year = null,
            rating = null,
            plot = description
        ).apply {
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return false
        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                iframe,
                referer = mainUrl,
                quality = Qualities.Unknown
            )
        )
        return true
    }
}
