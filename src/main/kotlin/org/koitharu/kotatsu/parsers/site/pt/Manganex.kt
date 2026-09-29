package org.koitharu.kotatsu.parsers.site.pt

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.util.EnumSet

internal abstract class MangaVerseParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val localePath: String,
	private val language: String,
	domainName: String,
) : PagedMangaParser(context, source, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain(domainName)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.ALPHABETICAL)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/$localePath/")
		.set("Origin", "https://$domain")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
		if (page == 1) {
			val url = buildListUrl(query).toHttpUrl()
			return parseMangaList(webClient.httpGet(url, getRequestHeaders()).parseHtml())
		}

		val config = getAjaxConfig()
		val isSearch = query != null
		val response = loadMore(
			config = config,
			page = page,
			type = if (isSearch) "search" else "series_grid",
			referer = buildListUrl(query),
			searchQuery = query,
		) ?: return emptyList()

		val html = response.optString("html")
		return parseMangaList(Jsoup.parse(html, buildListUrl(query)))
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(mangaUrl, getRequestHeaders()).parseHtml()
		val title = document.selectFirst(".series-title")?.text()?.takeIf { it.isNotBlank() } ?: manga.title
		val description = document.selectFirst(".series-description")?.html()
			?.takeIf { it.isNotBlank() }
			?: manga.description
		val coverUrl = document.selectFirst(".series-header-thumbnail img")
			?.let { it.attr("src").ifBlank { it.attr("data-src") } }
			?.takeIf { it.isNotBlank() }
			?.toAbsoluteUrl(domain)
			?: manga.coverUrl
		val altTitles = document.selectFirst(".series-description > h2")
			?.text()
			?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
			?.let(::setOf)
			?: manga.altTitles
		val tags = document.select(".series-meta a")
			.mapNotNull { element ->
				val tagTitle = element.text().trim()
				val tagUrl = element.attrAsRelativeUrl("href")
				if (tagTitle.isEmpty() || tagUrl.isEmpty()) {
					null
				} else {
					MangaTag(key = tagUrl, title = tagTitle, source = source)
				}
			}
			.toSet()

		return manga.copy(
			title = title,
			altTitles = altTitles,
			coverUrl = coverUrl,
			description = description,
			tags = tags.ifEmpty { manga.tags },
			state = parseState(document) ?: manga.state,
			chapters = getChapters(document, mangaUrl),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()
		val images = document.select("img[data-src]")
			.ifEmpty { document.select(".wp-block-image img") }

		return images.mapNotNull { element ->
			val imageUrl = element.attr("data-src")
				.ifBlank { element.attr("src") }
				.takeIf { it.isNotBlank() && !it.startsWith("data:") }
				?.toAbsoluteUrl(domain)
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}.distinctBy { it.url }
	}

	private suspend fun getChapters(document: Document, referer: String): List<MangaChapter> {
		val chapters = ArrayList<MangaChapter>()
		chapters += parseChapters(document)

		val loadMoreButton = document.selectFirst("#load-more-series") ?: return chapters
		val categoryId = loadMoreButton.attr("data-category").takeIf { it.isNotBlank() } ?: return chapters
		val order = document.selectFirst(".series-chapter-order .sort-trigger.active")
			?.attr("data-sort")
			?.takeIf { it.isNotBlank() }
			?: "desc"
		val config = parseAjaxConfig(document) ?: getAjaxConfig()

		var page = 2
		var hasMore = true
		while (hasMore && page <= MAX_CHAPTER_PAGES) {
			val response = loadMore(
				config = config,
				page = page,
				type = "series",
				referer = referer,
				categoryId = categoryId,
				order = order,
			) ?: break

			val html = response.optString("html")
			if (html.isBlank()) break
			val parsed = parseChapters(Jsoup.parse(html, referer))
			if (parsed.isEmpty()) break
			chapters += parsed
			hasMore = response.optBoolean("has_more", false)
			page++
		}

		return chapters
			.distinctBy { Triple(it.branch, it.volume, it.number) }
			.distinctBy { it.url }
			.reversed()
	}

	private fun parseChapters(document: Document): List<MangaChapter> {
		return document.select(".chapter-item .chapter-link").mapIndexedNotNull { index, element ->
			val url = element.attrAsRelativeUrl("href")
			if (url.isEmpty()) return@mapIndexedNotNull null

			val title = element.selectFirst(".chapter-title")?.text()?.takeIf { it.isNotBlank() }
				?: element.text().trim().ifBlank { "Capítulo " + (index + 1) }
			val number = CHAPTER_NUMBER_REGEX.find(title)
				?.groupValues
				?.get(1)
				?.replace(',', '.')
				?.toFloatOrNull()
				?: (index + 1).toFloat()

			MangaChapter(
				id = generateUid(url),
				title = title,
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}
	}

	private fun parseMangaList(document: Document): List<Manga> {
		return document.select("a.series-card-link").mapNotNull(::parseMangaCard)
	}

	private fun parseMangaCard(link: Element): Manga? {
		val url = link.attrAsRelativeUrl("href")
		val title = link.selectFirst(".series-card-title")?.text()?.takeIf { it.isNotBlank() }
			?: link.attr("title").takeIf { it.isNotBlank() }
			?: return null
		if (url.isEmpty()) return null

		val coverElement = link.selectFirst(".series-card-thumb")
		val coverUrl = coverElement?.attr("style")
			?.let(COVER_URL_REGEX::find)
			?.groupValues
			?.getOrNull(1)
			?.takeIf { it.isNotBlank() }
			?.toAbsoluteUrl(domain)
			?: link.selectFirst("img")
				?.let { it.attr("data-src").ifBlank { it.attr("src") } }
				?.takeIf { it.isNotBlank() }
				?.toAbsoluteUrl(domain)

		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = coverUrl,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = source,
		)
	}

	private fun parseState(document: Document): MangaState? {
		val text = document.selectFirst(".series-meta")?.text()?.lowercase().orEmpty()
		return when {
			"ongoing" in text || "em andamento" in text -> MangaState.ONGOING
			"completed" in text || "concluído" in text || "finalizado" in text -> MangaState.FINISHED
			"hiatus" in text || "pausado" in text -> MangaState.PAUSED
			else -> null
		}
	}

	private suspend fun getAjaxConfig(): AjaxConfig {
		val document = webClient.httpGet(buildListUrl(null).toHttpUrl(), getRequestHeaders()).parseHtml()
		return parseAjaxConfig(document)
			?: throw IllegalStateException("Manganex AJAX configuration was not found")
	}

	private fun parseAjaxConfig(document: Document): AjaxConfig? {
		val html = document.html()
		val assignment = AJAX_CONFIG_REGEX.find(html) ?: return null
		val json = extractJsonObject(html, assignment.range.last + 1) ?: return null
		val config = JSONObject(json)
		val ajaxUrl = config.getStringOrNull("ajax_url") ?: return null
		val nonce = config.getStringOrNull("nonce") ?: return null
		val language = config.getStringOrNull("current_lang") ?: "pt"

		return AjaxConfig(
			url = if (ajaxUrl.startsWith("http")) ajaxUrl else ajaxUrl.toAbsoluteUrl(domain),
			nonce = nonce,
			language = language,
		)
	}

	private suspend fun loadMore(
		config: AjaxConfig,
		page: Int,
		type: String,
		referer: String,
		searchQuery: String? = null,
		categoryId: String? = null,
		order: String? = null,
	): JSONObject? {
		val body = mutableMapOf(
			"action" to "mangaverse_load_more",
			"nonce" to config.nonce,
			"page" to page.toString(),
			"type" to type,
			"lang" to config.language,
		)
		searchQuery?.let { body["search_query"] = it }
		categoryId?.let { body["category_id"] = it }
		order?.let { body["order"] = it }

		val response = webClient.httpPost(
			config.url.toHttpUrl(),
			body,
			getAjaxHeaders(referer),
		).parseJson()
		if (!response.optBoolean("success", false)) return null
		return response.optJSONObject("data")
	}

	private fun getAjaxHeaders(referer: String): Headers = getRequestHeaders().newBuilder()
		.set("Accept", "application/json, text/javascript, */*; q=0.01")
		.set("Origin", "https://$domain")
		.set("Referer", referer)
		.set("X-Requested-With", "XMLHttpRequest")
		.build()

	private fun buildListUrl(query: String?): String {
		return "https://$domain/$localePath/".toHttpUrl().newBuilder()
			.addQueryParameter("lang", language)
			.apply { query?.let { addQueryParameter("s", it) } }
			.build()
			.toString()
	}

	private fun extractJsonObject(source: String, fromIndex: Int): String? {
		val startIndex = source.indexOf('{', fromIndex)
		if (startIndex < 0) return null

		var depth = 0
		var inString = false
		var escaped = false
		for (index in startIndex..source.lastIndex) {
			val char = source[index]
			if (inString) {
				when {
					escaped -> escaped = false
					char == '\\' -> escaped = true
					char == '"' -> inString = false
				}
			} else {
				when (char) {
					'"' -> inString = true
					'{' -> depth++
					'}' -> {
						depth--
						if (depth == 0) return source.substring(startIndex, index + 1)
					}
				}
			}
		}
		return null
	}

	private data class AjaxConfig(
		val url: String,
		val nonce: String,
		val language: String,
	)

	private companion object {
		private const val PAGE_SIZE = 20
		private const val MAX_CHAPTER_PAGES = 100

		private val AJAX_CONFIG_REGEX = Regex("""mangaverse_ajax\s*=""", RegexOption.IGNORE_CASE)
		private val COVER_URL_REGEX = Regex("""url\(['"]?([^)'"]+)['"]?\)""", RegexOption.IGNORE_CASE)
		private val CHAPTER_NUMBER_REGEX = Regex("""(?i)cap[ií]tulo\s+(\d+(?:[.,]\d+)?)""")
	}
}

@MangaSourceParser("MANGANEX_PT", "Manganex PT", "pt")
internal class Manganex(context: MangaLoaderContext) : MangaVerseParser(
	context = context,
	source = MangaParserSource.MANGANEX_PT,
	localePath = "pt",
	language = "pt",
	domainName = "www.manganex.com",
)
