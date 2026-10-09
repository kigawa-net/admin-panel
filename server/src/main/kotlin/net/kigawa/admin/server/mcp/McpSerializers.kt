package net.kigawa.admin.server.mcp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON-RPC の `id` / `result` に来る `Any` を受け入れるためのシリアライザ。
 *
 * McpRequest/McpResponse は `@Contextual Any?` で定義しているが、
 * SerializersModule に対応するシリアライザが無いと、リクエストの
 * デシリアライズが失敗して Ktor が空ボディの 400 を返す
 * (実機で確認: id 付きの initialize が全部 400 になっていた)。
 *
 * JSON の値型しか来ない(サーバー側で生成する値も同様)ため、
 * JsonElement へ正規化して往復させる。
 */
object AnyJsonSerializer : KSerializer<Any> {
    @kotlinx.serialization.ExperimentalSerializationApi
    override val descriptor: SerialDescriptor =
        SerialDescriptor("net.kigawa.admin.server.mcp.Any", JsonElement.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: Any) {
        encoder.encodeSerializableValue(JsonElement.serializer(), toJson(value))
    }

    override fun deserialize(decoder: Decoder): Any {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        return fromJson(element)
    }

    private fun toJson(value: Any): JsonElement = when (value) {
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        // 想定外の型は文字列化して握りつぶさない(エラーの方が問題を見つけるため)
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJson(element: JsonElement): Any = when (element) {
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "null" -> "null"
            element.booleanOrNull != null -> element.booleanOrNull as Any
            element.intOrNull != null -> element.intOrNull as Any
            element.longOrNull != null -> element.longOrNull as Any
            element.doubleOrNull != null -> element.doubleOrNull as Any
            else -> element.content
        }
        else -> element
    }
}

/** MCP の JSON-RPC モデルで使う SerializersModule。 */
val mcpSerializersModule = kotlinx.serialization.modules.SerializersModule {
    contextual(Any::class, AnyJsonSerializer)
}

/** 失敗時の例外メッセージを安全に取り出す(ログ用)。 */
fun SerializationException.safeMessage(): String = message ?: "serialization error"
