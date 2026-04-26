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
import com.w600.glasses.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AiCaptureFragment : Fragment() {

    private var _binding: FragmentAiCaptureBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAiCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnRefreshMedia.setOnClickListener {
            viewModel.refresh()
            viewModel.loadMediaList(0)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.aiStatus.collectLatest { status ->
                binding.tvAiStatus.text = status
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.aiFrames.collectLatest { data ->
                binding.tvPhotoMeta.text = "Photo packet: ${data.size} bytes"
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    binding.ivAiPhoto.setImageBitmap(bitmap)
                    binding.tvPhotoHint.visibility = View.GONE
                } else {
                    binding.tvPhotoHint.visibility = View.VISIBLE
                    binding.tvPhotoHint.text = "Raw photo packet received. Decoder is waiting for a complete JPEG frame."
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
