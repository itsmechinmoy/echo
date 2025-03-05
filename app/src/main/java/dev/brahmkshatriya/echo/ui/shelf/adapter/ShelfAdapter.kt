package dev.brahmkshatriya.echo.ui.shelf.adapter

import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.common.PagingUtils
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.shelf.adapter.lists.ShelfListsAdapter
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.applyScaleAnimation
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.lang.ref.WeakReference

class ShelfAdapter(
    private val listener: Listener, private val stateViewModel: StateViewModel
) : PagingDataAdapter<Shelf, ShelfAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<Shelf>() {
        override fun areItemsTheSame(oldItem: Shelf, newItem: Shelf) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Shelf, newItem: Shelf) = oldItem == newItem
    }

    abstract class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var extensionId: String? = null
        open fun onCurrentChanged(current: PlayerState.Current?) {}
        abstract fun bind(item: Shelf?)
    }

    interface Listener : ShelfListsAdapter.Listener {
        fun onMoreClicked(extensionId: String?, shelf: Shelf.Lists<*>?, view: View)
        fun onShuffleClicked(extensionId: String?, shelf: Shelf.Lists.Tracks?, view: View)
        fun onShelfSearchClicked(extensionId: String?, shelf: PagingData<Shelf>?, view: View)
        fun onShelfSortClicked(extensionId: String?, shelf: PagingData<Shelf>?, view: View)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
        holder.onCurrentChanged(current)
        holder.itemView.applyScaleAnimation(listOf(0.9f, 1f, 0.9f, 1f))
        if (holder !is ListsShelfViewHolder) return
        stateViewModel.visibleScrollableViews[position] = WeakReference(holder)
        holder.layoutManager?.apply {
            val state = stateViewModel.layoutManagerStates[position]
            if (state != null) onRestoreInstanceState(state)
            else scrollToPosition(0)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val (type, extra) = when (val item = getItem(position)) {
            is Shelf.Item -> 1 to MediaItemShelfViewHolder.getViewType(item)
            is Shelf.Category -> 2 to null
            is Shelf.Lists<*> -> 3 to null
            null -> error("null shelf item")
        }
        return type * 10 + (extra ?: 0)
    }

    private val sharedPool = RecycledViewPool()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val (type, extra) = viewType / 10 to viewType % 10
        val viewHolder = when (type) {
            1 -> MediaItemShelfViewHolder.create(listener, this, inflater, parent, extra)
            2 -> CategoryShelfViewHolder.create(listener, inflater, parent)
            3 -> ListsShelfViewHolder.create(sharedPool, listener, inflater, parent)
            else -> error("unknown view type")
        }
        viewHolder.extensionId = extensionId
        return viewHolder
    }

    var current: PlayerState.Current? = null
    override fun onViewAttachedToWindow(holder: ViewHolder) {
        holder.onCurrentChanged(current)
        holder.extensionId = extensionId
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.onCurrentChanged(current)
        holder.extensionId = extensionId
    }

    var recyclerView: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    private fun onEachViewHolder(action: ViewHolder.() -> Unit) {
        recyclerView?.let { rv ->
            for (i in 0 until rv.childCount) {
                val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? ViewHolder
                holder?.action()
            }
        }
    }

    fun onCurrentChanged(current: PlayerState.Current?) {
        this.current = current
        onEachViewHolder { onCurrentChanged(current) }
    }

    init {
        addLoadStateListener {
            if (it.refresh == LoadState.Loading) clearState()
        }
    }

    var extensionId: String? = null
    var shelf: PagedData<Shelf>? = null
    suspend fun submit(
        extensionId: String?,
        data: PagedData<Shelf>?,
        pagingData: PagingData<Shelf>?
    ) {
        saveState()
        this.extensionId = extensionId
        onEachViewHolder { this.extensionId = extensionId }
        this.shelf = data
        val page = if (extensionId == null) PagingUtils.loadingPagingData()
        else pagingData ?: PagingData.empty()
        submitData(page)
    }

    class StateViewModel : ViewModel() {
        val layoutManagerStates = hashMapOf<Int, Parcelable?>()
        val visibleScrollableViews = hashMapOf<Int, WeakReference<ListsShelfViewHolder>>()
    }

    private fun clearState() {
        stateViewModel.layoutManagerStates.clear()
        stateViewModel.visibleScrollableViews.clear()
    }

    private fun saveScrollState(
        holder: ListsShelfViewHolder, block: ((ListsShelfViewHolder) -> Unit)? = null
    ) {
        val layoutManagerStates = stateViewModel.layoutManagerStates
        layoutManagerStates[holder.bindingAdapterPosition] =
            holder.layoutManager?.onSaveInstanceState()
        block?.invoke(holder)
    }

    private fun saveState() {
        stateViewModel.visibleScrollableViews.values.forEach { item ->
            item.get()?.let { saveScrollState(it) }
        }
        stateViewModel.visibleScrollableViews.clear()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (holder is ListsShelfViewHolder) saveScrollState(holder) {
            stateViewModel.visibleScrollableViews.remove(holder.bindingAdapterPosition)
        }
    }

    companion object {
        fun Fragment.getShelfAdapter(
            listener: Listener
        ): ShelfAdapter {
            val viewModel by activityViewModel<PlayerViewModel>()
            val stateViewModel by viewModels<StateViewModel>()
            val adapter = ShelfAdapter(listener, stateViewModel)
            observe(viewModel.playerState.current) { adapter.onCurrentChanged(it) }
            return adapter
        }
    }
}