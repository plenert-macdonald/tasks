package org.tasks.todoist

import android.content.Context
import at.bitfire.ical4android.ICalendar.Companion.prodId
import com.todoroo.astrid.service.TaskDeleter
import dagger.hilt.android.qualifiers.ApplicationContext
import net.fortuna.ical4j.model.property.ProdId
import org.tasks.BuildConfig
import org.tasks.LocalBroadcastManager
import org.tasks.R
import org.tasks.Strings.isNullOrEmpty
import org.tasks.billing.Inventory
import org.tasks.caldav.VtodoCache
import org.tasks.caldav.iCalendar
import org.tasks.data.UUIDHelper
import org.tasks.data.dao.CaldavDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import timber.log.Timber
import javax.inject.Inject
import org.tasks.todoist.TodoistClient.TodoistCollection
import org.tasks.todoist.TodoistClient.TodoistItem

class TodoistSynchronizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val caldavDao: CaldavDao,
    private val localBroadcastManager: LocalBroadcastManager,
    private val taskDeleter: TaskDeleter,
    private val inventory: Inventory,
    private val clientProvider: TodoistClientProvider,
    private val iCal: iCalendar,
    private val vtodoCache: VtodoCache,
) {
    companion object {
        init {
            prodId = ProdId("+//IDN tasks.org//android-" + BuildConfig.VERSION_CODE + "//EN")
        }
    }

    suspend fun sync(account: CaldavAccount) {
        Timber.d("Synchronizing $account")
        Thread.currentThread().contextClassLoader = context.classLoader

        if (!inventory.hasPro) {
            setError(account, context.getString(R.string.requires_pro_subscription))
            return
        }
        if (isNullOrEmpty(account.password)) {
            setError(account, context.getString(R.string.password_required))
            return
        }
        try {
            synchronize(account)
        } catch (e: Exception) {
            setError(account, e)
        }
    }

    private suspend fun synchronize(account: CaldavAccount) {
        try {
            val client = clientProvider.forAccount(account)

            // Get collections from Todoist
            val collections = client.getCollections()
            if (collections.isEmpty()) {
                // Use a Todoist-specific string resource; fall back to generic if missing
                val msg = try {
                    context.getString(R.string.todoist_no_lists_found)
                } catch (e: Exception) {
                    context.getString(R.string.no_lists_found)
                }
                setError(account, msg)
                return
            }

            // Sync each collection/project
            val calendars = caldavDao.getCalendarsByAccount(account.id)

            // Process existing calendars first
            calendars.forEach { calendar ->
                val collectionId = calendar.url
                val matchingCollection = collections.find { it.uid == collectionId }

                if (matchingCollection != null) {
                    // Sync existing collection
                    fetchChanges(account, client, calendar, matchingCollection)
                } else {
                    // Collection was deleted in Todoist
                    Timber.d("Collection ${calendar.name} (${calendar.url}) no longer exists in Todoist")
                    // TODO: Handle deletion if needed
                }
            }

            // Add new collections from Todoist
            collections.forEach { collection ->
                if (calendars.none { it.url == collection.uid }) {
                    // Create a new calendar for this collection
                    val calendar = CaldavCalendar().apply {
                        account = account.id
                        url = collection.uid
                        name = collection.meta.name
                        color = collection.meta.color?.let { parseColor(it) } ?: 0
                        ctag = collection.stoken
                    }

                    val calendarId = caldavDao.insert(calendar)
                    val newCalendar = caldavDao.getCalendar(calendarId)

                    if (newCalendar != null) {
                        fetchChanges(account, client, newCalendar, collection)
                    }
                }
            }

            // Update account's last sync timestamp
            account.error = null
            account.lastSync = currentTimeMillis()
            caldavDao.update(account)

            localBroadcastManager.refreshList()
        } catch (e: Exception) {
            setError(account, e)
        }
    }

    private suspend fun setError(account: CaldavAccount, e: Throwable) =
        setError(account, e.message)

    private suspend fun setError(account: CaldavAccount, message: String?) {
        account.error = message
        caldavDao.update(account)
        localBroadcastManager.refreshList()
        if (!isNullOrEmpty(message)) {
            Timber.e(message)
        }
    }

    private suspend fun fetchChanges(
        account: CaldavAccount,
        client: TodoistClient,
        caldavCalendar: CaldavCalendar,
        collection: TodoistCollection
    ) {
        try {
            // First push any local changes
            pushLocalChanges(account, client, caldavCalendar, collection)

            // Then fetch remote changes
            client.fetchItems(collection, caldavCalendar) { result ->
                val (stoken, items) = result

                // Apply fetched items to local database
                applyEntries(account, caldavCalendar, items, stoken)

                // Update collection cache
                if (items.isNotEmpty()) {
                    client.updateCache(collection, items)
                }

                // Update calendar's ctag if we have a new sync token
                if (stoken != null && stoken != caldavCalendar.ctag) {
                    caldavCalendar.ctag = stoken
                    caldavDao.update(caldavCalendar)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error syncing Todoist collection: ${caldavCalendar.name}")
        }
    }

    private suspend fun pushLocalChanges(
        account: CaldavAccount,
        client: TodoistClient,
        caldavCalendar: CaldavCalendar,
        collection: TodoistCollection
    ) {
        try {
            // Get local tasks that need to be synced
            val localTasks = caldavDao.getTasks(caldavCalendar.id, TodoistSyncAdapter.TYPE)
            if (localTasks.isEmpty()) {
                return
            }

            // Group tasks by their sync status
            val tasksToCreate = mutableListOf<CaldavTask>()
            val tasksToUpdate = mutableListOf<CaldavTask>()
            val tasksToDelete = mutableListOf<CaldavTask>()

            localTasks.forEach { task ->
                when {
                    task.deleted == 1L -> tasksToDelete.add(task)
                    task.remoteId.isNullOrBlank() -> tasksToCreate.add(task)
                    task.dirty == 1L -> tasksToUpdate.add(task)
                }
            }

            // Process deletions
            tasksToDelete.forEach { task ->
                client.deleteItem(collection, task)?.let {
                    // Apply the deletion locally
                    taskDeleter.delete(task.task)
                }
            }

            // Process creations and updates
            val itemsToUpdate = mutableListOf<TodoistItem>()

            // Create new tasks
            tasksToCreate.forEach { task ->
                val vtodo = vtodoCache[task.task]
                val content = vtodo?.writeForCalendar(iCal) ?: ByteArray(0)

                val item = client.updateItem(collection, task, content)
                if (item.uid.isNotEmpty()) {
                    // Update task with remote ID
                    task.remoteId = item.uid
                    task.dirty = 0L
                    task.etag = currentTimeMillis().toString()
                    caldavDao.update(task)

                    itemsToUpdate.add(item)
                }
            }

            // Update existing tasks
            tasksToUpdate.forEach { task ->
                val vtodo = vtodoCache[task.task]
                val content = vtodo?.writeForCalendar(iCal) ?: ByteArray(0)

                val item = client.updateItem(collection, task, content)
                if (item.uid.isNotEmpty()) {
                    // Mark task as clean
                    task.dirty = 0L
                    task.etag = currentTimeMillis().toString()
                    caldavDao.update(task)

                    itemsToUpdate.add(item)
                }
            }

            // Update cache with modified items
            if (itemsToUpdate.isNotEmpty()) {
                client.updateCache(collection, itemsToUpdate)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error pushing local changes to Todoist")
        }
    }

    private suspend fun applyEntries(
        account: CaldavAccount,
        caldavCalendar: CaldavCalendar,
        items: List<TodoistItem>,
        stoken: String? = null,
        isLocalChange: Boolean = false
    ) {
        if (items.isEmpty()) {
            return
        }

        try {
            val existingTasks = caldavDao
                .getTasks(caldavCalendar.id, TodoistSyncAdapter.TYPE)
                .associateBy { it.remoteId }

            for (item in items) {
                if (item.isDeleted) {
                    // Handle deleted items
                    existingTasks[item.uid]?.let { task ->
                        if (task.deleted == 0L) {
                            // Mark as deleted locally
                            taskDeleter.delete(task.task)
                        }
                    }
                } else {
                    // Handle created or updated items
                    val existing = existingTasks[item.uid]

                    if (existing == null) {
                        // Create new task
                        val newVtodo = iCal.parse(item.content)

                        if (newVtodo != null) {
                            // Create new CaldavTask
                            val task = CaldavTask(
                                seen0 = 0L,
                                calendar = caldavCalendar.id,
                                remoteId = item.uid,
                                obj = item.contentString,
                                etag = item.meta.mtime.toString(),
                                lastSync = currentTimeMillis(),
                                deleted = 0L,
                                remoteParent = null,
                                isMoved = 0L,
                                remoteOrder = 0L,
                            )

                            // Insert task with Todoist data
                            val taskUid = UUIDHelper.newUUID()
                            val taskId =
                                iCal.createTask(newVtodo, taskUid, TodoistSyncAdapter.TYPE, caldavCalendar)
                            task.task = taskId
                            caldavDao.insert(task)
                        }
                    } else if (!isLocalChange &&
                        item.meta.mtime > (existing.etag?.toLongOrNull() ?: 0L)
                    ) {
                        // Update existing task if remote version is newer
                        val newVtodo = iCal.parse(item.content)

                        if (newVtodo != null) {
                            // Update the task data
                            existing.etag = item.meta.mtime.toString()
                            existing.obj = item.contentString
                            existing.dirty = 0L

                            // Update the task in database
                            iCal.updateTask(newVtodo, existing.task, TodoistSyncAdapter.TYPE)
                            caldavDao.update(existing)
                        }
                    }
                }
            }

            localBroadcastManager.refreshList()
        } catch (e: Exception) {
            Timber.e(e, "Error applying Todoist entries")
        }
    }

    /**
     * Converts a Todoist color string to an Android color integer
     */
    private fun parseColor(colorString: String): Int {
        return when (colorString) {
            "berry_red" -> 0xFFB8256F.toInt()
            "red" -> 0xFFDB4035.toInt()
            "orange" -> 0xFFFF9933.toInt()
            "yellow" -> 0xFFFAD000.toInt()
            "olive_green" -> 0xFF7ECC49.toInt()
            "lime_green" -> 0xFFB7DF1F.toInt()
            "green" -> 0xFF14AE5C.toInt()
            "mint_green" -> 0xFF4DCF8F.toInt()
            "teal" -> 0xFF14CCBB.toInt()
            "sky_blue" -> 0xFF14AAF5.toInt()
            "light_blue" -> 0xFF96C3EB.toInt()
            "blue" -> 0xFF0052CC.toInt()
            "grape" -> 0xFF884DFF.toInt()
            "violet" -> 0xFFAF38EB.toInt()
            "lavender" -> 0xFFEB96EB.toInt()
            "magenta" -> 0xFFE05194.toInt()
            "salmon" -> 0xFFFF8D85.toInt()
            "charcoal" -> 0xFF808080.toInt()
            "grey" -> 0xFFB8B8B8.toInt()
            "taupe" -> 0xFFCCAC93.toInt()
            else -> 0
        }
    }
}
