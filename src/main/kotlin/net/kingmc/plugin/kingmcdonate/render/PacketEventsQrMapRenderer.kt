package net.kingmc.plugin.kingmcdonate.render

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders the QR as a client-side virtual map via PacketEvents: it sends the map
 * data and places a filled-map into the player's held hotbar slot purely by packet,
 * never touching the real inventory. The item carries both the legacy NBT map id and
 * the data-component map id so PacketEvents serializes whichever the client's
 * protocol expects (no version branch). A single high map id is reused for every
 * player because the data is client-scoped and re-pushed on each show.
 *
 * Methods that send packets assume they run on the player's region thread; callers
 * schedule them via the entity scheduler.
 */
class PacketEventsQrMapRenderer(private val logger: PluginLogger) : QrMapRenderer {

    private data class ActiveQr(val mapBytes: ByteArray, val hotbarSlot: Int)

    private val active = ConcurrentHashMap<UUID, ActiveQr>()

    init {
        PacketEventsCreativeSlotGuard(
            { uuid -> active[uuid]?.let { PLAYER_INV_HOTBAR_START + it.hotbarSlot } },
            logger,
        ).register()
    }

    override fun show(player: Player, mapBytes: ByteArray) {
        val hotbarSlot = player.inventory.heldItemSlot
        active[player.uniqueId] = ActiveQr(mapBytes, hotbarSlot)
        sendMap(player, mapBytes)
        sendMapItem(player, hotbarSlot)
        logger.debug { "QR shown uuid=${player.uniqueId} slot=$hotbarSlot" }
    }

    override fun resend(player: Player) {
        val qr = active[player.uniqueId] ?: return
        sendMap(player, qr.mapBytes)
        sendMapItem(player, qr.hotbarSlot)
    }

    override fun clear(player: Player) {
        if (active.remove(player.uniqueId) == null) return
        // The fake map was never written server-side; resyncing the inventory overwrites the client slot.
        player.updateInventory()
        logger.debug { "QR cleared uuid=${player.uniqueId}" }
    }

    private fun sendMap(player: Player, mapBytes: ByteArray) {
        val packet = WrapperPlayServerMapData(MAP_ID, 0.toByte(), false, true, null, 128, 128, 0, 0, mapBytes)
        PacketEvents.getAPI().playerManager.sendPacket(player, packet)
    }

    private fun sendMapItem(player: Player, hotbarSlot: Int) {
        val nbt = NBTCompound()
        nbt.setTag("map", NBTInt(MAP_ID))
        val item = ItemStack.builder()
            .type(ItemTypes.FILLED_MAP)
            .nbt(nbt)
            .component(ComponentTypes.MAP_ID, MAP_ID)
            .build()
        val packet = WrapperPlayServerSetSlot(0, 0, PLAYER_INV_HOTBAR_START + hotbarSlot, item)
        PacketEvents.getAPI().playerManager.sendPacket(player, packet)
    }

    companion object {
        // Client-scoped id, re-pushed on each show; only must not collide with a real map the client views.
        private const val MAP_ID = Int.MAX_VALUE
        // Player-inventory container slot of hotbar index 0; offsets 0..8 are the hotbar.
        private const val PLAYER_INV_HOTBAR_START = 36
    }
}
