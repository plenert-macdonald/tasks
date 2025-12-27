package org.tasks.todoist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.google.android.material.snackbar.BaseTransientBottomBar
import org.tasks.data.entity.Task
import org.tasks.data.UUIDHelper
import dagger.hilt.android.AndroidEntryPoint
import org.tasks.R
import org.tasks.Strings.isNullOrEmpty
import org.tasks.analytics.Constants
import org.tasks.billing.PurchaseActivity
import org.tasks.caldav.BaseCaldavAccountSettingsActivity
import org.tasks.compose.ServerSelector
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.SERVER_UNKNOWN
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_LOCAL
import org.tasks.data.getPassword
import org.tasks.databinding.ActivityCaldavAccountSettingsBinding
import org.tasks.dialogs.Linkify
import org.tasks.extensions.addBackPressedCallback
import org.tasks.themes.TasksTheme
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class TodoistAccountSettingsActivity : BaseCaldavAccountSettingsActivity(), Toolbar.OnMenuItemClickListener {
    @Inject lateinit var clientProvider: TodoistClientProvider

    private val addAccountViewModel: AddTodoistAccountViewModel by viewModels()
    private val updateAccountViewModel: UpdateTodoistAccountViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCaldavAccountSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
            }
            binding.rootLayout.updatePadding(bottom = systemBars.bottom)
            insets
        }
        caldavAccount = if (savedInstanceState == null) intent.getParcelableExtra(EXTRA_CALDAV_DATA) else savedInstanceState.getParcelable(EXTRA_CALDAV_DATA)
        serverType = mutableStateOf(
            savedInstanceState?.getInt(EXTRA_SERVER_TYPE, SERVER_UNKNOWN)
                ?: caldavAccount?.serverType
                ?: SERVER_UNKNOWN
        )
        if (caldavAccount == null || caldavAccount!!.id == Task.NO_ID) {
            binding.nameLayout.visibility = View.GONE
            binding.description.visibility = View.VISIBLE
            binding.description.setText(description)
            Linkify.safeLinkify(binding.description, android.text.util.Linkify.WEB_URLS)
            serverType.value = SERVER_UNKNOWN
        } else {
            binding.nameLayout.visibility = View.VISIBLE
            binding.description.visibility = View.GONE
            caldavAccount?.error?.takeIf { it.isNotBlank() }?.let {
                binding.description.visibility = View.VISIBLE
                binding.description.setTextColor(ContextCompat.getColor(this, R.color.overdue))
                binding.description.text = getString(R.string.error_adding_account, it)
            }
        }
        if (savedInstanceState == null) {
            caldavAccount?.let {
                if (!isNullOrEmpty(it.password)) {
                    binding.password.setText(PASSWORD_MASK)
                }
            }
        }
        val toolbar = binding.toolbar.toolbar
        toolbar.title = if (caldavAccount == null) getString(R.string.add_account) else caldavAccount!!.name
        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_outline_save_24px)
        toolbar.setNavigationOnClickListener { save() }
        toolbar.inflateMenu(menuRes)
        toolbar.setOnMenuItemClickListener(this)
        toolbar.showOverflowMenu()
        if (caldavAccount == null) {
            toolbar.menu.findItem(R.id.remove).isVisible = false
            binding.name.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.name, InputMethodManager.SHOW_IMPLICIT)
        }
        binding.password.addTextChangedListener(
            onTextChanged = { _, _, _, _ -> binding.passwordLayout.error = null }
        )
        binding.serverSelector.setContent {
            TasksTheme(
                theme = tasksTheme.themeBase.index,
                primary = tasksTheme.themeColor.primaryColor,
            ) {
                var selected by rememberSaveable { serverType }
                ServerSelector(selected) {
                    serverType.value = it
                    selected = it
                }
            }
        }

        binding.serverSelector.visibility = View.GONE
        binding.showAdvanced.visibility = View.GONE
        binding.urlLayout.visibility = View.GONE
        binding.userLayout.visibility = View.GONE
        binding.passwordLayout.hint = getString(R.string.todoist_api_token)
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) {
            addAccountViewModel.observe(this, this::addAccount, this::requestFailed)
            updateAccountViewModel.observe(this, this::updateAccount, this::requestFailed)
        }
    }

    override fun onPause() {
        super.onPause()
        addAccountViewModel.removeObserver(this)
        updateAccountViewModel.removeObserver(this)
    }

    override val description: Int
        get() = R.string.todoist_account_description

    private suspend fun addAccount(session: String) {
        caldavAccount = CaldavAccount(
            accountType = CaldavAccount.TYPE_TODOIST,
            uuid = UUIDHelper.newUUID(),
        )
        applyTo(caldavAccount!!, session)
    }

    private suspend fun updateAccount(session: String) {
        caldavAccount!!.error = ""
        applyTo(caldavAccount!!, session)
    }

    private suspend fun applyTo(account: CaldavAccount, session: String) {
        hideProgressIndicator()
        account.name = newName
        account.url = newURL
        account.username = newUsername
        if (session != account.getPassword(encryption)) {
            account.password = encryption.encrypt(session)
        }
        saveAccountAndFinish()
    }

    private fun updateUrlVisibility() {
        binding.urlLayout.visibility = if (binding.showAdvanced.isChecked) View.VISIBLE else View.GONE
    }

    override suspend fun addAccount(url: String, username: String, password: String) =
        addAccountViewModel.addAccount(url, username, password)

    override suspend fun updateAccount(url: String, username: String, password: String) =
        updateAccountViewModel.updateAccount(
            url,
            username,
            if (PASSWORD_MASK == password) null else password,
            caldavAccount!!.getPassword(encryption)
        )

    override suspend fun updateAccount() {
        caldavAccount!!.name = newName
        saveAccountAndFinish()
    }

    /**
     * For Todoist we don't actually use a user-entered URL. We return a fixed internal
     * marker URL so that any generic code that expects a URL has something to store,
     * but it should never be opened in a browser.
     */
    override val newURL: String
        get() = "todoist://api"

    override val newPassword: String
        get() = binding.password.text.toString().trim { it <= ' ' }

    override val helpUrl = R.string.url_todoist

    private suspend fun saveAccountAndFinish() {
        if (caldavAccount!!.id == Task.NO_ID) {
            caldavDao.insert(caldavAccount!!)
            firebase.logEvent(
                R.string.event_sync_add_account,
                R.string.param_type to Constants.SYNC_TYPE_TODOIST
            )
        } else {
            caldavDao.update(caldavAccount!!)
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    override suspend fun removeAccount() {
        try {
            caldavAccount?.let { clientProvider.forAccount(it).logout() }
        } catch (e: Exception) {
            Timber.e(e)
        }
        super.removeAccount()
    }
}
