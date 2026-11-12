package com.yonizilber.flownics.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One conversation with the coach (v7). Kept on the device until the user deletes it. */
@Entity(tableName = "coach_conversations", indices = [Index("updatedAtMillis")])
data class CoachConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * One entry in a conversation (v7): the user's message, the coach's answer, a step it took (a
 * tool it ran), a proposal waiting for the user, or a note it saved to memory. [kind] is a
 * [com.yonizilber.flownics.domain.coach.CoachEntryKind] name; [dataJson] holds what that kind needs.
 */
@Entity(
    tableName = "coach_messages",
    foreignKeys = [
        ForeignKey(
            entity = CoachConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class CoachMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val atMillis: Long,
    val kind: String,
    val text: String,
    val dataJson: String,
)

/** Something the coach chose to remember about the user (v7). Shown, editable, deletable. */
@Entity(tableName = "coach_notes")
data class CoachNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAtMillis: Long,
)
