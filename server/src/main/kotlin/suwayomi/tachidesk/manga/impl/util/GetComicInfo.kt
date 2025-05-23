package suwayomi.tachidesk.manga.impl.util

import eu.kanade.tachiyomi.source.local.metadata.COMIC_INFO_FILE
import eu.kanade.tachiyomi.source.local.metadata.ComicInfo
import eu.kanade.tachiyomi.source.local.metadata.ComicInfoPublishingStatus
import nl.adaptivity.xmlutil.serialization.XML
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.anilist.EnhancedComicInfoGenerator
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.outputStream

fun getComicInfo(
    manga: ResultRow,
    chapter: ResultRow,
    chapterUrl: String,
    categories: List<String>?,
): ComicInfo {
    val dateUpload = chapter[ChapterTable.date_upload]
    val localDate =
        Instant.ofEpochMilli(dateUpload).atZone(ZoneId.systemDefault()).toLocalDate()

    return ComicInfo(
        title = ComicInfo.Title(chapter[ChapterTable.name]),
        series = ComicInfo.Series(manga[MangaTable.title]),
        number =
            chapter[ChapterTable.chapter_number].takeIf { it >= 0 }?.let {
                if ((it.rem(1) == 0.0f)) {
                    ComicInfo.Number(it.toInt().toString())
                } else {
                    ComicInfo.Number(it.toString())
                }
            },
        web = ComicInfo.Web(chapterUrl),
        summary = manga[MangaTable.description]?.let { ComicInfo.Summary(it) },
        writer = manga[MangaTable.author]?.let { ComicInfo.Writer(it) },
        penciller = manga[MangaTable.artist]?.let { ComicInfo.Penciller(it) },
        translator = chapter[ChapterTable.scanlator]?.let { ComicInfo.Translator(it) },
        genre = manga[MangaTable.genre]?.let { ComicInfo.Genre(it) },
        publishingStatus =
            ComicInfo.PublishingStatusTachiyomi(
                ComicInfoPublishingStatus.toComicInfoValue(manga[MangaTable.status].toLong()),
            ),
        categories = categories?.let { ComicInfo.CategoriesTachiyomi(it.joinToString()) },
        inker = null,
        colorist = null,
        letterer = null,
        coverArtist = null,
        tags = null,
        day = localDate?.dayOfMonth?.let { ComicInfo.Day(it) },
        month = localDate?.monthValue?.let { ComicInfo.Month(it) },
        year = localDate?.year?.let { ComicInfo.Year(it) },
    )
}

/**
 * Creates a ComicInfo.xml file inside the given directory.
 */
fun createComicInfoFile(
    dir: Path,
    manga: ResultRow,
    chapter: ResultRow,
) {
    // Check if AniList integration is enabled
    if (serverConfig.useAnilist.value) {
        // Use the enhanced ComicInfo generator with AniList integration
        val enhancedGenerator = EnhancedComicInfoGenerator()
        enhancedGenerator.createEnhancedComicInfoFile(dir, manga, chapter)
        return
    }
    
    // Original implementation for standard ComicInfo.xml generation
    val chapterUrl = chapter[ChapterTable.realUrl].orEmpty()
    val categories =
        transaction {
            CategoryMangaTable
                .innerJoin(CategoryTable)
                .selectAll()
                .where {
                    CategoryMangaTable.manga eq manga[MangaTable.id]
                }.orderBy(CategoryTable.order to SortOrder.ASC)
                .map {
                    it[CategoryTable.name]
                }
        }.takeUnless { it.isEmpty() }
    val comicInfo = getComicInfo(manga, chapter, chapterUrl, categories)
    // Remove the old file
    (dir / COMIC_INFO_FILE).deleteIfExists()
    (dir / COMIC_INFO_FILE).outputStream().use {
        val comicInfoString = Injekt.get<XML>().encodeToString(ComicInfo.serializer(), comicInfo)
        it.write(comicInfoString.toByteArray())
    }
}