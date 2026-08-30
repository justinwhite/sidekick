package com.cloudcrm.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

// ============================================================================
// GEMINI STRUCTURED OUTPUT CONTRACT (Native responseSchema)
// ============================================================================

/**
 * Root container for structured entity extraction from Gemini API.
 */
@Serializable
data class ExtractedEntitiesResult(
    @SerialName("extracted_entities")
    val extractedEntities: List<ExtractedEntity> = emptyList()
)

/**
 * An individual person/entity extracted from conversational notes.
 */
@Serializable
data class ExtractedEntity(
    @SerialName("full_name")
    val fullName: String = "",

    @SerialName("role_context")
    val roleContext: String = "",

    @SerialName("organization")
    val organization: String = "",

    @SerialName("interaction_summary")
    val interactionSummary: String = "",

    @SerialName("tags")
    val tags: List<String> = emptyList(),

    @SerialName("interaction_date_iso")
    val interactionDateIso: String = ""
)

// ============================================================================
// FIRESTORE CLOUD PERSISTENCE MODELS
// ============================================================================

/**
 * Top-level Contact document stored in the `contacts` Firestore collection.
 */
data class Contact(
    @DocumentId
    val id: String = "",

    @get:PropertyName("full_name")
    @set:PropertyName("full_name")
    var fullName: String = "",

    @get:PropertyName("role_context")
    @set:PropertyName("role_context")
    var roleContext: String = "",

    @get:PropertyName("organization")
    @set:PropertyName("organization")
    var organization: String = "",

    @get:PropertyName("tags")
    @set:PropertyName("tags")
    var tags: List<String> = emptyList(),

    @ServerTimestamp
    @get:PropertyName("updated_at")
    @set:PropertyName("updated_at")
    var updatedAt: Timestamp? = null
) {
    // No-arg constructor required for Firestore serialization
    constructor() : this("", "", "", "", emptyList(), null)

    fun toMap(): Map<String, Any?> = mapOf(
        "full_name" to fullName,
        "role_context" to roleContext,
        "organization" to organization,
        "tags" to tags,
        "updated_at" to (updatedAt ?: Timestamp.now())
    )
}

/**
 * Immutable interaction document stored in `contacts/{contactId}/interactions` subcollection.
 * Supports native Firestore vector search via high-dimensional embedding vector.
 */
data class Interaction(
    @DocumentId
    val id: String = "",

    @get:PropertyName("contact_id")
    @set:PropertyName("contact_id")
    var contactId: String = "",

    @get:PropertyName("contact_name")
    @set:PropertyName("contact_name")
    var contactName: String = "",

    @get:PropertyName("date")
    @set:PropertyName("date")
    var date: Timestamp = Timestamp.now(),

    @get:PropertyName("summary")
    @set:PropertyName("summary")
    var summary: String = "",

    @get:PropertyName("embedding_vector")
    @set:PropertyName("embedding_vector")
    var embeddingVector: List<Double> = emptyList()
) {
    // No-arg constructor required for Firestore serialization
    constructor() : this("", "", "", Timestamp.now(), "", emptyList())

    fun toMap(): Map<String, Any?> = mapOf(
        "contact_id" to contactId,
        "contact_name" to contactName,
        "date" to date,
        "summary" to summary,
        "embedding_vector" to embeddingVector
    )
}

// ============================================================================
// UI PRESENTATION & DIFF REVIEW MODELS
// ============================================================================

/**
 * State of an extracted entity being reviewed on Screen 2 (Interactive Diff).
 */
data class ExtractedEntityDiff(
    val id: String = UUID.randomUUID().toString(),
    val originalExtracted: ExtractedEntity,
    val matchedContact: Contact? = null,
    val isNewContact: Boolean = matchedContact == null,
    val editedFullName: String = originalExtracted.fullName,
    val editedRoleContext: String = originalExtracted.roleContext,
    val editedOrganization: String = originalExtracted.organization,
    val editedTags: List<String> = originalExtracted.tags,
    val editedInteractionSummary: String = originalExtracted.interactionSummary,
    val editedInteractionDateIso: String = originalExtracted.interactionDateIso,
    val isExpanded: Boolean = true,
    val isSynced: Boolean = false,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Represents an item in the chronological activity feed or semantic search results.
 */
data class TimelineItem(
    val interaction: Interaction,
    val contact: Contact? = null,
    val similarityScore: Double? = null
)

/**
 * Time filter bounds for the semantic timeline feed.
 */
enum class TimeRangeFilter(val label: String) {
    ALL("All Time"),
    LAST_7_DAYS("Last 7 Days"),
    THIS_MONTH("This Month")
}
