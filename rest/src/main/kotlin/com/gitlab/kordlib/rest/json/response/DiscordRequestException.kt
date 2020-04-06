package com.gitlab.kordlib.rest.json.response

import com.gitlab.kordlib.rest.json.JsonErrorCode
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer

@Serializable
data class DiscordRequestException(val code: JsonErrorCode, override val message: String): Exception() {
    companion object DiscordRequestExceptionSerializer : DeserializationStrategy<DiscordRequestException> {
        override val descriptor = SerialDescriptor("JsonErrorResponse") {
            element("code", Int.serializer().descriptor)
            element("message", String.serializer().descriptor)
        }

        override fun deserialize(decoder: Decoder): DiscordRequestException {
            var code: Int?  = null
            var message: String? = null
            with(decoder.beginStructure(descriptor)) {
                loop@ while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        CompositeDecoder.READ_DONE -> break@loop
                        0 -> code = decodeIntElement(descriptor, index)
                        1 -> message = decodeStringElement(descriptor, index)
                    }
                }

                endStructure(descriptor)
            }
            val enum = JsonErrorCode.values().singleOrNull { it.code == code } ?: JsonErrorCode.Unknown
            return DiscordRequestException(enum,message!!)
        }

        override fun patch(decoder: Decoder, old: DiscordRequestException): DiscordRequestException {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

    }
}