/*
 * SPDX-FileCopyrightText: 2024 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.adbconnection

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Simplified ADB client for TCP connections.
 * Based on ScrcpyForAndroid's DirectAdbClient implementation.
 */
class SimpleAdbClient(
    private val host: String,
    private val port: Int,
    private val prefs: SharedPreferences
) {
    private var socket: Socket? = null
    private var rawIn: InputStream? = null
    private var rawOut: OutputStream? = null
    private val nextLocalId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private var readerThread: Thread? = null

    @Volatile
    private var closed = false

    var isConnected: Boolean = false
        private set

    var onDisconnect: (() -> Unit)? = null

    companion object {
        private const val TAG = "SimpleAdbClient"
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_STLS = 0x534c5453
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val VERSION = 0x01000001
        private const val MAX_PAYLOAD = 256 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val PREF_PRIVATE_KEY = "adb_private_key"
        private const val PREF_PUBLIC_KEY = "adb_public_key"
        private const val PREF_KEY_NAME = "adb_key_name"

        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14,
        )

        private const val ADB_PUBLIC_KEY_WORDS = 64
        private const val ADB_PUBLIC_KEY_MODULUS_BYTES = 256
        private const val ADB_PUBLIC_KEY_BYTES = 4 + 4 + ADB_PUBLIC_KEY_MODULUS_BYTES +
                ADB_PUBLIC_KEY_MODULUS_BYTES + 4
    }

    private var privateKey: PrivateKey? = null
    private var publicKeyX509: ByteArray? = null
    private var keyName: String = "kdeconnect-adb"

    init {
        loadOrCreateKey()
    }

    private fun loadOrCreateKey() {
        try {
            val privB64 = prefs.getString(PREF_PRIVATE_KEY, null)
            val pubB64 = prefs.getString(PREF_PUBLIC_KEY, null)
            keyName = prefs.getString(PREF_KEY_NAME, "kdeconnect-adb") ?: "kdeconnect-adb"

            if (!privB64.isNullOrBlank() && !pubB64.isNullOrBlank()) {
                val privBytes = Base64.decode(privB64, Base64.DEFAULT)
                val spec = PKCS8EncodedKeySpec(privBytes)
                val kf = KeyFactory.getInstance("RSA")
                privateKey = kf.generatePrivate(spec)
                publicKeyX509 = Base64.decode(pubB64, Base64.DEFAULT)
                Log.i(TAG, "Loaded persisted ADB keys")
            } else {
                generateNewKey()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted key, generating new", e)
            generateNewKey()
        }
    }

    private fun generateNewKey() {
        try {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()

            privateKey = kp.private
            publicKeyX509 = kp.public.encoded

            prefs.edit()
                .putString(PREF_PRIVATE_KEY, Base64.encodeToString(privateKey!!.encoded, Base64.NO_WRAP))
                .putString(PREF_PUBLIC_KEY, Base64.encodeToString(publicKeyX509, Base64.NO_WRAP))
                .putString(PREF_KEY_NAME, keyName)
                .apply()

            Log.i(TAG, "Generated and persisted new ADB keys")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate ADB keys", e)
        }
    }

    fun connect(): Boolean {
        try {
            socket = Socket()
            socket!!.connect(InetSocketAddress(host, port), 10_000)
            socket!!.tcpNoDelay = true
            socket!!.keepAlive = true
            socket!!.soTimeout = 60_000

            rawIn = BufferedInputStream(socket!!.getInputStream(), 65_536)
            rawOut = socket!!.getOutputStream()

            return performHandshake()
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            close()
            return false
        }
    }

    private fun performHandshake(): Boolean {
        try {
            sendMsg(A_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))

            var first = recvMsg()

            if (first.command == A_STLS) {
                Log.i(TAG, "TLS upgrade requested (not supported)")
                return false
            }

            when (first.command) {
                A_CNXN -> {
                    Log.i(TAG, "Connected (no auth needed)")
                    isConnected = true
                    startReaderThread()
                    return true
                }
                A_AUTH -> {
                    if (first.arg0 != AUTH_TOKEN) {
                        Log.e(TAG, "Expected AUTH_TOKEN, got type=${first.arg0}")
                        return false
                    }

                    val signature = signToken(first.data)
                    sendMsg(A_AUTH, AUTH_SIGNATURE, 0, signature)

                    val afterSign = recvMsg()
                    when (afterSign.command) {
                        A_CNXN -> {
                            Log.i(TAG, "Connected (auth accepted)")
                            isConnected = true
                            startReaderThread()
                            return true
                        }
                        A_AUTH -> {
                            if (afterSign.arg0 != AUTH_TOKEN) {
                                Log.e(TAG, "Expected AUTH_TOKEN after rejected signature, got type=${afterSign.arg0}")
                                return false
                            }

                            val pubKeyBytes = buildAdbPubKey()
                            sendMsg(A_AUTH, AUTH_RSAPUBLICKEY, 0, pubKeyBytes)

                            val cnxn = recvMsg()
                            if (cnxn.command == A_CNXN) {
                                Log.i(TAG, "Connected (pubkey accepted, check device dialog)")
                                isConnected = true
                                startReaderThread()
                                return true
                            } else {
                                Log.e(TAG, "Connection rejected. Please accept the authorisation dialog on the target device.")
                                return false
                            }
                        }
                        else -> {
                            Log.e(TAG, "Unexpected message 0x${afterSign.command.toString(16)} after AUTH_SIGNATURE")
                            return false
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "Unexpected initial message 0x${first.command.toString(16)}")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed", e)
            return false
        }
    }

    private fun signToken(token: ByteArray): ByteArray {
        val payload = ByteArray(SHA1_DIGEST_INFO_PREFIX.size + token.size)
        SHA1_DIGEST_INFO_PREFIX.copyInto(payload, 0)
        token.copyInto(payload, SHA1_DIGEST_INFO_PREFIX.size)
        return Signature.getInstance("NONEwithRSA").apply {
            initSign(privateKey)
            update(payload)
        }.sign()
    }

    private fun buildAdbPubKey(): ByteArray {
        val kf = KeyFactory.getInstance("RSA")
        val pub = kf.generatePublic(X509EncodedKeySpec(publicKeyX509))
        val spec = kf.getKeySpec(pub, RSAPublicKeySpec::class.java)
        val adbKeyBytes = encodeAdbPublicKey(spec.modulus, spec.publicExponent.toInt())
        return "${Base64.encodeToString(adbKeyBytes, Base64.NO_WRAP)} $keyName\u0000"
            .toByteArray(Charsets.UTF_8)
    }

    private fun encodeAdbPublicKey(modulus: BigInteger, exponent: Int): ByteArray {
        val two32 = BigInteger.ONE.shiftLeft(32)
        val mask32 = two32.subtract(BigInteger.ONE)

        fun toBigEndianPadded(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            val arr = ByteArray(ADB_PUBLIC_KEY_MODULUS_BYTES)
            val src = if (raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            src.copyInto(arr, destinationOffset = ADB_PUBLIC_KEY_MODULUS_BYTES - src.size)
            return arr
        }

        val modBE = toBigEndianPadded(modulus)
        val n0 = modulus.and(mask32)
        val n0inv = n0.modInverse(two32).negate().mod(two32).toInt()
        val r = BigInteger.ONE.shiftLeft(ADB_PUBLIC_KEY_MODULUS_BYTES * 8)
        val rrBE = toBigEndianPadded(r.multiply(r).mod(modulus))

        val buf = ByteBuffer.allocate(ADB_PUBLIC_KEY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(ADB_PUBLIC_KEY_WORDS)
        buf.putInt(n0inv)
        for (i in ADB_PUBLIC_KEY_WORDS - 1 downTo 0) {
            val o = i * 4
            buf.put(modBE[o + 3]); buf.put(modBE[o + 2]); buf.put(modBE[o + 1]); buf.put(modBE[o])
        }
        for (i in ADB_PUBLIC_KEY_WORDS - 1 downTo 0) {
            val o = i * 4
            buf.put(rrBE[o + 3]); buf.put(rrBE[o + 2]); buf.put(rrBE[o + 1]); buf.put(rrBE[o])
        }
        buf.putInt(exponent)
        return buf.array()
    }

    private fun startReaderThread() {
        socket!!.soTimeout = 0
        readerThread = thread(isDaemon = true, name = "adb-reader-$host:$port") {
            readLoop()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val msg = recvMsg()
                when (msg.command) {
                    A_OKAY -> {
                        streams[msg.arg1]?.onRemoteOkay(msg.arg0)
                    }
                    A_WRTE -> {
                        val s = streams[msg.arg1]
                        if (s != null) {
                            s.onData(msg.data)
                            sendMsg(A_OKAY, msg.arg1, msg.arg0)
                        } else {
                            sendMsg(A_CLSE, 0, msg.arg0)
                        }
                    }
                    A_CLSE -> streams.remove(msg.arg1)?.forceClose()
                    A_OPEN -> sendMsg(A_CLSE, 0, msg.arg0)
                }
            }
        } catch (e: Exception) {
            if (!closed) {
                Log.e(TAG, "readLoop exception", e)
                closed = true
                isConnected = false
                streams.values.forEach { it.forceClose() }
                try { onDisconnect?.invoke() } catch (_: Exception) {}
            }
        }
    }

    fun openAbstractSocket(name: String): AdbStream? {
        if (!isConnected) return null
        return try {
            openStream("localabstract:$name")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open abstract socket: $name", e)
            null
        }
    }

    fun openStream(service: String): AdbStream {
        val localId = nextLocalId.getAndIncrement()
        val stream = AdbStream(localId)
        streams[localId] = stream
        sendMsg(A_OPEN, localId, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
        try {
            stream.awaitOpen(15_000)
        } catch (e: Exception) {
            streams.remove(localId)
            throw e
        }
        return stream
    }

    fun shell(command: String): String? {
        if (!isConnected) return null
        val stream = openStream("shell:$command")
        return try {
            stream.inputStream.readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed", e)
            null
        } finally {
            stream.close()
        }
    }

    fun push(localPath: String, remotePath: String, unixMode: Int = 420): Boolean {
        if (!isConnected) return false
        Log.d(TAG, "push: $localPath -> $remotePath")
        val stream = openStream("sync:")
        Log.d(TAG, "push: sync stream opened")
        return try {
            val out = stream.outputStream
            val inp = stream.inputStream

            val pathMode = "$remotePath,$unixMode".toByteArray(Charsets.UTF_8)
            Log.d(TAG, "push: sending SEND")
            out.write("SEND".toByteArray(Charsets.US_ASCII))
            out.writeIntLE(pathMode.size)
            out.write(pathMode)

            Log.d(TAG, "push: sending DATA chunks")
            FileInputStream(localPath).use { fis ->
                val chunkBuf = ByteArray(64 * 1024)
                var totalSent = 0
                while (true) {
                    val len = fis.read(chunkBuf)
                    if (len <= 0) break
                    out.write("DATA".toByteArray(Charsets.US_ASCII))
                    out.writeIntLE(len)
                    out.write(chunkBuf, 0, len)
                    totalSent += len
                }
                Log.d(TAG, "push: sent $totalSent bytes")
            }

            Log.d(TAG, "push: sending DONE")
            out.write("DONE".toByteArray(Charsets.US_ASCII))
            out.writeIntLE((System.currentTimeMillis() / 1000).toInt())
            out.flush()

            Log.d(TAG, "push: waiting for response")
            val idBuf = ByteArray(4)
            inp.readExact(idBuf)
            val msgLen = inp.readIntLE()
            val id = String(idBuf, Charsets.US_ASCII)
            Log.d(TAG, "push: response id=$id, msgLen=$msgLen")
            if (id != "OKAY") {
                val msg = if (msgLen > 0) ByteArray(msgLen).also { inp.readExact(it) }
                    .toString(Charsets.UTF_8) else id
                Log.e(TAG, "Push failed: $msg")
                false
            } else {
                if (msgLen > 0) inp.skip(msgLen.toLong())
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push failed", e)
            false
        } finally {
            stream.close()
        }
    }

    fun pull(remotePath: String, localPath: String): Boolean {
        if (!isConnected) return false
        val stream = openStream("sync:")
        return try {
            val out = stream.outputStream
            val inp = stream.inputStream

            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            out.write("RECV".toByteArray(Charsets.US_ASCII))
            out.writeIntLE(pathBytes.size)
            out.write(pathBytes)
            out.flush()

            val fos = FileOutputStream(localPath)
            try {
                var success = false
                while (true) {
                    val idBuf = ByteArray(4)
                    inp.readExact(idBuf)
                    val msgLen = inp.readIntLE()
                    when (val id = String(idBuf, Charsets.US_ASCII)) {
                        "DATA" -> {
                            val chunk = ByteArray(msgLen)
                            inp.readExact(chunk)
                            fos.write(chunk)
                        }
                        "DONE" -> {
                            if (msgLen > 0) inp.skip(msgLen.toLong())
                            success = true
                            break
                        }
                        "FAIL" -> {
                            val msg = if (msgLen > 0) ByteArray(msgLen).also { inp.readExact(it) }
                                .toString(Charsets.UTF_8) else "unknown error"
                            Log.e(TAG, "Pull failed: $msg")
                            break
                        }
                        else -> {
                            if (msgLen > 0) inp.skip(msgLen.toLong())
                            Log.e(TAG, "Pull failed: unexpected sync id $id")
                            break
                        }
                    }
                }
                success
            } finally {
                fos.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pull failed", e)
            false
        } finally {
            stream.close()
        }
    }

    @Synchronized
    private fun sendMsg(command: Int, arg0: Int = 0, arg1: Int = 0, data: ByteArray = ByteArray(0), ignoreDisconnect: Boolean = false) {
        try {
            val crc = data.fold(0L) { acc, b -> acc + (b.toLong() and 0xFF) }.toInt()
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1)
                .putInt(data.size).putInt(crc).putInt(command xor -1)
                .array()
            rawOut!!.write(header)
            if (data.isNotEmpty()) rawOut!!.write(data)
            rawOut!!.flush()
        } catch (e: Exception) {
            if (!ignoreDisconnect) {
                isConnected = false
                try { onDisconnect?.invoke() } catch (_: Exception) {}
            }
            throw e
        }
    }

    private fun recvMsg(): AdbMsg {
        val h = ByteArray(24)
        rawIn!!.readExact(h)
        val buf = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLen = buf.int
        buf.int // crc
        buf.int // magic
        val data = if (dataLen > 0) ByteArray(dataLen).also { rawIn!!.readExact(it) } else ByteArray(0)
        return AdbMsg(command, arg0, arg1, data)
    }

    private fun InputStream.readExact(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val read = this.read(buf, offset, buf.size - offset)
            if (read < 0) throw IOException("EOF")
            offset += read
        }
    }

    private fun OutputStream.writeIntLE(value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        this.write(buf.array())
    }

    private fun InputStream.readIntLE(): Int {
        val buf = ByteArray(4)
        readExact(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            streams.values.forEach { it.forceClose() }
            streams.clear()
            rawIn?.close()
            rawOut?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close failed", e)
        }
        isConnected = false
    }

    private data class AdbMsg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val END_OF_STREAM = Any()

    inner class AdbStream(val localId: Int) {
        @Volatile
        var remoteId: Int = 0
            private set

        @Volatile
        var closed: Boolean = false
            private set

        private val latch = CountDownLatch(1)
        private val latchOk = AtomicBoolean(false)
        private val queue = LinkedBlockingQueue<Any>()

        val inputStream: InputStream = InStream()
        val outputStream: OutputStream = OutStream()

        internal fun onRemoteOkay(remote: Int) {
            if (remoteId == 0) {
                remoteId = remote
                latchOk.set(true)
                latch.countDown()
            }
        }

        internal fun onData(data: ByteArray) {
            if (!closed) queue.offer(data)
        }

        internal fun forceClose() {
            closed = true
            queue.offer(END_OF_STREAM)
            latch.countDown()
        }

        fun awaitOpen(timeoutMs: Long) {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("ADB stream open timed out (localId=$localId)")
            }
            if (!latchOk.get()) {
                throw IOException("ADB stream rejected by device (localId=$localId)")
            }
        }

        fun close() {
            if (!closed) {
                closed = true
                try {
                    sendMsg(A_CLSE, localId, remoteId, ignoreDisconnect = true)
                } catch (_: Exception) {}
                streams.remove(localId)
            }
        }

        private inner class InStream : InputStream() {
            private var leftover: ByteArray? = null

            override fun read(): Int {
                if (leftover != null) {
                    val b = leftover!![0].toInt() and 0xFF
                    leftover = if (leftover!!.size > 1) leftover!!.copyOfRange(1, leftover!!.size) else null
                    return b
                }
                val item = queue.take()
                if (item === END_OF_STREAM) return -1
                val bytes = item as ByteArray
                if (bytes.isEmpty()) return read()
                if (bytes.size > 1) {
                    leftover = bytes.copyOfRange(1, bytes.size)
                }
                return bytes[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (leftover != null) {
                    val available = leftover!!
                    val toCopy = minOf(len, available.size)
                    System.arraycopy(available, 0, b, off, toCopy)
                    leftover = if (available.size > toCopy) available.copyOfRange(toCopy, available.size) else null
                    return toCopy
                }
                Log.d(TAG, "InStream: waiting for data, len=$len")
                val item = queue.take()
                if (item === END_OF_STREAM) return -1
                val bytes = item as ByteArray
                Log.d(TAG, "InStream: received ${bytes.size} bytes")
                if (bytes.isEmpty()) return read(b, off, len)
                val toCopy = minOf(len, bytes.size)
                System.arraycopy(bytes, 0, b, off, toCopy)
                if (bytes.size > toCopy) {
                    leftover = bytes.copyOfRange(toCopy, bytes.size)
                }
                return toCopy
            }
        }

        private inner class OutStream : OutputStream() {
            override fun write(b: Int) {
                sendMsg(A_WRTE, localId, remoteId, byteArrayOf(b.toByte()))
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                sendMsg(A_WRTE, localId, remoteId, b.copyOfRange(off, off + len))
            }
        }
    }
}
