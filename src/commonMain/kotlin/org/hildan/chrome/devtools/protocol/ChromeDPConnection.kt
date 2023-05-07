package org.hildan.chrome.devtools.protocol

import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString

/**
 * Wraps this [WebSocketSession] to provide Chrome DevTools Protocol capabilities.
 *
 * The returned [ChromeDPConnection] can be used to send requests and listen to events.
 */
internal fun WebSocketSession.chromeDp(): ChromeDPConnection = ChromeDPConnection(this)

/**
 * A connection to Chrome, providing communication primitives for the Chrome DevTools protocol.
 *
 * It encodes/decodes ChromeDP frames, and handles sharing of incoming events.
 */
internal class ChromeDPConnection(
    private val webSocket: WebSocketSession,
) {
    private val coroutineScope = CoroutineScope(CoroutineName("ChromeDP-frame-decoder"))

    private val frames = webSocket.incoming.receiveAsFlow()
        .filterIsInstance<Frame.Text>()
        .map { frame -> chromeDpJson.decodeFromString(InboundFrameSerializer, frame.readText()) }
        .shareIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
        )

    /**
     * Sends the given ChromeDP [request], and returns the corresponding [ResponseFrame].
     *
     * @throws RequestNotSentException if the socket is already closed and the request cannot be sent
     * @throws RequestFailed if the Chrome debugger returns an error frame
     */
    suspend fun request(request: RequestFrame): ResponseFrame {
        val resultFrame = frames.onSubscription { sendOrFailUniformly(request) }
            .filterIsInstance<ResultFrame>()
            .filter { it.matchesRequest(request) }
            .first() // a shared flow never completes, so this will never throw NoSuchElementException (but can hang forever)

        when (resultFrame) {
            is ResponseFrame -> return resultFrame
            is ErrorFrame -> throw RequestFailed(request, resultFrame.error)
        }
    }

    private suspend fun sendOrFailUniformly(request: RequestFrame) {
        try {
            webSocket.send(chromeDpJson.encodeToString(request))
        } catch (e: Exception) {
            // It's possible to get CancellationException without being cancelled, for example
            // when ChromeDPConnection.close() was called before calling request().
            // Not sure why we don't get ClosedSendChannelException in that case - requires further investigation.
            currentCoroutineContext().ensureActive()
            throw RequestNotSentException(request, e)
        }
    }

    private fun ResultFrame.matchesRequest(request: RequestFrame): Boolean =
        // id is only unique within a session, so we need to check sessionId too
        id == request.id && sessionId == request.sessionId

    /**
     * A flow of incoming events.
     */
    fun events() = frames.filterIsInstance<EventFrame>()

    /**
     * Stops listening to incoming events and closes the underlying web socket connection.
     */
    suspend fun close() {
        coroutineScope.cancel()
        webSocket.close()
    }
}

/**
 * An exception thrown when an error occurred during the processing of a request on Chrome side.
 */
class RequestFailed(val request: RequestFrame, val error: RequestError) : Exception(error.message)

/**
 * An exception thrown when an error prevented sending a request via the Chrome web socket.
 */
class RequestNotSentException(
    val request: RequestFrame,
    cause: Throwable?,
) : Exception("Could not send request '${request.method}': $cause", cause)
