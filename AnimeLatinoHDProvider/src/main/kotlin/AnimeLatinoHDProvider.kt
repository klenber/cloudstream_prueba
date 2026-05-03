package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@CloudstreamPlugin
class AnimeLatinoHDProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeLatinoHDProvider())
    }
}

class AnimeLatinoHDProvider : MainAPI() {

    override var mainUrl = "https://animelhd.com"
    override var name = "AnimeLHD"
    override var lang = "es"
    override val hasMainPage = true
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        mainUrl to "Inicio",
        "$mainUrl/series/" to "Series",
        "$mainUrl/category/accion/" to "Accion",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/comedia/" to "Comedia"
    )

    private val cloudflareResolver by lazy {
        WebViewResolver(
            Regex("""https://(?:www\.)?(?:animelatinohd\.com|animelhd\.com)/(?!cdn-cgi/).*"""),
            userAgent = null,
            useOkhttp = false
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getDocument(request.data.toPagedUrl(page))
        val home = document.toSearchResponses()
        return newHomePageResponse(
            listOf(HomePageList(request.name, home, request.horizontalImages)),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDocument("$mainUrl/?s=${query.encodeUri()}")
        return document.toSearchResponses()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val title = document.titleFromPage() ?: return null
        val poster = document.imageFromPage()
        val description = document.descriptionFromPage()
        val episodeDocuments = if (url.contains("/series/")) {
            document.seasonUrls().mapNotNull { seasonUrl ->
                runCatching { getDocument(seasonUrl) }.getOrNull()
            }
        } else {
            emptyList()
        }
        val episodes = (listOf(document) + episodeDocuments)
            .flatMap { it.episodeElements() }
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
            .ifEmpty { listOfNotNull(url.toEpisodeFromUrl()) }

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
        val document = getDocument(data)
        val embeds = document.embedUrls()

        var found = false
        embeds.forEach { embed ->
            if (loadExtractor(embed, subtitleCallback, callback)) found = true
            if (embed.startsWith(mainUrl)) {
                val nestedEmbeds = runCatching {
                    app.get(embed, referer = data).document.embedUrls()
                }.getOrDefault(emptyList())
                nestedEmbeds.forEach { nested ->
                    if (loadExtractor(nested, subtitleCallback, callback)) found = true
                }
            }
        }
        return found
    }

    private suspend fun getDocument(url: String): Document {
        return try {
            val document = app.get(url, referer = mainUrl).document
            if (document.isCloudflareChallenge()) {
                app.get(url, referer = mainUrl, interceptor = cloudflareResolver).document
            } else {
                document
            }
        } catch (_: Exception) {
            app.get(url, referer = mainUrl, interceptor = cloudflareResolver).document
        }
    }

    private fun Document.isCloudflareChallenge(): Boolean {
        return title().contains("Just a moment", ignoreCase = true) ||
            text().contains("Enable JavaScript and cookies", ignoreCase = true)
    }

    private fun Document.toSearchResponses(): List<SearchResponse> {
        val containers = select(
            "div.TPostMv, div.TPost, article, div.item, div.result-item, div.post, tr:has(a[href*=/ver/]), li:has(a[href*=/ver/]), li:has(a[href*=/series/])"
        ).mapNotNull { it.toSearchResponse() }

        return containers.ifEmpty {
            select("a[href*=/series/], a[href*=/ver/]").mapNotNull { it.toSearchResponse() }
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href*=/series/], a[href*=/ver/]")
            ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!href.contains("/series/") && !href.contains("/ver/")) return null

        val scope = if (tagName() == "a") parent() ?: this else this
        val title = listOfNotNull(
            scope.selectFirst("h1, h2.Title, .Title, h2, h3, .title, .entry-title")?.ownText(),
            scope.selectFirst("h1, h2.Title, .Title, h2, h3, .title, .entry-title")?.text(),
            anchor.attr("title"),
            scope.selectFirst("img")?.attr("alt"),
            anchor.text()
        ).firstNotNullOfOrNull { it.cleanText().takeIf(String::isNotBlank) }
            ?.cleanAnimeTitle()
            ?: return null

        val poster = scope.selectFirst("img")?.imageUrl()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    private fun Element.toEpisode(): Episode? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href*=/ver/]")
            ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        val episodeNumber = Regex("""(?i)episodio-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: selectFirst(".Num")?.text()?.cleanText()?.toIntOrNull()
        val label = listOfNotNull(
            selectFirst(".MvTbTtl a")?.text(),
            anchor.attr("title"),
            anchor.text()
        )
            .firstNotNullOfOrNull { it.cleanText().takeIf(String::isNotBlank) }
        return newEpisode(href) {
            name = label ?: episodeNumber?.let { "Episodio $it" } ?: "Episodio"
            episode = episodeNumber
        }
    }

    private fun String.toEpisodeFromUrl(): Episode? {
        val episodeNumber = Regex("""(?i)episodio-(\d+)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        return newEpisode(this) {
            name = "Episodio $episodeNumber"
            episode = episodeNumber
        }
    }

    private fun Document.titleFromPage(): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:title]")?.attr("content"),
            selectFirst("h1, .entry-title, .title")?.text(),
            title()
        ).firstNotNullOfOrNull { it.cleanText().cleanAnimeTitle().takeIf(String::isNotBlank) }
    }

    private fun Document.descriptionFromPage(): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:description]")?.attr("content"),
            selectFirst("meta[name=description]")?.attr("content"),
            selectFirst(".Description p, .sinopsis, .description, .entry-content p, p")?.text()
        ).firstNotNullOfOrNull { it.cleanText().takeIf(String::isNotBlank) }
    }

    private fun Document.imageFromPage(): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image]")?.attr("content"),
            selectFirst(".TPostBg, .poster img, .Image img, .image img, article img, img")?.imageUrl()
        ).firstNotNullOfOrNull { it.toAbsoluteUrl()?.takeIf(String::isNotBlank) }
    }

    private fun Document.seasonUrls(): List<String> {
        return select("a[href*=/mirar/]").mapNotNull {
            it.attr("href").toAbsoluteUrl()
        }.distinct()
    }

    private fun Document.episodeElements(): List<Element> {
        return select("tr:has(a[href*=/ver/]), a[href*=/ver/]")
    }

    private fun Document.embedUrls(): List<String> {
        val iframeUrls = select("iframe[src], iframe[data-src]").mapNotNull {
            it.attr("src").ifBlank { it.attr("data-src") }.toAbsoluteUrl()
        }
        val optionUrls = select("[data-id][data-key]").mapNotNull {
            val id = it.attr("data-id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val key = it.attr("data-key").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val type = if (it.attr("data-typ").equals("movie", ignoreCase = true)) "1" else "2"
            "$mainUrl/?trembed=$key&trid=$id&trtype=$type"
        }
        return (iframeUrls + optionUrls).distinct()
    }

    private fun Element.imageUrl(): String? {
        return listOf("data-src", "data-lazy-src", "src")
            .firstNotNullOfOrNull { attr(it).takeIf(String::isNotBlank) }
            ?.toAbsoluteUrl()
    }

    private fun String.toPagedUrl(page: Int): String {
        return if (page <= 1) this else "${trimEnd('/')}/page/$page"
    }

    private fun String.cleanText(): String {
        return replace("\u00a0", " ").trim()
    }

    private fun String.cleanAnimeTitle(): String {
        return cleanText()
            .removePrefix("Image ")
            .replace(Regex("""^\d{2}-\d{2}-\d{4}\s+"""), "")
            .replace(Regex("""(?i)\s*(?:cap.tulo|episodio)\s*\d+.*$"""), "")
            .replace(Regex("""(?i)\s*sub\s*espa.ol.*$"""), "")
            .replace(Regex("""(?i)\s*online\s*.*$"""), "")
            .replace(Regex("""(?i)\s+serie$"""), "")
            .trim()
    }

    private fun String.toAbsoluteUrl(): String? {
        val url = cleanText()
        if (url.isBlank() || url == "#") return null
        return when {
            url.startsWith("//") -> "https:$url"
            else -> fixUrlNull(url)
        }
    }
}
