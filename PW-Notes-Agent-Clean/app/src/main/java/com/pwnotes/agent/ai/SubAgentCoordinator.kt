package com.pwnotes.agent.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object SubAgentCoordinator {

    sealed class WorkflowStage {
        object Idle : WorkflowStage()
        object IngestingScreens : WorkflowStage()
        object GeneratingCompleteNotes : WorkflowStage()
        data class WaitingForUserReview(val rawNotes: String) : WorkflowStage()
        object GeneratingA4VisualLayout : WorkflowStage()
        data class Completed(val a4HtmlPages: List<String>) : WorkflowStage()
        data class Error(val message: String) : WorkflowStage()
    }

    private val _stage = MutableStateFlow<WorkflowStage>(WorkflowStage.Idle)
    val stage: StateFlow<WorkflowStage> = _stage

    private val _capturedBitmaps = mutableListOf<Bitmap>()
    private val _capturedCount = MutableStateFlow(0)
    val capturedCount: StateFlow<Int> = _capturedCount

    var apiKey: String = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    const val SYSTEM_PROMPT = """Act as a faithful transcription-and-arranged-notes assistant for uploaded study PDFs. Use the user's example document as the style reference. Match its organization, visual hierarchy, note style, and level of detail, while allowing cleaner wording and improved readability when meaning remains unchanged.

Source fidelity is mandatory: use only information actually present in the PDF being converted. Do not add outside facts, explanations, examples, corrections, interpretations, or inferred information. Do not remove meaningful source content. Preserve terminology, formulas, questions, answers, tables, diagrams, labels, handwritten annotations, highlights, and emphasized details that are readable. Non-content/navigation pages such as cover pages and 'Topics to be Covered' may be skipped when they contain no actual teaching content.

When converting a PDF, first produce ALL notes for the entire PDF in one chat response, rather than stopping after a few pages. Organize the notes page-by-page so the source coverage can be checked. Improve grammar and presentation only when the meaning and information remain unchanged. Use headings, bullets, numbered points, tables, comparisons, and flowcharts as appropriate to mirror the example. Do not silently fact-check or correct the source.

After the complete notes are produced, the user may review them and tell you exactly what content or pages they want removed or changed. Apply those edits without altering unrelated content. Do not proceed to visual note-page creation until the user confirms the final text/content is complete.

Once the final notes are confirmed, create a page-by-page visual notes layout. Analyze the amount of content and intelligently distribute it across vertical A4 pages so the text remains readable and the pages are not overcrowded. Determine page breaks based on content density, headings, tables, diagrams, and logical topic boundaries. Use the final approved notes as the sole textual source. Where the original PDF contains a relevant image, diagram, chart, or illustration for a topic, reuse or incorporate that relevant visual when appropriate rather than adding unrelated imagery. The visual pages should follow the approved note style and be designed as actual study-note pages.

For visual-page creation, preserve every approved piece of information across the pages. Do not omit content merely to make a page prettier; create additional A4 pages when needed. If an original visual cannot be reproduced clearly, represent its meaningful content without inventing new information. The user expects the final notes to be complete first, then edited by them, then turned into page-by-page A4 visual notes."""

    fun addCapturedPage(bitmap: Bitmap) {
        _capturedBitmaps.add(bitmap)
        _capturedCount.value = _capturedBitmaps.size
    }

    fun getCapturedPages(): List<Bitmap> = _capturedBitmaps.toList()

    fun clearCaptured() {
        _capturedBitmaps.clear()
        _capturedCount.value = 0
        _stage.value = WorkflowStage.Idle
    }

    suspend fun startTranscriptionPipeline() {
        if (_capturedBitmaps.isEmpty()) {
            _stage.value = WorkflowStage.Error("No pages captured. Please capture slides first.")
            return
        }

        _stage.value = WorkflowStage.GeneratingCompleteNotes

        withContext(Dispatchers.IO) {
            try {
                val base64Images = _capturedBitmaps.map { bitmap ->
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                    Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                }

                val notesText = callGeminiMultimodalTranscription(base64Images)
                _stage.value = WorkflowStage.WaitingForUserReview(notesText)
            } catch (e: Exception) {
                _stage.value = WorkflowStage.Error("Transcription failed: ${e.localizedMessage}")
            }
        }
    }

    fun applyUserEdits(updatedNotesText: String) {
        _stage.value = WorkflowStage.WaitingForUserReview(updatedNotesText)
    }

    suspend fun confirmAndGenerateA4VisualNotes(approvedNotes: String) {
        _stage.value = WorkflowStage.GeneratingA4VisualLayout

        withContext(Dispatchers.IO) {
            try {
                val a4PagesHtml = callGeminiA4LayoutGenerator(approvedNotes)
                _stage.value = WorkflowStage.Completed(a4PagesHtml)
            } catch (e: Exception) {
                _stage.value = WorkflowStage.Error("A4 layout generation failed: ${e.localizedMessage}")
            }
        }
    }

    private fun callGeminiMultimodalTranscription(base64Images: List<String>): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

        val contentsArray = JSONArray()
        val partsArray = JSONArray()

        val promptPart = JSONObject().put("text", "$SYSTEM_PROMPT\n\nTask: Transcribe all attached pages page-by-page faithfully. Do not skip any teaching content. Retain all formulas, questions, diagrams, and notes.")
        partsArray.put(promptPart)

        for (b64 in base64Images) {
            val inlineData = JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", b64)
            val imagePart = JSONObject().put("inlineData", inlineData)
            partsArray.put(imagePart)
        }

        contentsArray.put(JSONObject().put("parts", partsArray))
        val requestBodyJson = JSONObject().put("contents", contentsArray)

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val bodyString = response.body?.string() ?: throw Exception("Empty response from AI engine")

        if (!response.isSuccessful) {
            throw Exception("API Error (${response.code}): $bodyString")
        }

        val jsonResponse = JSONObject(bodyString)
        val candidates = jsonResponse.getJSONArray("candidates")
        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.getJSONArray("parts")
        return parts.getJSONObject(0).getString("text")
    }

    private fun callGeminiA4LayoutGenerator(approvedNotes: String): List<String> {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

        val instruction = """$SYSTEM_PROMPT

Now proceed to visual note-page creation. The user has confirmed the final text.
Create a page-by-page visual notes layout. Analyze the amount of content and intelligently distribute it across vertical A4 pages so the text remains readable and the pages are not overcrowded. Determine page breaks based on content density, headings, tables, diagrams, and logical topic boundaries.

Return the result as a JSON array of strings, where each element is the complete self-contained HTML/CSS for ONE single vertical A4 page (dimensions: 210mm x 297mm, styling: professional study notes, clean margins, font-family: system-ui, high contrast formulas, colored callout boxes for formulas/notes).
Format strictly as JSON: ["<html>...page 1...</html>", "<html>...page 2...</html>"]"""

        val partsArray = JSONArray()
        partsArray.put(JSONObject().put("text", "$instruction\n\nFinal Approved Notes:\n$approvedNotes"))

        val contentsArray = JSONArray().put(JSONObject().put("parts", partsArray))
        val requestBodyJson = JSONObject()
            .put("contents", contentsArray)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val bodyString = response.body?.string() ?: throw Exception("Empty response from layout engine")

        val jsonResponse = JSONObject(bodyString)
        val candidates = jsonResponse.getJSONArray("candidates")
        val text = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")

        val pagesJsonArray = JSONArray(text)
        val pagesList = mutableListOf<String>()
        for (i in 0 until pagesJsonArray.length()) {
            pagesList.add(pagesJsonArray.getString(i))
        }
        return pagesList
    }
}
