package net.kingmc.plugin.kingmcdonate.gui.screen

import net.kingmc.plugin.kingmcdonate.database.dao.LeaderboardDao
import net.kingmc.plugin.kingmcdonate.gui.Gui
import net.kingmc.plugin.kingmcdonate.gui.MenuItem
import net.kingmc.plugin.kingmcdonate.gui.Pagination
import net.kingmc.plugin.kingmcdonate.gui.menu.ItemTemplate
import net.kingmc.plugin.kingmcdonate.gui.menu.MenuDefinition
import net.kingmc.plugin.kingmcdonate.gui.menu.MenuService
import net.kingmc.plugin.kingmcdonate.leaderboard.HeadResolver
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardService
import net.kingmc.plugin.kingmcdonate.leaderboard.LeaderboardView
import net.kingmc.plugin.kingmcdonate.util.ItemBuilder
import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Paginated top-donor GUI built from `topnap.yml`. Metric (VND ⇄ point) and period
 * (all/day/week/month) toggle in place: a button click changes the view state, re-reads
 * the cached board, and rebuilds the content slots and toggle labels without reopening
 * the inventory. Player heads are resolved off the main thread and patched back per slot,
 * guarded by a generation counter so a late resolve from a previous view/page is dropped.
 */
class LeaderboardMenu(
    private val menus: MenuService,
    private val leaderboard: LeaderboardService,
    private val heads: HeadResolver,
    private val scheduler: Scheduler,
) {

    init {
        menus.registerOpener("topnap") { open(it) }
    }

    fun open(player: Player) {
        val definition = menus.registry.get("topnap") ?: return
        val view = LeaderboardView.DEFAULT
        scheduler.runIo {
            val entries = leaderboard.topEager(view.metric, view.period)
            scheduler.runAtEntity(player) { Session(definition, player, view).openWith(entries) }
        }
    }

    private data class RankedEntry(val rank: Int, val entry: LeaderboardDao.Entry)

    /** One open menu instance: its own view state, pagination, and head-fill generation. */
    private inner class Session(
        private val definition: MenuDefinition,
        private val player: Player,
        private var view: LeaderboardView,
    ) {

        private val gui: Gui = menus.create(definition, player, menus.baseTokens(player))
        private val charSlots = MenuDefinition.charSlots(definition.root.getStringList("layout"))
        private val metricLabels = labelMap("metric-labels")
        private val periodLabels = labelMap("period-labels")
        private val emptyItem: ItemStack? = definition.root.getConfigurationSection("empty-item")
            ?.let { ItemTemplate.fromConfig(it).build(emptyMap(), player) }

        private var entries: List<LeaderboardDao.Entry> = emptyList()

        // Only touched on the region thread; a late head resolve compares its captured value.
        private var generation = 0

        private val pagination = Pagination(gui, definition.contentSlots) { ranked: RankedEntry ->
            MenuItem(rowItem(ranked))
        }

        fun openWith(initial: List<LeaderboardDao.Entry>) {
            wireNavAndClose()
            gui.open(player)
            render(initial)
        }

        /** Rebuild toggle buttons + content + heads for the current [newEntries]; used on open and on every toggle. */
        private fun render(newEntries: List<LeaderboardDao.Entry>) {
            entries = newEntries
            renderToggleButtons()
            pagination.setItems(newEntries.mapIndexed { i, e -> RankedEntry(i + 1, e) })
            if (newEntries.isEmpty()) showEmpty()
            refreshHeads()
        }

        private fun reload() {
            scheduler.runIo {
                val next = leaderboard.topEager(view.metric, view.period)
                scheduler.runAtEntity(player) { if (isViewing()) render(next) }
            }
        }

        /** Resolve a head per visible entry and patch its slot when done, dropping stale generations. */
        private fun refreshHeads() {
            generation++
            val gen = generation
            val pageSize = definition.contentSlots.size.coerceAtLeast(1)
            val start = pagination.pageIndex * pageSize
            definition.contentSlots.forEachIndexed { i, slot ->
                val index = start + i
                if (index >= entries.size) return@forEachIndexed
                val ranked = RankedEntry(index + 1, entries[index])
                heads.resolve(rowItem(ranked), ranked.entry.uuid).thenAccept { resolved ->
                    scheduler.runAtEntity(player) {
                        if (isViewing() && gen == generation) gui.set(slot, MenuItem(resolved))
                    }
                }
            }
        }

        private fun renderToggleButtons() {
            metricSlot()?.let { slot ->
                gui.setItem(slot, buttonItem(slot, "metric", metricLabel())) { view = view.toggledMetric(); reload() }
            }
            periodSlot()?.let { slot ->
                gui.setItem(slot, buttonItem(slot, "period", periodLabel())) { view = view.toggledPeriod(); reload() }
            }
        }

        private fun wireNavAndClose() {
            slot('P')?.let { s -> gui.setItem(s, navItem(s)) { pagination.previous(); refreshHeads() } }
            slot('N')?.let { s -> gui.setItem(s, navItem(s)) { pagination.next(); refreshHeads() } }
            slot('C')?.let { s -> gui.setItem(s, navItem(s)) { player.closeInventory() } }
        }

        private fun showEmpty() {
            val slot = definition.contentSlots.firstOrNull() ?: return
            emptyItem?.let { gui.setItem(slot, it) }
        }

        private fun rowItem(ranked: RankedEntry): ItemStack {
            val tokens = view.rowTokens(ranked.rank, ranked.entry.name, ranked.entry.value, metricLabel())
            val template = definition.root.getConfigurationSection("entry-item")
            return if (template != null) {
                ItemTemplate.fromConfig(template).build(tokens, player)
            } else {
                ItemBuilder.of("PLAYER_HEAD").name("&e#${ranked.rank} &f${ranked.entry.name ?: "?"}").build()
            }
        }

        private fun buttonItem(slot: Int, token: String, label: String): ItemStack {
            val template = definition.staticItems[slot]
            return template?.build(mapOf(token to label), player)
                ?: ItemBuilder.of("PAPER").name("&f$label").build()
        }

        private fun navItem(slot: Int): ItemStack =
            definition.staticItems[slot]?.build(menus.baseTokens(player), player)
                ?: ItemBuilder.of("ARROW").build()

        private fun metricLabel() = metricLabels[view.metric.name] ?: view.metric.name
        private fun periodLabel() = periodLabels[view.period.name] ?: view.period.name
        private fun metricSlot() = slot('M')
        private fun periodSlot() = slot('K')
        private fun slot(char: Char): Int? = charSlots[char]?.firstOrNull()

        private fun labelMap(path: String): Map<String, String> {
            val section = definition.root.getConfigurationSection(path) ?: return emptyMap()
            return section.getKeys(false).associateWith { section.getString(it) ?: it }
        }

        private fun isViewing(): Boolean = player.openInventory.topInventory.holder === gui
    }
}
