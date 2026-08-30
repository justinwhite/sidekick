package com.cloudcrm.app.data.repository

import android.util.Log
import com.cloudcrm.app.data.model.Contact
import com.cloudcrm.app.data.model.ExtractedEntityDiff
import com.cloudcrm.app.data.model.Interaction
import com.cloudcrm.app.data.model.TimeRangeFilter
import com.cloudcrm.app.data.model.TimelineItem
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * Repository interface defining Cloud CRM persistence and semantic query capabilities.
 */
interface CloudCrmRepository {
    suspend fun findMatchingContact(name: String): Contact?
    suspend fun getAllContacts(): List<Contact>
    suspend fun getContactById(contactId: String): Contact?
    suspend fun syncExtractedDiffBatch(diffs: List<ExtractedEntityDiff>, embeddings: Map<String, List<Double>>): Result<List<String>>
    fun getTimelineFeedFlow(): Flow<List<TimelineItem>>
    suspend fun searchInteractionsSemantic(
        queryVector: List<Double>,
        limit: Long = 20,
        timeRangeFilter: TimeRangeFilter = TimeRangeFilter.ALL,
        selectedTags: Set<String> = emptySet()
    ): List<TimelineItem>
}

/**
 * Production implementation of [CloudCrmRepository] using Firebase Firestore
 * with in-memory local caching fallback.
 */
class CloudCrmRepositoryImpl(
    firestoreOverride: FirebaseFirestore? = null
) : CloudCrmRepository {

    private val firestore: FirebaseFirestore? = firestoreOverride ?: try {
        FirebaseFirestore.getInstance()
    } catch (e: Exception) {
        Log.w("CloudCrmRepository", "Firestore not initialized, running in memory-fallback mode: ${e.message}")
        null
    }

    // In-memory store for demo/offline fallback when Firebase is uninitialized
    private val localContacts = mutableListOf<Contact>()
    private val localInteractions = mutableListOf<Interaction>()
    private val timelineStateFlow = MutableStateFlow<List<TimelineItem>>(emptyList())

    companion object {
        private const val TAG = "CloudCrmRepository"
        const val COLLECTION_CONTACTS = "contacts"
        const val SUBCOLLECTION_INTERACTIONS = "interactions"
    }

    /**
     * Looks up an existing contact in Firestore matching the extracted name.
     */
    override suspend fun findMatchingContact(name: String): Contact? = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return@withContext null

        val localMatch = localContacts.firstOrNull { isFuzzyNameMatch(trimmedName, it.fullName) }
        val db = firestore ?: return@withContext localMatch

        try {
            kotlinx.coroutines.withTimeoutOrNull(800) {
                // 1. Check exact match
                val exactQuery = db.collection(COLLECTION_CONTACTS)
                    .whereEqualTo("full_name", trimmedName)
                    .limit(1)
                    .get()
                    .await()

                if (!exactQuery.isEmpty) {
                    val doc = exactQuery.documents.first()
                    return@withTimeoutOrNull doc.toObject(Contact::class.java)?.copy(id = doc.id)
                }

                // 2. Perform prefix / first-name token search
                val firstName = trimmedName.split(" ").firstOrNull() ?: trimmedName
                val prefixQuery = db.collection(COLLECTION_CONTACTS)
                    .whereGreaterThanOrEqualTo("full_name", firstName)
                    .whereLessThanOrEqualTo("full_name", firstName + "\uf8ff")
                    .limit(5)
                    .get()
                    .await()

                for (doc in prefixQuery.documents) {
                    val candidate = doc.toObject(Contact::class.java)?.copy(id = doc.id)
                    if (candidate != null && isFuzzyNameMatch(trimmedName, candidate.fullName)) {
                        return@withTimeoutOrNull candidate
                    }
                }
                null
            } ?: localMatch
        } catch (e: Exception) {
            Log.w(TAG, "Contact match lookup warning: ${e.message}")
            localMatch
        }
    }

    override suspend fun getAllContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val db = firestore
        if (db == null) return@withContext localContacts.toList()

        try {
            val snapshot = db.collection(COLLECTION_CONTACTS)
                .orderBy("full_name", Query.Direction.ASCENDING)
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                doc.toObject(Contact::class.java)?.copy(id = doc.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contacts: ${e.message}", e)
            localContacts.toList()
        }
    }

    override suspend fun getContactById(contactId: String): Contact? = withContext(Dispatchers.IO) {
        val db = firestore
        if (db == null) return@withContext localContacts.firstOrNull { it.id == contactId }

        try {
            val doc = db.collection(COLLECTION_CONTACTS).document(contactId).get().await()
            if (doc.exists()) {
                doc.toObject(Contact::class.java)?.copy(id = doc.id)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contact $contactId: ${e.message}")
            localContacts.firstOrNull { it.id == contactId }
        }
    }

    /**
     * Executes atomic batch writes to Firestore:
     * - Creates or updates Contact in `contacts` collection
     * - Appends immutable Interaction in `contacts/{contactId}/interactions` subcollection
     *   with text summary and high-dimensional vector embeddings.
     */
    override suspend fun syncExtractedDiffBatch(
        diffs: List<ExtractedEntityDiff>,
        embeddings: Map<String, List<Double>>
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val db = firestore
        val createdInteractionIds = mutableListOf<String>()

        if (db == null) {
            // Local fallback store
            for (diff in diffs) {
                val matched = localContacts.firstOrNull { it.id == diff.matchedContact?.id }
                val contactId = if (matched != null) {
                    matched.tags = (matched.tags + diff.editedTags).distinct()
                    if (diff.editedRoleContext.isNotBlank()) matched.roleContext = diff.editedRoleContext
                    if (diff.editedOrganization.isNotBlank()) matched.organization = diff.editedOrganization
                    matched.updatedAt = Timestamp.now()
                    matched.id
                } else {
                    val newId = java.util.UUID.randomUUID().toString()
                    val newContact = Contact(
                        id = newId,
                        fullName = diff.editedFullName,
                        roleContext = diff.editedRoleContext,
                        organization = diff.editedOrganization,
                        tags = diff.editedTags,
                        updatedAt = Timestamp.now()
                    )
                    localContacts.add(newContact)
                    newId
                }

                val interactionId = java.util.UUID.randomUUID().toString()
                val eventDate = parseIsoDateOrNow(diff.editedInteractionDateIso)
                val interaction = Interaction(
                    id = interactionId,
                    contactId = contactId,
                    contactName = diff.editedFullName,
                    date = Timestamp(eventDate),
                    summary = diff.editedInteractionSummary,
                    embeddingVector = embeddings[diff.id] ?: emptyList()
                )
                localInteractions.add(interaction)
                createdInteractionIds.add(interactionId)
            }

            updateLocalTimelineFlow()
            return@withContext Result.success(createdInteractionIds)
        }

        try {
            val batch = db.batch()

            for (diff in diffs) {
                val contactRef = if (diff.matchedContact != null && diff.matchedContact.id.isNotBlank()) {
                    // Update existing contact
                    val existingRef = db.collection(COLLECTION_CONTACTS).document(diff.matchedContact.id)
                    val mergedTags = (diff.matchedContact.tags + diff.editedTags).distinct()
                    
                    val updateMap = mutableMapOf<String, Any>(
                        "updated_at" to FieldValue.serverTimestamp(),
                        "tags" to mergedTags
                    )
                    if (diff.editedRoleContext.isNotBlank()) {
                        updateMap["role_context"] = diff.editedRoleContext
                    }
                    if (diff.editedOrganization.isNotBlank()) {
                        updateMap["organization"] = diff.editedOrganization
                    }

                    batch.update(existingRef, updateMap)
                    existingRef
                } else {
                    // Create new contact
                    val newContactRef = db.collection(COLLECTION_CONTACTS).document()
                    val newContact = Contact(
                        id = newContactRef.id,
                        fullName = diff.editedFullName,
                        roleContext = diff.editedRoleContext,
                        organization = diff.editedOrganization,
                        tags = diff.editedTags,
                        updatedAt = Timestamp.now()
                    )
                    batch.set(newContactRef, newContact.toMap())
                    newContactRef
                }

                // Create immutable interaction document in subcollection
                val interactionRef = contactRef.collection(SUBCOLLECTION_INTERACTIONS).document()
                val embeddingVector = embeddings[diff.id] ?: emptyList()
                val eventDate = parseIsoDateOrNow(diff.editedInteractionDateIso)

                val interaction = Interaction(
                    id = interactionRef.id,
                    contactId = contactRef.id,
                    contactName = diff.editedFullName,
                    date = Timestamp(eventDate),
                    summary = diff.editedInteractionSummary,
                    embeddingVector = embeddingVector
                )

                batch.set(interactionRef, interaction.toMap())
                createdInteractionIds.add(interactionRef.id)
            }

            // Commit atomic batch write to cloud
            batch.commit().await()
            Log.i(TAG, "Successfully synced ${diffs.size} entities to Firestore.")
            Result.success(createdInteractionIds)

        } catch (e: Exception) {
            Log.e(TAG, "Batch sync failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun updateLocalTimelineFlow() {
        val contactsMap = localContacts.associateBy { it.id }
        val items = localInteractions.sortedByDescending { it.date }.map { interaction ->
            TimelineItem(
                interaction = interaction,
                contact = contactsMap[interaction.contactId] ?: Contact(id = interaction.contactId, fullName = interaction.contactName)
            )
        }
        timelineStateFlow.value = items
    }

    /**
     * Real-time stream of all CRM interactions across all contacts using Firestore collectionGroup.
     */
    override fun getTimelineFeedFlow(): Flow<List<TimelineItem>> {
        val db = firestore ?: return timelineStateFlow

        return callbackFlow {
            val listenerRegistration = db.collectionGroup(SUBCOLLECTION_INTERACTIONS)
                .orderBy("date", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Timeline listener error: ${error.message}", error)
                        trySend(timelineStateFlow.value)
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val items = snapshot.documents.mapNotNull { doc ->
                            val interaction = doc.toObject(Interaction::class.java)?.copy(id = doc.id)
                            if (interaction != null) {
                                TimelineItem(
                                    interaction = interaction,
                                    contact = Contact(
                                        id = interaction.contactId,
                                        fullName = interaction.contactName
                                    )
                                )
                            } else null
                        }
                        trySend(items)
                    }
                }

            awaitClose {
                listenerRegistration.remove()
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Performs Semantic Search over interactions using vector embeddings and Firestore collectionGroup.
     * Computes cosine similarity between the query embedding and stored interaction embeddings.
     */
    override suspend fun searchInteractionsSemantic(
        queryVector: List<Double>,
        limit: Long,
        timeRangeFilter: TimeRangeFilter,
        selectedTags: Set<String>
    ): List<TimelineItem> = withContext(Dispatchers.IO) {
        try {
            val contactsMap = getAllContacts().associateBy { it.id }
            val db = firestore

            val interactionCandidates: List<Interaction> = if (db != null) {
                val snapshot = db.collectionGroup(SUBCOLLECTION_INTERACTIONS)
                    .orderBy("date", Query.Direction.DESCENDING)
                    .limit(150)
                    .get()
                    .await()
                snapshot.documents.mapNotNull { doc ->
                    doc.toObject(Interaction::class.java)?.copy(id = doc.id)
                }
            } else {
                localInteractions.toList()
            }

            val now = Date()
            val cutoffDate: Date? = when (timeRangeFilter) {
                TimeRangeFilter.ALL -> null
                TimeRangeFilter.LAST_7_DAYS -> Calendar.getInstance().apply {
                    time = now
                    add(Calendar.DAY_OF_YEAR, -7)
                }.time
                TimeRangeFilter.THIS_MONTH -> Calendar.getInstance().apply {
                    time = now
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }.time
            }

            val scoredItems = mutableListOf<TimelineItem>()

            for (interaction in interactionCandidates) {
                val parentContact = contactsMap[interaction.contactId]

                // Apply time filter
                if (cutoffDate != null && interaction.date.toDate().before(cutoffDate)) {
                    continue
                }

                // Apply tag filters if specified
                if (selectedTags.isNotEmpty()) {
                    val contactTags = parentContact?.tags?.map { it.lowercase() }?.toSet() ?: emptySet()
                    val hasMatchingTag = selectedTags.any { filterTag ->
                        contactTags.contains(filterTag.lowercase()) ||
                                interaction.summary.contains(filterTag, ignoreCase = true)
                    }
                    if (!hasMatchingTag) continue
                }

                // Compute high-dimensional Cosine Similarity
                val similarity = if (queryVector.isNotEmpty() && interaction.embeddingVector.isNotEmpty()) {
                    computeCosineSimilarity(queryVector, interaction.embeddingVector)
                } else {
                    0.0
                }

                scoredItems.add(
                    TimelineItem(
                        interaction = interaction,
                        contact = parentContact ?: Contact(id = interaction.contactId, fullName = interaction.contactName),
                        similarityScore = similarity
                    )
                )
            }

            // Rank by highest semantic similarity
            scoredItems.sortByDescending { it.similarityScore ?: 0.0 }
            scoredItems.take(limit.toInt())

        } catch (e: Exception) {
            Log.e(TAG, "Semantic vector query failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Calculates mathematical Cosine Similarity between two N-dimensional embedding vectors:
     * cos(θ) = (A · B) / (||A|| * ||B||)
     */
    private fun computeCosineSimilarity(v1: List<Double>, v2: List<Double>): Double {
        if (v1.isEmpty() || v2.isEmpty()) return 0.0
        val minDim = minOf(v1.size, v2.size)

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in 0 until minDim) {
            val a = v1[i]
            val b = v2[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        if (normA <= 0.0 || normB <= 0.0) return 0.0
        val similarity = dotProduct / (sqrt(normA) * sqrt(normB))
        return similarity.coerceIn(-1.0, 1.0)
    }

    private fun parseIsoDateOrNow(isoString: String): Date {
        if (isoString.isBlank()) return Date()
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
        for (format in formats) {
            try {
                format.timeZone = TimeZone.getDefault()
                val parsed = format.parse(isoString.trim())
                if (parsed != null) return parsed
            } catch (_: Exception) {}
        }
        return Date()
    }

    private fun isFuzzyNameMatch(name1: String, name2: String): Boolean {
        val n1 = name1.lowercase().trim()
        val n2 = name2.lowercase().trim()
        if (n1 == n2) return true
        if (n1.contains(n2) || n2.contains(n1)) return true

        val tokens1 = n1.split(" ").filter { it.length > 2 }
        val tokens2 = n2.split(" ").filter { it.length > 2 }
        return tokens1.any { t1 -> tokens2.any { t2 -> t1 == t2 } }
    }
}
