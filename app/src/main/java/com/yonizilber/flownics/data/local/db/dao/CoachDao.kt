package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yonizilber.flownics.data.local.db.entity.CoachConversationEntity
import com.yonizilber.flownics.data.local.db.entity.CoachMessageEntity
import com.yonizilber.flownics.data.local.db.entity.CoachNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CoachDao {
    @Insert
    suspend fun insertConversation(conversation: CoachConversationEntity): Long

    @Query("SELECT * FROM coach_conversations ORDER BY updatedAtMillis DESC")
    fun observeConversations(): Flow<List<CoachConversationEntity>>

    @Query("SELECT * FROM coach_conversations WHERE id = :id")
    suspend fun conversation(id: Long): CoachConversationEntity?

    @Query("SELECT * FROM coach_conversations ORDER BY updatedAtMillis DESC LIMIT 1")
    suspend fun latestConversation(): CoachConversationEntity?

    @Query("UPDATE coach_conversations SET title = :title, updatedAtMillis = :atMillis WHERE id = :id")
    suspend fun touchConversation(id: Long, title: String, atMillis: Long)

    @Query("DELETE FROM coach_conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Insert
    suspend fun insertMessage(message: CoachMessageEntity): Long

    @Query("SELECT * FROM coach_messages WHERE conversationId = :conversationId ORDER BY id")
    fun observeMessages(conversationId: Long): Flow<List<CoachMessageEntity>>

    @Query("SELECT * FROM coach_messages WHERE conversationId = :conversationId ORDER BY id")
    suspend fun messages(conversationId: Long): List<CoachMessageEntity>

    @Query("SELECT * FROM coach_messages WHERE id = :id")
    suspend fun message(id: Long): CoachMessageEntity?

    @Query("UPDATE coach_messages SET dataJson = :dataJson WHERE id = :id")
    suspend fun updateMessageData(id: Long, dataJson: String)

    @Query("DELETE FROM coach_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Insert
    suspend fun insertNote(note: CoachNoteEntity): Long

    @Query("SELECT * FROM coach_notes ORDER BY createdAtMillis")
    fun observeNotes(): Flow<List<CoachNoteEntity>>

    @Query("SELECT * FROM coach_notes ORDER BY createdAtMillis")
    suspend fun notes(): List<CoachNoteEntity>

    @Query("UPDATE coach_notes SET text = :text WHERE id = :id")
    suspend fun updateNote(id: Long, text: String)

    @Query("DELETE FROM coach_notes WHERE id = :id")
    suspend fun deleteNote(id: Long): Int
}
