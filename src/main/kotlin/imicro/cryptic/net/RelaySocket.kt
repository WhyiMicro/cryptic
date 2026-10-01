package imicro.cryptic.net

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/**
 * A websocket that is either open or not, and says nothing when it is not.
 *
 * Odin's `WebSocketConnection` (BSD 3-Clause, Copyright (c) 2025 odtheking),
 * which is how Odin users share what they can see with each other: everybody
 * in the same lobby connects to the same room of Odin's relay, and whatever
 * one sends, the rest receive. Built on the JDK's own client, so nothing new
 * is bundled.
 *
 * [onMessage] is called on the socket's own thread. Anything it does to the
 * game has to be handed to the client thread first.
 */
class RelaySocket(private val onMessage: (String) -> Unit) {
	private val logger = LoggerFactory.getLogger("cryptic/relay")

	private val socket = AtomicReference<WebSocket?>(null)

	/** True between the handshake and the close, which is when [send] does anything. */
	val connected: Boolean get() = socket.get() != null

	/** True from [connect] until the attempt has either opened or failed. */
	@Volatile
	var connecting = false
		private set

	private val client: HttpClient by lazy {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
	}

	fun connect(url: String) {
		socket.getAndSet(null)?.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting")
		connecting = true

		val listener = object : WebSocket.Listener {
			private val text = StringBuilder()

			override fun onOpen(webSocket: WebSocket) {
				logger.info("Connected to {}", url)
				socket.set(webSocket)
				connecting = false
				webSocket.request(1)
			}

			override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
				text.append(data)
				if (last) {
					val message = text.toString()
					text.clear()
					if (socket.get() === webSocket) deliver(message)
				}
				webSocket.request(1)
				return null
			}

			override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
				if (socket.get() === webSocket) deliver(StandardCharsets.UTF_8.decode(data).toString())
				webSocket.request(1)
				return null
			}

			override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
				logger.info("Relay closed: {} {}", statusCode, reason)
				socket.compareAndSet(webSocket, null)
				return null
			}

			override fun onError(webSocket: WebSocket, error: Throwable) {
				logger.warn("Relay error: {}", error.message)
				socket.compareAndSet(webSocket, null)
				connecting = false
			}
		}

		try {
			client.newWebSocketBuilder()
				.buildAsync(URI.create(url), listener)
				.exceptionally { error ->
					logger.warn("Could not reach {}: {}", url, error.message)
					connecting = false
					null
				}
		} catch (error: RuntimeException) {
			logger.warn("Could not reach {}: {}", url, error.message)
			connecting = false
		}
	}

	/**
	 * The JDK's socket fails a send made while the last is still going out, and
	 * a moving note sends several a second, so each waits for the one before.
	 */
	private var outgoing: CompletableFuture<*> = CompletableFuture.completedFuture(null)

	@Synchronized
	fun send(message: String): Boolean {
		val open = socket.get() ?: return false
		outgoing = outgoing
			.handle { _, _ -> null }
			.thenCompose { open.sendText(message, true) }
		return true
	}

	fun shutdown() {
		connecting = false
		socket.getAndSet(null)?.sendClose(WebSocket.NORMAL_CLOSURE, "Client shutdown")
	}

	private fun deliver(message: String) {
		try {
			onMessage(message)
		} catch (error: RuntimeException) {
			logger.warn("Relay message not understood: {}", error.message)
		}
	}
}
