package net.kingmc.plugin.kingmcdonate.gui.menu

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MenuDefinitionTest {

    private fun parse(yaml: String): MenuDefinition {
        val cfg = YamlConfiguration().apply { loadFromString(yaml.trimIndent()) }
        return MenuDefinition.parse("test", cfg)
    }

    @Test
    fun `charSlots maps each non-space char row-major`() {
        val map = MenuDefinition.charSlots(listOf("#########", "#XXXXXXX#"))
        assertEquals((0..8).toList(), map['#']!!.subList(0, 9))
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 16), map['X'])
    }

    @Test
    fun `content marker fills contentSlots and static items take their slots`() {
        val def = parse(
            """
            title: "&8Test"
            rows: 3
            layout:
              - "#########"
              - "#XXXXXXX#"
              - "####C####"
            items:
              X:
                content: true
              C:
                material: BARRIER
                actions: ["close"]
            filler:
              enabled: true
              material: GRAY_STAINED_GLASS_PANE
            """,
        )
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 16), def.contentSlots)
        assertTrue(def.staticItems.containsKey(22))
        assertEquals(emptyList<Int>(), def.contentSlots.filter { it in def.staticItems.keys })
        assertNotNull(def.filler)
    }

    @Test
    fun `explicit slot overrides a layout-char placement on the same slot`() {
        val def = parse(
            """
            title: "&8Test"
            rows: 3
            layout:
              - "C########"
            items:
              C:
                material: BARRIER
              K:
                material: DIAMOND
                slot: 0
            """,
        )
        // Both resolve to slot 0; the explicit-slot item (K=DIAMOND) must win.
        assertEquals("DIAMOND", def.staticItems[0]?.material)
    }

    @Test
    fun `disabled filler is null`() {
        val def = parse(
            """
            title: "&8Test"
            rows: 1
            filler:
              enabled: false
              material: GRAY_STAINED_GLASS_PANE
            """,
        )
        assertEquals(null, def.filler)
    }
}
