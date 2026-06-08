package com.itsmart.datecs.client.device

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedList

/**
 * USB InputStream matching the official Datecs UsbDeviceConnector pattern.
 *
 * Key design: a BACKGROUND THREAD continuously reads from USB bulk endpoint
 * into a synchronized LinkedList buffer. available() and read() are non-blocking
 * checks against this buffer, which is what FiscalSocket expects.
 *
 * The official Datecs sample uses exactly this pattern — without the background
 * thread, FiscalSocket.read() times out because available() returns 0 (USB data
 * hasn't been polled yet when the SDK checks).
 */
class UsbInputStream(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint,
    private val skipBytes: Int = 0
) : InputStream() {

    companion object {
        private const val TAG = "UsbInputStream"
        private const val BULK_TIMEOUT = 100 // ms, matches official sample
    }

    private val dataBuffer = LinkedList<Byte>()
    @Volatile private var lastError: String? = null

    init {
        // Start background reader thread — matches official Datecs InputStreamImpl
        val readerThread = Thread {
            val tmp = ByteArray(64)
            while (lastError == null) {
                val ms = System.currentTimeMillis() + BULK_TIMEOUT / 2
                val len = connection.bulkTransfer(endpoint, tmp, tmp.size, BULK_TIMEOUT)
                if (len < 0) {
                    try {
                        // If bulkTransfer returned negative faster than timeout/2,
                        // it's a real error (not just no data)
                        if (ms > System.currentTimeMillis()) {
                            lastError = "Read failed"
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else if (len > 0) {
                    Log.d(TAG, "bg: read $len bytes from USB")
                    synchronized(dataBuffer) {
                        for (i in skipBytes until len) {
                            dataBuffer.add(tmp[i])
                        }
                    }
                }
            }
        }
        readerThread.isDaemon = true
        readerThread.name = "UsbInputStream-reader"
        readerThread.start()
    }

    override fun available(): Int {
        lastError?.let { throw IOException(it) }
        synchronized(dataBuffer) {
            return dataBuffer.size
        }
    }

    override fun close() {
        lastError = "The stream is closed"
    }

    override fun read(): Int {
        while (true) {
            lastError?.let { throw IOException(it) }
            synchronized(dataBuffer) {
                if (dataBuffer.size > 0) {
                    return dataBuffer.removeFirst().toInt() and 0xFF
                }
            }
            SystemClock.sleep(10)
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            lastError?.let { throw IOException(it) }
            synchronized(dataBuffer) {
                val count = dataBuffer.size
                if (count > 0) {
                    val chunkSize = minOf(len, count)
                    for (i in 0 until chunkSize) {
                        b[off + i] = dataBuffer.removeFirst()
                    }
                    return chunkSize
                }
            }
            SystemClock.sleep(10)
        }
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }
}

/**
 * USB OutputStream matching the official Datecs UsbDeviceConnector pattern.
 *
 * Uses a packet buffer — individual write() calls accumulate in the buffer,
 * and flush() sends everything as one USB bulk transfer. This matches how
 * the SDK sends commands (write byte-by-byte, then flush).
 */
class UsbOutputStream(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint
) : OutputStream() {

    companion object {
        private const val TAG = "UsbOutputStream"
        private const val TIMEOUT = 100
    }

    private val packetBuffer = ByteArray(2048)
    private var packetLength = 0
    @Volatile private var lastError: String? = null

    override fun close() {
        lastError = "The stream is closed"
    }

    @Synchronized
    override fun write(b: Int) {
        lastError?.let { throw IOException(it) }
        if (packetLength == packetBuffer.size) {
            flush()
        }
        packetBuffer[packetLength++] = b.toByte()
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in 0 until len) {
            write(b[off + i].toInt())
        }
    }

    @Synchronized
    override fun flush() {
        while (packetLength > 0) {
            lastError?.let { throw IOException(it) }
            val len = connection.bulkTransfer(endpoint, packetBuffer, packetLength, TIMEOUT)
            if (len < 0) {
                Log.e(TAG, "Write bulkTransfer failed: $len")
                lastError = "Write error $len"
                throw IOException(lastError)
            } else {
                packetLength -= len
                if (packetLength > 0) {
                    System.arraycopy(packetBuffer, len, packetBuffer, 0, packetLength)
                }
            }
        }
    }
}
