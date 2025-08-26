package com.example.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun EditContactDialog(
    state: ContactState,
    onEvent: (ContactEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            onEvent(ContactEvent.HideDialog)
        },
        title = { Text(text = "Edit Contact") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = state.firstName,
                    onValueChange = { onEvent(ContactEvent.SetFirstName(it)) },
                    placeholder = { Text(text = "First Name") }
                )
                TextField(
                    value = state.lastName,
                    onValueChange = { onEvent(ContactEvent.SetLastName(it)) },
                    placeholder = { Text(text = "Last Name") }
                )
                TextField(
                    value = state.phoneNumber,
                    onValueChange = { onEvent(ContactEvent.SetPhoneNumber(it)) },
                    placeholder = { Text(text = "Phone Number") }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onEvent(ContactEvent.SaveEditedContact) }) {
                Text("Save")
            }
        }
    )
}


