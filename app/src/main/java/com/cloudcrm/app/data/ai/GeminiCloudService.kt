package com.cloudcrm.app.data.ai

import android.util.Log
import com.cloudcrm.app.data.model.ExtractedEntitiesResult
import com.cloudcrm.app.data.model.ExtractedEntity
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * State emitted during streaming entity extraction.
 */
sealed interface StreamingExtractionUpdate {
    /**
     * In-flight chunk received. Contains raw cumulative text and any entities
     * parsed thus far in real-time.
     */
    data class InFlight(
        val rawCumulativeText: String,
        val partialEntities: List<ExtractedEntity>
    ) : StreamingExtractionUpdate

    /**
     * Stream finished and valid final structured output decoded.
     */
    data class Success(
        val result: ExtractedEntitiesResult,
        val rawJson: String
    ) : StreamingExtractionUpdate

    /**
     * Extraction failed with an error.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : StreamingExtractionUpdate
}

/**
 * Cloud AI Service wrapping the Google GenAI SDK for streaming structured extraction
 * and text-embedding-004 vector embeddings.
 */
class GeminiCloudService(
    private val apiKeyProvider: () -> String
) {
    companion object {
        private const val TAG = "GeminiCloudService"
        
        // Recommended production Gemini models
        const val MODEL_EXTRACTION = "gemini-3.5-flash"
        const val MODEL_EMBEDDING = "text-embedding-004"
        const val EMBEDDING_DIMENSION = 768
        const val DEFAULT_THINKING_BUDGET = 0 // Thinking OFF by default for ultra-low latency
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        prettyPrint = true
    }

    /**
     * System instructions defining the role, extraction behavior, and temporal reference date.
     */
    private fun getSystemInstructionText(): String {
        val now = Date()
        val formattedDate = SimpleDateFormat("EEEE, MMMM dd, yyyy (yyyy-MM-dd)", Locale.US).format(now)
        return """
            You are a high-precision CRM entity and interaction extractor.
            Your task is to analyze conversational notes, voice transcripts, or meeting logs,
            and extract distinct persons/contacts mentioned, along with their roles, organizations,
            the specific factual interaction summary, relevant category tags, and the resolved date when the interaction occurred.
            
            REFERENCE TEMPORAL CONTEXT:
            Today is: $formattedDate.
            
            Rules:
            1. Extract every individual mentioned who has actionable context or relationship value.
            2. Format interaction_summary as a clear, concise, factual note preserving key dates, topics, and follow-ups.
            3. Assign relevant lower-case tags such as "neighborhood", "school", "work", "client", "sports", "family", "urgent".
            4. TEMPORAL RESOLUTION: If the notes refer to an interaction that occurred in the past (e.g. "last Friday", "yesterday", "3 days ago", "two weeks ago", "last August"), compute the exact past calendar date (YYYY-MM-DD) relative to today ($formattedDate) and populate 'interaction_date_iso'. If no past date is referenced, output today's date.
            5. Strict JSON adherence to the requested schema is mandatory.
        """.trimIndent()
    }

    /**
     * Builds the native Structured Output responseSchema matching the Gemini JSON contract.
     */
    private fun createExtractionSchema(): Schema<org.json.JSONObject> {
        val entityObjectSchema = Schema.obj(
            "extracted_entity",
            "Extracted CRM entity and interaction details",
            Schema.str("full_name", "Person's name or best identifier (e.g. 'Dave (Maya\'s dad)', 'Elena')"),
            Schema.str("role_context", "Relationship or role (e.g. 'Neighbor at #402', 'Plumber', 'Soccer Dad')"),
            Schema.str("organization", "Organization, club, company, or group (e.g. 'HOA', 'Acme Corp', 'Youth League')"),
            Schema.str("interaction_summary", "Summarized factual details of this specific interaction and follow-ups"),
            Schema.arr(
                "tags",
                "List of contextual category tags",
                Schema.str("tag", "Category tag name")
            ),
            Schema.str(
                "interaction_date_iso",
                "Resolved ISO-8601 date (YYYY-MM-DD) of when the interaction occurred based on relative temporal expressions (e.g. 'last Friday', 'yesterday')."
            )
        )

        return Schema.obj(
            "root",
            "Root CRM extraction container",
            Schema.arr(
                "extracted_entities",
                "List of extracted CRM contacts and interactions",
                entityObjectSchema
            )
        )
    }

    private fun buildSchemaJsonObject(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("properties", buildJsonObject {
            put("extracted_entities", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put("full_name", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Person's name or best identifier")
                        })
                        put("role_context", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Relationship or role")
                        })
                        put("organization", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Organization, club, company, or group")
                        })
                        put("interaction_summary", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Summarized factual details")
                        })
                        put("tags", buildJsonObject {
                            put("type", "ARRAY")
                            put("items", buildJsonObject {
                                put("type", "STRING")
                            })
                        })
                        put("interaction_date_iso", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Resolved ISO-8601 date (YYYY-MM-DD)")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("full_name"))
                        add(JsonPrimitive("interaction_summary"))
                    })
                })
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("extracted_entities"))
        })
    }

    /**
     * Initializes the GenerativeModel configured for streaming structured JSON outputs.
     */
    private fun createGenerativeModel(apiKey: String, modelName: String = MODEL_EXTRACTION): GenerativeModel {
        return GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = createExtractionSchema()
                temperature = 0.1f
                topP = 0.95f
            },
            systemInstruction = content { text(getSystemInstructionText()) },
            safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
            )
        )
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Direct SSE streaming engine with thinking budget control (budget 0 = Thinking OFF, -1 = Dynamic thinking).
     */
    private fun streamExtractionSse(
        apiKey: String,
        modelName: String,
        rawNotes: String?,
        mediaBytes: ByteArray?,
        mimeType: String = "audio/mp4",
        thinkingBudget: Int = DEFAULT_THINKING_BUDGET
    ): Flow<StreamingExtractionUpdate> = flow {
        val stringBuffer = StringBuilder()

        val payload = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", getSystemInstructionText()) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        if (mediaBytes != null && mediaBytes.isNotEmpty()) {
                            add(buildJsonObject {
                                put("inlineData", buildJsonObject {
                                    put("mimeType", mimeType)
                                    put("data", android.util.Base64.encodeToString(mediaBytes, android.util.Base64.NO_WRAP))
                                })
                            })
                            if (mimeType.startsWith("image/")) {
                                add(buildJsonObject {
                                    put("text", "Look at this screenshot of an interaction (e.g. text message thread) and extract CRM contacts, relationships, organizations, factual interaction summaries, tags, and resolved occurrence date:")
                                })
                            } else {
                                add(buildJsonObject {
                                    put("text", "Listen to this voice note and extract CRM contacts, relationships, organizations, factual interaction summaries, tags, and resolved occurrence date:")
                                })
                            }
                        } else {
                            add(buildJsonObject {
                                put("text", "Extract CRM entities and interactions from these notes:\n\n${rawNotes.orEmpty()}")
                            })
                        }
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseSchema", buildSchemaJsonObject())
                put("temperature", 0.1)
                put("topP", 0.95)
                if (thinkingBudget >= 0) {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingBudget", thinkingBudget)
                    })
                }
            })
        }

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:streamGenerateContent?alt=sse&key=$apiKey"
        Log.d(TAG, "Connecting to SSE endpoint for $modelName (thinkingBudget: $thinkingBudget)")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        Log.d(TAG, "Initiating OkHttp execute for $modelName (thinking: ${if (thinkingBudget == 0) "OFF" else if (thinkingBudget > 0) "$thinkingBudget" else "DYNAMIC"})...")
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "OkHttp execute failed for $modelName: ${e.message}", e)
            throw e
        }

        Log.d(TAG, "SSE response code received for $modelName: ${response.code}")
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "HTTP ${response.code}"
            Log.w(TAG, "Model $modelName HTTP error ${response.code}: $errorBody")
            throw IllegalStateException("API error HTTP ${response.code}: $errorBody")
        }

        val source = response.body?.source() ?: throw IllegalStateException("Empty body")
        while (coroutineContext.isActive) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.startsWith("data:")) {
                val jsonChunk = trimmed.removePrefix("data:").trim()
                if (jsonChunk.isNotBlank()) {
                    try {
                        val element = json.parseToJsonElement(jsonChunk).jsonObject
                        val candidates = element["candidates"]?.jsonArray
                        val parts = candidates?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                        parts?.forEach { part ->
                            val partObj = part.jsonObject
                            val isThought = partObj["thought"]?.jsonPrimitive?.booleanOrNull == true
                            if (!isThought) {
                                val text = partObj["text"]?.jsonPrimitive?.contentOrNull
                                if (!text.isNullOrEmpty()) {
                                    stringBuffer.append(text)
                                    val currentAccumulation = stringBuffer.toString()
                                    Log.d(TAG, "Chunk emitted ($modelName): $text")
                                    val partialEntities = parseLenientPartialEntities(currentAccumulation)
                                    emit(StreamingExtractionUpdate.InFlight(currentAccumulation, partialEntities))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing SSE chunk: ${e.message}")
                    }
                }
            }
        }

        val finalJson = stringBuffer.toString().trim()
        val parsedResult = try {
            json.decodeFromString<ExtractedEntitiesResult>(finalJson)
        } catch (e: Exception) {
            val lenientList = parseLenientPartialEntities(finalJson)
            ExtractedEntitiesResult(lenientList)
        }

        Log.d(TAG, "SSE Extraction completed successfully with model: $modelName")
        emit(StreamingExtractionUpdate.Success(parsedResult, finalJson))
    }

    /**
     * Streams structured entity extraction from conversational notes in real time.
     * Tries Thinking OFF (budget 0) first for instant parsing, and falls back to default dynamic thinking if needed.
     */
    fun streamExtractEntities(rawNotes: String): Flow<StreamingExtractionUpdate> = flow {
        Log.d(TAG, "streamExtractEntities flow started for note: $rawNotes")
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            emitLocalDemoStream(rawNotes)
            return@flow
        }

        val candidateModels = listOf(
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-3.5-flash-lite"
        )

        var lastError: Throwable? = null

        for (modelName in candidateModels) {
            // Attempt 1: Thinking OFF (thinkingBudget = 0)
            try {
                Log.d(TAG, "Attempting extraction with model: $modelName (Thinking OFF, budget: 0)")
                streamExtractionSse(apiKey, modelName, rawNotes = rawNotes, mediaBytes = null, thinkingBudget = 0)
                    .collect { update -> emit(update) }
                return@flow
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Model $modelName thinking OFF warning: ${t.message}")
            }

            // Attempt 2: Default dynamic thinking (thinkingConfig omitted)
            try {
                Log.d(TAG, "Attempting extraction with model: $modelName (Dynamic Thinking fallback)")
                streamExtractionSse(apiKey, modelName, rawNotes = rawNotes, mediaBytes = null, thinkingBudget = -1)
                    .collect { update -> emit(update) }
                return@flow
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Model $modelName dynamic thinking warning: ${t.message}")
                if (modelName != candidateModels.last()) {
                    continue
                }
            }
        }

        Log.e(TAG, "All extraction models failed: ${lastError?.message}", lastError)
        emit(StreamingExtractionUpdate.Error("Extraction failed: ${lastError?.localizedMessage ?: "Unknown AI error"}", lastError))
    }.flowOn(Dispatchers.IO)

    /**
     * Streams structured entity extraction directly from raw recorded audio bytes (e.g. AAC / M4A).
     * Tries Thinking OFF first and falls back to Dynamic thinking.
     */
    fun streamExtractEntitiesFromAudio(
        audioBytes: ByteArray,
        mimeType: String = "audio/mp4"
    ): Flow<StreamingExtractionUpdate> = flow {
        Log.d(TAG, "streamExtractEntitiesFromAudio flow started with ${audioBytes.size} bytes")
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            emitLocalDemoStream("Met Dave at soccer practice on Friday. Discussed tournament schedule.")
            return@flow
        }

        val candidateModels = listOf(
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-3.5-flash-lite"
        )

        var lastError: Throwable? = null

        for (modelName in candidateModels) {
            // Attempt 1: Thinking OFF
            try {
                Log.d(TAG, "Attempting audio extraction with model: $modelName (Thinking OFF, budget: 0)")
                streamExtractionSse(apiKey, modelName, rawNotes = null, mediaBytes = audioBytes, mimeType = mimeType, thinkingBudget = 0)
                    .collect { update -> emit(update) }
                return@flow
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Model $modelName audio thinking OFF warning: ${t.message}")
            }

            // Attempt 2: Dynamic thinking
            try {
                Log.d(TAG, "Attempting audio extraction with model: $modelName (Dynamic Thinking fallback)")
                streamExtractionSse(apiKey, modelName, rawNotes = null, mediaBytes = audioBytes, mimeType = mimeType, thinkingBudget = -1)
                    .collect { update -> emit(update) }
                return@flow
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Model $modelName audio dynamic thinking warning: ${t.message}")
                if (modelName != candidateModels.last()) {
                    continue
                }
            }
        }

        Log.e(TAG, "All audio extraction models failed: ${lastError?.message}", lastError)
        emit(StreamingExtractionUpdate.Error("Audio extraction failed: ${lastError?.localizedMessage ?: "Unknown AI error"}", lastError))
    }.flowOn(Dispatchers.IO)

    /**
     * Streams structured entity extraction directly from an image.
     */
    fun streamExtractEntitiesFromImage(
        imageBytes: ByteArray,
        mimeType: String
    ): Flow<StreamingExtractionUpdate> = flow {
        Log.d(TAG, "streamExtractEntitiesFromImage flow started with ${imageBytes.size} bytes")
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            emitLocalDemoStream("Saw a screenshot about a meeting with Alex Mercer.")
            return@flow
        }

        val candidateModels = listOf(
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-3.5-flash-lite"
        )

        var lastError: Throwable? = null

        for (modelName in candidateModels) {
            try {
                Log.d(TAG, "Attempting image extraction with model: $modelName")
                streamExtractionSse(apiKey, modelName, rawNotes = null, mediaBytes = imageBytes, mimeType = mimeType, thinkingBudget = -1)
                    .collect { update -> emit(update) }
                return@flow
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Model $modelName image extraction warning: ${t.message}")
                if (modelName != candidateModels.last()) continue
            }
        }

        Log.e(TAG, "All image extraction models failed: ${lastError?.message}", lastError)
        emit(StreamingExtractionUpdate.Error("Image extraction failed: ${lastError?.localizedMessage ?: "Unknown AI error"}", lastError))
    }.flowOn(Dispatchers.IO)

    /**
     * Generates a 768-dimensional text embedding vector using the text-embedding-004 model.
     *
     * @param text The interaction summary or search query to embed.
     * @return List of Double precision floats normalized for cosine distance in Firestore.
     */
    suspend fun generateEmbedding(text: String): List<Double> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank() || text.isBlank()) {
            return@withContext generateDeterministicMockEmbedding(text)
        }

        try {
            // Call Gemini text-embedding-004 REST endpoint via standard HTTP
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_EMBEDDING:embedContent?key=$apiKey"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val requestBody = """
                {
                    "model": "models/$MODEL_EMBEDDING",
                    "content": {
                        "parts": [{ "text": ${json.encodeToString(kotlinx.serialization.serializer(), text)} }]
                    }
                }
            """.trimIndent()

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val rootJson = json.parseToJsonElement(responseText).jsonObject
                val embeddingObj = rootJson["embedding"]?.jsonObject
                val valuesArray = embeddingObj?.get("values")?.jsonArray

                if (valuesArray != null && valuesArray.isNotEmpty()) {
                    return@withContext valuesArray.mapNotNull { it.jsonPrimitive.content.toDoubleOrNull() }
                }
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Embedding API returned code $responseCode: $errorStream")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding generation network error: ${e.message}", e)
        }

        // Fallback to deterministic pseudo-vector if network/quota is unavailable
        generateDeterministicMockEmbedding(text)
    }

    /**
     * Resilient incremental JSON parser that extracts partial entity fields
     * while the structured JSON string is still streaming over the wire.
     */
    fun parseLenientPartialEntities(incompleteJson: String): List<ExtractedEntity> {
        if (incompleteJson.isBlank()) return emptyList()

        // 1. Try full standard parse first
        try {
            val result = json.decodeFromString<ExtractedEntitiesResult>(incompleteJson)
            if (result.extractedEntities.isNotEmpty()) return result.extractedEntities
        } catch (_: Exception) {
            // Expected during streaming
        }

        // 2. Lenient regex-based object extractor for in-flight streaming chunks
        val entities = mutableListOf<ExtractedEntity>()
        try {
            // Find all JSON object chunks inside "extracted_entities": [ ... ]
            val arrayStart = incompleteJson.indexOf('[')
            if (arrayStart != -1) {
                val arrayContent = incompleteJson.substring(arrayStart)
                val objectRegex = Regex("""\{([^{}]+)\}""")
                val matches = objectRegex.findAll(arrayContent).toList()

                for (match in matches) {
                    val objStr = "{" + match.groupValues[1] + "}"
                    try {
                        val parsedObj = json.parseToJsonElement(objStr).jsonObject
                        val fullName = parsedObj["full_name"]?.jsonPrimitive?.content ?: ""
                        val roleContext = parsedObj["role_context"]?.jsonPrimitive?.content ?: ""
                        val org = parsedObj["organization"]?.jsonPrimitive?.content ?: ""
                        val summary = parsedObj["interaction_summary"]?.jsonPrimitive?.content ?: ""
                        val tags = parsedObj["tags"]?.let { tagEl ->
                            if (tagEl is JsonArray) tagEl.mapNotNull { it.jsonPrimitive.content } else emptyList()
                        } ?: emptyList()

                        val interactionDateIso = parsedObj["interaction_date_iso"]?.jsonPrimitive?.content ?: ""

                        if (fullName.isNotBlank() || summary.isNotBlank()) {
                            entities.add(
                                ExtractedEntity(
                                    fullName = fullName,
                                    roleContext = roleContext,
                                    organization = org,
                                    interactionSummary = summary,
                                    tags = tags,
                                    interactionDateIso = interactionDateIso
                                )
                            )
                        }
                    } catch (_: Exception) {
                        // Incomplete inner object
                    }
                }

                // Handle the last currently trailing unclosed object
                val lastOpenBrace = arrayContent.lastIndexOf('{')
                val lastCloseBrace = arrayContent.lastIndexOf('}')
                if (lastOpenBrace > lastCloseBrace && lastOpenBrace != -1) {
                    val trailingStr = arrayContent.substring(lastOpenBrace)
                    val fullName = extractFieldLenient(trailingStr, "full_name")
                    val roleContext = extractFieldLenient(trailingStr, "role_context")
                    val org = extractFieldLenient(trailingStr, "organization")
                    val summary = extractFieldLenient(trailingStr, "interaction_summary")
                    val interactionDateIso = extractFieldLenient(trailingStr, "interaction_date_iso")

                    if (fullName.isNotBlank() || summary.isNotBlank() || roleContext.isNotBlank()) {
                        entities.add(
                            ExtractedEntity(
                                fullName = fullName,
                                roleContext = roleContext,
                                organization = org,
                                interactionSummary = summary,
                                tags = emptyList(),
                                interactionDateIso = interactionDateIso
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Lenient parse error: ${e.message}")
        }

        return entities
    }

    private fun extractFieldLenient(fragment: String, fieldName: String): String {
        val pattern = Regex(""""$fieldName"\s*:\s*"([^"]*)""")
        return pattern.find(fragment)?.groupValues?.get(1) ?: ""
    }

    /**
     * Local deterministic embedding generation for offline use, unit testing, or initial demo.
     * Uses normalized bag-of-words hash projection to 768 dimensions.
     */
    private fun generateDeterministicMockEmbedding(text: String): List<Double> {
        val vector = DoubleArray(EMBEDDING_DIMENSION) { 0.0 }
        val tokens = text.lowercase().split(Regex("[\\s,;:.!?]+")).filter { it.isNotBlank() }
        
        if (tokens.isEmpty()) {
            return vector.toList()
        }

        for (token in tokens) {
            val hash = token.hashCode()
            for (i in 0 until 8) {
                val index = Math.floorMod(hash * 31 + i * 97, EMBEDDING_DIMENSION)
                val sign = if ((hash shr i) and 1 == 1) 1.0 else -1.0
                vector[index] += sign
            }
        }

        // L2 normalize vector for cosine similarity
        var normSq = 0.0
        for (v in vector) normSq += v * v
        val norm = sqrt(normSq)
        if (norm > 0) {
            for (i in vector.indices) vector[i] /= norm
        }

        return vector.toList()
    }

    /**
     * Provides an authentic streaming experience for mock demo inputs with resolved temporal dates.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamingExtractionUpdate>.emitLocalDemoStream(rawNotes: String) {
        Log.d(TAG, "emitLocalDemoStream started with note: $rawNotes")

        // Dynamically compute last Friday date and yesterday date relative to current device time
        val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        var daysBackToFriday = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.FRIDAY + 7) % 7
        if (daysBackToFriday == 0) daysBackToFriday = 7
        val lastFridayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysBackToFriday) }
        val lastFridayIso = isoFormat.format(lastFridayCal.time)
        val todayIso = isoFormat.format(Date())

        val demoEntities = if (rawNotes.contains("Dave", ignoreCase = true) || rawNotes.contains("Elena", ignoreCase = true)) {
            listOf(
                ExtractedEntity(
                    fullName = "Dave",
                    roleContext = "Maya's Dad (Soccer team)",
                    organization = "Youth Soccer League",
                    interactionSummary = "Met Dave at soccer practice. Discussed carpooling and upcoming tournament.",
                    tags = listOf("school", "sports", "parent"),
                    interactionDateIso = lastFridayIso // Accurately resolved past date!
                ),
                ExtractedEntity(
                    fullName = "Elena",
                    roleContext = "Building Resident #402",
                    organization = "HOA Board",
                    interactionSummary = "Elena mentioned elevator maintenance is scheduled for Friday morning.",
                    tags = listOf("neighborhood", "urgent", "building"),
                    interactionDateIso = todayIso
                )
            )
        } else {
            listOf(
                ExtractedEntity(
                    fullName = "Alex Mercer",
                    roleContext = "Senior Systems Architect",
                    organization = "Cloud Dynamics",
                    interactionSummary = "Discussed migration timeline to modern cloud infrastructure with target Q3 completion.",
                    tags = listOf("work", "tech", "infrastructure"),
                    interactionDateIso = todayIso
                )
            )
        }

        val result = ExtractedEntitiesResult(demoEntities)
        val fullJson = json.encodeToString(ExtractedEntitiesResult.serializer(), result)
        Log.d(TAG, "emitLocalDemoStream generated json: $fullJson")

        // Stream in realistic chunks
        val chunkSize = 25
        val sb = StringBuilder()
        for (i in 0 until fullJson.length step chunkSize) {
            val end = (i + chunkSize).coerceAtMost(fullJson.length)
            val chunk = fullJson.substring(i, end)
            sb.append(chunk)
            val partial = parseLenientPartialEntities(sb.toString())
            emit(StreamingExtractionUpdate.InFlight(sb.toString(), partial))
            kotlinx.coroutines.delay(45)
        }

        Log.d(TAG, "emitLocalDemoStream emitting Success with ${result.extractedEntities.size} entities")
        emit(StreamingExtractionUpdate.Success(result, fullJson))
    }
}
