import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

/*
 * Copyright (C) Contributors to the Suwayomi project
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

const val MainClass = "suwayomi.tachidesk.MainKt"

// should be bumped with each stable release
val getTachideskVersion = { "v1.1.${getCommitCount()}" }

// Function to get the latest WebUI release tag from GitHub
val getLatestWebUITag = {
    runCatching {
        val apiUrl = URI.create("https://api.github.com/repos/MorningStarGG/Suwayomi-WebUI-preview/releases/latest").toURL()
        val connection = apiUrl.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()
            
            // Simple string parsing to extract the tag_name
            val tagPattern = "\"tag_name\":\\s*\"([^\"]+)\"".toRegex()
            val matchResult = tagPattern.find(response)
            matchResult?.groupValues?.get(1) ?: "r2488" // Fallback if pattern not found
        } else {
            "r2488" // Fallback if API call fails
        }
    }.getOrDefault("r2488") // Fallback to a known version if anything goes wrong
}

// Use the function to get the latest tag
val webUIRevisionTag = getLatestWebUITag()

private val getCommitCount = {
    runCatching {
        ProcessBuilder()
            .command("git", "rev-list", "HEAD", "--count")
            .start()
            .let { process ->
                process.waitFor()
                val output = process.inputStream.use {
                    it.bufferedReader().use(BufferedReader::readText)
                }
                process.destroy()
                output.trim()
            }
    }.getOrDefault("0")
}

// counts commits on the current checked out branch
val getTachideskRevision = { "r${getCommitCount()}" }
