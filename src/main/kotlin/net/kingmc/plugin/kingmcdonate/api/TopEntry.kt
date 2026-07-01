package net.kingmc.plugin.kingmcdonate.api

import java.util.UUID

/** One ranked row of a leaderboard; [rank] starts at 1. Part of the stable public API. */
data class TopEntry(val rank: Int, val uuid: UUID, val name: String?, val value: Long)
