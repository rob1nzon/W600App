package com.w600.glasses.ui.media

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.w600.glasses.databinding.FragmentMediaBinding
import com.w600.glasses.databinding.ItemMediaFileBinding
import com.w600.glasses.model.MediaFile
import com.w600.glasses.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MediaFragment : Fragment() {

    private var _binding: FragmentMediaBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private var currentPage = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = MediaAdapter(
            onDelete = { file ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete ${file.fileName}?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteMedia(file.fileId) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onDownload = { file ->
                viewModel.downloadMedia(file.fileId)
                Toast.makeText(requireContext(), "Downloading ${file.fileName}…", Toast.LENGTH_SHORT).show()
            }
        )

        binding.recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recycler.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            currentPage = 0
            viewModel.loadMediaList(0)
        }
        binding.btnLoadMore.setOnClickListener {
            currentPage++
            viewModel.loadMediaList(currentPage)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mediaCount.collectLatest { mc ->
                mc?.let {
                    binding.tvCount.text = "Photos: ${it.photos}  Videos: ${it.videos}  Recordings: ${it.records}"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mediaList.collectLatest { files ->
                binding.swipeRefresh.isRefreshing = false
                if (currentPage == 0) adapter.submitList(files)
                else adapter.appendList(files)
                binding.btnLoadMore.visibility = if (files.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        // Auto-load
        viewModel.loadMediaList(0)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class MediaAdapter(
    private val onDelete: (MediaFile) -> Unit,
    private val onDownload: (MediaFile) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    private val items = mutableListOf<MediaFile>()

    fun submitList(list: List<MediaFile>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun appendList(list: List<MediaFile>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    inner class VH(val b: ItemMediaFileBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        VH(ItemMediaFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val f = items[pos]
        holder.b.tvFileName.text = f.fileName.ifEmpty { "File ${pos + 1}" }
        holder.b.tvFileSize.text = formatSize(f.fileSize)
        holder.b.tvFileType.text = f.fileType.uppercase()
        holder.b.tvDate.text = f.createTime.take(10)
        holder.b.ivTypeIcon.setImageResource(
            if (f.fileType == "video")
                android.R.drawable.ic_media_play
            else
                android.R.drawable.ic_menu_camera
        )
        holder.b.btnDelete.setOnClickListener { onDelete(f) }
        holder.b.btnDownload.setOnClickListener { onDownload(f) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1024      -> "${"%.0f".format(bytes / 1024.0)} KB"
        else               -> "$bytes B"
    }
}
