package dev.brahmkshatriya.echo.ui.extensions.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.helpers.ExtensionType
import dev.brahmkshatriya.echo.databinding.FragmentManageExtensionsBinding
import dev.brahmkshatriya.echo.ui.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.UiViewModel.Companion.applyContentInsets
import dev.brahmkshatriya.echo.ui.UiViewModel.Companion.applyInsetsMain
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.extensions.ExtensionInfoFragment
import dev.brahmkshatriya.echo.ui.extensions.ExtensionInfoFragment.Companion.getType
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel
import dev.brahmkshatriya.echo.ui.extensions.add.ExtensionsAddListBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import dev.brahmkshatriya.echo.utils.ui.UiUtils.configure
import dev.brahmkshatriya.echo.utils.ui.UiUtils.onAppBarChangeListener
import kotlinx.coroutines.Job
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ManageExtensionsFragment : Fragment() {
    private var binding by autoCleared<FragmentManageExtensionsBinding>()
    private val viewModel by activityViewModel<ExtensionsViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentManageExtensionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view)
        applyInsetsMain(binding.appBarLayout, binding.recyclerView) {
            binding.fabContainer.applyContentInsets(it)
        }
        applyBackPressCallback()
        binding.appBarLayout.onAppBarChangeListener { offset ->
            binding.appBarOutline.alpha = offset
            binding.appBarOutline.isVisible = offset > 0
            binding.toolBar.alpha = 1 - offset
        }
        binding.toolBar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        FastScrollerHelper.applyTo(binding.recyclerView)
        val refresh = binding.toolBar.findViewById<View>(R.id.menu_refresh)
        refresh.setOnClickListener { viewModel.refresh() }
        binding.swipeRefresh.configure { viewModel.refresh() }

        binding.fabAddExtensions.setOnClickListener {
            ExtensionsAddListBottomSheet.LinkFile().show(parentFragmentManager, null)
        }

        val tabs = ExtensionType.entries.map {
            binding.tabLayout.newTab().apply {
                setText(getType(it))
            }
        }
        binding.tabLayout.run {
            tabs.forEach { addTab(it) }
        }

        var type = ExtensionType.entries[binding.tabLayout.selectedTabPosition]
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                viewModel.moveExtensionItem(type, toPos, fromPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun getMovementFlags(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ) = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        }

        val touchHelper = ItemTouchHelper(callback)
        val extensionAdapter = ExtensionAdapter(object : ExtensionAdapter.Listener {
            override fun onClick(extension: Extension<*>, view: View) {
                openFragment(ExtensionInfoFragment.newInstance(extension), view)
            }

            override fun onDragHandleTouched(viewHolder: ExtensionAdapter.ViewHolder) {
                touchHelper.startDrag(viewHolder)
            }

            override fun onOpenClick(extension: Extension<*>) {
                viewModel.onExtensionSelected(extension as MusicExtension)
                parentFragmentManager.popBackStack()
                parentFragmentManager.popBackStack()
            }
        })

        var job: Job? = null
        fun change(pos: Int): Job {
            job?.cancel()
            type = ExtensionType.entries[pos]
            viewModel.lastSelectedManageExt = pos
            val flow = viewModel.extensions.getFlow(type)
            return observe(flow) { list ->
                binding.swipeRefresh.isRefreshing = list == null
                extensionAdapter.submit(list ?: emptyList())
            }
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            fun select(tab: TabLayout.Tab) = run { job = change(tab.position) }
            override fun onTabSelected(tab: TabLayout.Tab) = select(tab)
            override fun onTabReselected(tab: TabLayout.Tab) = select(tab)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
        })

        binding.tabLayout.selectTab(tabs[viewModel.lastSelectedManageExt])
        binding.recyclerView.adapter = extensionAdapter.withEmptyAdapter()
        touchHelper.attachToRecyclerView(binding.recyclerView)
    }
}