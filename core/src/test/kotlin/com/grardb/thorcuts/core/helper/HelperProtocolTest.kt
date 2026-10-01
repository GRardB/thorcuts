package com.grardb.thorcuts.core.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HelperProtocolTest {

    @Test
    fun `parses ready`() {
        assertEquals(HelperMessage.Ready(2), HelperProtocol.parse("R 2"))
    }

    @Test
    fun `parses an event`() {
        assertEquals(
            HelperMessage.Event(device = 1, type = 1, code = 194, value = 1),
            HelperProtocol.parse("E 1 1 194 1"),
        )
    }

    @Test
    fun `parses a negative event value for the D-pad`() {
        // ABS_HAT0X reports -1 / 0 / +1, so negatives must survive the wire.
        assertEquals(
            HelperMessage.Event(device = 0, type = 3, code = 16, value = -1),
            HelperProtocol.parse("E 0 3 16 -1"),
        )
    }

    @Test
    fun `parses fired`() {
        assertEquals(HelperMessage.Fired(1), HelperProtocol.parse("F 1"))
    }

    @Test
    fun `parses log and exiting, keeping the whole remainder`() {
        assertEquals(HelperMessage.Log("grabbed pad ok"), HelperProtocol.parse("L grabbed pad ok"))
        assertEquals(HelperMessage.Exiting("watchdog: no ping"), HelperProtocol.parse("X watchdog: no ping"))
    }

    @Test
    fun `rejects unknown and malformed lines`() {
        assertNull(HelperProtocol.parse(""))
        assertNull(HelperProtocol.parse("Z whatever"))
        assertNull(HelperProtocol.parse("R"))
        assertNull(HelperProtocol.parse("R notanumber"))
        assertNull(HelperProtocol.parse("E 1 1 194"))
        assertNull(HelperProtocol.parse("E 1 1 194 1 extra"))
        assertNull(HelperProtocol.parse("F"))
        assertNull(HelperProtocol.parse("F x"))
    }

    @Test
    fun `rejects lines containing a line break`() {
        // A value carrying a newline would inject a second message.
        assertNull(HelperProtocol.parse("L ok\nF 1"))
        assertNull(HelperProtocol.parse("L ok\rF 1"))
    }

    @Test
    fun `encodes every command`() {
        assertEquals("H", HelperProtocol.encode(HelperCommand.Handshake))
        assertEquals("P", HelperProtocol.encode(HelperCommand.Ping))
        assertEquals("M match", HelperProtocol.encode(HelperCommand.ModeMatch))
        assertEquals("M capture", HelperProtocol.encode(HelperCommand.ModeCapture))
        assertEquals("S", HelperProtocol.encode(HelperCommand.Stop))
    }
}
