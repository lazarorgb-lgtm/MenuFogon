package com.menufogon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.menufogon.app.pdf.PdfTextExtractor
import com.menufogon.core.parser.MenuParser
import com.menufogon.core.text.TextItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.YearMonth
import kotlin.test.assertEquals

/**
 * Runs on a device or emulator (./gradlew :app:connectedDebugAndroidTest).
 * Checks that PdfBox-Android reads the sample PDFs like desktop PdfBox did for the fixtures:
 * same words in the same order, and the same parsed menu.
 */
@RunWith(AndroidJUnit4::class)
class PdfExtractionInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val november = YearMonth.of(2026, 11)

    private fun check(name: String) = runBlocking {
        val assets = instrumentation.context.assets
        val pdf = File(instrumentation.targetContext.cacheDir, "$name.pdf")
        assets.open("$name.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }
        val fixtureText = assets.open("$name.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixture = Json.decodeFromString(ListSerializer(TextItem.serializer()), fixtureText)

        val extracted = PdfTextExtractor(instrumentation.targetContext).extract(pdf)
        pdf.delete()

        assertEquals(0, extracted.ocrPages, "the samples have a text layer")
        assertEquals(fixture.map { it.text }, extracted.items.map { it.text }, "words of $name")
        // Positions may differ by rounding between the two libraries; the parsed menu must not.
        assertEquals(
            MenuParser().parse(fixture, november).days,
            MenuParser().parse(extracted.items, november).days,
            "parsed menu of $name",
        )
    }

    @Test fun normal() = check("MENU_NORMAL_NOVIEMBRE_26")
    @Test fun diabetic() = check("MENU_DIB_NOVIEMBRE_26")
    @Test fun eei() = check("MENU_EEI_NOVIEMBRE")
}
