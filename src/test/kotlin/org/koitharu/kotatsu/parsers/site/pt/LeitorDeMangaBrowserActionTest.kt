package org.koitharu.kotatsu.parsers.site.pt

import kotlinx.coroutines.test.runTest
import okhttp3.Response
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.site.madara.pt.LeitorDeManga

internal class LeitorDeMangaBrowserActionTest {

	@Test
	fun readerInterstitialRequestsManualBrowserAction() = runTest {
		val parser = LeitorDeManga(StaticHtmlContext(READER_INTERSTITIAL))

		val error = try {
			parser.getPages(chapter)
			throw AssertionError("Expected the reader interstitial to request a manual browser action")
		} catch (e: BrowserActionRequested) {
			e
		}

		assertEquals("https://leitordemangas.com/manga/one-piece/1193", error.url)
	}

	@Test
	fun cloudflareChallengeRequestsManualBrowserAction() = runTest {
		val parser = LeitorDeManga(StaticHtmlContext(CLOUDFLARE_CHALLENGE))

		val error = try {
			parser.getPages(chapter)
			throw AssertionError("Expected the challenge to request a manual browser action")
		} catch (e: BrowserActionRequested) {
			e
		}

		assertEquals("https://leitordemangas.com/manga/one-piece/1193", error.url)
	}

	@Test
	fun preloadedReaderImagesAreParsedWithoutBrowserAction() = runTest {
		val parser = LeitorDeManga(StaticHtmlContext(READER_PAGES))

		val pages = parser.getPages(chapter)

		assertEquals(listOf("https://cdn.example/one-piece/1193/1.jpg"), pages.map { it.url })
	}

	private class BrowserActionRequested(val url: String) : RuntimeException()

	private class StaticHtmlContext(private val html: String) : MangaLoaderContext() {

		private val delegate = MangaLoaderContextMock

		override val httpClient get() = delegate.httpClient
		override val cookieJar get() = delegate.cookieJar

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = evaluateJs("", script, 10_000L)

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String =
			JSONObject.quote(html)

		override fun getConfig(source: MangaSource): MangaSourceConfig = delegate.getConfig(source)
		override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()

		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
			delegate.redrawImageResponse(response, redraw)

		override fun createBitmap(width: Int, height: Int): Bitmap = delegate.createBitmap(width, height)

		override fun requestBrowserAction(parser: MangaParser, url: String): Nothing =
			throw BrowserActionRequested(url)
	}

	private companion object {
		val chapter = MangaChapter(
			id = 1193L,
			title = null,
			number = 1193f,
			volume = 0,
			url = "/manga/one-piece/1193",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = MangaParserSource.LEITORDEMANGA,
		)

		const val READER_INTERSTITIAL = """
			<html><body>
				<h1>Um passo para ler.</h1>
				<p>Leia sem anúncios, assine premium no MUGIVERSO.com.</p>
				<a href="https://redenovax.com/jump/one-piece">Acesse aqui para continuar</a>
			</body></html>
		"""

		const val CLOUDFLARE_CHALLENGE = """
			<html><body>
				<form id="challenge-form"><div class="cf-turnstile"></div></form>
			</body></html>
		"""

		const val READER_PAGES = """
			<html><body>
				<div class="reading-content"></div>
				<script id="chapter_preloaded_images">var chapter_preloaded_images = ["https://cdn.example/one-piece/1193/1.jpg"];</script>
			</body></html>
		"""
	}
}
