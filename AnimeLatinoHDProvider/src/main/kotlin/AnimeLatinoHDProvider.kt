package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

@CloudstreamPlugin
class AnimeLatinoHDProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeLatinoHDProvider())
    }
}

class AnimeLatinoHDProvider : MainAPI() {

    override var mainUrl = "https://www.animelatinohd.com"
    override var name = "AnimeLatinoHD"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = document.select("article").mapNotNull {
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(HomePageList("Animes", home))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull {
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: return null
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst("p")?.text()
        val episodes = document.select("li").mapIndexedNotNull { index, episode ->
            val epUrl = episode.selectFirst("a")?.attr("href") ?: return@mapIndexedNotNull null
            newEpisode(epUrl) { name = "Episodio ${index + 1}" }
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframe = document.selectFirst("iframe")?.attr("src") ?: return false
        loadExtractor(iframe, subtitleCallback, callback)
        return true
    }
}