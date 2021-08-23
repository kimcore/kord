package dev.kord.core.behavior

import dev.kord.core.behavior.interaction.InteractionBehavior
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Guild

/**
 * The behavior of a [dev.kord.core.entity.interaction.Interaction][Interaction] that was invoked in a [Guild]
 */
interface GuildInteractionBehavior : InteractionBehavior {

    val guildId: Snowflake


    /**
     * The [GuildBehavior] for the guild the command was executed in.
     */
    val guildBehavior get() = GuildBehavior(guildId, kord)


    suspend fun getGuildOrNull(): Guild? = supplier.getGuildOrNull(guildId)

    suspend fun getGuild(): Guild = supplier.getGuild(guildId)

    companion object;

}