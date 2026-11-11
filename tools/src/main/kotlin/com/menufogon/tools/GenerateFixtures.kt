package com.menufogon.tools

import com.menufogon.core.parser.MenuParser
import com.menufogon.core.text.Glyph
import com.menufogon.core.text.TextItem
import com.menufogon.core.text.WordBuilder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import java.io.StringWriter
import java.time.YearMonth

/** Extracts word positions with desktop PdfBox, the same way the Android app does with PdfBox-Android. */
private class PositionStripper : PDFTextStripper() {
    val items = mutableListOf<TextItem>()

    init {
        sortByPosition = true
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val page = currentPageNo - 1
        items += WordBuilder.words(
            textPositions.map { Glyph(it.unicode, it.xDirAdj, it.yDirAdj, it.widthDirAdj, it.yScale, page) },
        )
    }
}

fun extract(pdf: File): List<TextItem> = PDDocument.load(pdf).use { document ->
    val stripper = PositionStripper()
    stripper.writeText(document, StringWriter())
    stripper.items
}

fun main(args: Array<String>) {
    val samples = File(args.getOrElse(0) { "samples" })
    val output = File(args.getOrElse(1) { "core/src/test/resources/fixtures" })
    output.mkdirs()
    val json = Json { prettyPrint = false }
    val pdfs = samples.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }.orEmpty().sortedBy { it.name }
    require(pdfs.isNotEmpty()) { "No PDF files in ${samples.absolutePath}" }
    for (pdf in pdfs) {
        val items = extract(pdf)
        val target = File(output, pdf.nameWithoutExtension + ".json")
        // One item per line keeps the committed file diff-friendly.
        target.writeText(
            items.joinToString(",\n", "[\n", "\n]\n") { json.encodeToString(TextItem.serializer(), it) },
            Charsets.UTF_8,
        )
        // Sanity check so a broken fixture is noticed here and not only in the tests.
        val check = json.decodeFromString(ListSerializer(TextItem.serializer()), target.readText(Charsets.UTF_8))
        val parsed = MenuParser().parse(check, YearMonth.of(2026, 11))
        if (System.getenv("MENUFOGON_PRINT") != null) {
            for (d in parsed.days) {
                println("  ${d.date} ${d.issues} | ${d.first?.name} [${d.first?.allergens}] | ${d.second?.name} [${d.second?.allergens}]")
            }
        }
        println("${pdf.name}: ${items.size} words -> ${target.path} (${parsed.days.size} days read as November 2026)")
    }
}
