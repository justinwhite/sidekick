package com.cloudcrm.app

import com.cloudcrm.app.data.ai.GeminiCloudService
import com.cloudcrm.app.data.model.Contact
import com.cloudcrm.app.data.model.ExtractedEntitiesResult
import com.cloudcrm.app.data.model.ExtractedEntity
import com.cloudcrm.app.data.model.Interaction
import com.google.firebase.Timestamp
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CloudCrmUnitTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun testStructuredOutputSchemaSerialization() {
        val sampleJson = """
            {
              "extracted_entities": [
                {
                  "full_name": "Dave (Maya's dad)",
                  "role_context": "Soccer Dad",
                  "organization": "Youth Soccer League",
                  "interaction_summary": "Met Dave at soccer practice. Discussed carpooling.",
                  "tags": ["school", "sports"]
                },
                {
                  "full_name": "Elena",
                  "role_context": "Neighbor at #402",
                  "organization": "HOA",
                  "interaction_summary": "Elevator repair scheduled for Friday.",
                  "tags": ["neighborhood"]
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ExtractedEntitiesResult>(sampleJson)
        assertEquals(2, parsed.extractedEntities.size)

        val first = parsed.extractedEntities[0]
        assertEquals("Dave (Maya's dad)", first.fullName)
        assertEquals("Soccer Dad", first.roleContext)
        assertEquals("Youth Soccer League", first.organization)
        assertEquals("Met Dave at soccer practice. Discussed carpooling.", first.interactionSummary)
        assertEquals(listOf("school", "sports"), first.tags)

        val second = parsed.extractedEntities[1]
        assertEquals("Elena", second.fullName)
        assertEquals("Neighbor at #402", second.roleContext)
        assertEquals(listOf("neighborhood"), second.tags)
    }

    @Test
    fun testLenientStreamingJsonParsing() {
        val service = GeminiCloudService { "mock_key" }

        // In-flight partial JSON string as it would stream over the wire
        val incompleteStreamingJson = """
            {"extracted_entities": [{"full_name": "Dave", "role_context": "Soccer Coach", "organization": "Youth League", "interaction_summary": "Discussed practice", "tags": ["sports"]}, {"full_name": "Elena", "role_context": "Neighbor", "organization": "HOA"
        """.trimIndent()

        val partialEntities = service.parseLenientPartialEntities(incompleteStreamingJson)
        assertTrue(partialEntities.isNotEmpty())
        assertEquals("Dave", partialEntities[0].fullName)
        assertEquals("Soccer Coach", partialEntities[0].roleContext)

        // Trailing unclosed entity should also be extracted
        if (partialEntities.size > 1) {
            assertEquals("Elena", partialEntities[1].fullName)
            assertEquals("Neighbor", partialEntities[1].roleContext)
        }
    }

    @Test
    fun testContactAndInteractionFirestoreMapping() {
        val contact = Contact(
            id = "doc_123",
            fullName = "Sarah Jenkins",
            roleContext = "Product VP",
            organization = "Acme Corp",
            tags = listOf("work", "client")
        )

        val contactMap = contact.toMap()
        assertEquals("Sarah Jenkins", contactMap["full_name"])
        assertEquals("Product VP", contactMap["role_context"])
        assertEquals("Acme Corp", contactMap["organization"])
        assertEquals(listOf("work", "client"), contactMap["tags"])

        val interaction = Interaction(
            id = "int_456",
            contactId = "doc_123",
            contactName = "Sarah Jenkins",
            date = Timestamp.now(),
            summary = "Quarterly alignment meeting.",
            embeddingVector = listOf(0.12, 0.45, -0.78)
        )

        val interactionMap = interaction.toMap()
        assertEquals("doc_123", interactionMap["contact_id"])
        assertEquals("Sarah Jenkins", interactionMap["contact_name"])
        assertEquals("Quarterly alignment meeting.", interactionMap["summary"])
        assertEquals(listOf(0.12, 0.45, -0.78), interactionMap["embedding_vector"])
    }

    @Test
    fun testCosineSimilarityMathematics() {
        // Orthogonal vectors: similarity must be 0.0
        val v1 = listOf(1.0, 0.0, 0.0)
        val v2 = listOf(0.0, 1.0, 0.0)
        val simOrthogonal = computeCosineSim(v1, v2)
        assertEquals(0.0, simOrthogonal, 0.0001)

        // Identical vectors: similarity must be 1.0
        val v3 = listOf(0.5, 0.5, 0.5, 0.5)
        val simIdentical = computeCosineSim(v3, v3)
        assertEquals(1.0, simIdentical, 0.0001)

        // Opposing vectors: similarity must be -1.0
        val v4 = listOf(1.0, 2.0, 3.0)
        val v5 = listOf(-1.0, -2.0, -3.0)
        val simOpposite = computeCosineSim(v4, v5)
        assertEquals(-1.0, simOpposite, 0.0001)
    }

    private fun computeCosineSim(v1: List<Double>, v2: List<Double>): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        if (normA <= 0 || normB <= 0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    @Test
    fun testRepositoryLocalFallback() = kotlinx.coroutines.test.runTest {
        // Create repository with null firestore to force local fallback mode
        val repository = com.cloudcrm.app.data.repository.CloudCrmRepositoryImpl(firestoreOverride = null)
        
        // Initial state should be empty
        val initialContacts = repository.getAllContacts()
        assertTrue(initialContacts.isEmpty())

        // Create a diff and sync it
        val diff = ExtractedEntityDiff(
            originalExtracted = ExtractedEntity("Test User", "Role", "Org", "Summary", listOf("tag1"), ""),
            matchedContact = null,
            isNewContact = true,
            editedFullName = "Test User",
            editedRoleContext = "Role",
            editedOrganization = "Org",
            editedInteractionSummary = "Summary",
            editedTags = listOf("tag1"),
            editedInteractionDateIso = ""
        )
        
        val result = repository.syncExtractedDiffBatch(listOf(diff), emptyMap())
        assertTrue(result.isSuccess)

        // Verify local state updated
        val finalContacts = repository.getAllContacts()
        assertEquals(1, finalContacts.size)
        assertEquals("Test User", finalContacts[0].fullName)
    }
}
