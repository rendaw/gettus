package com.zarbosoft.gettus;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zarbosoft.rendaw.common.Common;
import io.undertow.UndertowOptions;
import io.undertow.client.*;
import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.util.ConcurrentDirectDeque;
import io.undertow.util.FastConcurrentDirectDeque;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.*;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;
import org.xnio.ssl.XnioSsl;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.zarbosoft.rendaw.common.Common.uncheck;
import static org.xnio.Options.READ_TIMEOUT;

public abstract class GettusBase<I> {
	public final static ObjectMapper jackson = new ObjectMapper();
	private final static ConcurrentHashMap<String, ConcurrentDirectDeque<ClientConnection>> connectionPool =
			new ConcurrentHashMap<>();
	private final Logger logger = LoggerFactory.getLogger("gettus");
	private static final AtomicLong count = new AtomicLong(0);
	final long index;
	private static final ByteBufferPool bufferPool;
	private final XnioWorker worker;
	private boolean dontCheckCerts = false;
	private final String connectionKey;
	private volatile XnioExecutor.Key timeoutKey;

	/**
	 * Formats a string using `String.format` and returns it as a URI.
	 *
	 * @param pattern
	 * @param args
	 * @return
	 */
	public static URI formatURI(final String pattern, final Object... args) {
		return uncheck(() -> new URI(String.format(pattern, args)));
	}

	/**
	 * Called when an asynchronous action completes.
	 *
	 * @param resolver data passed to send() or body()
	 * @param value    the downloaded Headers or Body object
	 */
	protected abstract void resolve(Object resolver, Object value);

	/**
	 * Called when an asynchronous action fails with an error.
	 *
	 * @param resolver  data passed to send() or body()
	 * @param exception the error
	 */
	protected abstract void resolveException(Object resolver, RuntimeException exception);

	/**
	 * For use when extending, to provide an alt Headers class with method overrides.
	 *
	 * @param connection Pass this to the constructor
	 * @param exchange   Pass this to the constructor
	 * @param response   Pass this to the constructor
	 * @param <K>
	 * @return
	 */
	protected abstract <K> Headers<K> createHeaders(
			final ClientConnection connection, final ClientExchange exchange, final ClientResponse response
	);

	public static class ResponseTooLargeError extends RuntimeException {

	}

	/**
	 * Raised if `check()` fails.
	 */
	public static class ResponseCodeError extends RuntimeException {

		public ResponseCodeError(final int code) {
			super(String.format("Recieved response status code %s", code));
		}

		public ResponseCodeError(final int code, final String body) {
			super(String.format("Recieved response status code %s:\n%s", code, body));
		}
	}

	static {
		// ** Copied from Undertow.java (?)
		final long maxMemory = Runtime.getRuntime().maxMemory();
		final int bufferSize;
		final boolean directBuffers;
		//smaller than 64mb of ram we use 512b buffers
		if (maxMemory < 64 * 1024 * 1024) {
			//use 512b buffers
			directBuffers = false;
			bufferSize = 512;
		} else if (maxMemory < 128 * 1024 * 1024) {
			//use 1k buffers
			directBuffers = true;
			bufferSize = 1024;
		} else {
			//use 16k buffers for best performance
			//as 16k is generally the max amount of data that can be sent in a single write() call
			directBuffers = true;
			bufferSize = 1024 * 16;
		}
		bufferPool = new DefaultByteBufferPool(directBuffers, bufferSize, -1, 4);
	}

	ClientRequest request = new ClientRequest();
	final URI uri;
	byte[] body = null;
	private int timeout = 60 * 1000;
	private int limitSize = 0;

	public GettusBase(final XnioWorker worker, final URI uri) {
		this.worker = worker;
		this.index = GettusBase.count.getAndIncrement();
		this.uri = uri;
		this.connectionKey = String.format("%s:%s", uri.getHost(), uri.getPort());
		final StringBuilder path = new StringBuilder();
		path.append(uri.getRawPath());
		if (uri.getRawQuery() != null) {
			path.append("?");
			path.append(uri.getRawQuery());
		}
		this.request.setPath(path.toString());
	}

	/**
	 * Set the request method (GET, POST, etc)
	 *
	 * @param method
	 * @return
	 */
	public I method(final HttpString method) {
		this.request.setMethod(method);
		return (I) this;
	}

	/**
	 * Set the request body
	 *
	 * @param body
	 * @return
	 */
	public I body(final byte[] body) {
		this.body = body;
		return (I) this;
	}

	/**
	 * Set the request body as JSON serialized from an object
	 *
	 * @param jackson
	 * @param o
	 * @return
	 */
	public I bodyJson(final ObjectMapper jackson, final Object o) {
		try {
			return this.body(jackson.writeValueAsBytes(o));
		} catch (final JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Set the request body as JSON serialized from an object
	 *
	 * @param o
	 * @return
	 */
	public I bodyJson(final Object o) {
		return this.bodyJson(GettusBase.jackson, o);
	}

	/**
	 * Use a functor to generate a JSON body
	 *
	 * @param write
	 * @return
	 */
	public I bodyJson(final Common.Consumer2<JsonGenerator> write) {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		try (final JsonGenerator bodyWriter = new JsonFactory().createGenerator(body)) {
			uncheck(() -> write.accept(bodyWriter));
		} catch (final IOException e) {
			throw uncheck(e);
		}
		return this.body(body.toByteArray());
	}

	/**
	 * Set request headers
	 *
	 * @param headers
	 * @return
	 */
	public I headers(final Map<HttpString, String> headers) {
		headers.forEach((k, v) -> this.request.getRequestHeaders().add(k, v));
		return (I) this;
	}

	/**
	 * Set an individual request header
	 *
	 * @param name
	 * @param value
	 * @return
	 */
	public I header(final HttpString name, final String value) {
		this.request.getRequestHeaders().add(name, value);
		return (I) this;
	}

	/**
	 * Set basic-auth AUTHORIZATION header
	 *
	 * @param user
	 * @param password
	 * @return
	 */
	public I basicAuth(final String user, final String password) {
		return this.header(io.undertow.util.Headers.AUTHORIZATION, String.format("Basic %s",
				Base64
						.getEncoder()
						.encodeToString(String.format("%s:%s", user, password).getBytes(StandardCharsets.US_ASCII))
		));
	}

	/**
	 * Abort the operation if no progress occurs for this duration in milliseconds.  Note that the total time before the
	 * request aborts may be longer than this value.
	 *
	 * @param seconds
	 * @return
	 */
	public I timeout(final int seconds) {
		this.timeout = seconds;
		return (I) this;
	}

	/**
	 * Limit response size - raise an error and stop downloading if this size is exceeded.
	 *
	 * @param size
	 * @return
	 */
	public I limitSize(final int size) {
		this.limitSize = size;
		return (I) this;
	}

	/**
	 * Ignore SSL cert validation errors.
	 *
	 * @return
	 */
	public I dontCheckCerts() {
		this.dontCheckCerts = true;
		return (I) this;
	}

	/**
	 * Send the request. resolve/resolveError will be called when the headers are downloaded or an error occus.
	 *
	 * @param resolver Passed to resolve/resolveError
	 */
	public void send(final Object resolver) {
		if (this.logger.isDebugEnabled()) {
			this.logger.debug(String.format("SEND %s %s\nHeaders: %s\nBody: %s",
					this.request.getMethod(),
					this.uri,
					this.request.getRequestHeaders(),
					this.body == null ? "" : new String(this.body, StandardCharsets.UTF_8)
			));
		}
		this.request
				.getRequestHeaders()
				.add(io.undertow.util.Headers.CONTENT_LENGTH,
						Objects.toString(this.body == null ? 0 : this.body.length)
				);
		final SSLContext sslContext;
		if (this.dontCheckCerts) {
			sslContext = uncheck(() -> SSLContext.getInstance("SSL"));
			uncheck(() -> sslContext.init(null, new TrustManager[] {
					new X509TrustManager() {
						@Override
						public X509Certificate[] getAcceptedIssuers() {
							return new X509Certificate[0];
						}

						@Override
						public void checkClientTrusted(
								final X509Certificate[] certs, final String authType
						) {
						}

						@Override
						public void checkServerTrusted(
								final X509Certificate[] certs, final String authType
						) {
						}
					}
			}, null));
		} else {
			sslContext = uncheck(() -> SSLContext.getDefault());
		}
		final XnioSsl ssl = new UndertowXnioSsl(this.worker.getXnio(),
				OptionMap.create(Options.USE_DIRECT_BUFFERS, true),
				sslContext
		);
		try {
			this.initializeTimeout(resolver);
			do {
				final ConcurrentDirectDeque<ClientConnection> queue = GettusBase.connectionPool.get(this.connectionKey);
				if (queue == null)
					break;
				final ClientConnection connection = queue.pollLast();
				if (queue.isEmpty())
					GettusBase.connectionPool.remove(queue); // Race condition, but missed connections should clean up eventually
				if (connection == null || !connection.isOpen())
					break;
				connection.sendRequest(this.request, new StageSendRequest(resolver, connection));
				return;
			} while (false);
			UndertowClient.getInstance().
					connect(new StageGetConnection(resolver),
							this.uri,
							this.worker,
							ssl,
							GettusBase.bufferPool,
							OptionMap.builder().set(UndertowOptions.IDLE_TIMEOUT, this.timeout).getMap()
					);
		} catch (final Exception e) {
			this.resolveException(resolver, new GettusError(this, e));
		}
	}

	private void initializeTimeout(final Object resolver) {
		if (this.timeout <= 0)
			return;
		this.timeoutKey = this.worker.getIoThread().executeAfter(() -> {
			this.resolveException(resolver, new GettusError(this, String.format("Request timeout")));
		}, this.timeout, TimeUnit.MILLISECONDS);
	}

	private boolean extendTimeout(final XnioIoThread thread, final Object resolver) {
		if (this.timeout <= 0)
			return true;
		if (this.timeoutKey != null && !this.timeoutKey.remove())
			return false;
		this.timeoutKey = thread.executeAfter(() -> {
			this.resolveException(resolver, new GettusError(this, String.format("Request timeout")));
		}, this.timeout, TimeUnit.MILLISECONDS);
		return true;
	}

	private boolean extendTimeout(final Object resolver) {
		return this.extendTimeout(this.worker.getIoThread(), resolver);
	}

	private void fatal(final ExecutorService executor, final Throwable e) {
		this.logger.error("Uncaught error in executor; shutting down", e);
		executor.shutdown();
	}

	private void resolve1(final Object resolver, final Object value) {
		if (this.timeoutKey != null) {
			if (!this.timeoutKey.remove())
				return;
			this.timeoutKey = null;
		}
		this.worker.submit(() -> {
			try {
				this.resolve(resolver, value);
			} catch (final Throwable e) {
				this.fatal(this.worker, e);
			}
		});
	}

	private void resolveException1(final Object resolver, final RuntimeException error) {
		if (this.timeoutKey != null) {
			if (!this.timeoutKey.remove())
				return;
			this.timeoutKey = null;
		}
		this.worker.submit(() -> {
			try {
				this.resolveException(resolver, error);
			} catch (final Throwable e) {
				this.fatal(this.worker, e);
			}
		});
	}

	private class StageGetConnection implements ClientCallback<ClientConnection> {

		private final Object resolver;

		public StageGetConnection(final Object resolver) {
			this.resolver = resolver;
		}

		@Override
		public void completed(final ClientConnection result) {
			if (!GettusBase.this.extendTimeout(this.resolver))
				return;
			result.sendRequest(GettusBase.this.request, new StageSendRequest(this.resolver, result));
		}

		@Override
		public void failed(final IOException e) {
			GettusBase.this.resolveException1(this.resolver, new GettusError(GettusBase.this, e));
		}
	}

	private class StageSendRequest implements ClientCallback<ClientExchange> {

		private final Object resolver;
		private final ClientConnection connection;

		public StageSendRequest(final Object resolver, final ClientConnection connection) {
			this.resolver = resolver;
			this.connection = connection;
		}

		@Override
		public void completed(final ClientExchange exchange) {
			if (!GettusBase.this.extendTimeout(this.resolver))
				return;
			if (GettusBase.this.body != null) {
				final ByteBuffer bytes = ByteBuffer.wrap(GettusBase.this.body);
				final StreamSinkChannel channel = exchange.getRequestChannel();
				// If channel null means accidental double get
				int i = 0;
				ByteBuffer[] bufs = null;
				PooledByteBuffer[] pooledBuffers = null;
				while (bytes.hasRemaining()) {
					final PooledByteBuffer pooled = GettusBase.bufferPool.allocate();
					// TODO should pooled be closed?
					if (bufs == null) {
						final int noBufs = (bytes.remaining() + pooled.getBuffer().remaining() - 1) /
								pooled.getBuffer().remaining(); //round up division trick
						pooledBuffers = new PooledByteBuffer[noBufs];
						bufs = new ByteBuffer[noBufs];
					}
					pooledBuffers[i] = pooled;
					bufs[i] = pooled.getBuffer();
					Buffers.copy(pooled.getBuffer(), bytes);
					pooled.getBuffer().flip();
					++i;
				}
				final StageWriteBody writeBody = new StageWriteBody(pooledBuffers, bufs, this.resolver);
				writeBody.handleEvent(channel);
			}
			exchange.setResponseListener(new StageWaitForResponse(this.resolver, this.connection, exchange));
		}

		@Override
		public void failed(final IOException e) {
			GettusBase.this.release(this.connection);
			GettusBase.this.resolveException1(this.resolver, new GettusError(GettusBase.this, e));
		}
	}

	private class StageWriteBody implements ChannelListener<StreamSinkChannel> {

		private final PooledByteBuffer[] pooledBuffers;
		private final ByteBuffer[] bufs;
		private boolean first = true;
		private final Object resolver;

		public StageWriteBody(
				final PooledByteBuffer[] pooledBuffers, final ByteBuffer[] bufs, final Object resolver
		) {
			this.pooledBuffers = pooledBuffers;
			this.bufs = bufs;
			this.resolver = resolver;
		}

		private void clean(final StreamSinkChannel channel) {
			for (final PooledByteBuffer buffer : this.pooledBuffers) {
				buffer.close();
			}

			try {
				channel.shutdownWrites();
				channel.flush();
			} catch (final IOException e) {
				IoUtils.safeClose(channel);
				throw new GettusError(GettusBase.this, e);
			}
		}

		@Override
		public void handleEvent(final StreamSinkChannel channel) {
			if (!GettusBase.this.extendTimeout(channel.getIoThread(), this.resolver)) {
				this.clean(channel);
				return;
			}
			try {
				final long remaining = Buffers.remaining(this.bufs);
				long written = 0;
				do {
					final long res;
					try {
						res = channel.write(this.bufs);
					} catch (final IOException e) {
						throw new GettusError(GettusBase.this, e);
					}
					written += res;
					if (res == 0) {
						if (this.first) {
							channel.getWriteSetter().set(this);
							channel.resumeWrites();
							this.first = false;
						}
						return;
					}
				} while (written < remaining);
				channel.suspendWrites();
				this.clean(channel);
			} catch (final Exception e) {
				GettusBase.this.logger.warn(String.format("[%s] Error sending body", GettusBase.this.index), e);
				this.clean(channel);
			}
		}
	}

	private class StageWaitForResponse implements ClientCallback<ClientExchange>, ChannelListener<StreamSourceChannel> {

		private final Object resolver;
		private final ClientConnection connection;
		private final ClientExchange exchange;

		public StageWaitForResponse(
				final Object resolver, final ClientConnection connection, final ClientExchange exchange
		) {
			this.resolver = resolver;
			this.connection = connection;
			this.exchange = exchange;
		}

		@Override
		public void completed(final ClientExchange exchange) {
			exchange.getResponseChannel().getCloseSetter().set(null);
			GettusBase.this.resolve1(this.resolver,
					GettusBase.this.createHeaders(this.connection, exchange, exchange.getResponse())
			);
		}

		@Override
		public void failed(final IOException e) {
			this.exchange.getResponseChannel().getCloseSetter().set(null);
			GettusBase.this.release(this.connection);
			GettusBase.this.resolveException1(this.resolver, new GettusError(GettusBase.this, e));
		}

		@Override
		public void handleEvent(final StreamSourceChannel channel) {
		}
	}

	/**
	 * The response headers
	 *
	 * @param <K> For overriding - the specific Headers derivation
	 */
	public class Headers<K> {
		private final ClientConnection connection;
		private final ClientResponse response;
		private final ClientExchange exchange;

		Headers(
				final ClientConnection connection, final ClientExchange exchange, final ClientResponse response
		) {
			this.connection = connection;
			this.exchange = exchange;
			this.response = response;
		}

		/**
		 * @return the response code
		 */
		public int code() {
			return this.response.getResponseCode();
		}

		/**
		 * Get a header value by name
		 *
		 * @param name
		 * @return
		 */
		public String header(final String name) {
			return this.response.getResponseHeaders().getFirst(name);
		}

		/**
		 * Raise an error if a non 2XX/3XX code was received
		 *
		 * @return
		 */
		public K check() {
			if (this.code() < 200 || this.code() >= 400) {
				this.close();
				throw this.errorForCode();
			}
			return (K) this;
		}

		/**
		 * Close the connection
		 *
		 * @return
		 */
		public K close() {
			GettusBase.this.release(this.connection);
			return (K) this;
		}

		/**
		 * Raise an error for the response status code
		 *
		 * @return
		 */
		public ResponseCodeError errorForCode() {
			return new ResponseCodeError(this.code());
		}

		/**
		 * Raise an error if the response status code matches any listed code
		 *
		 * @param codes
		 * @return
		 */
		public K checkOnly(final int... codes) {
			if (Arrays.stream(codes).anyMatch(c -> this.code() == c)) {
				this.close();
				throw this.errorForCode();
			}
			return (K) this;
		}

		/**
		 * Wait for the body. resolve/resolveError will be called when the body is downloaded or an error occurs.
		 *
		 * @param resolver Passed to resolve/resolveError
		 */
		void body(final Object resolver) {
			final String contentLengthString =
					this.exchange.getResponse().getResponseHeaders().getFirst(io.undertow.util.Headers.CONTENT_LENGTH);
			final long contentLength;
			final ByteArrayOutputStream body;
			if (contentLengthString != null) {
				contentLength = Long.parseLong(contentLengthString);
				if (contentLength > Integer.MAX_VALUE) {
					throw new ResponseTooLargeError();
				}
				body = new ByteArrayOutputStream((int) contentLength);
			} else {
				contentLength = -1;
				body = new ByteArrayOutputStream();
			}
			if (GettusBase.this.limitSize > 0) {
				if (contentLength > GettusBase.this.limitSize) {
					throw new ResponseTooLargeError();
				}
			}
			GettusBase.this.initializeTimeout(resolver);
			try {
				final StreamSourceChannel channel = this.exchange.getResponseChannel();
				uncheck(() -> channel.setOption(READ_TIMEOUT, GettusBase.this.timeout));
				new StageReadBody(resolver, body).handleEvent(channel);
			} catch (final Exception e) {
				throw new GettusError(GettusBase.this, e);
			}
		}

		private class StageReadBody implements ChannelListener<StreamSourceChannel> {
			private Object resolver;
			private final ByteArrayOutputStream body;
			private boolean first = true;

			public StageReadBody(
					final Object resolver, final ByteArrayOutputStream body
			) {
				this.resolver = resolver;
				this.body = body;
			}

			private void finish(final StreamSourceChannel channel) {
				if (this.resolver == null)
					return;
				try {
					final XnioWorker worker = channel.getWorker();
					final Object resolver1 = this.resolver;
					worker.submit(() -> {
						try {
							GettusBase.this.resolve1(resolver1, new Body(Headers.this.response, this.body));
						} catch (final Throwable t) {
							GettusBase.this.logger.error("Unhandled exception, shutting down executor", t);
							worker.shutdown();
						}
					});
				} finally {
					Headers.this.close();
					this.resolver = null;
				}
			}

			@Override
			public void handleEvent(final StreamSourceChannel channel) {
				if (!GettusBase.this.extendTimeout(this.resolver))
					return;
				final PooledByteBuffer pooled = GettusBase.bufferPool.allocate();
				final ByteBuffer buffer = pooled.getBuffer();
				try {
					do {
						try {
							buffer.clear();
							final int res = channel.read(buffer);
							if (res == -1) {
								this.finish(channel);
								return;
							} else if (res == 0) {
								if (this.first) {
									channel.getReadSetter().set(this);
									channel.resumeReads();
									this.first = false;
								}
								return;
							} else {
								buffer.flip();
								while (buffer.hasRemaining()) {
									this.body.write(buffer.get());
								}
								if (GettusBase.this.limitSize > 0 && this.body.size() > GettusBase.this.limitSize) {
									GettusBase.this.resolveException1(this.resolver, new ResponseTooLargeError());
									this.resolver = null;
									return;
								}
							}
						} catch (final IOException e) {
							GettusBase.this.resolveException1(this.resolver, new GettusError(GettusBase.this, e));
							return;
						}
					} while (true);
				} catch (final Exception e) {
					GettusBase.this.logger.warn(String.format("[%s] Error reading body", GettusBase.this.index), e);
				} finally {
					pooled.close();
				}
			}
		}
	}

	private static final Pattern charsetPattern = Pattern.compile("charset=([^ ]+)");

	/**
	 * The response body
	 */
	public class Body {
		private final ClientResponse response;
		private final ByteArrayOutputStream body;

		public Body(final ClientResponse response, final ByteArrayOutputStream body) {
			this.response = response;
			this.body = body;
		}

		/**
		 * The response status code
		 *
		 * @return
		 */
		public int code() {
			return this.response.getResponseCode();
		}

		/**
		 * Get a header value by name
		 *
		 * @param name
		 * @return
		 */
		public String header(final String name) {
			return this.response.getResponseHeaders().getFirst(name);
		}

		/**
		 * Return the body as a stream. The body is completely in memory at this point.
		 *
		 * @return
		 */
		public InputStream stream() {
			return new ByteArrayInputStream(this.body.toByteArray());
		}

		/**
		 * @return the charset if specified in the headers, otherwise null.
		 */
		public Charset explicitCharset() {
			final String header = this.response.getResponseHeaders().getFirst(io.undertow.util.Headers.CONTENT_TYPE);
			if (header == null)
				return null;
			final Matcher matcher = GettusBase.charsetPattern.matcher(header);
			if (!matcher.find())
				return null;
			try {
				return Charset.forName(matcher.group(1));
			} catch (final UnsupportedCharsetException e) {
				return null;
			}
		}

		/**
		 * @return a best guess about the document charset
		 */
		public Charset charset() {
			final Charset charset = this.explicitCharset();
			if (charset != null)
				return charset;
			return Charset.forName("ISO-8859-1");
		}

		/**
		 * @return the body as a string
		 */
		public String text() {
			return new String(this.body.toByteArray(), this.charset());
		}

		/**
		 * Deserialize a json body to an object
		 *
		 * @param klass
		 * @param <T>
		 * @return
		 */
		public <T> T json(final Class<T> klass) {
			try {
				return GettusBase.jackson.readValue(this.stream(), klass);
			} catch (final IOException e) {
				throw new GettusError(GettusBase.this, e);
			}
		}

		/**
		 * Deserialize the json body as a generic json tree
		 *
		 * @return
		 */
		public JsonNode json() {
			try {
				return GettusBase.jackson.readTree(this.stream());
			} catch (final IOException e) {
				throw new GettusError(GettusBase.this, e);
			}
		}

		/**
		 * Raise an error if the status code isn't 2XX or 3XX. The error includes the response body text.
		 *
		 * @return
		 */
		public Body check() {
			if (this.code() < 200 || this.code() >= 400) {
				throw new ResponseCodeError(this.code(), new String(this.body.toByteArray(), StandardCharsets.UTF_8));
			}
			return this;
		}
	}

	private void release(final ClientConnection connection) {
		if (connection.isOpen())
			GettusBase.connectionPool
					.computeIfAbsent(this.connectionKey, k -> new FastConcurrentDirectDeque<>())
					.addLast(connection);
	}
}
