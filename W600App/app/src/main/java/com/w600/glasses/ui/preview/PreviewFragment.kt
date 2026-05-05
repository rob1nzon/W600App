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
import com.w600.glasses.util.AppLogger
import java.nio.ByteBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "PreviewFragment"

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
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var decodedInputCount = 0

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

        val nals = annexBNals(data)
        if (nals.isEmpty()) return

        nals.firstOrNull { it.type == 7 }?.bytes?.let { pendingSps = it }
        nals.firstOrNull { it.type == 8 }?.bytes?.let { pendingPps = it }

        val nalTypes = nals.map { it.type }.toSet()
        val dimensions = pendingSps?.let { parseSpsDimensions(it) }
        if (dimensions != null && (codecWidth != dimensions.first || codecHeight != dimensions.second)) {
            codecWidth = dimensions.first
            codecHeight = dimensions.second
            releaseCodec()
            pendingSps = nals.firstOrNull { it.type == 7 }?.bytes
            pendingPps = nals.firstOrNull { it.type == 8 }?.bytes
            AppLogger.d(TAG, "H264 SPS size=${codecWidth}x$codecHeight")
        }

        if (decodedInputCount % 60 == 0 || (codec == null && nalTypes.any { it == 5 || it == 7 || it == 8 })) {
            AppLogger.d(TAG, "H264 frame bytes=${data.size} nals=$nalTypes codec=${codec != null} size=${codecWidth}x$codecHeight")
        }

        if (codec == null) {
            val sps = pendingSps
            val pps = pendingPps
            val hasIdr = nalTypes.any { it == 5 }
            if (sps == null || pps == null || !hasIdr) {
                return
            }
            setupCodec(surface, sps, pps) ?: return
            canDecodeH264 = true
        }

        val c = codec ?: return
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
                decodedInputCount++
            }
            val info = MediaCodec.BufferInfo()
            var outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                AppLogger.d(TAG, "H264 output format=${c.outputFormat}")
                outIdx = c.dequeueOutputBuffer(info, 0)
            }
            while (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true)
                outIdx = c.dequeueOutputBuffer(info, 0)
            }
        } catch (err: Exception) {
            AppLogger.e(TAG, "H264 decode failed", err)
            releaseCodec()
        }
    }

    private fun setupCodec(surface: Surface, sps: ByteArray, pps: ByteArray): MediaCodec? {
        return try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, codecWidth, codecHeight)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 128 * 1024)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(fmt, surface, null, 0)
                it.start()
                codec = it
                AppLogger.d(TAG, "H264 codec started name=${it.name} size=${codecWidth}x$codecHeight sps=${sps.size} pps=${pps.size}")
            }
        } catch (err: Exception) {
            AppLogger.e(TAG, "H264 codec start failed", err)
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
        pendingSps = null
        pendingPps = null
        decodedInputCount = 0
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
        return annexBNals(data).map { it.type }.toSet()
    }

    private data class NalUnit(val type: Int, val bytes: ByteArray)

    private fun annexBNals(data: ByteArray): List<NalUnit> {
        val units = mutableListOf<NalUnit>()
        var i = 0
        while (i < data.size) {
            val start = findStartCode(data, i)
            if (start < 0) break
            val startCodeLength = if (data.getOrNull(start + 2) == 1.toByte()) 3 else 4
            val nalStart = start + startCodeLength
            if (nalStart >= data.size) break
            val nextStart = findStartCode(data, nalStart)
            val nalEnd = if (nextStart >= 0) nextStart else data.size
            val type = data[nalStart].toInt() and 0x1F
            if (nalEnd > start) {
                units.add(NalUnit(type, data.copyOfRange(start, nalEnd)))
            }
            i = nalEnd
        }
        return units
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i <= data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (i <= data.size - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    private fun parseSpsDimensions(spsWithStartCode: ByteArray): Pair<Int, Int>? {
        val start = findStartCode(spsWithStartCode, 0).takeIf { it >= 0 } ?: 0
        val startCodeLength = if (spsWithStartCode.getOrNull(start + 2) == 1.toByte()) 3 else 4
        val nalStart = (start + startCodeLength + 1).coerceAtMost(spsWithStartCode.size)
        val rbsp = mutableListOf<Int>()
        var i = nalStart
        while (i < spsWithStartCode.size) {
            if (i + 2 < spsWithStartCode.size &&
                spsWithStartCode[i] == 0.toByte() &&
                spsWithStartCode[i + 1] == 0.toByte() &&
                spsWithStartCode[i + 2] == 3.toByte()
            ) {
                rbsp.add(0)
                rbsp.add(0)
                i += 3
            } else {
                rbsp.add(spsWithStartCode[i].toInt() and 0xFF)
                i++
            }
        }
        val reader = BitReader(rbsp)
        return runCatching {
            val profileIdc = reader.readBits(8)
            reader.readBits(8)
            reader.readBits(8)
            reader.readUE()
            if (profileIdc in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chromaFormatIdc = reader.readUE()
                if (chromaFormatIdc == 3) reader.readBits(1)
                reader.readUE()
                reader.readUE()
                reader.readBits(1)
                if (reader.readBits(1) == 1) {
                    repeat(if (chromaFormatIdc != 3) 8 else 12) {
                        if (reader.readBits(1) == 1) skipScalingList(reader, if (it < 6) 16 else 64)
                    }
                }
            }
            reader.readUE()
            when (reader.readUE()) {
                0 -> reader.readUE()
                1 -> {
                    reader.readBits(1)
                    reader.readSE()
                    reader.readSE()
                    repeat(reader.readUE()) { reader.readSE() }
                }
            }
            reader.readUE()
            reader.readBits(1)
            val picWidthInMbsMinus1 = reader.readUE()
            val picHeightInMapUnitsMinus1 = reader.readUE()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) reader.readBits(1)
            reader.readBits(1)
            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.readBits(1) == 1) {
                cropLeft = reader.readUE()
                cropRight = reader.readUE()
                cropTop = reader.readUE()
                cropBottom = reader.readUE()
            }
            val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * 2
            val height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16 - (cropTop + cropBottom) * 2
            width to height
        }.getOrNull()
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        repeat(size) {
            if (nextScale != 0) {
                nextScale = (lastScale + reader.readSE() + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private class BitReader(private val bytes: List<Int>) {
        private var bitOffset = 0

        fun readBits(count: Int): Int {
            var value = 0
            repeat(count) {
                val byteIndex = bitOffset / 8
                val bitIndex = 7 - (bitOffset % 8)
                value = (value shl 1) or (((bytes.getOrElse(byteIndex) { 0 }) shr bitIndex) and 1)
                bitOffset++
            }
            return value
        }

        fun readUE(): Int {
            var zeros = 0
            while (readBits(1) == 0) zeros++
            val suffix = if (zeros == 0) 0 else readBits(zeros)
            return (1 shl zeros) - 1 + suffix
        }

        fun readSE(): Int {
            val codeNum = readUE()
            return if (codeNum % 2 == 0) -(codeNum / 2) else (codeNum + 1) / 2
        }
    }

    override fun onDestroyView() {
        stopPreview()
        releaseCodec()
        super.onDestroyView()
        _binding = null
    }
}
