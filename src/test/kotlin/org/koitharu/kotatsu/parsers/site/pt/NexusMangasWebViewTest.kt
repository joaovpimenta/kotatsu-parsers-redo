package org.koitharu.kotatsu.parsers.site.pt

import kotlinx.coroutines.test.runTest
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery

internal class NexusMangasWebViewTest {

	@Test
	fun popularListReturnsMangaWhenWebViewPageIsReady() = runTest {
		val parser = NexusMangas(PromiseAwareContext())

		val manga = parser.getList(MangaSearchQuery.EMPTY)

		assertEquals("Obra de teste", manga.single().title)
	}

	private class PromiseAwareContext : MangaLoaderContext() {

		private val delegate = MangaLoaderContextMock

		override val httpClient get() = delegate.httpClient
		override val cookieJar get() = delegate.cookieJar

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = evaluateJs("", script, 10_000L)

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? {
			// Android WebView serializes an unawaited Promise result as an empty object.
			return if (script.contains("new Promise")) "{}" else JSONObject.quote(RENDERED_RANKING)
		}

		override fun getConfig(source: MangaSource): MangaSourceConfig = delegate.getConfig(source)
		override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()

		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
			delegate.redrawImageResponse(response, redraw)

		override fun createBitmap(width: Int, height: Int): Bitmap = delegate.createBitmap(width, height)

		private companion object {
			const val RENDERED_RANKING = """
				<html><body>
					<h2>OBRAS MAIS VISTAS</h2>
					<a href="/obra/obra-de-teste"><h3>Obra de teste</h3></a>
				</body></html>
			"""
		}
	}
}
