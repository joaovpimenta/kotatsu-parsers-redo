package org.koitharu.kotatsu.parsers.site.madara.pt

import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.Locale

internal abstract class MangaCatalogParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
) : MadaraParser(context, source, domain, 10) {
    override val datePattern = "d 'de' MMMM 'de' yyyy"
    override val listUrl = "manga/"
    override val stylePage = "?style=paged"

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val pageNumber = page + 1
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> append("/?s=${filter.query.urlEncoded()}&post_type=wp-manga")
                filter.tags.isNotEmpty() -> append("/manga-genre/${filter.tags.oneOrThrowIfMany()?.key}/")
                else -> {
                    append("/")
                    append(listUrl)
                    if (pageNumber > 1) append("page/$pageNumber/")
                }
            }
        }
        return parseMangaList(captureDocument(url))
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        val items = doc.select("#loop-content > .page-listing-item")
        if (items.isEmpty()) return super.parseMangaList(doc)
        return items.mapNotNull { item ->
            val a = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            val title = item.selectFirst(".post-title, .manga-name, h3, h4")?.text()?.trim()
                ?.takeIf(String::isNotEmpty) ?: a.text().trim()
            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(item.host ?: domain),
                title = title,
                altTitles = emptySet(),
                authors = emptySet(),
                coverUrl = item.selectFirst("img")?.src(),
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = captureDocument(fullUrl)
        val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")
            ?.toRelativeUrl(domain) ?: manga.url
        val chapters = if (doc.select(selectTestAsync).isEmpty()) {
            loadChapters(href, doc)
        } else {
            getChapters(manga, doc)
        }
        val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content")
        val state = stateDiv?.let {
            when (it.text().lowercase()) {
                in ongoing -> MangaState.ONGOING
                in finished -> MangaState.FINISHED
                in abandoned -> MangaState.ABANDONED
                in paused -> MangaState.PAUSED
                else -> null
            }
        }
        val alt = doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()
        return manga.copy(
            title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            tags = doc.body().select(selectGenre).mapNotNull { createMangaTag(it) }.toSet(),
            description = doc.select(selectDesc).html(),
            altTitles = setOfNotNull(alt),
            state = state,
            chapters = chapters,
            contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
                ContentRating.ADULT
            } else {
                ContentRating.SAFE
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = captureDocument(fullUrl)
        val script = doc.selectFirst("#chapter_preloaded_images")?.data()
        if (script != null) {
            val rawArray = script.substringAfter("var chapter_preloaded_images = [")
                .substringBefore("]")
            val images = JSONArray("[$rawArray]")
            if (images.length() == 0) throw ParseException("No pages found", fullUrl)
            return (0 until images.length()).map { index ->
                val imageUrl = images.getString(index).toRelativeUrl(domain)
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source,
                )
            }
        }
        return super.getPages(chapter)
    }

    private suspend fun captureDocument(url: String): Document {
        val script = """
            (() => {
                const pageText = (document.body?.innerText || "").toLocaleLowerCase();
                const hasLeitorReaderGate = pageText.includes("um passo para ler") &&
                    Array.from(document.querySelectorAll("a[href]")).some(link =>
                        (link.innerText || "").trim().toLocaleLowerCase().includes("acesse aqui para continuar")
                    );
                const hasVerificationChallenge = document.querySelector('$MANUAL_ACTION_SELECTORS') !== null ||
                    ["checking your browser", "verify you are human", "human verification", "security verification",
                     "verifique se você é humano", "verifique que você é humano"]
                        .some(message => pageText.includes(message));
                if (hasLeitorReaderGate || hasVerificationChallenge) {
                    window.stop();
                    return document.documentElement.outerHTML;
                }

                const hasReadingContent = document.querySelector('div.reading-content') !== null ||
                    document.querySelector('div.page-break') !== null ||
                    document.querySelector('img[data-src]') !== null ||
                    document.querySelector('#chapter_preloaded_images') !== null;
                const hasMangaList = document.querySelector('#loop-content > .page-listing-item') !== null ||
                    document.querySelector('div.page-item-detail') !== null ||
                    document.querySelector('.wp-manga-item') !== null;
                const hasMangaDetails = document.querySelector('div.summary_content') !== null ||
                    document.querySelector('.manga-chapters') !== null ||
                    document.querySelector('.post-title') !== null;
                if (hasReadingContent || hasMangaList || hasMangaDetails) {
                    window.stop();
                    document.querySelectorAll('script:not(#chapter_preloaded_images), iframe, object, embed, style')
                        .forEach(el => el.remove());
                    return document.documentElement.outerHTML;
                }
                return null;
            })();
        """.trimIndent()
        val rawHtml = context.evaluateJs(url, script, 30000L)
            ?: throw ParseException("Failed to load page", url)
        val html = if (rawHtml.startsWith("\"") && rawHtml.endsWith("\"")) {
            rawHtml.substring(1, rawHtml.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
                    match.groupValues[1].toInt(16).toChar().toString()
                }
        } else rawHtml
        val document = Jsoup.parse(html, url)
        if (requiresManualBrowserAction(document)) {
            context.requestBrowserAction(this, url)
        }
        return document
    }

    private fun requiresManualBrowserAction(document: Document): Boolean {
        val text = document.body()?.text().orEmpty()
        val normalizedText = text.lowercase(Locale.ROOT)
        val hasLeitorReaderGate = "um passo para ler" in normalizedText &&
            document.select("a[href]").any {
                it.text().contains("acesse aqui para continuar", ignoreCase = true)
            }
        val hasVerificationChallenge = document.selectFirst(MANUAL_ACTION_SELECTORS) != null ||
            listOf(
                "checking your browser",
                "verify you are human",
                "human verification",
                "security verification",
                "verifique se você é humano",
                "verifique que você é humano",
            ).any(normalizedText::contains)
        return hasLeitorReaderGate || hasVerificationChallenge
    }

    private companion object {
        const val MANUAL_ACTION_SELECTORS = "#challenge-form, #challenge-running, #cf-challenge-running, " +
            ".cf-browser-verification, .cf-turnstile, [name=\"cf-turnstile-response\"], " +
            "iframe[src*=\"challenges.cloudflare.com\"], .g-recaptcha, iframe[src*=\"recaptcha\"], " +
            ".h-captcha, iframe[src*=\"hcaptcha.com\"]"
    }
}

@MangaSourceParser("LEITORDEMANGA", "LeitorDeManga", "pt")
internal class LeitorDeManga(context: MangaLoaderContext) :
    MangaCatalogParser(context, MangaParserSource.LEITORDEMANGA, "leitordemangas.com")

@MangaSourceParser("MANGASBRASUKAS", "Mangas Brasukas", "pt")
internal class MangasBrasukas(context: MangaLoaderContext) :
    MangaCatalogParser(context, MangaParserSource.MANGASBRASUKAS, "mangasbrasuka.org")
