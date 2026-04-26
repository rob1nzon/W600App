package com.w600.glasses.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.w600.glasses.protocol.*
import com.w600.glasses.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.UUID

private const val TAG = "GlassesConn"
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class SessionEvent(val head: Byte, val cmdId: Int, val payload: ByteArray)

class GlassesConnection(private val device: BluetoothDevice) {

    private var socket: BluetoothSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incoming = MutableSharedFlow<NodeData>(extraBufferCapacity = 64)
    val incoming: SharedFlow<NodeData> = _incoming.asSharedFlow()

    private val _rawFrames = MutableSharedFlow<SppPacket>(extraBufferCapacity = 16)
    val rawFrames: SharedFlow<SppPacket> = _rawFrames.asSharedFlow()

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    val isConnected get() = socket?.isConnected == true

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            AppLogger.d(TAG,"RFCOMM connect to ${device.address} channel 8...")
            val sock = try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 8) as BluetoothSocket
            } catch (e: Exception) {
                AppLogger.w(TAG,"Reflection failed, falling back to SDP: ${e.message}")
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            }
            sock.connect()
            socket = sock
            AppLogger.d(TAG,"SPP connected ch8, starting read loop")
            startReading(sock)
        }
    }

    private fun startReading(sock: BluetoothSocket) {
        scope.launch {
            val buf = ByteArray(4096)
            val accumulator = mutableListOf<Byte>()
            try {
                val ins = sock.inputStream
                while (isActive) {
                    val n = ins.read(buf)
                    if (n < 0) { AppLogger.d(TAG,"SPP stream ended (n=$n)"); break }
                    val hex = buf.take(minOf(n, 32)).joinToString(" ") { "%02X".format(it) }
                    AppLogger.d(TAG,"SPP READ ($n bytes): $hex")
                    accumulator.addAll(buf.slice(0 until n))
                    processAccumulator(accumulator)
                }
            } catch (e: IOException) {
                AppLogger.d(TAG,"SPP read ended: ${e.message}")
            }
        }
    }

    fun disconnect() {
        scope.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun send(data: ByteArray): Boolean {
        val sock = socket ?: run { AppLogger.e(TAG,"send: socket is null"); return false }
        val hex = data.take(32).joinToString(" ") { "%02X".format(it) }
        AppLogger.d(TAG,"SPP SEND (${data.size} bytes): $hex")
        return try {
            sock.outputStream.write(data)
            sock.outputStream.flush()
            true
        } catch (e: IOException) {
            AppLogger.e(TAG,"SPP write error", e)
            false
        }
    }

    suspend fun awaitResponse(urn: String, timeoutMs: Long = 5000L): NodeData? =
        withTimeoutOrNull(timeoutMs) { incoming.first { it.urn == urn } }

    private suspend fun processAccumulator(acc: MutableList<Byte>) {
        while (acc.size >= SppPacket.HEADER_SIZE) {
            val arr = acc.toByteArray()
            val head = arr[0]

            if (head == Head.SESSION || head == 0x0B.toByte() || head == Head.FILE_TRANSFER
                || head == Head.CAMERA_PREVIEW || head == Head.VIDEO_PREVIEW
                || head == Head.AI_PREVIEW
                || head == Head.PHOTO_LIB) {
                val payloadSize = if (acc.size < 8) break
                else java.nio.ByteBuffer.wrap(arr, 6, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .short.toInt() and 0xFFFF
                val totalSize = SppPacket.HEADER_SIZE + payloadSize
                if (acc.size < totalSize) break
                val payload = arr.sliceArray(SppPacket.HEADER_SIZE until totalSize)
                val cmdId = java.nio.ByteBuffer.wrap(arr, 2, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                val cmdOrder = arr[1].toInt() and 0xFF
                AppLogger.d(TAG,"← head=0x${head.toString(16).padStart(2,'0')} cmdId=$cmdId ord=$cmdOrder payloadLen=$payloadSize hex=${payload.toHex()}")
                repeat(totalSize) { acc.removeAt(0) }

                _rawFrames.tryEmit(SppPacket.parse(arr.copyOf(totalSize)) ?: continue)

                if (head == Head.SESSION) {
                    _sessionEvents.emit(SessionEvent(head, cmdId, payload))
                    if (cmdId == 0x8001 || cmdId == 0x8002) {
                        send(PacketBuilder.ack(cmdOrder.toByte()))
                    }
                }
                continue
            }

            if (head != Head.NODE_PROTOCOL) {
                acc.removeAt(0)
                continue
            }

            val pkt = SppPacket.parse(arr) ?: break
            val payloadSize = pkt.payloadLen.toInt() and 0xFFFF
            val totalSize = SppPacket.HEADER_SIZE + payloadSize
            if (acc.size < totalSize) break

            repeat(totalSize) { acc.removeAt(0) }

            val cmdId = pkt.cmdId.toInt() and 0xFFFF
            AppLogger.d(TAG,"← cmdId=0x${cmdId.toString(16).padStart(4,'0')} ord=${pkt.cmdOrder.toInt() and 0xFF} divPayLen=${pkt.dividePayloadLen.toInt() and 0xFFFF}")

            if (cmdId == 0x8001 || cmdId == 0x8002) {
                send(PacketBuilder.ack(pkt.cmdOrder))
            }

            _rawFrames.tryEmit(pkt)

            if ((cmdId == 0x8001 || cmdId == 0x8002) && pkt.payload.isNotEmpty()) {
                val pkg = PayloadPackage.parse(pkt.payload) ?: continue
                pkg.items.forEach { node ->
                    AppLogger.d(TAG,"← URN=${node.urn} fmt=${node.dataFmt} len=${node.data.size} hex=${node.data.toHex()}")
                    _incoming.emit(node)
                }
            }
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    fun sendSessionPacket(head: Byte, cmdOrder: Byte, cmdId: Short, payload: ByteArray): ByteArray {
        val crcValue = CRC16.compute(payload)
        val buf = java.nio.ByteBuffer.allocate(SppPacket.HEADER_SIZE + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(head)
        buf.put(cmdOrder)
        buf.putShort(cmdId)
        buf.put(0)
        buf.put(0)
        buf.putShort(payload.size.toShort())
        buf.putInt(0)
        buf.putInt(crcValue)
        buf.put(payload)
        return buf.array()
    }
}
