package dev.kord.core.entity.application

import dev.kord.common.annotation.KordPreview
import dev.kord.common.entity.DiscordGuildApplicationCommandPermission
import dev.kord.common.entity.Snowflake
import dev.kord.core.cache.data.GuildApplicationCommandPermissionData
import dev.kord.core.cache.data.GuildApplicationCommandPermissionsData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow


class GuildApplicationCommandPermission(val data: GuildApplicationCommandPermissionData) {

    val id: Snowflake get() = data.id

    val type: DiscordGuildApplicationCommandPermission.Type get() = data.type

    val permission: Boolean get() = data.permission
}


class ApplicationCommandPermissions(val data: GuildApplicationCommandPermissionsData) {
    val id: Snowflake get() = data.id

    val applicationId: Snowflake get() = data.applicationId

    val guildId: Snowflake get() = data.guildId

    val permissions: Flow<GuildApplicationCommandPermission>
        get() = flow {
            data.permissions.forEach { emit(GuildApplicationCommandPermission(it)) }
        }
}
