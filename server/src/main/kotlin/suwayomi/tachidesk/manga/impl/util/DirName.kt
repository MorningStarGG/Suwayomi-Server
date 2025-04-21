package suwayomi.tachidesk.manga.impl.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.io.File
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.util.SafePath

private val applicationDirs: ApplicationDirs by injectLazy()

private fun getMangaDir(mangaId: Int): String {
    val mangaEntry = getMangaEntry(mangaId)
    
    // Use the format from config
    val format = serverConfig.mangaFolderFormat.value
    val variables = FormatHelper.createMangaVariables(mangaEntry)
    return FormatHelper.formatString(format, variables)
}

private fun getChapterDir(
    mangaId: Int,
    chapterId: Int,
): String {
    val chapterEntry = transaction { ChapterTable.selectAll().where { ChapterTable.id eq chapterId }.first() }
    val mangaEntry = getMangaEntry(mangaId)
    
    // Use the format from config
    val format = serverConfig.chapterFolderFormat.value
    val variables = FormatHelper.createChapterVariables(chapterEntry, mangaEntry)
    val chapterDir = FormatHelper.formatString(format, variables)

    return getMangaDir(mangaId) + "/$chapterDir"
}

fun getThumbnailDownloadPath(mangaId: Int): String = applicationDirs.thumbnailDownloadsRoot + "/$mangaId"

fun getMangaDownloadDir(mangaId: Int): String = applicationDirs.mangaDownloadsRoot + "/" + getMangaDir(mangaId)

fun getChapterDownloadPath(
    mangaId: Int,
    chapterId: Int,
): String = applicationDirs.mangaDownloadsRoot + "/" + getChapterDir(mangaId, chapterId)

fun getChapterCbzPath(
    mangaId: Int,
    chapterId: Int,
): String = getChapterDownloadPath(mangaId, chapterId) + ".cbz"

fun getChapterCachePath(
    mangaId: Int,
    chapterId: Int,
): String = applicationDirs.tempMangaCacheRoot + "/" + getChapterDir(mangaId, chapterId)

/** return value says if rename/move was successful */
fun updateMangaDownloadDir(
    mangaId: Int,
    newTitle: String,
): Boolean {
    val mangaEntry = getMangaEntry(mangaId)

    // Create variables for old path
    val oldVariables = FormatHelper.createMangaVariables(mangaEntry)
    
    // Create variables for new path with updated title
    val newVariables = oldVariables.toMutableMap()
    newVariables["title"] = newTitle
    
    // Format paths using the configured format
    val format = serverConfig.mangaFolderFormat.value
    val oldMangaDir = FormatHelper.formatString(format, oldVariables)
    val newMangaDir = FormatHelper.formatString(format, newVariables)

    val oldDir = "${applicationDirs.downloadsRoot}/$oldMangaDir"
    val newDir = "${applicationDirs.downloadsRoot}/$newMangaDir"

    val oldDirFile = File(oldDir)
    val newDirFile = File(newDir)

    return if (oldDirFile.exists()) {
        oldDirFile.renameTo(newDirFile)
    } else {
        true
    }
}

private fun getMangaEntry(mangaId: Int): ResultRow = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first() }