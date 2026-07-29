package com.olamovies.cloudstream

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File

object Fixtures {
    private val fixtureDir = File("test-fixtures")

    fun load(name: String): String {
        val file = File(fixtureDir, name)
        require(file.exists()) { "Fixture not found: $name" }
        return file.readText()
    }

    fun loadDocument(name: String): Document {
        return Jsoup.parse(load(name))
    }
}