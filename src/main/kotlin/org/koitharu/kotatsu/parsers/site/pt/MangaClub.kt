package org.koitharu.kotatsu.parsers.site.pt

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource

@MangaSourceParser("MANGACLUB_PTBR", "MangaClub PT-BR", "pt")
internal class MangaClub(context: MangaLoaderContext) : MangaVerseParser(
	context = context,
	source = MangaParserSource.MANGACLUB_PTBR,
	localePath = "pt-br",
	language = "pt",
	domainName = "www.mangaclub.net",
)
