package dev.kord.voice

import com.gitlab.kordlib.gateway.*
import com.gitlab.kordlib.gateway.retry.Retry
import dev.kord.voice.VoiceGatewayCloseCode.*
import io.ktor.client.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.http.cio.websocket.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.*
import io.ktor.util.network.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import me.uport.knacl.nacl
import mu.KotlinLogging
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates

private val defaultVoiceGatewayLogger = KotlinLogging.logger {}

data class VoiceGatewayData(
        val guildId: String,
        val channelId: String,
        val userId: String,
        val provider: AudioProvider,
        val httpClient: HttpClient,
        val gateway: Gateway,
        val reconnectRetry: Retry,
        val deaf: Boolean = false,
        val mute: Boolean = false
)

private sealed class State(val retry: Boolean) {
    object Stopped : State(false)
    class Running(retry: Boolean) : State(retry)
    object Detached : State(false)
}


enum class VoiceGatewayCloseCode(val code: Int) {
    UnknownOpCode(4001),
    NotAuthenticated(4003),
    AuthenticationFailed(4004),
    AlreadyAuthenticated(4005),
    SessionNoLongerValid(4006),
    SessionTimeout(4009),
    ServerNotFound(4011),
    UnknownProtocol(4012),
    Disconnected(4014),
    VoiceServerCrashed(4015),
    UnknownEncryptionMode(4016)
}

val VoiceGatewayCloseCode.retry
    get() = when (this) {
        AuthenticationFailed -> false
        SessionNoLongerValid -> false
        Disconnected -> false
        UnknownEncryptionMode -> false

        SessionTimeout -> true
        UnknownOpCode -> true
        NotAuthenticated -> true
        AlreadyAuthenticated -> true
        ServerNotFound -> true
        UnknownProtocol -> true
        VoiceServerCrashed -> true
    }


class DefaultVoiceGateway(val data: VoiceGatewayData, val dispatcher: CoroutineContext = Dispatchers.Default) : VoiceGateway {

    override val coroutineContext: CoroutineContext
        get() = Job() + dispatcher


    val channel = BroadcastChannel<VoiceEvent>(1)

    val providerChannel = Channel<AudioFrame>(1)


    override val events: Flow<VoiceEvent>
        get() = channel.openSubscription().asFlow().buffer(Channel.UNLIMITED)

    private lateinit var udp: ConnectedDatagramSocket

    private lateinit var secretKey: ByteArray

    private val ticker = Ticker()

    private val state: AtomicRef<State> = atomic(State.Stopped)

    private val sequence = atomic<Short>(0)

    lateinit var socket: DefaultClientWebSocketSession

    lateinit var token: String

    var ssrc: Int by Delegates.notNull()

    private val rtpHeader
        get() = ByteBuffer.allocate(12)
                .put(0x80.toByte())
                .put(0x78)
                .putShort(sequence.getAndIncrement())
                .putInt(sequence.value * 960)
                .putInt(ssrc)
                .array()

    var sessionId: String? = null

    private val jsonParser = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
        useArrayPolymorphism = true
    }

    init {
        with(data.provider) {
            produce(providerChannel)
        }
        launch {
            for (e in providerChannel) {
                try {
                    if (e is AudioFrame.Frame) {
                        send(VoiceCommand.Speaking(5, 0, ssrc))
                        send(e.data.array())
                    } else if (e is AudioFrame.Silence) send(e.data.array())
                } catch (_: Exception) {
                }
            }
        }
    }

    override suspend fun start() {
        state.update { State.Running(true) } //resetting state
        data.reconnectRetry.reset()
        while (data.reconnectRetry.hasNext && state.value is State.Running) {
            // must open a subscription before sending the event so we can be sure we get the desired events.

            val voiceUpdateWatcher = async {
                data.gateway.events.filterIsInstance<VoiceStateUpdate>().first {
                    it.voiceState.userId == data.userId && it.voiceState.channelId == data.channelId
                }.voiceState
            }

            val serverUpdateWatcher = async {
                data.gateway.events.filterIsInstance<VoiceServerUpdate>().first {
                    it.voiceServerUpdateData.guildId == data.guildId
                }.voiceServerUpdateData
            }

            data.gateway.send(UpdateVoiceStatus(data.guildId, data.channelId, data.mute, data.deaf))

            val voiceUpdate = voiceUpdateWatcher.await()
            val session = serverUpdateWatcher.await()

            val endpoint = "wss://" + session.endpoint

            on<VoiceReady> {
                ssrc = it.ssrc
                val network = NetworkAddress(it.ip, it.port)
                defaultVoiceGatewayLogger.trace { "${it.ip}, ${it.port}" }
                udp = aSocket(ActorSelectorManager(dispatcher)).udp().connect(network)

                val externalNetwork = discovery(network, ssrc)
                send(VoiceCommand.SelectProtocol("udp", VoiceCommand.SelectProtocolData(externalNetwork.hostname, externalNetwork.port, "xsalsa20_poly1305")))
            }

            on<SessionDescription> {
                secretKey = it.secretKey.toByteArray()
            }

            on<Close> {
                ticker.stop()
            }


            on<VoiceHello> {

                send(VoiceCommand.Identify(data.guildId, data.userId, voiceUpdate.sessionId, session.token))
                ticker.tickAt(it.heartbeatInterval.toLong()) {
                    send(VoiceCommand.Heartbeat(System.currentTimeMillis()))
                }
                send(VoiceCommand.Heartbeat(System.currentTimeMillis()))
            }
            try {
                socket = data.httpClient.webSocketSession {
                    url { url(endpoint) }
                    parameter("v", 3)
                }
            } catch (exception: Exception) {
                defaultVoiceGatewayLogger.error(exception)
                if (exception is java.nio.channels.UnresolvedAddressException) {
                    channel.send(VoiceClose.Timeout)
                }
                data.reconnectRetry.retry()
                continue

            }

            try {
                readSocket()
                data.reconnectRetry.reset() //connected and read without problems, resetting retry counter
            } catch (exception: Exception) {
                defaultVoiceGatewayLogger.error(exception)
            }

            defaultVoiceGatewayLogger.trace { "gateway connection closing" }

            try {
                handleClose()
            } catch (exception: Exception) {
                defaultVoiceGatewayLogger.error(exception)
            }

            defaultVoiceGatewayLogger.trace { "handled gateway connection closed" }

            if ((state.value).retry) data.reconnectRetry.retry()
            else channel.send(VoiceClose.RetryLimitReached)
        }

        if (!data.reconnectRetry.hasNext) defaultVoiceGatewayLogger.warn { "retry limit exceeded, gateway closing" }

    }

    private suspend fun trySend(command: VoiceCommand): Boolean {
        return try {
            sendUnsafe(command)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    private suspend fun sendUnsafe(command: VoiceCommand) {
        val json = Json.encodeToString(VoiceCommand, command)
        defaultVoiceGatewayLogger.trace { "Gateway >>> $json" }
        socket.send(json)
    }


    override suspend fun send(command: VoiceCommand): Boolean {
        return trySend(command)
    }


    private inline fun <reified T> on(crossinline block: suspend (T) -> Unit) {
        events.filterIsInstance<T>().onEach {
            try {
                block(it)
            } catch (exception: Exception) {
                defaultVoiceGatewayLogger.error(exception)
            }
        }.launchIn(this)
    }

    private suspend fun handleClose() {
        val reason = withTimeoutOrNull(1500) {
            socket.closeReason.await()
        } ?: return

        defaultVoiceGatewayLogger.trace { "Gateway closed: ${reason.code} ${reason.message}" }
        val discordReason = values().firstOrNull { it.code == reason.code.toInt() } ?: return

        channel.send(VoiceClose.DiscordClose(discordReason, discordReason.retry))

        if (discordReason.retry) return

        throw  IllegalStateException("Gateway closed: ${reason.code} ${reason.message}")

    }


    override suspend fun detach() {
        if (state.value is State.Detached) return
        state.update { State.Detached }
        channel.send(VoiceClose.Detach)
        if (::socket.isInitialized) {
            socket.close()
        }
        if (::udp.isInitialized) udp.close()
        channel.cancel()
    }

    override suspend fun stop() {
        check(state.value !is State.Detached) { "The resources of this gateway are detached, create another one" }
        channel.send(VoiceClose.UserClose)
        state.update { State.Stopped }
        if (socketOpen) data.gateway.send(UpdateVoiceStatus(data.guildId, null, selfMute = false, selfDeaf = false))
    }


    private suspend fun readSocket() {
        socket.incoming.asFlow().collect {
            when (it) {
                is Frame.Binary, is Frame.Text -> read(it)
                else -> { /*ignore*/
                }
            }
        }

    }

    private suspend fun read(frame: Frame) {
        val json = frame.data.toString(Charset.defaultCharset())

        try {
            defaultVoiceGatewayLogger.trace { "Gateway <<< $json" }
            jsonParser.decodeFromString(VoiceEvent.Companion, json)?.let { channel.send(it) }
        } catch (exception: Exception) {
            defaultVoiceGatewayLogger.error(exception)
        }

    }

    private suspend fun discovery(address: NetworkAddress, ssrc: Int): NetworkAddress {
        val buffer = ByteBuffer.allocate(70)
                .putShort(1)
                .putShort(70)
                .putInt(ssrc)
        val datagram = Datagram(ByteReadPacket(buffer), address)
        udp.send(datagram)
        val received = udp.receive().packet
        received.discardExact(4)
        val ip = received.readBytes(received.remaining.toInt() - 2).toString().trim()
        val port = received.readShortLittleEndian().toInt()

        return NetworkAddress(ip, port)
    }

    private suspend fun send(data: ByteArray) {
        val header = rtpHeader
        val nonce = header.copyOf(24)
        val encrypted = nacl.secretbox.seal(data, nonce, secretKey)
        defaultVoiceGatewayLogger.trace { "Gateway >>> packet" }

        udp.send(Datagram(ByteReadPacket(header + encrypted), udp.localAddress))
    }


    private val socketOpen = ::socket.isInitialized

}

inline fun AtomicRef<Short>.getAndIncrement() = getAndUpdate { it.inc() }

internal fun <T> ReceiveChannel<T>.asFlow() = flow {
    try {
        for (value in this@asFlow) emit(value)
    } catch (ignore: CancellationException) {
        //reading was stopped from somewhere else, ignore
    }
}

