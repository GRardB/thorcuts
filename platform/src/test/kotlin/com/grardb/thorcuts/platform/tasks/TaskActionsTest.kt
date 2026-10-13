package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.platform.privilege.FakeGateway
import com.grardb.thorcuts.platform.privilege.FakeGateway.Call
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskActionsTest {

    private val tool = "com.grardb.thorcuts.platform.tasks.TaskTool"

    private fun entryPoint(vararg args: String): Call = Call.EntryPoint(tool, args.toList())

    @Test
    fun `listTasks runs the tool's list and returns its line verbatim`() {
        val g = FakeGateway("OK swappable 12@0=com.example")
        assertEquals("OK swappable 12@0=com.example", TaskActions(g).listTasks())
        assertEquals(listOf(entryPoint("list")), g.calls)
    }

    @Test
    fun `swapScreens runs swap on the two measured display ids`() {
        val g = FakeGateway("OK swapped 12@0 <-> 7@4")
        assertEquals("OK swapped 12@0 <-> 7@4", TaskActions(g).swapScreens())
        assertEquals(listOf(entryPoint("swap", "0", "4")), g.calls)
    }

    @Test
    fun `no reply from pservice is one ERR line, never a null`() {
        assertEquals("ERR no reply from pservice", TaskActions(FakeGateway(null)).listTasks())
        assertEquals("ERR no reply from pservice", TaskActions(FakeGateway(null)).swapScreens())
    }
}
