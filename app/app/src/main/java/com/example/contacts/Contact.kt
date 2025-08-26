package com.example.contacts

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Contact (
    @PrimaryKey
    val id: String,
    val firstName : String,
    val lastName : String,
    val phoneNumber : String,
    val isSynced: Boolean = false,
    val isSoftDeleted: Boolean = false
)