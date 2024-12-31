package dev.brahmkshatriya.echo.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.UserClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extensions.isClient
import dev.brahmkshatriya.echo.offline.OfflineExtension
import dev.brahmkshatriya.echo.utils.future
import dev.brahmkshatriya.echo.utils.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

abstract class AndroidAutoCallback(
    open val settings: SharedPreferences,
    open val context: Context,
    open val scope: CoroutineScope,
    open val extensionList: StateFlow<List<MusicExtension>?>
) : MediaLibrarySession.Callback {

    companion object {
        private const val ROOT = "root"
        private const val LIBRARY = "library"
        private const val HOME = "home"
        private const val SEARCH = "search"
        private const val SHELF = "shelf"
        private const val LIST = "list"

        private const val ARTIST = "artist"
        private const val USER = "user"
        private const val ALBUM = "album"
        private const val PLAYLIST = "playlist"
        private const val RADIO = "radio"

        private fun browsableItem(
            id: String,
            title: String,
            subtitle: String? = null,
            browsable: Boolean = true,
            artWorkUri: String? = null,
            type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        ) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(browsable)
                    .setMediaType(type)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artWorkUri?.toUri())
                    .build()
            )
            .build()

        private fun Extension<*>.toMediaItem(context: Context) = browsableItem(
            "$ROOT/$id", name, context.getString(R.string.extension),
            instance.value.isSuccess,
            metadata.iconUrl?.toImageHolder()?.toJson()
        )

        @OptIn(UnstableApi::class)
        val notSupported =
            LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_NOT_SUPPORTED)

        @OptIn(UnstableApi::class)
        val errorIo = LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_IO)

        inline fun <reified C> Extension<*>.get(
            block: C.() -> List<MediaItem>
        ): LibraryResult<ImmutableList<MediaItem>> = runCatching {
            val client = instance.value.getOrThrow() as? C ?: return@runCatching notSupported
            LibraryResult.ofItemList(
                client.block(),
                MediaLibraryService.LibraryParams.Builder()
                    .setOffline(client is OfflineExtension)
                    .build()
            )
        }.getOrElse {
            it.printStackTrace()
            errorIo
        }

        private val radioMap = mutableMapOf<String, Radio>()
        private fun EchoMediaItem.toMediaItem(
            settings: SharedPreferences, extId: String
        ): MediaItem = when (this) {
            is EchoMediaItem.TrackItem -> MediaItemUtils.build(settings, track, extId, null)
            else -> {
                val (page, type) = when (this) {
                    is EchoMediaItem.Profile.ArtistItem -> ARTIST to MediaMetadata.MEDIA_TYPE_ARTIST
                    is EchoMediaItem.Profile.UserItem -> USER to MediaMetadata.MEDIA_TYPE_PODCAST
                    is EchoMediaItem.Lists.AlbumItem -> ALBUM to MediaMetadata.MEDIA_TYPE_ALBUM
                    is EchoMediaItem.Lists.PlaylistItem -> PLAYLIST to MediaMetadata.MEDIA_TYPE_PLAYLIST
                    is EchoMediaItem.Lists.RadioItem -> {
                        radioMap[id] = radio
                        RADIO to MediaMetadata.MEDIA_TYPE_RADIO_STATION
                    }

                    else -> throw IllegalStateException("Invalid type")
                }
                browsableItem(
                    "$ROOT/$extId/$page", title, subtitleWithE, true, cover?.toJson(), type
                )
            }
        }

        // TODO Add support for shelf.more
        private val listsMap = mutableMapOf<String, Shelf.Lists<*>>()
        private fun getListsItems(
            settings: SharedPreferences, id: String, extId: String
        ) = when (val shelf = listsMap[id]!!) {
            is Shelf.Lists.Categories -> shelf.list.map { it.toMediaItem(settings, extId) }
            is Shelf.Lists.Items -> shelf.list.map { it.toMediaItem(settings, extId) }
            is Shelf.Lists.Tracks -> shelf.list.map {
                MediaItemUtils.build(settings, it, extId, null)
            }
        }

        private fun Shelf.toMediaItem(
            settings: SharedPreferences, extId: String
        ): MediaItem = when (this) {
            is Shelf.Category -> {
                val items = items
                if (items != null) {
                    val id = "${items.hashCode()}"
                    shelvesMap[id] = items
                }
                browsableItem("$ROOT/$extId/$SHELF/$id", title, subtitle, items != null)
            }

            is Shelf.Item -> media.toMediaItem(settings, extId)
            is Shelf.Lists<*> -> {
                val id = "${hashCode()}"
                listsMap[id] = this
                browsableItem("$ROOT/$extId/$LIST/$id", title, subtitle)
            }
        }


        // THIS PROBABLY BREAKS GOING BACK TBH, NEED TO TEST
        private val shelvesMap = mutableMapOf<String, PagedData<Shelf>>()
        private suspend fun getShelfItems(
            context: Context, settings: SharedPreferences, id: String, extId: String
        ): List<MediaItem> {
            val shelf = shelvesMap[id]!!
            val list = shelf.loadNext() ?: emptyList()
            val hasMore = shelf.hasNext()
            return listOfNotNull(
                *list.map { it.toMediaItem(settings, extId) }.toTypedArray(),
                if (hasMore)
                    browsableItem("$ROOT/$extId/$SHELF/$id", context.getString(R.string.more))
                else null
            )
        }

        private suspend fun PagedData<Shelf>.toMediaItems(
            context: Context, settings: SharedPreferences, extId: String
        ): List<MediaItem> {
            val id = "${hashCode()}"
            shelvesMap[id] = this
            return getShelfItems(context, settings, id, extId)
        }

        private suspend inline fun <reified T> Extension<*>.getFeed(
            context: Context,
            settings: SharedPreferences,
            parentId: String,
            page: String,
            getTabs: T.() -> List<Tab>,
            getFeed: T.(tab: Tab?) -> PagedData<Shelf>
        ) = get<T> {
            val tabId = parentId.substringAfter("$page/").substringBefore("/")
            if (tabId.isNotBlank()) {
                val tab = Tab(tabId, tabId)
                getFeed(tab).toMediaItems(context, settings, id)
            } else {
                val tabs = getTabs()
                if (tabs.isEmpty()) getFeed(null).toMediaItems(context, settings, id)
                else tabs.map { browsableItem("$ROOT/$id/$page/${it.id}", it.title) }
            }
        }
    }

    @CallSuper
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ) = scope.future {
        LibraryResult.ofItem(browsableItem(ROOT, "", browsable = false), null)
    }

    @OptIn(UnstableApi::class)
    @CallSuper
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val extensions = extensionList.first { it != null }!!
        if (parentId == ROOT) return@future LibraryResult.ofItemList(
            extensions.map { it.toMediaItem(context) },
            null
        )
        val extId = parentId.substringAfter("$ROOT/").substringBefore("/")
        val extension = extensions.first { it.id == extId }
        val searchQuery = params?.extras?.getString("search_query") ?: ""
        val type = parentId.substringAfter("$extId/").substringBefore("/")
        when (type) {
            ALBUM -> extension.get<AlbumClient> {
                val id = parentId.substringAfter("$ALBUM/").substringBefore("/")
                val album = loadAlbum(Album(id, ""))
                val tracks = loadTracks(album).loadAll()
                val mediaItem = album.toMediaItem()
                tracks.map { MediaItemUtils.build(settings, it, extId, mediaItem) }
            }

            PLAYLIST -> extension.get<PlaylistClient> {
                val id = parentId.substringAfter("$PLAYLIST/").substringBefore("/")
                val playlist = loadPlaylist(Playlist(id, "", false))
                val tracks = loadTracks(playlist).loadAll()
                val mediaItem = playlist.toMediaItem()
                tracks.map { MediaItemUtils.build(settings, it, extId, mediaItem) }
            }

            RADIO -> extension.get<RadioClient> {
                val id = parentId.substringAfter("$RADIO/").substringBefore("/")
                val radio = radioMap[id]!!
                val tracks = loadTracks(radio).loadAll()
                val mediaItem = radio.toMediaItem()
                tracks.map { MediaItemUtils.build(settings, it, extId, mediaItem) }
            }

            ARTIST -> extension.get<ArtistClient> {
                val id = parentId.substringAfter("$ARTIST/").substringBefore("/")
                val artist = loadArtist(Artist(id, ""))
                getShelves(artist).toMediaItems(context, settings, extId)
            }

            USER -> extension.get<UserClient> {
                val id = parentId.substringAfter("$USER/").substringBefore("/")
                val user = loadUser(User(id, ""))
                getShelves(user).toMediaItems(context, settings, extId)
            }

            LIST -> extension.get<ExtensionClient> {
                val id = parentId.substringAfter("$LIST/").substringBefore("/")
                getListsItems(settings, id, extId)
            }

            SHELF -> extension.get<ExtensionClient> {
                val id = parentId.substringAfter("$SHELF/").substringBefore("/")
                getShelfItems(context, settings, id, extId)
            }

            HOME -> extension.getFeed<HomeFeedClient>(
                context, settings, parentId, HOME, { getHomeTabs() }, { getHomeFeed(it) }
            )

            LIBRARY -> extension.getFeed<LibraryFeedClient>(
                context, settings, parentId, LIBRARY, { getLibraryTabs() }, { getLibraryFeed(it) }
            )

            SEARCH -> extension.getFeed<SearchFeedClient>(
                context, settings, parentId, SEARCH,
                { searchTabs(searchQuery) }, { searchFeed(searchQuery, it) }
            )

            else -> LibraryResult.ofItemList(
                listOfNotNull(
                    if (extension.isClient<HomeFeedClient>())
                        browsableItem("$ROOT/$extId/$HOME", context.getString(R.string.home))
                    else null,
                    if (extension.isClient<SearchFeedClient>())
                        browsableItem("$ROOT/$extId/$SEARCH", context.getString(R.string.search))
                    else null,
                    if (extension.isClient<LibraryFeedClient>())
                        browsableItem("$ROOT/$extId/$LIBRARY", context.getString(R.string.library))
                    else null,
                ),
                null
            )
        }
    }

    @OptIn(UnstableApi::class)
    @CallSuper
    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future {
            val extensions = extensionList.first { it != null }!!
            LibraryResult.ofItemList(
                extensions.map { ext ->
                    browsableItem("$ROOT/${ext.id}/$SEARCH", ext.name, query)
                },
                MediaLibraryService.LibraryParams.Builder()
                    .setExtras(bundleOf("search_query" to query))
                    .build()
            )
        }
    }
}