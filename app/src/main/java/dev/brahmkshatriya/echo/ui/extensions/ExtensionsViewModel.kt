package dev.brahmkshatriya.echo.ui.extensions

import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.SettingsChangeListenerClient
import dev.brahmkshatriya.echo.common.helpers.ExtensionType
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.Extensions.Companion.priorityKey
import dev.brahmkshatriya.echo.extensions.InstallationUtils
import dev.brahmkshatriya.echo.extensions.InstallationUtils.installExtension
import dev.brahmkshatriya.echo.extensions.InstallationUtils.uninstallExtension
import dev.brahmkshatriya.echo.extensions.Updater
import dev.brahmkshatriya.echo.extensions.db.models.ExtensionEntity
import dev.brahmkshatriya.echo.ui.extensions.list.ExtensionListViewModel
import dev.brahmkshatriya.echo.ui.settings.ExtensionFragment.ExtensionPreference.Companion.prefId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

class ExtensionsViewModel(
    val extensionLoader: ExtensionLoader,
    val app: App
) : ExtensionListViewModel<MusicExtension>() {
    val extensions = extensionLoader.extensions
    override val extensionsFlow = extensions.music
    override val currentSelectionFlow = MutableStateFlow(extensions.current.value)
    override fun onExtensionSelected(extension: MusicExtension) {
        extensions.setupMusicExtension(extension)
    }

    fun onSettingsChanged(extension: Extension<*>, settings: Settings, key: String?) {
        viewModelScope.launch {
            extension.get<SettingsChangeListenerClient, Unit>(app.throwFlow) {
                onSettingsChanged(settings, key)
            }
        }
    }

    var lastSelectedManageExt: Int = 0
    fun refresh() = extensionLoader.refresh()

    private val extensionDao = extensionLoader.db.extensionDao()
    fun setExtensionEnabled(extensionType: ExtensionType, id: String, checked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            extensionDao.setExtension(ExtensionEntity(id, extensionType, checked))
            refresh()
        }
    }

    suspend fun install(context: FragmentActivity, file: File, installAsApk: Boolean): Boolean {
        val result = installExtension(context, file, installAsApk).getOrElse {
            app.throwFlow.emit(it)
            false
        }
        if (result) app.messageFlow.emit(
            Message(app.context.getString(R.string.extension_installed_successfully))
        )
        return result
    }

    suspend fun uninstall(
        context: FragmentActivity, extension: Extension<*>, function: (Boolean) -> Unit
    ) {
        context.deleteSharedPreferences(extension.prefId)
        val result = uninstallExtension(context, extension).getOrElse {
            app.throwFlow.emit(it)
            false
        }
        if (result) app.messageFlow.emit(
            Message(app.context.getString(R.string.extension_uninstalled_successfully))
        )
        function(result)
    }

    fun moveExtensionItem(type: ExtensionType, toPos: Int, fromPos: Int) {
        val flow = extensions.priorityMap[type]!!
        val list = extensions.getFlow(type).value.orEmpty().map { it.id }.toMutableList()
        list.add(toPos, list.removeAt(fromPos))
        flow.value = list
        app.settings.edit {
            putString(type.priorityKey(), list.joinToString(","))
        }
    }

    fun addFromFile(context: FragmentActivity) {
        viewModelScope.launch {
            InstallationUtils.addFromFile(context)
        }
    }

    fun addExtensions(
        requireActivity: FragmentActivity,
        selectedExtensions: MutableList<Updater.ExtensionAssetResponse>
    ) {
        viewModelScope.launch {
            InstallationUtils.addExtensions(
                this@ExtensionsViewModel,
                requireActivity,
                selectedExtensions
            )
        }
    }

    val addingFlow = extensionLoader.updater.addingFlow
    fun addFromLinkOrCode(link: String) {
        viewModelScope.launch {
            extensionLoader.updater.addFromLinkOrCode(link)
        }
    }

    companion object {
        fun FragmentActivity.updateExtensions(force: Boolean = false) {
            val viewModel by viewModel<ExtensionsViewModel>()
            viewModel.viewModelScope.launch {
                viewModel.extensionLoader.updater.updateExtensions(this@updateExtensions, force)
            }
        }
    }
}