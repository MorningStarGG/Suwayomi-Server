import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.json.JSONArray

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
        val apiUrl = "https://api.github.com/repos/MorningStarGG/Suwayomi-WebUI-preview/releases"
        val client = HttpClient.newBuilder().build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept", "application/json")
            .GET()
            .build()
        
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val jsonArray = JSONArray(response.body())
        
        if (jsonArray.length() > 0) {
            val latestRelease = jsonArray.getJSONObject(0)
            latestRelease.getString("tag_name")
        } else {
            "r2488" // Fallback to a known version if API call fails
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
