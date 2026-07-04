package net.kingmc.plugin.kingmcdonate.bedrock

import org.bukkit.entity.Player
import org.geysermc.cumulus.form.Form
import org.geysermc.floodgate.api.FloodgateApi

/**
 * Thin boundary over Floodgate: the only class (besides the form builders) that imports
 * `org.geysermc.*`. It is constructed only after the Floodgate plugin is confirmed present,
 * so on a server without Floodgate this class is never loaded and no `NoClassDefFoundError`
 * can occur. [isAvailable] reflects whether the Floodgate API resolved; [isBedrock] tells a
 * Bedrock client apart; [send] delivers a Cumulus form to the player.
 */
class BedrockForms private constructor(private val api: FloodgateApi?) {

    val isAvailable: Boolean get() = api != null

    fun isBedrock(player: Player): Boolean = api?.isFloodgatePlayer(player.uniqueId) ?: false

    fun send(player: Player, form: Form): Boolean = api?.sendForm(player.uniqueId, form) ?: false

    companion object {
        /** Resolve the Floodgate API; yields a no-op ([isAvailable] = false) instance if it cannot be reached. */
        fun create(): BedrockForms = BedrockForms(
            try {
                FloodgateApi.getInstance()
            } catch (e: Throwable) {
                null
            },
        )
    }
}
