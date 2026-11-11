package com.menufogon.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.menufogon.core.text.Glyph
import com.menufogon.core.text.TextItem
import com.menufogon.core.text.WordBuilder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringWriter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ExtractedPdf(
    val items: List<TextItem>,
    val pageCount: Int,
    /** Pages that had no text layer and were read with text recognition (less reliable). */
    val ocrPages: Int,
)

class PdfReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads the text of a PDF with its positions. Uses the text layer through PdfBox-Android;
 * pages without a text layer are rendered and read with ML Kit (on-device, no network).
 */
class PdfTextExtractor(private val context: Context) {

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

    fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "menu.pdf"
    }

    suspend fun extract(uri: Uri): ExtractedPdf = withContext(Dispatchers.IO) {
        val copy = File.createTempFile("import", ".pdf", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw PdfReadException("cannot open file")
            input.use { stream -> copy.outputStream().use { stream.copyTo(it) } }
            extract(copy)
        } catch (e: PdfReadException) {
            throw e
        } catch (e: Exception) {
            throw PdfReadException("cannot read PDF", e)
        } finally {
            copy.delete()
        }
    }

    suspend fun extract(file: File): ExtractedPdf = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val (textItems, pageCount) = PDDocument.load(file).use { document ->
            val stripper = PositionStripper()
            stripper.writeText(document, StringWriter())
            stripper.items.toList() to document.numberOfPages
        }
        val pagesWithText = textItems.map { it.page }.toSet()
        val missing = (0 until pageCount).filter { it !in pagesWithText }
        val ocrItems = if (missing.isEmpty()) emptyList() else recognise(file, missing)
        ExtractedPdf(textItems + ocrItems, pageCount, missing.size)
    }

    private suspend fun recognise(file: File, pages: List<Int>): List<TextItem> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val items = mutableListOf<TextItem>()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (index in pages) {
                        val bitmap = renderer.openPage(index).use { page ->
                            Bitmap.createBitmap(
                                (page.width * OCR_SCALE).toInt(),
                                (page.height * OCR_SCALE).toInt(),
                                Bitmap.Config.ARGB_8888,
                            ).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                        try {
                            val text = suspendCancellableCoroutine { continuation ->
                                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                                    .addOnSuccessListener { continuation.resume(it) }
                                    .addOnFailureListener { continuation.resumeWithException(it) }
                            }
                            for (block in text.textBlocks) for (line in block.lines) for (element in line.elements) {
                                val box = element.boundingBox ?: continue
                                items += TextItem(
                                    text = element.text,
                                    x = box.left / OCR_SCALE,
                                    // Baseline approximated by the bottom of the box, as PdfBox reports it.
                                    y = box.bottom / OCR_SCALE,
                                    page = index,
                                    width = box.width() / OCR_SCALE,
                                    height = box.height() / OCR_SCALE,
                                )
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            recognizer.close()
        }
        return items
    }

    private companion object {
        /** PDF points to pixels: 3x is about 216 dpi, enough for 7 pt allergen text. */
        const val OCR_SCALE = 3f
    }
}
