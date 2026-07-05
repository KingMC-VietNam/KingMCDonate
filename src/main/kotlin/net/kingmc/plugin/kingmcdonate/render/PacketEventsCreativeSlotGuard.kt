package net.kingmc.plugin.kingmcdonate.render

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.entity.Player

class PacketEventsCreativeSlotGuard(
    private val lookup: QrSlotLookup,
    private val logger: PluginLogger,
) : PacketListenerAbstract() {

    fun register() {
        PacketEvents.getAPI().eventManager.registerListener(this)
    }

    fun unregister() {
        PacketEvents.getAPI().eventManager.unregisterListener(this)
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) return
        val player = event.getPlayer() as? Player ?: return
        val protectedSlot = lookup.protectedSlot(player.uniqueId) ?: return
        if (WrapperPlayClientCreativeInventoryAction(event).slot == protectedSlot) {
            event.isCancelled = true
            logger.debug { "Blocked creative slot echo uuid=${player.uniqueId} slot=$protectedSlot" }
        }
    }
}
