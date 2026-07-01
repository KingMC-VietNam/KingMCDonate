package net.kingmc.plugin.kingmcdonate.api

import java.util.UUID

/**
 * Stable public entry point for third-party plugins integrating with KingMCDonate.
 * Obtain an instance via [get] (set while the plugin is enabled) or the Bukkit
 * ServicesManager. All read methods are backed by thread-safe caches and may be
 * called from any thread.
 */
interface KingMCDonateAPI {

    /** Total donated VND across all methods for [uuid] (all-time). */
    fun getTotalVnd(uuid: UUID): Long

    /** Total reward points earned by [uuid] (all-time). */
    fun getPoint(uuid: UUID): Long

    /** Top donors for [metric] over [period], ranked from 1. */
    fun getTop(metric: DonationMetric, period: DonationPeriod): List<TopEntry>

    /**
     * Credit a manual (admin-issued) top-up to [uuid]. [method] is "card" or "bank"
     * (the totals bucket); [point] overrides the flat-rate default when non-null.
     * The credit runs asynchronously and converges on the normal success path.
     */
    fun giveManual(uuid: UUID, method: String, amount: Long, point: Long?)

    companion object {
        @Volatile
        private var instance: KingMCDonateAPI? = null

        /** The active API instance while KingMCDonate is enabled, or null otherwise. */
        @JvmStatic
        fun get(): KingMCDonateAPI? = instance

        /** Set or clear the active instance. Called by the plugin on enable/disable. */
        internal fun set(api: KingMCDonateAPI?) {
            instance = api
        }
    }
}
