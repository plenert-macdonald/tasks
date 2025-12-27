package org.tasks.todoist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.tasks.data.dao.CaldavDao
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.http.HttpClientFactory
import org.tasks.security.KeyStoreEncryption
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import timber.log.Timber
import java.io.IOException
import java.util.UUID

class TodoistClient(
    private val context: Context,
    private val username: String,
    private val caldavDao: CaldavDao,
    private val httpClientFactory: HttpClientFactory? = null,
    private val encryption: KeyStoreEncryption? = null
) {
    private val cache = TodoistLocalCache.getInstance(context, username)
    private val syncApiUrl = "https://api.todoist.com/sync/v9/sync"
    private var httpClient: OkHttpClient? = null

    private suspend fun getHttpClient(): OkHttpClient {
        if (httpClient == null) {
            httpClient = httpClientFactory?.newClient(foreground = true) ?: OkHttpClient()
        }
        return httpClient!!
    }

    /**
     * Returns the Todoist API token for this username.
     *
     * For now this is just the username itself (the API token is entered in the
     * "password" field in the UI and stored as the account username).
     */
    fun getSession(): String = username

    suspend fun getCollections(): List<TodoistCollection> = withContext(Dispatchers.IO) {
        try {
            val token = getSession()
            val syncToken = cache.loadStoken() ?: "*"

            val json = JSONObject().apply {
                put("token", token)
                put("sync_token", syncToken)
                put("resource_types", JSONArray().put("projects"))
            }

            val response = makeSyncRequest(json.toString())
            if (response != null) {
                val projects = response.optJSONArray("projects") ?: JSONArray()
                val newSyncToken = response.optString("sync_token")

                if (newSyncToken.isNotEmpty()) {
                    cache.saveStoken(newSyncToken)
                }

                val collections = mutableListOf<TodoistCollection>()
                for (i in 0 until projects.length()) {
                    val project = projects.optJSONObject(i) ?: continue
                    collections.add(
                        TodoistCollection().apply {
                            uid = project.optString("id", "")
                            meta.name = project.optString("name", "")
                            meta.color = project.optString("color", "")
                            meta.mtime = currentTimeMillis()
                            stoken = newSyncToken
                        }
                    )
                }

                // Cache the collections
                collections.forEach { collection ->
                    cache.collectionSet(Unit, collection)
                }

                return@withContext collections
            }

            // If the request fails, try to get collections from cache
            return@withContext cache.collectionList(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get Todoist collections")
            return@withContext emptyList()
        }
    }

    suspend fun fetchItems(
        collection: TodoistCollection,
        calendar: CaldavCalendar,
        callback: suspend (Pair<String?, List<TodoistItem>>) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val token = getSession()
            val projectId = collection.uid
            val syncToken = cache.loadStoken() ?: "*"

            val json = JSONObject().apply {
                put("token", token)
                put("sync_token", syncToken)
                put("resource_types", JSONArray().put("items"))
            }

            val response = makeSyncRequest(json.toString())
            if (response != null) {
                val items = response.optJSONArray("items") ?: JSONArray()
                val newSyncToken = response.optString("sync_token")

                if (newSyncToken.isNotEmpty()) {
                    cache.saveStoken(newSyncToken)
                }

                val todoistItems = mutableListOf<TodoistItem>()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    // Only include items from the specified project
                    if (item.optString("project_id", "") != projectId) continue

                    // Convert item to TodoistItem
                    val todoistItem = convertJsonToTodoistItem(item)
                    todoistItems.add(todoistItem)

                    // Update cache
                    cache.itemSet(Unit, collection.uid, todoistItem)
                }

                callback(Pair(newSyncToken, todoistItems))
            } else {
                // If the request fails, return empty list
                callback(Pair(null, emptyList()))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Todoist items for collection ${collection.uid}")
            callback(Pair(null, emptyList()))
        }
    }

    private fun convertJsonToTodoistItem(jsonObject: JSONObject): TodoistItem {
        return TodoistItem().apply {
            uid = jsonObject.optString("id", "")
            contentString = jsonObject.optString("content", "")
            content = contentString.toByteArray()
            isDeleted = jsonObject.optBoolean("is_deleted", false)

            meta.name = jsonObject.optString("content", "")
            meta.mtime = currentTimeMillis()
        }
    }

    suspend fun updateItem(
        collection: TodoistCollection,
        task: CaldavTask,
        content: ByteArray
    ): TodoistItem = withContext(Dispatchers.IO) {
        try {
            val token = getSession()
            val taskContent = String(content)
            val tempId = UUID.randomUUID().toString()

            // Generate a unique command UUID
            val commandUuid = UUID.randomUUID().toString()

            // Create or update the task
            val command = JSONObject().apply {
                put("type", if (task.remoteId.isNullOrEmpty()) "item_add" else "item_update")
                put("uuid", commandUuid)
                put(
                    "args",
                    JSONObject().apply {
                        if (!task.remoteId.isNullOrEmpty()) {
                            put("id", task.remoteId)
                        } else {
                            put("project_id", collection.uid)
                            put("temp_id", tempId)
                        }
                        put("content", taskContent)
                        // Additional task properties can be added here
                    }
                )
            }

            val json = JSONObject().apply {
                put("token", token)
                put("commands", JSONArray().put(command))
            }

            val response = makeSyncRequest(json.toString())
            if (response != null) {
                // Handle response for item_add or item_update
                val syncStatus = response.optJSONObject("sync_status") ?: JSONObject()
                val commandStatus = syncStatus.optJSONObject(commandUuid)

                if (commandStatus != null && commandStatus.optString("status") == "ok") {
                    // For item_add, we need to get the permanent id
                    val tempIdMapping = response.optJSONObject("temp_id_mapping")
                    val permanentId =
                        if (tempIdMapping != null && task.remoteId.isNullOrEmpty()) {
                            tempIdMapping.optString(tempId, "")
                        } else {
                            task.remoteId ?: ""
                        }

                    val todoistItem = TodoistItem().apply {
                        uid = permanentId
                        contentString = taskContent
                        meta.name = taskContent
                        meta.mtime = currentTimeMillis()
                    }

                    // Update cache
                    cache.itemSet(Unit, collection.uid, todoistItem)

                    return@withContext todoistItem
                }
            }

            // Return empty item if update fails
            return@withContext TodoistItem()
        } catch (e: Exception) {
            Timber.e(e, "Failed to update Todoist item")
            return@withContext TodoistItem()
        }
    }

    suspend fun deleteItem(collection: TodoistCollection, task: CaldavTask): TodoistItem? =
        withContext(Dispatchers.IO) {
            try {
                val token = getSession()
                val taskId = task.remoteId ?: return@withContext null

                // Generate a unique command UUID
                val commandUuid = UUID.randomUUID().toString()

                val command = JSONObject().apply {
                    put("type", "item_delete")
                    put("uuid", commandUuid)
                    put(
                        "args",
                        JSONObject().apply {
                            put("id", taskId)
                        }
                    )
                }

                val json = JSONObject().apply {
                    put("token", token)
                    put("commands", JSONArray().put(command))
                }

                val response = makeSyncRequest(json.toString())
                if (response != null) {
                    val syncStatus = response.optJSONObject("sync_status") ?: JSONObject()
                    val commandStatus = syncStatus.optJSONObject(commandUuid)

                    if (commandStatus != null && commandStatus.optString("status") == "ok") {
                        // Create a TodoistItem with deleted flag
                        val todoistItem = TodoistItem().apply {
                            uid = taskId
                            isDeleted = true
                            meta.mtime = currentTimeMillis()
                        }

                        // Remove from cache
                        cache.itemSet(Unit, collection.uid, todoistItem)

                        return@withContext todoistItem
                    }
                }

                return@withContext null
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete Todoist item ${task.remoteId}")
                return@withContext null
            }
        }

    suspend fun updateCache(collection: TodoistCollection, items: List<TodoistItem>) {
        try {
            for (item in items) {
                cache.itemSet(Unit, collection.uid, item)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update cache for collection ${collection.uid}")
        }
    }

    suspend fun uploadChanges(collection: TodoistCollection, items: List<TodoistItem>) =
        withContext(Dispatchers.IO) {
            try {
                val token = getSession()
                val commands = JSONArray()

                for (item in items) {
                    val commandUuid = UUID.randomUUID().toString()

                    // Determine if item is new, updated, or deleted
                    val command = JSONObject().apply {
                        if (item.isDeleted) {
                            put("type", "item_delete")
                            put("uuid", commandUuid)
                            put(
                                "args",
                                JSONObject().apply {
                                    put("id", item.uid)
                                }
                            )
                        } else if (item.uid.isEmpty()) {
                            // New item
                            put("type", "item_add")
                            put("uuid", commandUuid)
                            put(
                                "args",
                                JSONObject().apply {
                                    put("project_id", collection.uid)
                                    put("content", item.contentString)
                                    put("temp_id", UUID.randomUUID().toString())
                                }
                            )
                        } else {
                            // Existing item - update
                            put("type", "item_update")
                            put("uuid", commandUuid)
                            put(
                                "args",
                                JSONObject().apply {
                                    put("id", item.uid)
                                    put("content", item.contentString)
                                }
                            )
                        }
                    }

                    commands.put(command)
                }

                if (commands.length() > 0) {
                    val json = JSONObject().apply {
                        put("token", token)
                        put("commands", commands)
                    }

                    makeSyncRequest(json.toString())
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to upload changes to Todoist")
            }
        }

    suspend fun logout() {
        try {
            TodoistLocalCache.clear(context, username)
            httpClient = null
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    suspend fun makeCollection(name: String, color: Int): String = withContext(Dispatchers.IO) {
        try {
            val token = getSession()
            val commandUuid = UUID.randomUUID().toString()
            val tempId = UUID.randomUUID().toString()

            val hexColor = color.toHexColor()
            val todoistColor = mapHexToTodoistColor(hexColor)

            val command = JSONObject().apply {
                put("type", "project_add")
                put("uuid", commandUuid)
                put(
                    "args",
                    JSONObject().apply {
                        put("name", name)
                        put("color", todoistColor)
                        put("temp_id", tempId)
                    }
                )
            }

            val json = JSONObject().apply {
                put("token", token)
                put("commands", JSONArray().put(command))
            }

            val response = makeSyncRequest(json.toString())
            if (response != null) {
                val tempIdMapping = response.optJSONObject("temp_id_mapping")
                if (tempIdMapping != null) {
                    val projectId = tempIdMapping.optString(tempId, "")
                    if (projectId.isNotEmpty()) {
                        // Create collection and add to cache
                        val collection = TodoistCollection().apply {
                            uid = projectId
                            meta.name = name
                            meta.color = todoistColor
                            meta.mtime = currentTimeMillis()
                        }

                        cache.collectionSet(Unit, collection)

                        return@withContext projectId
                    }
                }
            }

            return@withContext "todoist-collection-id"
        } catch (e: Exception) {
            Timber.e(e, "Failed to create Todoist collection")
            return@withContext "todoist-collection-id"
        }
    }

    suspend fun updateCollection(calendar: CaldavCalendar, name: String, color: Int): String =
        withContext(Dispatchers.IO) {
            try {
                val token = getSession()
                val projectId = calendar.url ?: return@withContext "todoist-collection-id"
                val commandUuid = UUID.randomUUID().toString()

                val hexColor = color.toHexColor()
                val todoistColor = mapHexToTodoistColor(hexColor)

                val command = JSONObject().apply {
                    put("type", "project_update")
                    put("uuid", commandUuid)
                    put(
                        "args",
                        JSONObject().apply {
                            put("id", projectId)
                            put("name", name)
                            put("color", todoistColor)
                        }
                    )
                }

                val json = JSONObject().apply {
                    put("token", token)
                    put("commands", JSONArray().put(command))
                }

                val response = makeSyncRequest(json.toString())
                if (response != null) {
                    val syncStatus = response.optJSONObject("sync_status") ?: JSONObject()
                    val commandStatus = syncStatus.optJSONObject(commandUuid)

                    if (commandStatus != null && commandStatus.optString("status") == "ok") {
                        // Update collection in cache
                        val existingCollection = cache.collectionGet(Unit, projectId)
                        val updatedCollection = existingCollection.apply {
                            meta.name = name
                            meta.color = todoistColor
                            meta.mtime = currentTimeMillis()
                        }

                        cache.collectionSet(Unit, updatedCollection)

                        return@withContext projectId
                    }
                }

                return@withContext projectId
            } catch (e: Exception) {
                Timber.e(e, "Failed to update Todoist collection ${calendar.url}")
                return@withContext calendar.url ?: "todoist-collection-id"
            }
        }

    suspend fun deleteCollection(calendar: CaldavCalendar) = withContext(Dispatchers.IO) {
        try {
            val token = getSession()
            val projectId = calendar.url ?: return@withContext
            val commandUuid = UUID.randomUUID().toString()

            val command = JSONObject().apply {
                put("type", "project_delete")
                put("uuid", commandUuid)
                put(
                    "args",
                    JSONObject().apply {
                        put("id", projectId)
                    }
                )
            }

            val json = JSONObject().apply {
                put("token", token)
                put("commands", JSONArray().put(command))
            }

            val response = makeSyncRequest(json.toString())
            if (response != null) {
                val syncStatus = response.optJSONObject("sync_status") ?: JSONObject()
                val commandStatus = syncStatus.optJSONObject(commandUuid)

                if (commandStatus != null && commandStatus.optString("status") == "ok") {
                    // Remove from cache
                    cache.collectionUnset(Unit, projectId)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete Todoist collection ${calendar.url}")
        }
    }

    private suspend fun makeSyncRequest(jsonBody: String): JSONObject? {
        try {
            val client = getHttpClient()
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(syncApiUrl)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                return JSONObject(responseBody)
            }

            Timber.e("Todoist API request failed: ${response.code} - $responseBody")
            return null
        } catch (e: IOException) {
            Timber.e(e, "Network error during Todoist API request")
            return null
        } catch (e: JSONException) {
            Timber.e(e, "Error parsing Todoist API response")
            return null
        } catch (e: Exception) {
            Timber.e(e, "Error during Todoist API request")
            return null
        }
    }

    private fun mapHexToTodoistColor(hexColor: String?): String {
        // Mapping closest hex colors to Todoist color names
        return when (hexColor?.uppercase()) {
            "#B8256F" -> "berry_red"
            "#DB4035" -> "red"
            "#FF9933" -> "orange"
            "#FAD000" -> "yellow"
            "#7ECC49" -> "olive_green"
            "#14AAF5" -> "light_blue"
            "#96C3EB" -> "blue"
            "#884DFF" -> "grape"
            "#AF38EB" -> "violet"
            "#EB96EB" -> "lavender"
            "#FF8D85" -> "salmon"
            "#808080" -> "charcoal"
            "#B8B8B8" -> "grey"
            "#CCAC93" -> "taupe"
            else -> "charcoal" // Default color
        }
    }

    companion object {
        private fun Int.toHexColor(): String? =
            takeIf { this != 0 }?.let {
                String.format("#%06X", 0xFFFFFF and it)
            }
    }

    // Stub classes to represent Todoist entities
    class TodoistCollection {
        var uid: String = ""
        var stoken: String = ""
        var meta = TodoistItemMetadata()

        fun delete() {}
    }

    class TodoistItem {
        var uid: String = ""
        var meta = TodoistItemMetadata()
        var content: ByteArray = ByteArray(0)
        var contentString: String = ""
        var isDeleted: Boolean = false

        fun delete() {}
    }

    class TodoistItemMetadata {
        var name: String = ""
        var color: String? = null
        var mtime: Long = 0
    }
}
