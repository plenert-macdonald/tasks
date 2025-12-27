package org.tasks.todoist

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tasks.todoist.TodoistClient.TodoistCollection
import org.tasks.todoist.TodoistClient.TodoistItem
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class TodoistLocalCache private constructor(private val context: Context, private val username: String) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        "todoist_$username", Context.MODE_PRIVATE)

    // In-memory cache for collections and items
    private val collectionsCache = ConcurrentHashMap<String, TodoistCollection>()
    private val itemsCache = ConcurrentHashMap<String, MutableMap<String, TodoistItem>>()

    init {
        // Load collections from shared preferences on initialization
        loadCollections()
        loadItems()
    }

    suspend fun clearUserCache() = withContext(Dispatchers.IO) {
        try {
            // Clear in-memory cache
            collectionsCache.clear()
            itemsCache.clear()

            // Clear shared preferences
            preferences.edit()
                .clear()
                .apply()

            Timber.d("Cleared Todoist cache for user $username")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing Todoist cache")
        }
    }

    suspend fun saveStoken(stoken: String) = withContext(Dispatchers.IO) {
        try {
            preferences.edit()
                .putString(KEY_SYNC_TOKEN, stoken)
                .apply()

            Timber.d("Saved Todoist sync token: $stoken")
        } catch (e: Exception) {
            Timber.e(e, "Error saving Todoist sync token")
        }
    }

    suspend fun loadStoken(): String? = withContext(Dispatchers.IO) {
        try {
            val token = preferences.getString(KEY_SYNC_TOKEN, null)
            Timber.d("Loaded Todoist sync token: $token")
            return@withContext token
        } catch (e: Exception) {
            Timber.e(e, "Error loading Todoist sync token")
            return@withContext null
        }
    }

    suspend fun collectionList(colMgr: Any): List<TodoistCollection> =
            withContext(Dispatchers.IO) {
                try {
                    return@withContext collectionsCache.values.toList()
                } catch (e: Exception) {
                    Timber.e(e, "Error getting Todoist collections from cache")
                    return@withContext emptyList()
                }
            }

    suspend fun collectionGet(colMgr: Any, colUid: String): TodoistCollection =
            withContext(Dispatchers.IO) {
                try {
                    return@withContext collectionsCache[colUid] ?: TodoistCollection()
                } catch (e: Exception) {
                    Timber.e(e, "Error getting Todoist collection from cache: $colUid")
                    return@withContext TodoistCollection()
                }
            }

    suspend fun collectionSet(colMgr: Any, collection: TodoistCollection) = withContext(Dispatchers.IO) {
        try {
            // Add to in-memory cache
            collectionsCache[collection.uid] = collection

            // Save to shared preferences
            saveCollections()

            Timber.d("Saved Todoist collection to cache: ${collection.uid}")
        } catch (e: Exception) {
            Timber.e(e, "Error saving Todoist collection to cache: ${collection.uid}")
        }
    }

    suspend fun collectionUnset(colMgr: Any, collection: Any) = withContext(Dispatchers.IO) {
        try {
            when (collection) {
                is TodoistCollection -> collectionUnset(colMgr, collection.uid)
                is String -> collectionUnset(colMgr, collection)
                else -> Timber.e("Invalid collection type: ${collection.javaClass}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error removing collection from cache")
        }
    }

    suspend fun collectionUnset(colMgr: Any, colUid: String) = withContext(Dispatchers.IO) {
        try {
            // Remove from in-memory cache
            collectionsCache.remove(colUid)

            // Remove items for this collection
            itemsCache.remove(colUid)

            // Save changes
            saveCollections()
            saveItems()

            Timber.d("Removed Todoist collection from cache: $colUid")
        } catch (e: Exception) {
            Timber.e(e, "Error removing Todoist collection from cache: $colUid")
        }
    }

    suspend fun itemGet(itemMgr: Any, colUid: String, itemUid: String): TodoistItem? =
            withContext(Dispatchers.IO) {
                try {
                    return@withContext itemsCache[colUid]?.get(itemUid)
                } catch (e: Exception) {
                    Timber.e(e, "Error getting Todoist item from cache: $colUid/$itemUid")
                    return@withContext null
                }
            }

    suspend fun itemSet(itemMgr: Any, colUid: String, item: TodoistItem) = withContext(Dispatchers.IO) {
        try {
            // Ensure we have a map for this collection
            if (!itemsCache.containsKey(colUid)) {
                itemsCache[colUid] = ConcurrentHashMap()
            }

            // Add to in-memory cache
            itemsCache[colUid]!![item.uid] = item

            // Save to shared preferences
            saveItems()

            Timber.d("Saved Todoist item to cache: $colUid/${item.uid}")
        } catch (e: Exception) {
            Timber.e(e, "Error saving Todoist item to cache: $colUid/${item.uid}")
        }
    }

    private fun loadCollections() {
        try {
            val collectionsJson = preferences.getString(KEY_COLLECTIONS, null)
            if (collectionsJson != null) {
                val collectionsArray = JSONArray(collectionsJson)

                for (i in 0 until collectionsArray.length()) {
                    val collectionJson = collectionsArray.getJSONObject(i)
                    val collection = TodoistCollection().apply {
                        uid = collectionJson.optString("uid", "")
                        stoken = collectionJson.optString("stoken", "")

                        val metaJson = collectionJson.optJSONObject("meta")
                        if (metaJson != null) {
                            meta.name = metaJson.optString("name", "")
                            meta.color = metaJson.optString("color", "")
                            meta.mtime = metaJson.optLong("mtime", 0)
                        }
                    }

                    if (collection.uid.isNotEmpty()) {
                        collectionsCache[collection.uid] = collection
                    }
                }

                Timber.d("Loaded ${collectionsCache.size} Todoist collections from cache")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading Todoist collections from cache")
        }
    }

    private fun saveCollections() {
        try {
            val collectionsArray = JSONArray()

            for (collection in collectionsCache.values) {
                val collectionJson = JSONObject().apply {
                    put("uid", collection.uid)
                    put("stoken", collection.stoken)

                    put("meta", JSONObject().apply {
                        put("name", collection.meta.name)
                        put("color", collection.meta.color)
                        put("mtime", collection.meta.mtime)
                    })
                }

                collectionsArray.put(collectionJson)
            }

            preferences.edit()
                .putString(KEY_COLLECTIONS, collectionsArray.toString())
                .apply()

            Timber.d("Saved ${collectionsCache.size} Todoist collections to cache")
        } catch (e: Exception) {
            Timber.e(e, "Error saving Todoist collections to cache")
        }
    }

    private fun loadItems() {
        try {
            val itemsJson = preferences.getString(KEY_ITEMS, null)
            if (itemsJson != null) {
                val collectionsArray = JSONArray(itemsJson)

                for (i in 0 until collectionsArray.length()) {
                    val collectionJson = collectionsArray.getJSONObject(i)
                    val collectionId = collectionJson.optString("collection_id", "")

                    if (collectionId.isNotEmpty()) {
                        val collectionItems = ConcurrentHashMap<String, TodoistItem>()
                        itemsCache[collectionId] = collectionItems

                        val itemsArray = collectionJson.optJSONArray("items")
                        if (itemsArray != null) {
                            for (j in 0 until itemsArray.length()) {
                                val itemJson = itemsArray.getJSONObject(j)
                                val item = TodoistItem().apply {
                                    uid = itemJson.optString("uid", "")
                                    contentString = itemJson.optString("content", "")
                                    content = contentString.toByteArray()
                                    isDeleted = itemJson.optBoolean("is_deleted", false)

                                    val metaJson = itemJson.optJSONObject("meta")
                                    if (metaJson != null) {
                                        meta.name = metaJson.optString("name", "")
                                        meta.color = metaJson.optString("color", "")
                                        meta.mtime = metaJson.optLong("mtime", 0)
                                    }
                                }

                                if (item.uid.isNotEmpty()) {
                                    collectionItems[item.uid] = item
                                }
                            }
                        }
                    }
                }

                Timber.d("Loaded Todoist items for ${itemsCache.size} collections")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading Todoist items from cache")
        }
    }

    private fun saveItems() {
        try {
            val collectionsArray = JSONArray()

            for ((collectionId, items) in itemsCache) {
                val collectionJson = JSONObject().apply {
                    put("collection_id", collectionId)

                    val itemsArray = JSONArray()
                    for (item in items.values) {
                        val itemJson = JSONObject().apply {
                            put("uid", item.uid)
                            put("content", item.contentString)
                            put("is_deleted", item.isDeleted)

                            put("meta", JSONObject().apply {
                                put("name", item.meta.name)
                                put("color", item.meta.color)
                                put("mtime", item.meta.mtime)
                            })
                        }

                        itemsArray.put(itemJson)
                    }

                    put("items", itemsArray)
                }

                collectionsArray.put(collectionJson)
            }

            preferences.edit()
                .putString(KEY_ITEMS, collectionsArray.toString())
                .apply()

            Timber.d("Saved Todoist items for ${itemsCache.size} collections")
        } catch (e: Exception) {
            Timber.e(e, "Error saving Todoist items to cache")
        }
    }

    companion object {
        private const val KEY_SYNC_TOKEN = "sync_token"
        private const val KEY_COLLECTIONS = "collections"
        private const val KEY_ITEMS = "items"

        private val localCacheCache: HashMap<String, TodoistLocalCache> = HashMap()

        fun getInstance(context: Context, username: String): TodoistLocalCache {
            synchronized(localCacheCache) {
                val cached = localCacheCache[username]
                return if (cached != null) {
                    cached
                } else {
                    val ret = TodoistLocalCache(context, username)
                    localCacheCache[username] = ret
                    ret
                }
            }
        }

        fun clear(context: Context) = runBlocking {
            val users = synchronized(localCacheCache) {
                localCacheCache.keys.toList()
            }
            users.forEach { clear(context, it) }
        }

        suspend fun clear(context: Context, username: String) {
            val localCache = getInstance(context, username)
            localCache.clearUserCache()
            localCacheCache.remove(username)
        }
    }
}
