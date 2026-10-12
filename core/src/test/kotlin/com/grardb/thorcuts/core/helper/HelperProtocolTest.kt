package com.grardb.thorcuts.core.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `parses a fire as the installed generation and the rule index`() {
        assertEquals(HelperMessage.Fired(generation = 1, index = 0), HelperProtocol.parse("F 1 0"))
        assertEquals(HelperMessage.Fired(2147483647, 63), HelperProtocol.parse("F 2147483647 63"))
    }

    @Test
    fun `rejects a fire that is not two non-negative numbers`() {
        assertNull(HelperProtocol.parse("F 1"))            // M1's one-number shape is gone
        assertNull(HelperProtocol.parse("F 1 x"))
        assertNull(HelperProtocol.parse("F -1 0"))
        assertNull(HelperProtocol.parse("F 1 -1"))
        assertNull(HelperProtocol.parse("F 1 0 extra"))
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

    @Test
    fun `encodes a table push as one line`() {
        val cmd = HelperCommand.PushTable(generation = 1, hex = "544852430100000001000000")
        assertEquals("T 1 544852430100000001000000", HelperProtocol.encode(cmd))
    }

    @Test
    fun `a table push refuses hex the helper would refuse`() {
        val refused = listOf(
            1 to "abc",                 // odd length
            1 to "ABCD",                // uppercase
            1 to "zz",                  // not hex
            -1 to "ab",                 // negative generation
            1 to "a".repeat(4122),      // 2061 bytes, over MAX_BYTES
        )
        for ((gen, hex) in refused) {
            assertFailsWith<IllegalArgumentException>("gen=$gen hex=${hex.take(8)}") {
                HelperCommand.PushTable(gen, hex)
            }
        }
        HelperCommand.PushTable(0, "")                   // empty: the helper's to refuse (short)
        HelperCommand.PushTable(Int.MAX_VALUE, "a".repeat(4120))
    }

    @Test
    fun `parses a table acknowledgement, installed or deferred`() {
        assertEquals(
            HelperMessage.TableAccepted(1, 1, installed = true),
            HelperProtocol.parse("T ok 1 1 installed"),
        )
        assertEquals(
            HelperMessage.TableAccepted(7, 0, installed = false),
            HelperProtocol.parse("T ok 7 0 deferred"),
        )
        assertEquals(
            HelperMessage.TableAccepted(2147483647, 64, installed = true),
            HelperProtocol.parse("T ok 2147483647 64 installed"),
        )
    }

    @Test
    fun `parses a table rejection, keeping the whole reason`() {
        assertEquals(
            HelperMessage.TableRejected(3, "hold rule 2"),
            HelperProtocol.parse("T bad 3 hold rule 2"),
        )
        assertEquals(HelperMessage.TableRejected(0, "line"), HelperProtocol.parse("T bad 0 line"))
        assertEquals(HelperMessage.TableRejected(9, "hex"), HelperProtocol.parse("T bad 9 hex"))
    }

    @Test
    fun `rejects malformed table lines`() {
        assertNull(HelperProtocol.parse("T"))
        assertNull(HelperProtocol.parse("T ok"))
        assertNull(HelperProtocol.parse("T ok 1"))
        assertNull(HelperProtocol.parse("T ok 1 1"))               // the three-word shape is gone
        assertNull(HelperProtocol.parse("T ok x 1 installed"))
        assertNull(HelperProtocol.parse("T ok 1 x installed"))
        assertNull(HelperProtocol.parse("T ok 1 1 later"))
        assertNull(HelperProtocol.parse("T ok 1 1 installed extra"))
        assertNull(HelperProtocol.parse("T ok -1 1 installed"))
        assertNull(HelperProtocol.parse("T nope 1 1 installed"))
        assertNull(HelperProtocol.parse("T bad 1"))
        assertNull(HelperProtocol.parse("T bad 1 "))
    }
}
