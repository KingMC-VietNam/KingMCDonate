package net.kingmc.plugin.kingmcdonate.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MenuActionTest {

    @Test
    fun `verb and arg split on the first colon`() {
        assertEquals(MenuAction("open", "card-type"), MenuAction.parse("open:card-type"))
    }

    @Test
    fun `command body keeps later colons and spaces`() {
        assertEquals(MenuAction("console", "say hello: world"), MenuAction.parse("console: say hello: world"))
    }

    @Test
    fun `bare token parses to verb with empty arg`() {
        assertEquals(MenuAction("close", ""), MenuAction.parse("close"))
    }

    @Test
    fun `verb is lowercased and trimmed`() {
        assertEquals(MenuAction("page", "next"), MenuAction.parse("  PAGE : next "))
    }
}
