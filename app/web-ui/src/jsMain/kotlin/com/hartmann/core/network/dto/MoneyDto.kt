package com.hartmann.core.network.dto

import com.hartmann.core.network.exception.UnexpectedResponseException
import com.hartmann.core.model.Money
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive

/** The services send amounts as JSON numbers (`79.00`); [amount] keeps their exact digits as text. */
@Serializable
data class MoneyDto(
    @Serializable(with = DecimalSerializer::class) val amount: String,
    val currency: String,
)

fun fromMoneyDto(dto: MoneyDto): Money =
    Money.parse(dto.amount, dto.currency)
        ?: throw UnexpectedResponseException("Unexpected amount in a response: ${dto.amount}")

fun toMoneyDto(money: Money): MoneyDto = MoneyDto(money.toDecimalString(), money.currency)

/**
 * Reads a JSON number as its literal text and writes it back as a number - never through a Double, which would lose
 * the exact decimal digits.
 */
private object DecimalSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("Decimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String =
        (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content

    override fun serialize(encoder: Encoder, value: String) {
        (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value))
    }
}
