package com.w600.glasses.ui.preview

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.w600.glasses.databinding.FragmentPreviewBinding
import com.w600.glasses.ui.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var previewJob: Job? = null
    private var isPreviewing = false
    private var codec: MediaCodec? = null
    private var codecWidth = 160
    private var codecHeight = 120
    private var canDecodeH264 = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnStartPreview.setOnClickListener {
            if (!isPreviewing) startPreview() else stopPreview()
        }

        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releaseCodec()
            }
        })

        // Info overlay
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deviceInfo.collectLatest { info ->
                info?.let {
                    binding.tvResolution.text = "Preview: ${it.previewW}×${it.previewH}"
                    if (codecWidth != it.previewW || codecHeight != it.previewH) {
                        codecWidth = it.previewW
                        codecHeight = it.previewH
                        releaseCodec()
                    }
                }
            }
        }
    }

    private fun startPreview() {
        isPreviewing = true
        binding.btnStartPreview.text = "Stop Preview"
        viewModel.startPreview()

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.previewFrames.collectLatest { frameData ->
                renderFrame(frameData)
            }
        }
    }

    private fun stopPreview() {
        if (!isPreviewing) return
        isPreviewing = false
        binding.btnStartPreview.text = "Start Preview"
        viewModel.stopPreview()
        previewJob?.cancel()
        previewJob = null
    }

    private fun renderFrame(data: ByteArray) {
        when {
            isJpeg(data) -> {
                releaseCodec()
                renderJpegFrame(data)
            }
            isH264(data) -> {
                decodeH264Frame(data)
            }
        }
    }

    private fun renderJpegFrame(data: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        val holder = _binding?.surfaceView?.holder ?: run {
            bitmap.recycle()
            return
        }
        if (!holder.surface.isValid) {
            bitmap.recycle()
            return
        }

        val canvas = holder.lockCanvas() ?: run {
            bitmap.recycle()
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            val dst = fitCenter(
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                targetWidth = canvas.width,
                targetHeight = canvas.height
            )
            canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dst, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
            bitmap.recycle()
        }
    }

    private fun decodeH264Frame(data: ByteArray) {
        val surface = _binding?.surfaceView?.holder?.surface ?: return
        if (!surface.isValid) return

        val nalTypes = nalTypes(data)
        if (!canDecodeH264) {
            canDecodeH264 = nalTypes.any { it == 7 || it == 5 }
            if (!canDecodeH264) return
        }

        val c = codec ?: setupCodec(surface) ?: return
        try {
            val idx = c.dequeueInputBuffer(10_000L)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                if (data.size > buf.remaining()) {
                    releaseCodec()
                    return
                }
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outIdx = c.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true)
                outIdx = c.dequeueOutputBuffer(info, 0)
            }
        } catch (_: Exception) {
            releaseCodec()
        }
    }

    private fun setupCodec(surface: Surface): MediaCodec? {
        return try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, codecWidth, codecHeight)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 128 * 1024)
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(fmt, surface, null, 0)
                it.start()
                codec = it
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseCodec() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        canDecodeH264 = false
    }

    private fun fitCenter(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Rect {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return Rect(0, 0, targetWidth, targetHeight)
        }
        val scale = minOf(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
        val width = (sourceWidth * scale).toInt()
        val height = (sourceHeight * scale).toInt()
        val left = (targetWidth - width) / 2
        val top = (targetHeight - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun isJpeg(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()

    private fun isH264(data: ByteArray): Boolean =
        data.size >= 5 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()

    private fun nalTypes(data: ByteArray): Set<Int> {
        val types = mutableSetOf<Int>()
        var i = 0
        while (i <= data.size - 5) {
            val startCodeLength = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startCodeLength > 0) {
                val headerIndex = i + startCodeLength
                if (headerIndex < data.size) {
                    types.add(data[headerIndex].toInt() and 0x1F)
                }
                i = headerIndex + 1
            } else {
                i++
            }
        }
        return types
    }

    override fun onDestroyView() {
        stopPreview()
        releaseCodec()
        super.onDestroyView()
        _binding = null
    }
}
