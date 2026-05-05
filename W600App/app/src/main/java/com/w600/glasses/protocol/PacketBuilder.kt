package com.w600.glasses.protocol

import java.util.concurrent.atomic.AtomicInteger

object PacketBuilder {

    private val seq = AtomicInteger(0)
    private val pkgId = AtomicInteger(1)

    /** Global sequential cmdOrder shared by ALL phone packets (requests + ACKs): 0, 1, 2, 3, ... */
    fun nextSeq(): Byte = (seq.getAndIncrement() and 0xFF).toByte()
    private fun currentSeq(): Byte = (seq.get() and 0xFF).toByte()
    private fun nextPkgId() = (pkgId.getAndIncrement() % 0x7FFF).toShort()

    /** Wrap NodeData items in an SPP packet with head=0x30, cmdId=0x0001 */
    private fun nodePacket(
        actionType: Byte,
        vararg nodes: NodeData,
        packageSeq: Int = -1
    ): ByteArray {
        val pkg = PayloadPackage(
            id = nextPkgId(),
            packageSeq = packageSeq,
            actionType = actionType,
            packageLimit = 0,   // btsnoop: limit=0 for all phone→device packets
            items = nodes.toList()
        )
        val payload = pkg.toBytes()
        val pkt = SppPacket(
            head = Head.NODE_PROTOCOL,
            cmdOrder = nextSeq(),
            cmdId = 0x0001,     // 0x0001 = request; device uses 0x8002/0x8001 for response
            dividePayloadLen = payload.size.toShort(),
            payloadLen = 0,     // 0 for non-fragmented (per protocol)
            payload = payload
        )
        return pkt.toBytes()
    }

    /**
     * ACK packet (cmdId=0x0004). Sent by phone after each device data packet.
     * cmdOrder uses the global sequential counter (same as requests).
     * Payload = [0x01, deviceDataCmdOrder] where deviceDataCmdOrder is from the device's data packet.
     */
    fun ack(deviceDataCmdOrder: Byte): ByteArray {
        val payload = byteArrayOf(0x01, deviceDataCmdOrder)
        return SppPacket(
            head = Head.NODE_PROTOCOL,
            cmdOrder = nextSeq(),   // global sequential counter, not device's cmdOrder
            cmdId = 0x0004,
            dividePayloadLen = 2,
            payload = payload
        ).toBytes()
    }

    /** READ request – just sends the URN, no data */
    fun read(urn: String) = nodePacket(ActionType.READ, NodeData(urn))

    /** WRITE request */
    fun write(urn: String, fmt: Int, data: ByteArray) =
        nodePacket(ActionType.WRITE, NodeData(urn, fmt.toByte(), data))

    /** WRITE JSON */
    fun writeJson(urn: String, json: String) =
        write(urn, DataFmt.JSON, json.toByteArray(Charsets.UTF_8))

    /** EXECUTE with no data */
    fun execute(urn: String) =
        nodePacket(ActionType.EXECUTE, NodeData(urn, DataFmt.BIN.toByte(), ByteArray(0)))

    /** EXECUTE with binary data */
    fun executeBin(urn: String, data: ByteArray) =
        nodePacket(ActionType.EXECUTE, NodeData(urn, DataFmt.BIN.toByte(), data))

    // ── Auth handshake ────────────────────────────────────────────────────────

    /**
     * Step 1: phone sends 0001 EXECUTE with 61 random alphanumeric bytes.
     *
     * LensMoo's l9c.H() calls BtUtils.hexStringToByteArray(h(61)) which produces
     * 61 random alphanumeric ASCII bytes. The device likely validates that the data
     * is all-alphanumeric (same validation as for 0002) and silently drops packets
     * with non-alphanumeric bytes.
     */
    fun challenge(): ByteArray {
        val data = SjbtCrypto.hBytes(61, java.util.Random(System.currentTimeMillis()))
        return nodePacket(ActionType.EXECUTE, NodeData(Cmd.CHALLENGE, DataFmt.BIN.toByte(), data))
    }

    /**
     * Step 3: phone sends 0002 EXECUTE with 64-byte encrypted response.
     *
     * Device's session-init payload (61 bytes) is structured as:
     *   [0..13]   header (ignored)
     *   [14..15]  16-bit LFSR key (big-endian)
     *   [16..47]  32-byte challenge to encrypt
     *   [48..60]  footer (ignored)
     *
     * Our response: random(14) + key(2) + encrypt(key, challenge) + random(16) = 64 bytes
     */
    fun handshakeResponse(deviceData: ByteArray): ByteArray {
        val resp = SjbtCrypto.buildAuthResponse(deviceData)
        return nodePacket(ActionType.EXECUTE,
            NodeData(Cmd.CHALLENGE_RESP, DataFmt.BIN.toByte(), resp))
    }

    /**
     * Bind (102E): EXECUTE payload built like LensMoo's `l9c.d(WmBindInfo)`.
     *
     * Payload structure:
     *   byte[0]    : bind type ordinal
     *   bytes[1-16]: random code, truncated/padded to 16 bytes
     *   byte[17]   : userId byte length
     *   bytes[18..]: userId bytes
     */
    fun bind(bindType: Int, userId: String, randomCode: String?): ByteArray {
        return bindPacket(bindPayload(bindType, userId, randomCode))
    }

    fun bindPayload(bindType: Int, userId: String, randomCode: String?): ByteArray {
        val userBytes = userId.toByteArray(Charsets.UTF_8)
        val userLen = minOf(userBytes.size, 255)
        val data = ByteArray(18 + userLen)
        data[0] = bindType.toByte()
        fixedBytes(randomCode, 16).copyInto(data, destinationOffset = 1)
        data[17] = userLen.toByte()
        userBytes.copyInto(data, destinationOffset = 18, endIndex = userLen)
        return data
    }

    private fun bindPacket(data: ByteArray): ByteArray {
        return nodePacket(ActionType.EXECUTE, NodeData(Cmd.BIND, DataFmt.BIN.toByte(), data))
    }

    private fun fixedBytes(value: String?, size: Int): ByteArray {
        val out = ByteArray(size)
        if (!value.isNullOrEmpty()) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            bytes.copyInto(out, endIndex = minOf(bytes.size, size))
        }
        return out
    }

    // ── Concrete commands ────────────────────────────────────────────────────

    fun requestDeviceInfo() = read(Cmd.DEVICE_INFO)

    fun requestBattery() = read(Cmd.BATTERY)

    fun syncDatetime(): ByteArray {
        val now = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        val sdfFull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        val date = java.util.Date(now)
        val tz = java.util.TimeZone.getDefault()
        val offset = tz.getOffset(now) / 1000 / 60
        val sign = if (offset >= 0) "+" else "-"
        val absOffset = kotlin.math.abs(offset)
        val tzStr = "GMT$sign${"%02d".format(absOffset / 60)}:${"%02d".format(absOffset % 60)}"
        val json = """{"currDate":"${sdf.format(date)}","currTime":"${sdfFull.format(date)}","timeZoo":"$tzStr","timestamp":${now / 1000}}"""
        return writeJson(Cmd.DATETIME_SYNC, json)
    }

    fun requestMediaCount() = read(Cmd.MEDIA_COUNT)

    fun requestSdCapacity() = read(Cmd.SD_CAPACITY)

    fun startPreview(): ByteArray = executeBin(Cmd.PREVIEW_TOGGLE, byteArrayOf(1))

    fun stopPreview(): ByteArray = executeBin(Cmd.PREVIEW_TOGGLE, byteArrayOf(0))

    fun previewFrameAck(head: Byte = Head.VIDEO_PREVIEW, success: Boolean): ByteArray =
        SppPacket(
            head = head,
            cmdOrder = nextSeq(),
            cmdId = 0x0002.toShort(),
            dividePayloadLen = 0,
            payload = byteArrayOf(if (success) 1 else 0)
        ).toBytes()

    fun requestMediaList(page: Int = 0, pageSize: Int = 20, type: String = "photo"): ByteArray {
        val json = """{"page":$page,"page_size":$pageSize,"type":"$type"}"""
        return writeJson(Cmd.MEDIA_LIST, json)
    }

    fun deleteMedia(fileId: String): ByteArray =
        writeJson(Cmd.MEDIA_DELETE, """{"file_id":"$fileId"}""")

    fun downloadMedia(fileId: String): ByteArray =
        writeJson(Cmd.MEDIA_DOWNLOAD, """{"file_id":"$fileId","file_name":"$fileId"}""")

    fun requestPhotoLibraryNames(): ByteArray =
        executeBin(Cmd.PHOTO_NAMES, byteArrayOf(0))

    fun requestPhotoElementCount(name: String): ByteArray =
        write(Cmd.PHOTO_ELEMENT_COUNT, DataFmt.BIN, name.toByteArray(Charsets.UTF_8))

    fun requestPhotoElement(index: Int): ByteArray =
        write(Cmd.PHOTO_ELEMENT, DataFmt.BIN, byteArrayOf(index.toByte()))

    fun endPhotoLibraryImport(): ByteArray =
        executeBin(Cmd.PHOTO_END, byteArrayOf(0))

    fun photoLibraryAck(deviceDataCmdOrder: Byte, success: Boolean = true): ByteArray =
        SppPacket(
            head = Head.PHOTO_LIB,
            // LensMoo sends 0x4A/0x0009 with the current phone command index and
            // does not advance it. The following 7310/7300 node request reuses
            // the same order value, which the glasses appear to require before
            // accepting photo element requests.
            cmdOrder = currentSeq(),
            cmdId = 0x0009.toShort(),
            dividePayloadLen = 0,
            payload = byteArrayOf(if (success) 1 else 0)
        ).toBytes()

    fun setLanguage(lang: String = "en"): ByteArray =
        write(Cmd.LANGUAGE, DataFmt.PLAIN_TXT, lang.toByteArray(Charsets.UTF_8))

    fun reboot() = execute(Cmd.REBOOT)

    fun powerOff() = execute(Cmd.POWER_OFF)
}
