package suwayomi.tachidesk.manga.impl

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.ArchiveProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.FolderProvider
import suwayomi.tachidesk.manga.impl.download.model.DownloadChapter
import suwayomi.tachidesk.manga.impl.util.FormatHelper
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig


object ChapterDownloadHelper {
    fun getImage(
        mangaId: Int,
        chapterId: Int,
        index: Int,
    ): Pair<InputStream, String> = provider(mangaId, chapterId).getImage().execute(index)

    fun delete(
        mangaId: Int,
        chapterId: Int,
    ): Boolean = provider(mangaId, chapterId).delete()

    suspend fun download(
        mangaId: Int,
        chapterId: Int,
        download: DownloadChapter,
        scope: CoroutineScope,
        step: suspend (DownloadChapter?, Boolean) -> Unit,
    ): Boolean = provider(mangaId, chapterId).download().execute(download, scope, step)

    // return the appropriate provider based on how the download was saved. For the logic is simple but will evolve when new types of downloads are available
    private fun provider(
        mangaId: Int,
        chapterId: Int,
    ): ChaptersFilesProvider<*> {
        val chapterFolder = File(getChapterDownloadPath(mangaId, chapterId))
        val cbzFile = File(getChapterCbzPath(mangaId, chapterId))
        if (cbzFile.exists()) return ArchiveProvider(mangaId, chapterId)
        if (!chapterFolder.exists() && serverConfig.downloadAsCbz.value) return ArchiveProvider(mangaId, chapterId)
        return FolderProvider(mangaId, chapterId)
    }

    fun getArchiveStreamWithSize(
        mangaId: Int,
        chapterId: Int,
    ): Pair<InputStream, Long> = provider(mangaId, chapterId).getAsArchiveStream()

    fun getCbzForDownload(chapterId: Int): Triple<InputStream, String, Long> {
        val (chapterData, mangaEntry, chapterEntry) =
            transaction {
                val row =
                    (ChapterTable innerJoin MangaTable)
                        .select(ChapterTable.columns + MangaTable.columns)
                        .where { ChapterTable.id eq chapterId }
                        .firstOrNull() ?: throw IllegalArgumentException("ChapterId $chapterId not found")
                val chapter = ChapterTable.toDataClass(row)
                Triple(chapter, row, row)
            }
    
        // Use the format from config
        val format = serverConfig.cbzFileFormat.value
        val variables = FormatHelper.createChapterVariables(chapterEntry, mangaEntry)
        val fileName = FormatHelper.formatString(format, variables) + ".cbz"
    
        val cbzFile = provider(chapterData.mangaId, chapterData.id).getAsArchiveStream()
    
        return Triple(cbzFile.first, fileName, cbzFile.second)
    }    
}