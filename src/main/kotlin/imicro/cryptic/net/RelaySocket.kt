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
 * A websocket to Odin's relay, where everyone in the same lobby shares what they see. Quiet when it
 * isn't connected.
 *
 * [onMessage] runs on the socket's thread; hand anything that touches the game to the client
 * thread.
 */
class RelaySocket(private val onMessage: (String) -> Unit) {
	private val logger = LoggerFactory.getLogger("cryptic/relay")

	private val socket = AtomicReference<WebSocket?>(null)

	val connected: Boolean get() = socket.get() != null

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

	/** The JDK socket fails a send while the last is still going, so each send waits for the one before. */
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
