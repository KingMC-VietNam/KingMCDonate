package net.kingmc.plugin.kingmcdonate.currency

import net.kingmc.plugin.kingmcdonate.util.Scheduler
import org.black_ixx.playerpoints.PlayerPoints
import java.util.UUID

/**
 * PlayerPoints adapter. Only instantiated when the PlayerPoints plugin is
 * enabled (so the PlayerPoints classes are guaranteed on the classpath).
 * PlayerPoints stores points as `int`, so VND-scale amounts are truncated to int.
 *
 * [give] runs on the global region thread (rewards are credited from off-main
 * payment threads) to stay Folia-safe.
 */
class PlayerPointsCurrency(private val scheduler: Scheduler) : CurrencyProvider {

    private val api = PlayerPoints.getInstance().getAPI()

    override val name = "playerpoints"

    override fun isAvailable() = true

    override fun give(uuid: UUID, amount: Long) {
        // PlayerPoints takes an int; clamp so a misconfigured large value can't overflow into a negative deduction.
        val points = amount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        scheduler.runNextTick { api.give(uuid, points) }
    }

    override fun balance(uuid: UUID): Long = api.look(uuid).toLong()
}
