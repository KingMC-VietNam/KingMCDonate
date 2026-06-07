package net.kingmc.plugin.kingmcdonate.currency

import org.black_ixx.playerpoints.PlayerPoints
import java.util.UUID

/**
 * PlayerPoints adapter. Only instantiated when the PlayerPoints plugin is
 * enabled (so the PlayerPoints classes are guaranteed on the classpath).
 * PlayerPoints stores points as `int`, so VNĐ-scale amounts are truncated to int.
 */
class PlayerPointsCurrency : CurrencyProvider {

    private val api = PlayerPoints.getInstance().getAPI()

    override val name = "playerpoints"

    override fun isAvailable() = true

    override fun give(uuid: UUID, amount: Long) {
        api.give(uuid, amount.toInt())
    }

    override fun balance(uuid: UUID): Long = api.look(uuid).toLong()
}
