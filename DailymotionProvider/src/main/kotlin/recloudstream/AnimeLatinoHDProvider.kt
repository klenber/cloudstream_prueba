package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class AnimeLatinoHDProvider : MainAPI() {

    override var mainUrl = "https://www.animelatinohd.com"
    override var name = "AnimeLatinoHD"
    override var lang = "es"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(mainUrl).document

        val home = document.select("article").mapNotNull {

            val title = it.selectFirst("h3")?.text()
                ?: return@mapNotNull null

            val href = it.selectFirst("a")?.attr("href")
                ?: return@mapNotNull null

            val poster = it.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(
                title,
                href,
                TvType.Anime
            ) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Animes",
                    home
                )
            )
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val document =
            app.get("$mainUrl/?s=$query").document

        return document.select("article").mapNotNull {

            val title = it.selectFirst("h3")?.text()
                ?: return@mapNotNull null

            val href = it.selectFirst("a")?.attr("href")
                ?: return@mapNotNull null

            val poster = it.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(
                title,
                href,
                TvType.Anime
            ) {
                this.posterUrl = poster
            }

        }.toSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title =
            document.selectFirst("h1")?.text()
                ?: return null

        val poster =
            document.selectFirst("img")?.attr("src")

        val description =
            document.selectFirst("p")?.text()

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {

            posterUrl = poster
            plot = description

        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val iframe =
            document.selectFirst("iframe")
                ?.attr("src")
                ?: return false

        loadExtractor(
            iframe,
            subtitleCallback,
            callback
        )

        return true
    }
}