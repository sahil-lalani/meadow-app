package com.example.contacts

// we define all the user actions here, like adding a contact, deleting one, etc

sealed interface ContactEvent {
    object SaveContact : ContactEvent
    data class SetFirstName(val firstName: String) : ContactEvent
    data class SetLastName(val lastName: String) : ContactEvent
    data class SetPhoneNumber(val phoneNumber: String) : ContactEvent
    object ShowDialog : ContactEvent
    object HideDialog : ContactEvent
    data class DeleteContact(val contact: Contact) : ContactEvent
    data class ShowEditDialog(val contact: Contact) : ContactEvent
    object SaveEditedContact : ContactEvent
}