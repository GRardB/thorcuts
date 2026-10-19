package com.grardb.thorcuts.platform.tasks

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PngTest {

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `the eight-byte signature, with anything after it, is a PNG`() {
        assertTrue(isPng(signature))
        assertTrue(isPng(signature + byteArrayOf(0, 0, 0, 13, 'I'.code.toByte())))
    }

    @Test
    fun `a short, empty or differently-headed file is not`() {
        assertFalse(isPng(ByteArray(0)))
        assertFalse(isPng(signature.copyOf(7)))
        assertFalse(isPng("JFIF....".toByteArray()))
        assertFalse(isPng(signature.copyOf().also { it[0] = 0x88.toByte() }))
    }
}
