package org.hildan.chrome.devtools.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.hildan.chrome.devtools.domains.target.SessionID
import java.util.concurrent.atomic.AtomicLong

/**
 * A wrapper around a [ChromeDPConnection] to handle session-scoped request IDs and filter events of a specific session.
 *
 * It also provides helpers to serialize/deserialize typed payloads from the dynamic parts of the ChromeDP frames.
 */
internal class ChromeDPSession(
    val connection: ChromeDPConnection,
    val sessionId: SessionID?,
) {
    private val nextRequestId = AtomicLong(0)

    suspend inline fun <reified I, reified O> request(methodName: String, requestParams: I?): O =
        request(methodName, requestParams, serializer = serializer(), deserializer = serializer())

    /**
     * Sends request and captures response from the stream.
     */
    suspend fun <I, O> request(
        methodName: String,
        requestParams: I?,
        serializer: SerializationStrategy<I>,
        deserializer: DeserializationStrategy<O>,
    ): O {
        val params = requestParams?.let { json.encodeToJsonElement(serializer, it) }
        val request = RequestFrame(
            id = nextRequestId.incrementAndGet(),
            method = methodName,
            params = params,
            sessionId = sessionId,
        )
        return connection.request(request).decodeResponsePayload(deserializer)
    }

    /**
     * Subscribes to events of the given [eventName], converting their payload to instances of [E].
     */
    inline fun <reified E> events(eventName: String): Flow<E> = events(eventName, serializer())

    /**
     * Subscribes to events of the given [eventName], converting their payload to instances of [E] using [deserializer].
     */
    fun <E> events(eventName: String, deserializer: DeserializationStrategy<E>): Flow<E> = connection.events()
        .filter { it.sessionId == sessionId }
        .filter { it.eventName == eventName }
        .map { it.decodePayload(deserializer) }

    /**
     * Subscribes to events whose names are in the provided [deserializers] map, converting their payload to subclasses
     * of [E] using the corresponding deserializer in the map.
     */
    fun <E> events(deserializers: Map<String, DeserializationStrategy<out E>>): Flow<E> = connection.events()
        .filter { it.sessionId == sessionId }
        .mapNotNull { f -> deserializers[f.eventName]?.let { f.decodePayload(it) } }

    /**
     * Closes the underlying web socket connection, effectively closing every session based on the same web socket
     * connection.
     */
    suspend fun closeWebSocket() {
        connection.close()
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun <T> ResponseFrame.decodeResponsePayload(deserializer: DeserializationStrategy<T>): T =
    json.decodeFromJsonElement(deserializer, result ?: error("Missing result in response"))

private fun <T> EventFrame.decodePayload(deserializer: DeserializationStrategy<T>): T =
    json.decodeFromJsonElement(deserializer, payload)
