package com.cloudcrm.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.cloudcrm.app.data.model.Contact
import com.cloudcrm.app.data.model.Interaction
import com.cloudcrm.app.data.model.TimelineItem
import com.cloudcrm.app.ui.TimelineFeedCard
import com.google.firebase.Timestamp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["androidx.loader.content"], sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SemanticTimelineScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLongPressDeletesInteraction() {
        var deleteCalled = false
        val contact = Contact(id = "c1", fullName = "Test Contact")
        val interaction = Interaction(
            id = "i1",
            contactId = "c1",
            contactName = "Test Contact",
            date = Timestamp.now(),
            summary = "Test interaction summary",
            embeddingVector = emptyList()
        )
        val timelineItem = TimelineItem(interaction, contact)

        composeTestRule.setContent {
            var interactionToDelete by remember { mutableStateOf<TimelineItem?>(null) }
            
            TimelineFeedCard(
                timelineItem = timelineItem,
                onLongClick = { interactionToDelete = timelineItem }
            )
            
            if (interactionToDelete != null) {
                AlertDialog(
                    onDismissRequest = { interactionToDelete = null },
                    title = { Text("Delete Interaction") },
                    text = { Text("Are you sure you want to delete this interaction?") },
                    confirmButton = {
                        TextButton(onClick = { 
                            deleteCalled = true 
                            interactionToDelete = null
                        }) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { interactionToDelete = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        // Initially dialog is not shown
        composeTestRule.onNodeWithText("Delete Interaction").assertDoesNotExist()

        // Long press the card
        composeTestRule.onNodeWithText("Test interaction summary").performTouchInput {
            longClick()
        }

        // Dialog should be shown
        composeTestRule.onNodeWithText("Delete Interaction").assertIsDisplayed()

        // Click delete
        composeTestRule.onNodeWithText("Delete").performClick()

        // Assert delete callback was called
        assert(deleteCalled)
    }
}
