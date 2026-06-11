package org.kde.kdeconnect.plugins.adbconnection.scrcpy

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DirectAdbConnection(
    val host: String,
    val port: Int,
    private val prefs: SharedPreferences,
) : AutoCloseable {

    private val sha1DigestInfoPrefix = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
        0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14,
    )

    private val socket = Socket()
    private lateinit var rawIn: BufferedInputStream
    private lateinit var rawOut: OutputStream
    private val nextLocalId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, AdbSocketStream>()

    @Volatile
    private var closed = false
    private var readerThread: Thread? = null

    private var privateKey: PrivateKey? = null
    private var publicKeyX509: ByteArray? = null
    private var keyName: String = "kdeconnect-adb"

    companion object {
        private const val TAG = "DirectAdbConnection"
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val VERSION = 0x01000001
        private const val MAX_PAYLOAD = 256 * 1024

        private const val ADB_PUBLIC_KEY_WORDS = 64
        private const val ADB_PUBLIC_KEY_MODULUS_BYTES = 256
        private const val ADB_PUBLIC_KEY_BYTES = 4 + 4 + ADB_PUBLIC_KEY_MODULUS_BYTES +
                ADB_PUBLIC_KEY_MODULUS_BYTES + 4

        private const val PREF_PRIVATE_KEY = "adb_private_key"
        private const val PREF_PUBLIC_KEY = "adb_public_key"
    }

    private fun ensureKeys() {
        if (privateKey != null) return

        val privB64 = prefs.getString(PREF_PRIVATE_KEY, null)
        val pubB64 = prefs.getString(PREF_PUBLIC_KEY, null)

        if (!privB64.isNullOrBlank() && !pubB64.isNullOrBlank()) {
            try {
                val privBytes = Base64.decode(privB64, Base64.DEFAULT)
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privBytes))
                publicKeyX509 = Base64.decode(pubB64, Base64.DEFAULT)
                Log.i(TAG, "Loaded persisted ADB keys")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load persisted key, generating new", e)
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        privateKey = kp.private
        publicKeyX509 = kp.public.encoded

        prefs.edit()
            .putString(PREF_PRIVATE_KEY, Base64.encodeToString(privateKey!!.encoded, Base64.NO_WRAP))
            .putString(PREF_PUBLIC_KEY, Base64.encodeToString(publicKeyX509, Base64.NO_WRAP))
            .apply()
        Log.i(TAG, "Generated and persisted new ADB keys")
    }

    fun handshake() {
        ensureKeys()
        Log.i(TAG, "handshake(): tcp connect -> $host:$port")
        socket.connect(InetSocketAddress(host, port), 10_000)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 60_000
        rawIn = BufferedInputStream(socket.getInputStream(), 65_536)
        rawOut = socket.getOutputStream()

        sendMsg(A_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))

        val first = recvMsg()

        when (first.command) {
            A_CNXN -> Unit
            A_AUTH -> {
                if (first.arg0 != AUTH_TOKEN) {
                    throw java.io.IOException("ADB: expected AUTH_TOKEN, got type=${first.arg0}")
                }
                sendMsg(A_AUTH, AUTH_SIGNATURE, 0, signToken(first.data))
                val afterSign = recvMsg()
                when (afterSign.command) {
                    A_CNXN -> Unit
                    A_AUTH -> {
                        if (afterSign.arg0 != AUTH_TOKEN) {
                            throw java.io.IOException("ADB: expected AUTH_TOKEN after rejected signature, got type=${afterSign.arg0}")
                        }
                        sendMsg(A_AUTH, AUTH_RSAPUBLICKEY, 0, buildAdbPubKey())
                        val cnxn = recvMsg()
                        if (cnxn.command != A_CNXN) {
                            throw java.io.IOException("ADB: connection rejected. Please accept the authorisation dialog on the target device.")
                        }
                    }

                    else -> throw java.io.IOException(
                        "ADB: unexpected message 0x${afterSign.command.toString(16)} after AUTH_SIGNATURE",
                    )
                }
            }

            else -> throw java.io.IOException("ADB: unexpected initial message 0x${first.command.toString(16)}")
        }

        socket.soTimeout = 0
        readerThread = thread(isDaemon = true, name = "adb-reader-$host:$port") { readLoop() }
    }

    fun openStream(service: String): AdbSocketStream {
        val localId = nextLocalId.getAndIncrement()
        val stream = AdbSocketStream(localId) { command, arg0, arg1, data ->
            sendMsg(command, arg0, arg1, data)
        }
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

    fun shell(command: String): String =
        openStream("shell:$command")
            .use { it.inputStream.readBytes().toString(Charsets.UTF_8) }

    fun push(data: ByteArray, remotePath: String, unixMode: Int = 420) {
        data.inputStream().use { push(it, remotePath, unixMode) }
    }

    fun push(input: InputStream, remotePath: String, unixMode: Int = 420) {
        openStream("sync:")
            .use { stream ->
                val out = stream.outputStream
                val inp = stream.inputStream
                val pathMode = "$remotePath,$unixMode".toByteArray(Charsets.UTF_8)

                out.write("SEND".toByteArray(Charsets.US_ASCII))
                out.writeIntLE(pathMode.size)
                out.write(pathMode)

                val chunkBuf = ByteArray(64 * 1024)
                while (true) {
                    val len = input.read(chunkBuf)
                    if (len <= 0) break
                    out.write("DATA".toByteArray(Charsets.US_ASCII))
                    out.writeIntLE(len)
                    out.write(chunkBuf, 0, len)
                }

                out.write("DONE".toByteArray(Charsets.US_ASCII))
                out.writeIntLE((System.currentTimeMillis() / 1000).toInt())
                out.flush()

                val idBuf = ByteArray(4).also { inp.readExact(it) }
                val msgLen = inp.readIntLE()
                val id = String(idBuf, Charsets.US_ASCII)
                if (id != "OKAY") {
                    val msg = if (msgLen > 0) ByteArray(msgLen).also { inp.readExact(it) }
                        .toString(Charsets.UTF_8) else id
                    throw java.io.IOException("ADB push failed: $msg")
                } else if (msgLen > 0) {
                    inp.skip(msgLen.toLong())
                }
            }
    }

    fun isAlive(): Boolean = !closed && !socket.isClosed && socket.isConnected

    override fun close() {
        if (!closed) {
            closed = true
            streams.values.forEach { runCatching { it.forceClose() } }
            streams.clear()
            runCatching { socket.close() }
            runCatching { readerThread?.interrupt() }
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val msg = recvMsg()
                when (msg.command) {
                    A_OKAY -> streams[msg.arg1]?.onRemoteOkay(msg.arg0)
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
                streams.values.forEach { runCatching { it.forceClose() } }
            }
        }
    }

    @Synchronized
    private fun sendMsg(
        command: Int,
        arg0: Int = 0,
        arg1: Int = 0,
        data: ByteArray = ByteArray(0),
    ) {
        val crc = data.fold(0L) { acc, b -> acc + (b.toLong() and 0xFF) }.toInt()
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1)
            .putInt(data.size).putInt(crc).putInt(command xor -1)
            .array()
        rawOut.write(header)
        if (data.isNotEmpty()) rawOut.write(data)
        rawOut.flush()
    }

    private fun recvMsg(): AdbMsg {
        val h = ByteArray(24)
        rawIn.readExact(h)
        val buf = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLen = buf.int
        buf.int
        buf.int
        val data =
            if (dataLen > 0) ByteArray(dataLen).also { rawIn.readExact(it) } else ByteArray(0)
        return AdbMsg(command, arg0, arg1, data)
    }

    private data class AdbMsg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun signToken(token: ByteArray): ByteArray {
        val payload = ByteArray(sha1DigestInfoPrefix.size + token.size)
        sha1DigestInfoPrefix.copyInto(payload, 0)
        token.copyInto(payload, sha1DigestInfoPrefix.size)
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
        val words = 64
        val bytes = 256
        val two32 = BigInteger.ONE.shiftLeft(32)
        val mask32 = two32.subtract(BigInteger.ONE)

        fun toBigEndianPadded(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            val arr = ByteArray(bytes)
            val src = if (raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            src.copyInto(arr, destinationOffset = bytes - src.size)
            return arr
        }

        val modBE = toBigEndianPadded(modulus)
        val n0 = modulus.and(mask32)
        val n0inv = n0.modInverse(two32).negate().mod(two32).toInt()
        val r = BigInteger.ONE.shiftLeft(bytes * 8)
        val rrBE = toBigEndianPadded(r.multiply(r).mod(modulus))

        val buf = ByteBuffer.allocate(4 + 4 + bytes + bytes + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv)
        for (i in words - 1 downTo 0) {
            val o = i * 4
            buf.put(modBE[o + 3]); buf.put(modBE[o + 2]); buf.put(modBE[o + 1]); buf.put(modBE[o])
        }
        for (i in words - 1 downTo 0) {
            val o = i * 4
            buf.put(rrBE[o + 3]); buf.put(rrBE[o + 2]); buf.put(rrBE[o + 1]); buf.put(rrBE[o])
        }
        buf.putInt(exponent)
        return buf.array()
    }
}

fun InputStream.readExact(buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) throw EOFException("readExact: expected ${buf.size} bytes, got $off")
        off += n
    }
}

fun InputStream.readIntLE(): Int {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b3 < 0) throw EOFException("readIntLE: EOF")
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

fun OutputStream.writeIntLE(v: Int) {
    write(v and 0xFF)
    write(v shr 8 and 0xFF)
    write(v shr 16 and 0xFF)
    write(v shr 24 and 0xFF)
}
