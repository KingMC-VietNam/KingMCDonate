package net.kingmc.plugin.kingmcdonate.leaderboard

import com.cryptomorin.xseries.profiles.builder.XSkull
import com.cryptomorin.xseries.profiles.objects.Profileable
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Resolves a player-head texture onto an item off the main thread via XSeries' XSkull,
 * which caches resolved profiles and throttles/retries Mojang lookups itself. On failure
 * the original [base] item is returned unchanged (default head) so a missing skin never
 * breaks the menu.
 */
class HeadResolver(private val logger: PluginLogger) {

    fun resolve(base: ItemStack, uuid: UUID): CompletableFuture<ItemStack> =
        XSkull.of(base).profile(Profileable.of(uuid)).applyAsync()
            .thenApply { it as ItemStack }
            .exceptionally { error ->
                logger.debug { "Head resolve failed for $uuid: ${error.message}" }
                base
            }
}
