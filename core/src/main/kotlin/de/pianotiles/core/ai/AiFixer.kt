package de.pianotiles.core.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import de.pianotiles.core.i18n.CoreText.t
import de.pianotiles.core.model.Song
import java.time.Duration
import java.util.Base64

/** One PDF page as image, prepared by the app (PNG or JPEG, about 1600 px wide). */
class PageImage(val page: Int, val bytes: ByteArray, val jpeg: Boolean)

/** Asks a model about one page. Exists as an interface so the runner can be tested without network. */
fun interface PageReviewer {
    /** Returns the model's raw JSON text. Throws [AiException] with a user-readable message on failure. */
    fun review(image: PageImage, text: String): String
}

class AiException(message: String, val fatal: Boolean = false) : Exception(message)

/** Talks to the Claude API through the official Anthropic SDK. */
/**
 * [language] is the language the model writes its "reason" and "summary" texts in (e.g. "German", "English").
 */
class ClaudeReviewer(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val language: String = "German",
) : PageReviewer, AutoCloseable {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(10))
        .maxRetries(3) // network errors, 429 and 5xx are retried by the SDK with backoff
        .build()

    override fun review(image: PageImage, text: String): String {
        val schema = JsonOutputFormat.Schema.builder()
        AiPrompt.outputSchema.forEach { (k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)) }
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(AiPrompt.system(language))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.HIGH) // careful visual comparison is worth the extra thinking
                    .format(JsonOutputFormat.builder().schema(schema.build()).build())
                    .build(),
            )
            // If a safety classifier declines, let the API retry on its recommended fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder().source(
                            Base64ImageSource.builder()
                                .data(Base64.getEncoder().encodeToString(image.bytes))
                                .mediaType(if (image.jpeg) Base64ImageSource.MediaType.IMAGE_JPEG else Base64ImageSource.MediaType.IMAGE_PNG)
                                .build(),
                        ).build(),
                    ),
                    ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()),
                ),
            )
            .build()
        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw AiException(t("API-Schlüssel wurde abgelehnt – bitte in den Einstellungen prüfen.", "The API key was rejected – please check it in the settings."), fatal = true)
        } catch (e: AnthropicServiceException) {
            throw AiException(t("Fehler vom KI-Dienst", "Error from the AI service") + " (${e.statusCode()}): ${e.message?.take(200)}")
        } catch (e: Exception) {
            throw AiException(t("Keine Verbindung zum KI-Dienst", "No connection to the AI service") + ": ${e.message?.take(200)}")
        }
        val stop = response.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) throw AiException(t("Die KI hat diese Seite abgelehnt.", "The AI declined this page."))
        if (stop == StopReason.MAX_TOKENS) throw AiException(t("Antwort war zu lang und wurde abgeschnitten.", "The answer was too long and got cut off."))
        val text = response.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) throw AiException(t("Leere Antwort der KI.", "Empty answer from the AI."))
        return text
    }

    override fun close() = client.close()
}

/** Progress events shown live in the app. */
sealed interface AiEvent {
    data class Progress(val done: Int, val total: Int, val message: String) : AiEvent
    data class Log(val message: String) : AiEvent
    data class PageResult(val page: Int, val corrections: List<AiCorrection>, val summary: String) : AiEvent
}

/**
 * Runs the review page by page. Pages without measure positions are skipped. Invalid answers are
 * re-requested once with the list of problems.
 */
class AiFixRunner(private val reviewer: PageReviewer) {
    fun run(
        song: Song,
        pages: List<Int>,
        loadPage: (Int) -> PageImage,
        keyName: String?,
        warnings: List<String>,
        isCancelled: () -> Boolean = { false },
        onEvent: (AiEvent) -> Unit,
    ): List<AiCorrection> {
        val all = ArrayList<AiCorrection>()
        val total = pages.size
        val pageCount = (song.sheetMeasures.maxOfOrNull { it.page } ?: 0) + 1
        for ((i, page) in pages.withIndex()) {
            if (isCancelled()) { onEvent(AiEvent.Log(t("Abgebrochen.", "Cancelled."))); break }
            val measures = AiPrompt.measuresOnPage(song, page)
            if (measures.isEmpty()) continue
            onEvent(AiEvent.Progress(i, total, t(
                "Seite ${page + 1}/$pageCount wird geprüft (Takte ${measures.first() + 1}–${measures.last() + 1}) …",
                "Checking page ${page + 1}/$pageCount (measures ${measures.first() + 1}–${measures.last() + 1}) …",
            )))
            val image = loadPage(page)
            var errors = emptyList<String>()
            for (attempt in 0..1) {
                val raw = try {
                    reviewer.review(image, AiPrompt.pageText(song, page, pageCount, keyName, warnings, errors))
                } catch (e: AiException) {
                    onEvent(AiEvent.Log(t("Seite", "Page") + " ${page + 1}: ${e.message}"))
                    if (e.fatal) return all
                    break
                }
                val (corr, summary, problems) = AiPrompt.parse(raw, song, measures)
                if (problems.isNotEmpty() && attempt == 0) {
                    onEvent(AiEvent.Log(t(
                        "Seite ${page + 1}: Antwort unvollständig (${problems.size} Probleme) – frage nach …",
                        "Page ${page + 1}: answer incomplete (${problems.size} problems) – asking again …",
                    )))
                    errors = problems
                    continue
                }
                problems.forEach { onEvent(AiEvent.Log(t("Seite ${page + 1}: übersprungen – $it", "Page ${page + 1}: skipped – $it"))) }
                all.addAll(corr)
                onEvent(AiEvent.PageResult(page, corr, summary))
                onEvent(AiEvent.Log(
                    t("Seite", "Page") + " ${page + 1}: " +
                        (if (corr.isEmpty()) t("keine Änderungen nötig", "no changes needed") else t("${corr.size} Korrekturen vorgeschlagen", "${corr.size} corrections proposed")) +
                        ". $summary",
                ))
                break
            }
            onEvent(AiEvent.Progress(i + 1, total, t("Seite ${page + 1} fertig", "Page ${page + 1} done")))
        }
        return all
    }
}
