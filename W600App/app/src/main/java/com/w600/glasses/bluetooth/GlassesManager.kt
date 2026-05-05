package com.w600.glasses.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.gson.Gson
import com.w600.glasses.util.AppLogger
import com.w600.glasses.model.*
import com.w600.glasses.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "GlassesManager"
private const val DEFAULT_BIND_USER_ID = "1f05e668d4e76858a2ca175e29755b86"

/** Singleton that owns the connection and exposes high-level commands. */
class GlassesManager private constructor(private val ctx: Context) {

    private val btAdapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var conn: GlassesConnection? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    /**
     * LensMoo stores this as DEVICE_CACHE_LOGIN_USER_ID. The value below is from
     * the successful LensMoo btsnoop for the target glasses.
     */
    var bindUserId: String = DEFAULT_BIND_USER_ID
    var bindRandomCode: String? = null
    var bindType: Int = 1 // BindType.DISCOVERY; successful LensMoo response echoes 01 00

    // ── State flows ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    private val _battery = MutableStateFlow<BatteryInfo?>(null)
    val battery: StateFlow<BatteryInfo?> = _battery

    private val _mediaCount = MutableStateFlow<MediaCount?>(null)
    val mediaCount: StateFlow<MediaCount?> = _mediaCount

    private val _mediaList = MutableStateFlow<List<MediaFile>>(emptyList())
    val mediaList: StateFlow<List<MediaFile>> = _mediaList

    private val _audioList = MutableStateFlow<List<MediaFile>>(emptyList())
    val audioList: StateFlow<List<MediaFile>> = _audioList
    private var pendingMediaListType = "photo"
    private var videoPreviewActive = false

    private val _previewFrames = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 8)
    val previewFrames: SharedFlow<ByteArray> = _previewFrames

    private val _aiFrames = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 8)
    val aiFrames: SharedFlow<ByteArray> = _aiFrames

    private val _aiStatus = MutableStateFlow("Waiting for AI button")
    val aiStatus: StateFlow<String> = _aiStatus

    private val _aiTriggers = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val aiTriggers: SharedFlow<Unit> = _aiTriggers

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    // ── Bluetooth scan ───────────────────────────────────────────────────────

    /** Returns bonded (paired) devices that look like W600 glasses */
    fun pairedGlasses(): List<BluetoothDevice> {
        return btAdapter?.bondedDevices
            ?.filter { it.name?.contains("W600", ignoreCase = true) == true
                    || it.name?.contains("Lens", ignoreCase = true) == true
                    || it.name?.contains("glasses", ignoreCase = true) == true }
            ?: emptyList()
    }

    fun allPairedDevices(): List<BluetoothDevice> =
        btAdapter?.bondedDevices?.toList() ?: emptyList()

    // ── Connection ───────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        val state = _connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) return
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting(device.name ?: device.address))
            val c = GlassesConnection(device)

            // Start listening BEFORE the socket opens so we don't miss early packets
            listenToDevice(c)

            val result = c.connect()
            if (result.isSuccess) {
                conn = c
                AppLogger.i(TAG,"Connected to ${device.address}")
                val handshakeOk = runCatching { doHandshake(c) }.getOrElse { err ->
                    AppLogger.e(TAG, "Handshake exception", err)
                    false
                }
                if (handshakeOk) {
                    _connectionState.emit(ConnectionState.Connected(device.name ?: device.address))
                } else {
                    c.disconnect()
                    conn = null
                    _connectionState.emit(ConnectionState.Error("Handshake failed"))
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Connection failed"
                AppLogger.e(TAG,"Connect failed: $err")
                _connectionState.emit(ConnectionState.Error(err))
            }
        }
    }

    fun disconnect() {
        conn?.disconnect()
        conn = null
        scope.launch { _connectionState.emit(ConnectionState.Disconnected) }
    }

    // ── Handshake ────────────────────────────────────────────────────────────

    /**
     * Full connection sequence as observed in btsnoop:
     *   1. Phone → 0001 EXECUTE (61-byte phone challenge)
     *   2. Device → SESSION cmdId=1 or node 0001 (61-byte auth payload)
     *   3. Phone → 0002 EXECUTE (64-byte encrypted response)
     *   4. Device → SESSION cmdId=2 or node 0002 (auth confirmed)
     *   5. Phone → READ 7100
     *   6. Device → 7100 support payload
     *   7. Phone → EXECUTE 102E bind(userId/randomCode/bindType)
     *   8. Phone → READ 7110
     *   9. Device → 102E bind result
     *  10. Phone → WRITE 1007, READ 1001, READ 1003, READ 5713
     */
    private suspend fun doHandshake(c: GlassesConnection): Boolean {
        delay(2000)

        scope.launch {
            c.sessionEvents.collect { evt ->
                AppLogger.d(TAG,"Session: head=0x${evt.head.toString(16)} cmdId=${evt.cmdId} hex=${evt.payload.toHexDump()}")
                when {
                    evt.head == Head.SESSION && evt.cmdId == 1 -> {
                        AppLogger.d(TAG,"Session init (cmdId=1), payload(${evt.payload.size})")
                    }
                    evt.head == Head.SESSION && evt.cmdId == 2 -> {
                        AppLogger.d(TAG,"Auth confirmed (cmdId=2)")
                    }
                }
            }
        }

        AppLogger.d(TAG,"Handshake: sending 0001 challenge")
        c.send(PacketBuilder.challenge())

        val deviceChallenge = waitForAuthChallenge(c)
        if (deviceChallenge != null) {
            AppLogger.d(TAG, "Got auth challenge (${deviceChallenge.size}b): ${deviceChallenge.toHexDump()}")
        } else {
            AppLogger.w(TAG,"No auth challenge after 5s — proceeding with fallback auth")
        }

        val authJob = scope.async { waitForAuthOk(c) }

        AppLogger.d(TAG,"Handshake: sending 0002 LFSR response")
        c.send(PacketBuilder.handshakeResponse(deviceChallenge ?: ByteArray(0)))

        val authOk = authJob.await()
        AppLogger.d(
            TAG,
            "Handshake: auth = ${
                authOk ?: "NONE"
            }"
        )
        if (authOk == null) return false

        AppLogger.d(TAG,"Handshake: sending 7100 READ")
        c.send(PacketBuilder.read(Cmd.HANDSHAKE_7100))
        val support7100 = withTimeoutOrNull(4000) {
            c.incoming.first { it.urn == Cmd.HANDSHAKE_7100 }
        }
        if (support7100 == null) {
            AppLogger.w(TAG,"Handshake: no 7100 response within 4s")
        } else {
            AppLogger.d(TAG,"Handshake: got 7100 fmt=${support7100.dataFmt} len=${support7100.data.size}")
        }

        AppLogger.d(
            TAG,
            "Handshake: sending 102E bind userId=$bindUserId bindType=$bindType randomCode=${bindRandomCode ?: "<null>"} payload=${PacketBuilder.bindPayload(bindType, bindUserId, bindRandomCode).toHexDump()}"
        )
        c.send(PacketBuilder.bind(bindType, bindUserId, bindRandomCode))

        AppLogger.d(TAG,"Handshake: sending 7110 READ")
        c.send(PacketBuilder.read(Cmd.HANDSHAKE_7110))

        val bindResult = withTimeoutOrNull(5000) {
            c.incoming.first { it.urn == Cmd.BIND }
        }
        if (bindResult == null) {
            AppLogger.w(TAG,"Handshake: no 102E bind result within 5s")
            return false
        }
        val bindSuccess = bindResult.dataFmt.toInt() == DataFmt.BIN &&
            bindResult.data.size >= 2 &&
            (bindResult.data[1].toInt() and 0xFF) == 0
        AppLogger.d(TAG,"Handshake: bind result fmt=${bindResult.dataFmt} hex=${bindResult.data.toHexDump()} success=$bindSuccess")
        if (!bindSuccess) return false

        val support7110 = withTimeoutOrNull(2000) {
            c.incoming.first { it.urn == Cmd.HANDSHAKE_7110 }
        }
        if (support7110 != null) {
            AppLogger.d(TAG,"Handshake: got 7110 fmt=${support7110.dataFmt} len=${support7110.data.size}")
        }

        AppLogger.d(TAG,"Handshake: sending datetime sync")
        c.send(PacketBuilder.syncDatetime())
        delay(200)

        AppLogger.d(TAG,"Handshake: requesting device info")
        c.send(PacketBuilder.requestDeviceInfo())
        delay(200)

        AppLogger.d(TAG,"Handshake: requesting battery")
        c.send(PacketBuilder.requestBattery())
        delay(200)

        AppLogger.d(TAG,"Handshake: requesting media count")
        c.send(PacketBuilder.requestMediaCount())
        AppLogger.d(TAG,"Handshake: complete")
        return true
    }

    private suspend fun waitForAuthChallenge(c: GlassesConnection): ByteArray? = coroutineScope {
        val sessionJob = async {
            withTimeoutOrNull(5000) {
                c.sessionEvents.first { it.head == Head.SESSION && it.cmdId == 1 }.payload
            }
        }
        val nodeJob = async {
            withTimeoutOrNull(5000) {
                c.incoming.first { it.urn == Cmd.CHALLENGE }.data
            }
        }
        select<ByteArray?> {
            sessionJob.onAwait { it }
            nodeJob.onAwait { it }
        }.also {
            sessionJob.cancel()
            nodeJob.cancel()
        }
    }

    private suspend fun waitForAuthOk(c: GlassesConnection): String? = coroutineScope {
        val sessionJob = async {
            withTimeoutOrNull(5000) {
                val evt = c.sessionEvents.first { it.head == Head.SESSION && it.cmdId == 2 }
                "SESSION payload=${evt.payload.toHexDump()}"
            }
        }
        val nodeJob = async {
            withTimeoutOrNull(5000) {
                val node = c.incoming.first { it.urn == Cmd.CHALLENGE_RESP }
                "NODE fmt=${node.dataFmt} payload=${node.data.toHexDump()}"
            }
        }
        select<String?> {
            sessionJob.onAwait { it }
            nodeJob.onAwait { it }
        }.also {
            sessionJob.cancel()
            nodeJob.cancel()
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun listenToDevice(c: GlassesConnection) {
        scope.launch {
            c.incoming.collect { node -> handleNode(node) }
        }
        // Raw preview frames. AI button uses 0x1F; live view uses 0x1E/H.264.
        scope.launch {
            val videoFrameCache = ByteArrayOutputStream()
            val aiFrameCache = ByteArrayOutputStream()
            val fileTransferCache = ByteArrayOutputStream()
            c.rawFrames.collect { pkt ->
                when (pkt.head) {
                    Head.FILE_TRANSFER, Head.PHOTO_LIB -> {
                        handleFileTransferPacket(pkt, fileTransferCache)
                    }
                    Head.CAMERA_PREVIEW -> {
                        if (pkt.payload.isNotEmpty()) {
                            _previewFrames.emit(pkt.payload)
                        }
                    }
                    Head.VIDEO_PREVIEW -> {
                        handleVideoPreviewPacket(c, pkt, videoFrameCache)
                    }
                    Head.AI_PREVIEW -> {
                        handleAiPreviewPacket(c, pkt, aiFrameCache)
                    }
                }
            }
        }
    }

    private suspend fun handleFileTransferPacket(
        pkt: SppPacket,
        cache: ByteArrayOutputStream
    ) {
        val payload = collectDividedPayload(pkt, cache) ?: return
        AppLogger.d(TAG, "File transfer packet complete head=0x${pkt.head.toString(16)} payload=${payload.size}")
        val jpeg = extractJpeg(payload)
        if (jpeg != null) {
            AppLogger.d(TAG, "Downloaded JPEG frame=${jpeg.size}")
            _aiStatus.emit("Downloaded photo: ${jpeg.size} bytes")
            _aiFrames.emit(jpeg)
            cache.reset()
            return
        }
        if (payload.size > 2 * 1024 * 1024) {
            AppLogger.w(TAG, "File transfer payload ignored at ${payload.size} bytes without JPEG")
            cache.reset()
        }
    }

    private suspend fun handleVideoPreviewPacket(
        c: GlassesConnection,
        pkt: SppPacket,
        cache: ByteArrayOutputStream
    ) {
        when (pkt.divideType.toInt() and 0xFF) {
            DivideType.SINGLE -> {
                val frame = extractVideoPreviewFrame(stripChunkIndex(pkt.payload))
                AppLogger.d(TAG, "Video preview packet single payload=${pkt.payload.size} frame=${frame.size}")
                if (frame.isNotEmpty()) {
                    _previewFrames.emit(frame)
                }
                c.send(PacketBuilder.previewFrameAck(pkt.head, true))
            }
            DivideType.FIRST -> {
                cache.reset()
                cache.write(stripChunkIndex(pkt.payload))
                AppLogger.d(TAG, "Video preview packet first payload=${pkt.payload.size}")
            }
            DivideType.MIDDLE -> {
                cache.write(stripChunkIndex(pkt.payload))
                AppLogger.d(TAG, "Video preview packet middle payload=${pkt.payload.size}")
            }
            DivideType.LAST -> {
                cache.write(stripChunkIndex(pkt.payload))
                val combined = cache.toByteArray()
                val frame = extractVideoPreviewFrame(combined)
                AppLogger.d(TAG, "Video preview packet last payload=${pkt.payload.size} total=${combined.size} frame=${frame.size}")
                cache.reset()
                if (frame.isNotEmpty()) {
                    _previewFrames.emit(frame)
                    c.send(PacketBuilder.previewFrameAck(pkt.head, true))
                } else {
                    c.send(PacketBuilder.previewFrameAck(pkt.head, false))
                }
            }
            else -> {
                AppLogger.w(TAG, "Video preview packet unknown divideType=${pkt.divideType.toInt() and 0xFF}")
                c.send(PacketBuilder.previewFrameAck(pkt.head, false))
            }
        }
    }

    private fun stripChunkIndex(payload: ByteArray): ByteArray {
        if (payload.size <= 4) return payload
        val chunkIndex = ByteBuffer.wrap(payload, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        return if (chunkIndex in 0..4096) payload.copyOfRange(4, payload.size) else payload
    }

    private fun extractVideoPreviewFrame(payload: ByteArray): ByteArray {
        val h264Start = payload.indexOfSequence(byteArrayOf(0, 0, 0, 1))
        if (h264Start >= 0) return payload.copyOfRange(h264Start, payload.size)

        val jpegStart = payload.indexOfSequence(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (jpegStart >= 0) return payload.copyOfRange(jpegStart, payload.size)

        if (payload.size <= 5) return payload
        val declaredLen = ByteBuffer.wrap(payload, 1, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        if (declaredLen <= 0 || declaredLen > payload.size) return payload.copyOfRange(5, payload.size)
        val end = minOf(payload.size, 5 + declaredLen)
        return payload.copyOfRange(5, end)
    }

    private fun extractJpeg(payload: ByteArray): ByteArray? {
        val start = payload.indexOfSequence(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (start < 0) return null
        val end = payload.indexOfSequence(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), start + 2)
        if (end < 0) return null
        return payload.copyOfRange(start, end + 2)
    }

    private suspend fun handleAiPreviewPacket(
        c: GlassesConnection,
        pkt: SppPacket,
        cache: ByteArrayOutputStream
    ) {
        val frame = collectIndexedDividedPayload(pkt, cache)
        if (frame != null) {
            val extracted = extractVideoPreviewFrame(frame)
            AppLogger.d(TAG, "AI preview packet payload=${frame.size} frame=${extracted.size}")
            if (extracted.isNotEmpty()) {
                _aiFrames.emit(extracted)
                _aiStatus.emit("AI photo packet: ${extracted.size} bytes")
                _aiTriggers.emit(Unit)
                c.send(PacketBuilder.previewFrameAck(pkt.head, true))
                return
            }
        }
        if ((pkt.divideType.toInt() and 0xFF) != DivideType.FIRST &&
            (pkt.divideType.toInt() and 0xFF) != DivideType.MIDDLE
        ) {
            c.send(PacketBuilder.previewFrameAck(pkt.head, false))
        }
    }

    private fun collectIndexedDividedPayload(pkt: SppPacket, cache: ByteArrayOutputStream): ByteArray? {
        val payload = stripChunkIndex(pkt.payload)
        return when (pkt.divideType.toInt() and 0xFF) {
            DivideType.SINGLE -> payload
            DivideType.FIRST -> {
                cache.reset()
                cache.write(payload)
                null
            }
            DivideType.MIDDLE -> {
                cache.write(payload)
                null
            }
            DivideType.LAST -> {
                cache.write(payload)
                cache.toByteArray().also { cache.reset() }
            }
            else -> null
        }
    }

    private fun collectDividedPayload(pkt: SppPacket, cache: ByteArrayOutputStream): ByteArray? {
        return when (pkt.divideType.toInt() and 0xFF) {
            DivideType.SINGLE -> pkt.payload
            DivideType.FIRST -> {
                cache.reset()
                cache.write(pkt.payload)
                null
            }
            DivideType.MIDDLE -> {
                cache.write(pkt.payload)
                null
            }
            DivideType.LAST -> {
                cache.write(pkt.payload)
                cache.toByteArray().also { cache.reset() }
            }
            else -> null
        }
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        return indexOfSequence(needle, 0)
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray, fromIndex: Int): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        val start = fromIndex.coerceAtLeast(0)
        if (start > size - needle.size) return -1
        for (i in start..(size - needle.size)) {
            var matches = true
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    matches = false
                    break
                }
            }
            if (matches) return i
        }
        return -1
    }

    private fun ByteArray.toHexDump() = joinToString("") { "%02x".format(it) }

    private suspend fun handleNode(node: NodeData) {
        AppLogger.d(TAG,"Node: urn=${node.urn} fmt=${node.dataFmt} len=${node.data.size} hex=${node.data.toHexDump()}")
        when (node.urn) {
            Cmd.DEVICE_INFO -> {
                runCatching {
                    val info = gson.fromJson(node.dataAsString, DeviceInfo::class.java)
                    _deviceInfo.emit(info)
                }
            }
            Cmd.BATTERY -> {
                if (node.data.size >= 2) {
                    _battery.emit(BatteryInfo(
                        level = node.data[1].toInt() and 0xFF,
                        isCharging = node.data[0].toInt() != 0
                    ))
                }
            }
            Cmd.MEDIA_COUNT -> {
                runCatching {
                    val count = gson.fromJson(node.dataAsString, MediaCount::class.java)
                    _mediaCount.emit(count)
                }
            }
            Cmd.MEDIA_LIST -> {
                runCatching {
                    val list = gson.fromJson(node.dataAsString, MediaList::class.java)
                    AppLogger.d(TAG, "Media list type=$pendingMediaListType total=${list.total} files=${list.files.size}")
                    if (pendingMediaListType == "record") {
                        _audioList.emit(list.files)
                    } else {
                        _mediaList.emit(list.files)
                    }
                }.onFailure {
                    if (videoPreviewActive) {
                        AppLogger.d(TAG, "Preview 5720 state: ${node.data.toHexDump()}")
                    } else {
                        _aiStatus.emit("AI photo state: ${node.data.toHexDump()}")
                        _aiTriggers.emit(Unit)
                    }
                }
            }
            Cmd.MEDIA_DOWNLOAD -> {
                AppLogger.d(TAG, "Media download response fmt=${node.dataFmt} hex=${node.data.toHexDump()}")
                _aiStatus.emit("Download response: ${node.data.toHexDump()}")
            }
            Cmd.MEDIA_PROGRESS -> {
                AppLogger.d(TAG, "Media download progress fmt=${node.dataFmt} hex=${node.data.toHexDump()}")
                _aiStatus.emit("Download progress: ${node.data.toHexDump()}")
            }
            Cmd.MEDIA_LIB_A, Cmd.MEDIA_LIB_B, Cmd.MEDIA_LIB_B1, Cmd.MEDIA_LIB_C -> {
                AppLogger.d(TAG, "Media library node urn=${node.urn} fmt=${node.dataFmt} len=${node.data.size}")
            }
            Cmd.AI_AUDIO_STATE, Cmd.PREVIEW_STATE -> {
                if (videoPreviewActive) {
                    AppLogger.d(TAG, "Preview state: ${node.data.toHexDump()}")
                } else {
                    _aiStatus.emit("AI audio state: ${node.data.toHexDump()}")
                    _aiTriggers.emit(Unit)
                    conn?.send(PacketBuilder.requestMediaCount())
                    requestMediaList("photo")
                    delay(250)
                    requestMediaList("record")
                }
            }
            Cmd.CAMERA_RESULT -> {
                val ok = node.data.firstOrNull()?.toInt() == 0
                _events.emit(if (ok) "Photo taken!" else "Photo failed")
            }
            Cmd.CAMERA_MODE -> {
                _events.emit("Camera mode changed")
            }
            Cmd.MEDIA_EVENT -> {
                _events.emit("New media event")
                conn?.send(PacketBuilder.requestMediaCount())
            }
            Cmd.HANDSHAKE_7100 -> {
                AppLogger.d(TAG,"Device action support 7100: ${node.data.toHexDump()}")
            }
            Cmd.HANDSHAKE_7110 -> {
                AppLogger.d(TAG,"Glasses action support 7110: ${node.data.toHexDump()}")
            }
            Cmd.BIND -> {
                val bindSuccess = node.dataFmt.toInt() == DataFmt.BIN &&
                    node.data.size >= 2 &&
                    (node.data[1].toInt() and 0xFF) == 0
                _events.emit(if (bindSuccess) "Bind success" else "Bind failed")
            }
            // Cmd.CHALLENGE (0001) is handled exclusively in doHandshake()
            // to avoid duplicate 0002 sends during the initial auth flow.
        }
    }

    // ── Public commands ──────────────────────────────────────────────────────

    fun sendDeviceInfoRequest() = conn?.send(PacketBuilder.requestDeviceInfo())
    fun sendBatteryRequest()    = conn?.send(PacketBuilder.requestBattery())
    fun sendTimeSync()          = conn?.send(PacketBuilder.syncDatetime())
    fun sendMediaCountRequest() = conn?.send(PacketBuilder.requestMediaCount())
    fun sendMediaListRequest(page: Int = 0, type: String = "photo") {
        pendingMediaListType = type
        conn?.send(PacketBuilder.requestMediaList(page, type = type))
    }
    private fun requestMediaList(type: String) {
        pendingMediaListType = type
        conn?.send(PacketBuilder.requestMediaList(type = type))
    }
    fun startPreview() {
        videoPreviewActive = true
        conn?.send(PacketBuilder.startPreview())
    }
    fun stopPreview() {
        videoPreviewActive = false
        conn?.send(PacketBuilder.stopPreview())
    }
    fun deleteMedia(id: String) = conn?.send(PacketBuilder.deleteMedia(id))
    fun downloadMedia(id: String) {
        AppLogger.d(TAG, "Request media download id=$id")
        conn?.send(PacketBuilder.downloadMedia(id))
    }
    fun reboot()                = conn?.send(PacketBuilder.reboot())
    fun powerOff()              = conn?.send(PacketBuilder.powerOff())

    companion object {
        @Volatile private var INSTANCE: GlassesManager? = null
        fun get(ctx: Context) = INSTANCE ?: synchronized(this) {
            INSTANCE ?: GlassesManager(ctx.applicationContext).also { INSTANCE = it }
        }
    }
}
