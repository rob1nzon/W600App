package com.w600.glasses.protocol

import java.util.Random

object SjbtCrypto {

    private const val POLY = 0xD103

    private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private fun loopKey(state: Int): Pair<Int, Int> {
        val bit15 = (state ushr 15) and 1
        var s = (state shl 1) and 0xFFFF
        if (bit15 == 1) s = s xor POLY
        s = s or bit15
        return Pair(s and 0xFFFF, bit15)
    }

    fun encryptByte(state: Int, byte: Int): Pair<Int, Int> {
        var s = state
        var result = 0
        for (i in 7 downTo 0) {
            val (ns, outBit) = loopKey(s)
            val dataBit = (byte ushr i) and 1
            result = result or ((dataBit xor outBit) shl i)
            s = ns
        }
        return Pair(result and 0xFF, s)
    }

    fun encrypt(key: Int, data: ByteArray): ByteArray {
        var state = key and 0xFFFF
        return ByteArray(data.size) { i ->
            val (enc, ns) = encryptByte(state, data[i].toInt() and 0xFF)
            state = ns
            enc.toByte()
        }
    }

    fun hBytes(n: Int, rng: Random): ByteArray {
        return ByteArray(n) {
            ALPHANUM[rng.nextInt(ALPHANUM.length)].code.toByte()
        }
    }

    /**
     * Build the 0002 auth response (64 bytes).
     *
     * LensMoo receives a 61-byte session payload from the glasses after sending node 0001.
     * That payload is structured as:
     *
     *   bytes  0-13  : ignored
     *   bytes 14-15  : big-endian LFSR key
     *   bytes 16-47  : 32-byte device challenge
     *   bytes 48-60  : ignored
     *
     * The phone then responds with:
     *
     *   random(14) + key(2) + encrypt(key, deviceChallenge32) + random(16)
     */
    fun buildAuthResponse(deviceData: ByteArray): ByteArray {
        val rng = Random(System.currentTimeMillis())
        val prefix = hBytes(14, rng)
        val suffix = hBytes(16, rng)

        if (deviceData.size < 48) {
            val fallbackChallenge = hBytes(32, rng)
            val fallbackKey =
                ((fallbackChallenge[0].toInt() and 0xFF) shl 8) or
                (fallbackChallenge[1].toInt() and 0xFF)
            val fallbackEncrypted = encrypt(fallbackKey, fallbackChallenge)
            return prefix +
                byteArrayOf(
                    ((fallbackKey ushr 8) and 0xFF).toByte(),
                    (fallbackKey and 0xFF).toByte()
                ) +
                fallbackEncrypted +
                suffix
        }

        val keyHi = deviceData[14].toInt() and 0xFF
        val keyLo = deviceData[15].toInt() and 0xFF
        val key = (keyHi shl 8) or keyLo
        val deviceChallenge = deviceData.copyOfRange(16, 48)
        val encrypted = encrypt(key, deviceChallenge)

        return prefix +
            byteArrayOf(keyHi.toByte(), keyLo.toByte()) +
            encrypted +
            suffix
    }
}
