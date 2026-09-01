package com.cloudcrm.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudcrm.app.CloudCrmApplication
import com.cloudcrm.app.data.ai.GeminiCloudService
import com.cloudcrm.app.data.ai.StreamingExtractionUpdate
import com.cloudcrm.app.data.model.Contact
import com.cloudcrm.app.data.model.ExtractedEntity
import com.cloudcrm.app.data.model.ExtractedEntityDiff
import com.cloudcrm.app.data.model.TimeRangeFilter
import com.cloudcrm.app.data.model.TimelineItem
import com.cloudcrm.app.data.repository.CloudCrmRepository
import com.cloudcrm.app.data.repository.CloudCrmRepositoryImpl
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

private const val TAG = "CloudCrmViewModel"

// ============================================================================
// UI STATES
// ============================================================================

/**
 * UI State for Screen 1: Quick Capture Inbox
 */
data class CaptureUiState(
    val noteInput: String = "",
    val isExtracting: Boolean = false,
    val errorMessage: String? = null
)

/**
 * UI State for Screen 2: Interactive Diff & Review (Streaming View)
 */
data class StreamingDiffUiState(
    val isStreaming: Boolean = false,
    val rawStreamBuffer: String = "",
    val diffCards: List<ExtractedEntityDiff> = emptyList(),
    val isSyncing: Boolean = false,
    val isSyncCompleted: Boolean = false,
    val syncedCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * UI State for Screen 3: Semantic Timeline Feed & Search
 */
data class SemanticTimelineUiState(
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val isVectorSearchActive: Boolean = false,
    val selectedTimeFilter: TimeRangeFilter = TimeRangeFilter.ALL,
    val selectedTags: Set<String> = emptySet(),
    val availableTags: List<String> = listOf("school", "work", "neighborhood", "sports", "urgent", "client"),
    val timelineItems: List<TimelineItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)


data class ContactDetailUiState(
    val isLoading: Boolean = false,
    val contact: Contact? = null,
    val interactions: List<TimelineItem> = emptyList(),
    val errorMessage: String? = null
)


data class ContactsListUiState(
    val isLoading: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    val errorMessage: String? = null
)
// ============================================================================
// VIEWMODEL IMPLEMENTATION

// ============================================================================

class CloudCrmViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: CloudCrmRepository = CloudCrmRepositoryImpl(),
    private val aiService: GeminiCloudService = GeminiCloudService {
        CloudCrmApplication.getApiKey(application)
    }
) : AndroidViewModel(application) {

    // Screen 1 State
    private val _captureState = MutableStateFlow(CaptureUiState())
    val captureState: StateFlow<CaptureUiState> = _captureState.asStateFlow()

    // Screen 2 State
    private val _diffState = MutableStateFlow(StreamingDiffUiState())
    val diffState: StateFlow<StreamingDiffUiState> = _diffState.asStateFlow()

    // Screen 3 State

    private val _timelineState = MutableStateFlow(SemanticTimelineUiState())
    val timelineState: StateFlow<SemanticTimelineUiState> = _timelineState.asStateFlow()


    private val _contactDetailState = MutableStateFlow(ContactDetailUiState())
    val contactDetailState: StateFlow<ContactDetailUiState> = _contactDetailState.asStateFlow()

    private val _contactsListState = MutableStateFlow(ContactsListUiState())
    val contactsListState: StateFlow<ContactsListUiState> = _contactsListState.asStateFlow()

    private val _navigateToDiffEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToDiffEvent: SharedFlow<Unit> = _navigateToDiffEvent.asSharedFlow()

    private var extractionJob: Job? = null
    private var searchJob: Job? = null
    private var timelineJob: Job? = null

    init {
        restartTimelineObserver()
    }

    // ========================================================================
    // SCREEN 1: CAPTURE ACTIONS
    // ========================================================================

    fun onNoteInputChanged(newText: String) {
        _captureState.update { it.copy(noteInput = newText, errorMessage = null) }
    }

    fun onSelectSamplePrompt(sample: String) {
        _captureState.update { it.copy(noteInput = sample, errorMessage = null) }
    }

    fun clearCaptureInput() {
        _captureState.update { it.copy(noteInput = "") }
    }

    // ========================================================================
    // SCREEN 2: STREAMING DIFF & REVIEW ACTIONS
    // ========================================================================

    /**
     * Initiates streaming structured extraction using the Google GenAI SDK.
     */
    fun startStreamingExtraction(noteText: String = _captureState.value.noteInput) {
        if (noteText.isBlank()) return

        extractionJob?.cancel()
        _diffState.update {
            StreamingDiffUiState(
                isStreaming = true,
                rawStreamBuffer = "",
                diffCards = emptyList(),
                isSyncing = false,
                isSyncCompleted = false,
                errorMessage = null
            )
        }

        Log.d(TAG, "startStreamingExtraction started for note: $noteText")
        extractionJob = viewModelScope.launch {
            try {
                aiService.streamExtractEntities(noteText).collect { update ->
                    Log.d(TAG, "Extraction update received: ${update::class.simpleName}")
                    when (update) {
                        is StreamingExtractionUpdate.InFlight -> {
                            handleStreamingInFlight(update.rawCumulativeText, update.partialEntities)
                        }
                        is StreamingExtractionUpdate.Success -> {
                            handleStreamingSuccess(update.result.extractedEntities, update.rawJson)
                        }
                        is StreamingExtractionUpdate.Error -> {
                            Log.e(TAG, "Extraction error update: ${update.message}")
                            _diffState.update {
                                it.copy(
                                    isStreaming = false,
                                    errorMessage = update.message
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extraction exception: ${e.message}", e)
                _diffState.update {
                    it.copy(
                        isStreaming = false,
                        errorMessage = "Streaming error: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Triggers multimodal streaming extraction directly from recorded voice audio.
     */
    fun startStreamingAudioExtraction(audioBytes: ByteArray) {
        if (audioBytes.isEmpty()) return

        extractionJob?.cancel()
        _diffState.update {
            StreamingDiffUiState(
                isStreaming = true,
                rawStreamBuffer = "",
                diffCards = emptyList(),
                isSyncing = false,
                isSyncCompleted = false,
                errorMessage = null
            )
        }

        Log.d(TAG, "startStreamingAudioExtraction started with ${audioBytes.size} bytes")
        extractionJob = viewModelScope.launch {
            try {
                aiService.streamExtractEntitiesFromAudio(audioBytes).collect { update ->
                    when (update) {
                        is StreamingExtractionUpdate.InFlight -> {
                            handleStreamingInFlight(update.rawCumulativeText, update.partialEntities)
                        }
                        is StreamingExtractionUpdate.Success -> {
                            handleStreamingSuccess(update.result.extractedEntities, update.rawJson)
                        }
                        is StreamingExtractionUpdate.Error -> {
                            Log.e(TAG, "Extraction error received: ${update.message}")
                            _diffState.update {
                                it.copy(
                                    isStreaming = false,
                                    errorMessage = update.message
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio extraction exception: ${e.message}", e)
                _diffState.update {
                    it.copy(
                        isStreaming = false,
                        errorMessage = "Audio extraction error: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Triggers multimodal streaming extraction directly from a shared image (e.g. screenshot).
     */
    fun startStreamingImageExtraction(imageBytes: ByteArray, mimeType: String) {
        if (imageBytes.isEmpty()) return

        extractionJob?.cancel()
        _diffState.update {
            StreamingDiffUiState(
                isStreaming = true,
                rawStreamBuffer = "",
                diffCards = emptyList(),
                isSyncing = false,
                isSyncCompleted = false,
                errorMessage = null
            )
        }

        Log.d(TAG, "startStreamingImageExtraction started with ${imageBytes.size} bytes")
        extractionJob = viewModelScope.launch {
            try {
                aiService.streamExtractEntitiesFromImage(imageBytes, mimeType).collect { update ->
                    when (update) {
                        is StreamingExtractionUpdate.InFlight -> {
                            handleStreamingInFlight(update.rawCumulativeText, update.partialEntities)
                        }
                        is StreamingExtractionUpdate.Success -> {
                            handleStreamingSuccess(update.result.extractedEntities, update.rawJson)
                        }
                        is StreamingExtractionUpdate.Error -> {
                            Log.e(TAG, "Image extraction error received: ${update.message}")
                            _diffState.update {
                                it.copy(
                                    isStreaming = false,
                                    errorMessage = update.message
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image extraction exception: ${e.message}", e)
                _diffState.update {
                    it.copy(
                        isStreaming = false,
                        errorMessage = "Image extraction error: ${e.localizedMessage}"
                    )
                }
            }
        }
        
        _navigateToDiffEvent.tryEmit(Unit)
    }

    private suspend fun handleStreamingInFlight(rawText: String, entities: List<ExtractedEntity>) {
        val currentCards = _diffState.value.diffCards.toMutableList()

        entities.forEachIndexed { index, extracted ->
            if (index < currentCards.size) {
                // Update existing card without overwriting user's manual edits
                val existing = currentCards[index]
                currentCards[index] = existing.copy(
                    editedFullName = if (existing.editedFullName == existing.originalExtracted.fullName) extracted.fullName else existing.editedFullName,
                    editedRoleContext = if (existing.editedRoleContext == existing.originalExtracted.roleContext) extracted.roleContext else existing.editedRoleContext,
                    editedOrganization = if (existing.editedOrganization == existing.originalExtracted.organization) extracted.organization else existing.editedOrganization,
                    editedInteractionSummary = if (existing.editedInteractionSummary == existing.originalExtracted.interactionSummary) extracted.interactionSummary else existing.editedInteractionSummary,
                    editedTags = if (existing.editedTags == existing.originalExtracted.tags) extracted.tags else existing.editedTags,
                    editedInteractionDateIso = if (existing.editedInteractionDateIso == existing.originalExtracted.interactionDateIso) extracted.interactionDateIso else existing.editedInteractionDateIso
                )
            } else {
                // Check if contact already exists in Firestore
                val match = repository.findMatchingContact(extracted.fullName)
                val newDiff = ExtractedEntityDiff(
                    originalExtracted = extracted,
                    matchedContact = match,
                    isNewContact = match == null,
                    editedFullName = extracted.fullName,
                    editedRoleContext = extracted.roleContext.ifBlank { match?.roleContext ?: "" },
                    editedOrganization = extracted.organization.ifBlank { match?.organization ?: "" },
                    editedTags = extracted.tags.ifEmpty { match?.tags ?: emptyList() },
                    editedInteractionSummary = extracted.interactionSummary,
                    editedInteractionDateIso = extracted.interactionDateIso
                )
                currentCards.add(newDiff)
            }
        }

        _diffState.update {
            it.copy(
                rawStreamBuffer = rawText,
                diffCards = currentCards
            )
        }
    }

    private suspend fun handleStreamingSuccess(finalEntities: List<ExtractedEntity>, finalJson: String) {
        val processedDiffs = mutableListOf<ExtractedEntityDiff>()

        for (entity in finalEntities) {
            val match = repository.findMatchingContact(entity.fullName)
            processedDiffs.add(
                ExtractedEntityDiff(
                    originalExtracted = entity,
                    matchedContact = match,
                    isNewContact = match == null,
                    editedFullName = entity.fullName,
                    editedRoleContext = entity.roleContext.ifBlank { match?.roleContext ?: "" },
                    editedOrganization = entity.organization.ifBlank { match?.organization ?: "" },
                    editedTags = (entity.tags + (match?.tags ?: emptyList())).distinct(),
                    editedInteractionSummary = entity.interactionSummary,
                    editedInteractionDateIso = entity.interactionDateIso
                )
            )
        }

        _diffState.update {
            it.copy(
                isStreaming = false,
                rawStreamBuffer = finalJson,
                diffCards = processedDiffs
            )
        }
    }

    fun updateDiffCard(cardId: String, transform: (ExtractedEntityDiff) -> ExtractedEntityDiff) {
        _diffState.update { state ->
            val updated = state.diffCards.map { card ->
                if (card.id == cardId) transform(card) else card
            }
            state.copy(diffCards = updated)
        }
    }

    fun addTagToCard(cardId: String, newTag: String) {
        val cleanTag = newTag.trim().lowercase().removePrefix("#")
        if (cleanTag.isBlank()) return

        updateDiffCard(cardId) { card ->
            if (!card.editedTags.contains(cleanTag)) {
                card.copy(editedTags = card.editedTags + cleanTag)
            } else card
        }
    }

    fun removeTagFromCard(cardId: String, tagToRemove: String) {
        updateDiffCard(cardId) { card ->
            card.copy(editedTags = card.editedTags.filter { it != tagToRemove })
        }
    }

    fun removeDiffCard(cardId: String) {
        _diffState.update { state ->
            val updated = state.diffCards.filter { it.id != cardId }
            state.copy(diffCards = updated)
        }
    }

    /**
     * Generates vector embeddings for each interaction summary and atomically commits
     * batch writes to Firebase Firestore.
     */
    fun confirmAndSyncToCloud() {
        val cardsToSync = _diffState.value.diffCards
        if (cardsToSync.isEmpty()) return

        viewModelScope.launch {
            _diffState.update { it.copy(isSyncing = true, errorMessage = null) }

            try {
                // 1. Generate text-embedding-004 vector embeddings in parallel
                val embeddingsMap = mutableMapOf<String, List<Double>>()
                for (card in cardsToSync) {
                    val textToEmbed = "${card.editedFullName}: ${card.editedInteractionSummary}"
                    val vector = aiService.generateEmbedding(textToEmbed)
                    embeddingsMap[card.id] = vector
                }

                // 2. Commit atomic batch write to Firestore
                val result = repository.syncExtractedDiffBatch(cardsToSync, embeddingsMap)

                if (result.isSuccess) {
                    _diffState.update {
                        it.copy(
                            isSyncing = false,
                            isSyncCompleted = true,
                            syncedCount = cardsToSync.size
                        )
                    }
                    // Reset capture note on success
                    clearCaptureInput()
                } else {
                    _diffState.update {
                        it.copy(
                            isSyncing = false,
                            errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Sync to Cloud failed."
                        )
                    }
                }
            } catch (e: Exception) {
                _diffState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = "Sync exception: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    // ========================================================================
    // SCREEN 3: SEMANTIC TIMELINE & SEARCH ACTIONS
    // ========================================================================

    fun restartTimelineObserver() {
        timelineJob?.cancel()
        timelineJob = viewModelScope.launch {
            repository.getTimelineFeedFlow().collect { rawItems ->
                // If not currently performing a semantic query, update feed with active filters
                if (_timelineState.value.searchQuery.isBlank()) {
                    val filtered = applyLocalFilters(
                        items = rawItems,
                        timeFilter = _timelineState.value.selectedTimeFilter,
                        tags = _timelineState.value.selectedTags
                    )
                    _timelineState.update { it.copy(timelineItems = filtered) }
                }
            }
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        _timelineState.update { it.copy(searchQuery = newQuery) }
        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _timelineState.update { it.copy(isVectorSearchActive = false) }
            refreshTimelineFeed()
        } else {
            searchJob = viewModelScope.launch {
                // Debounce search input
                kotlinx.coroutines.delay(350)
                executeSemanticVectorSearch(newQuery)
            }
        }
    }

    /**
     * Converts query text into a vector embedding and executes semantic similarity search.
     */
    fun executeSemanticVectorSearch(query: String = _timelineState.value.searchQuery) {
        if (query.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _timelineState.update { it.copy(isSearching = true, isVectorSearchActive = true, errorMessage = null) }

            try {
                // Generate embedding for query via text-embedding-004
                val queryVector = aiService.generateEmbedding(query)

                val results = repository.searchInteractionsSemantic(
                    queryVector = queryVector,
                    limit = 25,
                    timeRangeFilter = _timelineState.value.selectedTimeFilter,
                    selectedTags = _timelineState.value.selectedTags
                )

                _timelineState.update {
                    it.copy(
                        timelineItems = results,
                        isSearching = false
                    )
                }
            } catch (e: Exception) {
                _timelineState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = "Semantic search error: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun setTimeFilter(filter: TimeRangeFilter) {
        _timelineState.update { it.copy(selectedTimeFilter = filter) }
        if (_timelineState.value.searchQuery.isNotBlank()) {
            executeSemanticVectorSearch()
        } else {
            refreshTimelineFeed()
        }
    }

    fun toggleTagFilter(tag: String) {
        val cleanTag = tag.trim().lowercase().removePrefix("#")
        _timelineState.update { current ->
            val updatedTags = if (current.selectedTags.contains(cleanTag)) {
                current.selectedTags - cleanTag
            } else {
                current.selectedTags + cleanTag
            }
            current.copy(selectedTags = updatedTags)
        }

        if (_timelineState.value.searchQuery.isNotBlank()) {
            executeSemanticVectorSearch()
        } else {
            refreshTimelineFeed()
        }
    }

    fun clearAllFilters() {
        _timelineState.update {
            it.copy(
                searchQuery = "",
                isVectorSearchActive = false,
                selectedTimeFilter = TimeRangeFilter.ALL,
                selectedTags = emptySet()
            )
        }
        refreshTimelineFeed()
    }

    fun refreshTimelineFeed() {
        viewModelScope.launch {
            _timelineState.update { it.copy(isRefreshing = true) }
            try {
                val allContacts = repository.getAllContacts().associateBy { it.id }
                // Re-fetch semantic search if active or reload local flow
                if (_timelineState.value.searchQuery.isNotBlank()) {
                    executeSemanticVectorSearch()
                }
            } catch (_: Exception) {}
            _timelineState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun applyLocalFilters(
        items: List<TimelineItem>,
        timeFilter: TimeRangeFilter,
        tags: Set<String>
    ): List<TimelineItem> {
        val now = Date()
        val cutoffDate: Date? = when (timeFilter) {
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

        return items.filter { item ->
            val passesTime = cutoffDate == null || item.interaction.date.toDate().after(cutoffDate)
            val passesTags = tags.isEmpty() || tags.any { tag ->
                item.contact?.tags?.any { it.equals(tag, ignoreCase = true) } == true ||
                        item.interaction.summary.contains(tag, ignoreCase = true)
            }
            passesTime && passesTags
        }
    }

    fun loadContactDetail(contactId: String) {
        viewModelScope.launch {
            _contactDetailState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val contact = repository.getContactById(contactId)
                if (contact != null) {
                    repository.getTimelineFeedFlow().collect { allItems ->
                        val related = allItems.filter { item ->
                            item.interaction.contactId == contact.id || 
                            item.interaction.summary.contains(contact.fullName, ignoreCase = true)
                        }
                        _contactDetailState.update { 
                            it.copy(isLoading = false, contact = contact, interactions = related)
                        }
                    }
                } else {
                    _contactDetailState.update { it.copy(isLoading = false, contact = null) }
                }
            } catch (e: Exception) {
                _contactDetailState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun loadAllContacts() {
        viewModelScope.launch {
            _contactsListState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val allContacts = repository.getAllContacts()
                _contactsListState.update { it.copy(isLoading = false, contacts = allContacts) }
            } catch (e: Exception) {
                _contactsListState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun updateContact(contactId: String, newFullName: String, newRole: String, newOrg: String, newTags: List<String>) {
        viewModelScope.launch {
            val contact = repository.getContactById(contactId) ?: return@launch
            val updatedContact = contact.copy(
                fullName = newFullName,
                roleContext = newRole,
                organization = newOrg,
                tags = newTags
            )
            val result = repository.updateContact(updatedContact)
            if (result.isSuccess) {
                // Refresh views
                loadContactDetail(contactId)
                loadAllContacts()
                refreshTimelineFeed()
            } else {
                _contactDetailState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun deleteInteraction(interactionId: String) {
        viewModelScope.launch {
            val result = repository.deleteInteraction(interactionId)
            if (result.isSuccess) {
                refreshTimelineFeed()
            } else {
                _timelineState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }
}
