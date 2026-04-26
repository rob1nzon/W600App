package com.w600.glasses.ui.ai

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.w600.glasses.databinding.FragmentAiCaptureBinding
import com.w600.glasses.model.MediaFile
import com.w600.glasses.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AiCaptureFragment : Fragment() {

    private var _binding: FragmentAiCaptureBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private var requestedDownloadId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAiCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnRefreshMedia.setOnClickListener {
            viewModel.refresh()
            refreshMediaLists()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.aiStatus.collectLatest { status ->
                binding.tvAiStatus.text = status
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.aiFrames.collectLatest { data ->
                binding.tvPhotoMeta.text = "AI raw packet: ${data.size} bytes"
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    binding.ivAiPhoto.setImageBitmap(bitmap)
                    binding.tvPhotoHint.visibility = View.GONE
                } else {
                    binding.tvPhotoHint.visibility = View.VISIBLE
                    binding.tvPhotoHint.text = "AI packet received. This packet is not a JPEG photo; checking media list."
                    refreshMediaLists()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mediaCount.collectLatest { count ->
                if (count != null) {
                    binding.tvAudioMeta.text = "Recordings: ${count.records}"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mediaList.collectLatest { files ->
                val latest = files.firstOrNull()
                binding.tvLatestPhoto.text = "Latest photo: ${latest.format()}"
                val id = latest?.fileId.orEmpty()
                if (id.isNotEmpty() && requestedDownloadId != id) {
                    requestedDownloadId = id
                    binding.tvPhotoMeta.text = "Downloading latest photo..."
                    viewModel.downloadMedia(id)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.audioList.collectLatest { files ->
                binding.tvLatestAudio.text = "Latest audio: ${files.firstOrNull().format()}"
            }
        }

        refreshMediaLists()
    }

    private fun refreshMediaLists() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadMediaList(0)
            delay(300)
            viewModel.loadAudioList(0)
        }
    }

    private fun MediaFile?.format(): String {
        if (this == null) return "-"
        val name = fileName.ifEmpty { fileId.ifEmpty { "unnamed" } }
        val size = when {
            fileSize >= 1_048_576 -> "${"%.1f".format(fileSize / 1_048_576.0)} MB"
            fileSize >= 1024 -> "${"%.0f".format(fileSize / 1024.0)} KB"
            fileSize > 0 -> "$fileSize B"
            else -> "-"
        }
        val date = createTime.take(19)
        return listOf(name, date, size).filter { it.isNotEmpty() && it != "-" }.joinToString("  ")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
