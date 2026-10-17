package com.grardb.thorcuts

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportReaderTest {

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    /** Hands out one byte per read, as a slow provider might. */
    private fun trickle(text: String) = object : InputStream() {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private var i = 0
        override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val c = read()
            if (c < 0) return -1
            b[off] = c.toByte()
            return 1
        }
    }

    @Test
    fun `a text within the limit comes back whole, including one exactly at it`() {
        assertEquals("", ImportReader.read(stream(""), 8))
        assertEquals("{\"a\": 1}", ImportReader.read(stream("{\"a\": 1}"), 64))
        assertEquals("12345678", ImportReader.read(stream("12345678"), 8))
        // Multi-byte characters count as bytes, and come back intact.
        assertEquals("héllo", ImportReader.read(stream("héllo"), 6))
    }

    @Test
    fun `one byte over the limit is an IOException naming the limit`() {
        val e = assertFailsWith<IOException> { ImportReader.read(stream("123456789"), 8) }
        assertTrue("8 bytes" in e.message!!, e.message)
    }

    @Test
    fun `a stream that trickles one byte per read is read whole, and still bounded`() {
        assertEquals("12345678", ImportReader.read(trickle("12345678"), 8))
        assertFailsWith<IOException> { ImportReader.read(trickle("123456789"), 8) }
    }
}
