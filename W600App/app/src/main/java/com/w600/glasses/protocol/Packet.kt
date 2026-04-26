package com.w600.glasses.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-byte SJBT outer frame + variable payload.
 *
 * Wire format (all fields little-endian):
 *   [0]    head          : Byte
 *   [1]    cmdOrder      : Byte
 *   [2-3]  cmdId         : Short
 *   [4-5]  divideInfo    : packed totalLen/divideType
 *   [6-7]  payloadLen    : Short  = payload size in this packet
 *   [8-11] offset        : Int    = fragment byte offset
 *   [12-15] crc          : Int    = CRC over payload bytes
 *   [16+]  payload       : ByteArray (payloadLen bytes)
 */
data class SppPacket(
    val head: Byte,
    val cmdOrder: Byte,
    val cmdId: Short,
    val divideType: Byte = 0,
    val dividePayloadLen: Short = 0,
    val payloadLen: Short = 0,
    val offset: Int = 0,
    val crc: Int = 0,
    val payload: ByteArray = ByteArray(0)
) {
    private fun encodeDivideInfo(divideType: Byte, totalLen: Short): ByteArray {
        val total = totalLen.toInt() and 0xFFFF
        val hi = ((total ushr 8) shl 3) and 0xF8
        return byteArrayOf(((divideType.toInt() and 0x07) or hi).toByte(), (total and 0xFF).toByte())
    }

    fun toBytes(): ByteArray {
        val crcValue = CRC16.compute(payload)
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(head)
        buf.put(cmdOrder)
        buf.putShort(cmdId)
        buf.put(encodeDivideInfo(divideType, dividePayloadLen))
        buf.putShort(payload.size.toShort())
        buf.putInt(offset)
        buf.putInt(crcValue)
        buf.put(payload)
        return buf.array()
    }

    companion object {
        const val HEADER_SIZE = 16

        fun parse(data: ByteArray): SppPacket? {
            if (data.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val head = buf.get()
            val cmdOrder = buf.get()
            val cmdId = buf.short
            val divideInfo0 = buf.get().toInt() and 0xFF
            val divideInfo1 = buf.get().toInt() and 0xFF
            val divideType = (divideInfo0 and 0x07).toByte()
            val dividePayloadLen = ((((divideInfo0 and 0xF8) shr 3) shl 8) or divideInfo1).toShort()
            val payloadLen = buf.short
            val offset = buf.int
            val crc = buf.int
            val pLen = payloadLen.toInt() and 0xFFFF
            val payload = if (pLen > 0 && buf.remaining() >= pLen) {
                ByteArray(pLen).also { buf.get(it) }
            } else ByteArray(0)
            return SppPacket(head, cmdOrder, cmdId, divideType, dividePayloadLen, payloadLen, offset, crc, payload)
        }
    }
}

/** Node protocol payload (head=0x30) container */
data class PayloadPackage(
    val id: Short,
    val packageSeq: Int,
    val actionType: Byte,
    val packageLimit: Short,
    val items: List<NodeData>
) {
    fun toBytes(): ByteArray {
        val itemBytes = items.map { it.toBytes() }
        val totalItemSize = itemBytes.sumOf { it.size }
        val buf = ByteBuffer.allocate(10 + totalItemSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(id)
        buf.putInt(packageSeq)
        buf.put(actionType)
        buf.putShort(packageLimit)
        buf.put(items.size.toByte())
        itemBytes.forEach { buf.put(it) }
        return buf.array()
    }

    companion object {
        fun parse(data: ByteArray): PayloadPackage? {
            if (data.size < 10) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val id = buf.short
            val seq = buf.int
            val actionType = buf.get()
            val limit = buf.short
            val count = buf.get().toInt() and 0xFF
            val items = mutableListOf<NodeData>()
            repeat(count) {
                NodeData.parse(buf)?.let { items.add(it) }
            }
            return PayloadPackage(id, seq, actionType, limit, items)
        }
    }
}

/** Single command node inside a PayloadPackage */
data class NodeData(
    val urn: String,        // 4-char ASCII command code
    val dataFmt: Byte = DataFmt.BIN.toByte(),
    val data: ByteArray = ByteArray(0)
) {
    fun toBytes(): ByteArray {
        val urnBytes = urn.toByteArray(Charsets.US_ASCII).copyOf(4)
        val buf = ByteBuffer.allocate(4 + 1 + 2 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(urnBytes)
        buf.put(dataFmt)
        buf.putShort(data.size.toShort())
        buf.put(data)
        return buf.array()
    }

    val dataAsString get() = String(data, Charsets.UTF_8)

    companion object {
        fun parse(buf: ByteBuffer): NodeData? {
            if (buf.remaining() < 4) return null
            val urnBytes = ByteArray(4).also { buf.get(it) }
            val urn = String(urnBytes, Charsets.US_ASCII)
            if (buf.remaining() < 3) return NodeData(urn)
            val fmt = buf.get()
            val len = buf.short.toInt() and 0xFFFF
            val data = if (len > 0 && buf.remaining() >= len) {
                ByteArray(len).also { buf.get(it) }
            } else ByteArray(0)
            return NodeData(urn, fmt, data)
        }
    }
}
