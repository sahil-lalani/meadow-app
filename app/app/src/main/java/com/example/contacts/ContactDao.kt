package com.example.contacts

import androidx.room.Dao
import androidx.room.Upsert
import androidx.room.Delete
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Upsert
    suspend fun insertContact(contact: Contact)

    @Delete
    suspend fun deleteContact(contact: Contact)

    @Query("SELECT * FROM contact WHERE isSoftDeleted = 0")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("DELETE FROM contact WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM contact WHERE isSynced = 0 OR isSoftDeleted = 1")
    suspend fun getPendingSync(): List<Contact>

    @Query("UPDATE contact SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE contact SET isSoftDeleted = 1, isSynced = 0 WHERE id = :id")
    suspend fun softDelete(id: String)

    @Query("SELECT * FROM contact WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Contact?

    @Query("UPDATE contact SET pendingChange = :change WHERE id = :id")
    suspend fun markPendingChange(id: String, change: String?)

}