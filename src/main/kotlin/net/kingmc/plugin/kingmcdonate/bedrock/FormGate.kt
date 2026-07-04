package net.kingmc.plugin.kingmcdonate.bedrock

/**
 * Pure decision for whether a player should get a Bedrock form instead of the chest GUI:
 * the master toggle and this form's toggle are on, Floodgate is present, and the player is
 * a Bedrock client. Kept free of any `org.geysermc.*` reference so it is unit-testable.
 */
object FormGate {
    fun shouldUse(masterEnabled: Boolean, formEnabled: Boolean, floodgatePresent: Boolean, isBedrock: Boolean): Boolean =
        masterEnabled && formEnabled && floodgatePresent && isBedrock
}
