package dev.brahmkshatriya.echo.plugger

import android.content.Context
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.offline.OfflineExtension
import kotlinx.coroutines.flow.MutableStateFlow
import tel.jeelpa.plugger.PluginRepo

class LocalExtensionRepo(val context: Context) : PluginRepo<ExtensionMetadata, ExtensionClient> {
    private val extension = OfflineExtension(context)
    override fun getAllPlugins() = MutableStateFlow(
        listOf(
            Result.success(OfflineExtension.metadata to extension),
        )
    )
}