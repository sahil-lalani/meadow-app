package com.example.contacts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object ServerApi {
    // If running on Android emulator, use 10.0.2.2 to reach host machine localhost.
    private const val BASE_URL = "http://10.0.2.2:3000"
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun createContact(contact: Contact): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonObj = JSONObject().apply {
                put("id", contact.id)
                put("firstName", contact.firstName)
                put("lastName", contact.lastName)
                put("phoneNumber", contact.phoneNumber)
            }
            val body = jsonObj.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$BASE_URL/contacts")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                // 201 created OR 409 conflict (already exists) both count as synced
                val ok = response.isSuccessful || response.code == 409
                if (!ok) Log.w("ServerApi", "createContact failed: ${response.code}")
                return@use ok
            }
        } catch (e: Exception) {
            Log.w("ServerApi", "createContact exception", e)
            false
        }
    }

    suspend fun deleteContact(contactId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/contacts/$contactId")
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                // 204 no content OR 404 not found (already deleted) both count as synced
                val ok = response.isSuccessful || response.code == 404
                if (!ok) Log.w("ServerApi", "deleteContact failed: ${response.code}")
                return@use ok
            }
        } catch (e: Exception) {
            Log.w("ServerApi", "deleteContact exception", e)
            false
        }
    }

    suspend fun ackCreated(contactId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("type", "created") }
                .toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$BASE_URL/contacts/$contactId/ack")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                if (!ok) Log.w("ServerApi", "ackCreated failed: ${response.code}")
                return@use ok
            }
        } catch (e: Exception) {
            Log.w("ServerApi", "ackCreated exception", e)
            false
        }
    }

    suspend fun ackDeleted(contactId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("type", "deleted") }
                .toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$BASE_URL/contacts/$contactId/ack")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                if (!ok) Log.w("ServerApi", "ackDeleted failed: ${response.code}")
                return@use ok
            }
        } catch (e: Exception) {
            Log.w("ServerApi", "ackDeleted exception", e)
            false
        }
    }

    suspend fun getPending(): List<Contact> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/contacts/pending")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ServerApi", "getPending failed: ${response.code}")
                    return@use emptyList<Contact>()
                }
                val body = response.body?.string().orEmpty()
                val arr = org.json.JSONArray(body)
                val out = mutableListOf<Contact>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    out.add(
                        Contact(
                            id = obj.getString("id"),
                            firstName = obj.getString("firstName"),
                            lastName = obj.getString("lastName"),
                            phoneNumber = obj.getString("phoneNumber"),
                            isSynced = obj.optBoolean("isSynced", false),
                            isSoftDeleted = obj.optBoolean("isSoftDeleted", false)
                        )
                    )
                }
                return@use out
            }
        } catch (e: Exception) {
            Log.w("ServerApi", "getPending exception", e)
            emptyList()
        }
    }
}


