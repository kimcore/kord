package dev.kord.voice

import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import mu.KLogger
import mu.KotlinLogging.logger
import kotlin.time.Duration

interface VoiceGateway : CoroutineScope {
    val events: Flow<VoiceEvent>

    suspend fun start()

    suspend fun send(command: VoiceCommand): Boolean

    suspend fun detach()

    suspend fun stop()


}

