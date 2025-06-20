package dev.brahmkshatriya.echo.ui.extensions

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.InstallationUtils
import dev.brahmkshatriya.echo.extensions.InstallationUtils.installExtension
import dev.brahmkshatriya.echo.extensions.InstallationUtils.uninstallExtension
import dev.brahmkshatriya.echo.extensions.SettingsUtils.prefId
import dev.brahmkshatriya.echo.extensions.Updater
import dev.brahmkshatriya.echo.extensions.db.models.ExtensionEntity
import dev.brahmkshatriya.echo.ui.extensions.list.ExtensionListViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

class ExtensionsViewModel(
    val extensionLoader: ExtensionLoader,
    val app: App
) : ExtensionListViewModel<MusicExtension>() {
    val extensions = extensionLoader.extensions
    override val extensionsFlow = extensions.music
    override val currentSelectionFlow = extensions.current
    override fun onExtensionSelected(extension: MusicExtension) {
        extensions.setupMusicExtension(extension, true)
    }

    fun onSettingsChanged(extension: Extension<*>, settings: Settings, key: String?) {
        viewModelScope.launch {
            extension.get<SettingsChangeListenerClient, Unit>(app.throwFlow) {
                onSettingsChanged(settings, key)
            }
        }
    }

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


    fun addFromFile(context: FragmentActivity) {
        viewModelScope.launch {
            InstallationUtils.addFromFile(context)
        }
    }

    fun addExtensions(
        selectedExtensions: List<Updater.ExtensionAssetResponse>
    ) {
        viewModelScope.launch {
            InstallationUtils.addExtensions(
                this@ExtensionsViewModel,
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

    fun updateExtensions(activity: FragmentActivity, force: Boolean) {
        activity.lifecycleScope.launch {
            extensionLoader.updater.updateExtensions(activity, force)
        }
    }

    fun changeExtension(id: String) {
        viewModelScope.launch {
            runCatching {
                val ext = extensions.music.getExtensionOrThrow(id)
                extensions.setupMusicExtension(ext, true)
            }.getOrElse {
                app.throwFlow.emit(it)
            }
        }
    }

    val installExtension = MutableSharedFlow<String>()
    private val extensionInstalled = MutableSharedFlow<Boolean>()
    suspend fun awaitInstall(file: String) {
        installExtension.emit(file)
        extensionInstalled.first()
    }

    val lastSelectedManageExt = MutableStateFlow(0)
    val manageExtListFlow = extensions.combined.combine(lastSelectedManageExt) { _, last ->
        extensions.getFlow(ExtensionType.entries[last]).value
    }

    fun moveExtensionItem(toPos: Int, fromPos: Int) {
        val type = ExtensionType.entries[lastSelectedManageExt.value]
        val flow = extensions.priorityMap[type]!!
        val list = extensions.getFlow(type).value.orEmpty().map { it.id }.toMutableList()
        list.add(toPos, list.removeAt(fromPos))
        flow.value = list
    }

    companion object {
        fun FragmentActivity.configureExtensionsUpdater(force: Boolean = false) {
            val viewModel by viewModel<ExtensionsViewModel>()
            viewModel.updateExtensions(this, force)
            observe(viewModel.installExtension) {
                viewModel.extensionInstalled.emit(installExtension(it))
            }
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    viewModel.run {
                        viewModelScope.launch { extensionInstalled.emit(false) }
                    }
                }
            })
        }
    }
}