package com.example.contacts

data class ContactState(
    val contacts: List<Contact> = emptyList(),
    val firstName: String = "",
    val lastName: String = "",
    val phoneNumber: String = "",
    val isAddingContact: Boolean = false,
    val isEditingContact: Boolean = false,
    val editingContactId: String? = null,
)
