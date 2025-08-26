package com.example.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ContactViewModel(
    private val dao: ContactDao
) : ViewModel() {
    private val _contacts = dao.getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _state = MutableStateFlow(ContactState())

    // wait 5 seconds before shutting down the flow. why 5 seconds? so that if we rotate the screen or do smth small, we're still observing
    val state = combine(_state, _contacts) { state, contacts ->
        state.copy(
            contacts = contacts
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ContactState()
    )

    private val webSocketClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var reconnectDelayMs = 5000L
    //private val maxReconnectDelayMs = 30000L
    private var isReconnecting = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    init {
        connectWebSocket()
    }

    private fun connectWebSocket() {
        val request = Request.Builder()
            .url("ws://10.0.2.2:3000")
            //.url("wss://meadow-app-production.up.railway.app")
            .build()
        webSocket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d("WS", "connected: code=${response.code}")
                //reconnectDelayMs = 1000L
                reconnectAttempts = 0
                // Attempt to sync any pending local changes when we reconnect
                viewModelScope.launch {
                    trySyncPending()
                    // Also pull server backlog and ACK
                    try {
                        val pending = ServerApi.getPending()
                        for (c in pending) {
                            if (c.isSoftDeleted) {
                                dao.deleteById(c.id)
                                ServerApi.ackDeleted(c.id)
                            } else {
                                dao.insertContact(c.copy(isSynced = true, isSoftDeleted = false))
                                ServerApi.ackCreated(c.id)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WS", "closing: code=$code reason=$reason")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WS", "closed: code=$code reason=$reason")
                scheduleReconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e("WS", "failure: ${t.message}", t)
                scheduleReconnect()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WS", "message: $text")
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "server.hello" -> {
                            Log.d("WS", "hello from server")
                        }
                        "contact.created" -> {
                            val payload = obj.getJSONObject("payload")
                            val contact = Contact(
                                id = payload.getString("id"),
                                firstName = payload.getString("firstName"),
                                lastName = payload.getString("lastName"),
                                phoneNumber = payload.getString("phoneNumber"),
                                isSynced = true,
                                isSoftDeleted = false
                            )
                            viewModelScope.launch {
                                Log.d("WS", "upserting contact ${contact.id}")
                                dao.insertContact(contact)
                                // ACK that we processed the create from server
                                viewModelScope.launch {
                                    ServerApi.ackCreated(contact.id)
                                }
                            }
                        }
                        "contact.deleted" -> {
                            val payload = obj.getJSONObject("payload")
                            val id = payload.getString("id")
                            viewModelScope.launch {
                                Log.d("WS", "deleting contact $id")
                                dao.deleteById(id)
                                // ACK that we processed the delete from server
                                viewModelScope.launch {
                                    ServerApi.ackDeleted(id)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            }
        })
    }

    private suspend fun trySyncPending() {
        val pending = dao.getPendingSync()
        for (c in pending) {
            if (c.isSoftDeleted) {
                val ok = ServerApi.deleteContact(c.id)
                if (ok) {
                    dao.deleteById(c.id)
                }
            } else {
                val ok = ServerApi.createContact(c)
                if (ok) {
                    dao.markSynced(c.id)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.d("WS", "max reconnect attempts reached; stopping auto-reconnect")
            return
        }
        isReconnecting = true
        //val delayNow = reconnectDelayMs
        Log.d("WS", "reconnecting attempt ${reconnectAttempts + 1}/${maxReconnectAttempts} in ${reconnectDelayMs}ms")
        viewModelScope.launch {
//            delay(delayNow)
            delay(reconnectDelayMs)
            isReconnecting = false
            reconnectAttempts += 1
            connectWebSocket()
            // reconnectDelayMs = min(reconnectDelayMs * 2, maxReconnectDelayMs)
        }
    }

//    override fun onCleared() {
//        super.onCleared()
//        Log.d("WS", "cancelling websocket")
//        webSocket?.cancel()
//    }

    fun onEvent(event: ContactEvent) {
        when(event) {
            is ContactEvent.DeleteContact -> {
                viewModelScope.launch {
                    // Soft delete locally so UI hides it, and queue for sync
                    dao.softDelete(event.contact.id)
                    // Fire-and-forget server sync; local is source of truth
                    viewModelScope.launch {
                        if (ServerApi.deleteContact(event.contact.id)) {
                            // Remove permanently once server confirms
                            dao.deleteById(event.contact.id)
                        }
                    }
                }

            }
            ContactEvent.HideDialog -> {
                _state.update{
                    it.copy(
                        isAddingContact = false,
                        isEditingContact = false,
                        editingContactId = null
                    )
                }
            }
            ContactEvent.SaveContact -> {
                val firstName = state.value.firstName
                val lastName = state.value.lastName
                val phoneNumber = state.value.phoneNumber

                if (firstName.isBlank() || lastName.isBlank() || phoneNumber.isBlank()) {
                    return
                }

                val contact = Contact(
                    id = UUID.randomUUID().toString(),
                    firstName = firstName,
                    lastName = lastName,
                    phoneNumber = phoneNumber,
                    isSynced = false,
                    isSoftDeleted = false
                )
                viewModelScope.launch {
                    dao.insertContact(contact)
                    // Fire-and-forget server sync; local is source of truth
                    viewModelScope.launch {
                        if (ServerApi.createContact(contact)) {
                            dao.markSynced(contact.id)
                        }
                    }
                }

                _state.update {
                    it.copy(
                        isAddingContact = false,
                        firstName = "",
                        lastName = "",
                        phoneNumber = ""
                    )
                }
            }

            is ContactEvent.SetFirstName -> {
                _state.update {
                    it.copy(
                        firstName = event.firstName
                    )
                }
            }
            is ContactEvent.SetLastName -> {
                _state.update {
                    it.copy(
                        lastName = event.lastName
                    )
                }
            }
            is ContactEvent.SetPhoneNumber -> {
                _state.update {
                    it.copy(
                        phoneNumber = event.phoneNumber
                    )
                }
            }
            ContactEvent.ShowDialog ->
                _state.update {
                    it.copy(
                        isAddingContact = true
                    )
            }

            is ContactEvent.ShowEditDialog -> {
                val c = event.contact
                _state.update {
                    it.copy(
                        isAddingContact = false,
                        isEditingContact = true,
                        editingContactId = c.id,
                        firstName = c.firstName,
                        lastName = c.lastName,
                        phoneNumber = c.phoneNumber
                    )
                }
            }

            ContactEvent.SaveEditedContact -> {
                val id = state.value.editingContactId ?: return
                val firstName = state.value.firstName
                val lastName = state.value.lastName
                val phoneNumber = state.value.phoneNumber

                if (firstName.isBlank() || lastName.isBlank() || phoneNumber.isBlank()) {
                    return
                }

                viewModelScope.launch {
                    val existing = dao.getById(id)
                    val updated = Contact(
                        id = id,
                        firstName = firstName,
                        lastName = lastName,
                        phoneNumber = phoneNumber,
                        isSynced = existing?.isSynced ?: false,
                        isSoftDeleted = existing?.isSoftDeleted ?: false
                    )
                    dao.insertContact(updated)
                }

                _state.update {
                    it.copy(
                        isEditingContact = false,
                        editingContactId = null,
                        firstName = "",
                        lastName = "",
                        phoneNumber = ""
                    )
                }
            }
        }
    }
}