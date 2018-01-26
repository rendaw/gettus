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
	private boolean dontCheckCerts = false;
	private final String connectionKey;

	public static URI formatURI(final String pattern, final Object... args) {
		return uncheck(() -> new URI(String.format(pattern, args)));
	}

	protected abstract void resolve(Object resolver, Object value);

	protected abstract void resolveException(Object resolver, RuntimeException exception);

	protected abstract <K> Headers<K> createHeaders(
			final ClientConnection connection, final ClientExchange exchange, final ClientResponse response
	);

	public static class ResponseTooLargeError extends RuntimeException {

	}

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

	public GettusBase(final URI uri) {
		this.index = count.getAndIncrement();
		this.uri = uri;
		this.connectionKey = String.format("%s:%s", uri.getHost(), uri.getPort());
		final StringBuilder path = new StringBuilder();
		path.append(uri.getRawPath());
		if (uri.getRawQuery() != null) {
			path.append("?");
			path.append(uri.getRawQuery());
		}
		request.setPath(path.toString());
	}

	public I method(final HttpString method) {
		request.setMethod(method);
		return (I) this;
	}

	public I body(final byte[] body) {
		this.body = body;
		return (I) this;
	}

	public I bodyJson(final ObjectMapper jackson, final Object o) {
		try {
			return body(jackson.writeValueAsBytes(o));
		} catch (final JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	public I bodyJson(final Object o) {
		return bodyJson(jackson, o);
	}

	public I bodyJson(final Common.Consumer2<JsonGenerator> write) {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		try (final JsonGenerator bodyWriter = new JsonFactory().createGenerator(body)) {
			uncheck(() -> write.accept(bodyWriter));
		} catch (final IOException e) {
			throw uncheck(e);
		}
		return body(body.toByteArray());
	}

	public I headers(final Map<HttpString, String> headers) {
		headers.forEach((k, v) -> request.getRequestHeaders().add(k, v));
		return (I) this;
	}

	public I header(final HttpString name, final String value) {
		request.getRequestHeaders().add(name, value);
		return (I) this;
	}

	public I basicAuth(final String user, final String password) {
		return header(io.undertow.util.Headers.AUTHORIZATION, String.format(
				"Basic %s",
				Base64
						.getEncoder()
						.encodeToString(String.format("%s:%s", user, password).getBytes(StandardCharsets.US_ASCII))
		));
	}

	public I timeout(final int seconds) {
		this.timeout = seconds;
		return (I) this;
	}

	public I limitSize(final int size) {
		this.limitSize = size;
		return (I) this;
	}

	public I dontCheckCerts() {
		this.dontCheckCerts = true;
		return (I) this;
	}

	public void send(final XnioWorker worker, final Object resolver) {
		request
				.getRequestHeaders()
				.add(io.undertow.util.Headers.CONTENT_LENGTH, Objects.toString(body == null ? 0 : body.length));
		final SSLContext sslContext;
		if (dontCheckCerts) {
			sslContext = uncheck(() -> SSLContext.getInstance("SSL"));
			uncheck(() -> sslContext.init(null, new TrustManager[] {
					new X509TrustManager() {
						public X509Certificate[] getAcceptedIssuers() {
							return new X509Certificate[0];
						}

						public void checkClientTrusted(
								final X509Certificate[] certs, final String authType
						) {
						}

						public void checkServerTrusted(
								final X509Certificate[] certs, final String authType
						) {
						}
					}
			}, null));
		} else {
			sslContext = uncheck(() -> SSLContext.getDefault());
		}
		final XnioSsl ssl =
				new UndertowXnioSsl(worker.getXnio(), OptionMap.create(Options.USE_DIRECT_BUFFERS, true), sslContext);
		try {
			do {
				final ConcurrentDirectDeque<ClientConnection> queue = connectionPool.get(connectionKey);
				if (queue == null)
					break;
				final ClientConnection connection = queue.pollLast();
				if (queue.isEmpty())
					connectionPool.remove(queue); // Race condition, but missed connections should clean up eventually
				if (connection == null || !connection.isOpen())
					break;
				connection.sendRequest(request, new StageSendRequest(resolver, connection));
				return;
			} while (false);
			UndertowClient.getInstance().
					connect(
							new StageGetConnection(resolver),
							uri,
							worker,
							ssl,
							bufferPool,
							OptionMap.builder().set(UndertowOptions.IDLE_TIMEOUT, timeout).getMap()
					);
		} catch (final Exception e) {
			throw new GettusError(this, e);
		}
	}

	private class StageGetConnection implements ClientCallback<ClientConnection> {

		private final Object resolver;

		public StageGetConnection(final Object resolver) {
			this.resolver = resolver;
		}

		@Override
		public void completed(final ClientConnection result) {
			result.sendRequest(request, new StageSendRequest(resolver, result));
		}

		@Override
		public void failed(final IOException e) {
			resolveException(resolver, new GettusError(GettusBase.this, e));
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
			if (body != null) {
				final ByteBuffer bytes = ByteBuffer.wrap(body);
				final StreamSinkChannel channel = exchange.getRequestChannel();
				// If channel null means accidental double get
				int i = 0;
				ByteBuffer[] bufs = null;
				PooledByteBuffer[] pooledBuffers = null;
				while (bytes.hasRemaining()) {
					final PooledByteBuffer pooled = bufferPool.allocate();
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
				final StageWriteBody writeBody = new StageWriteBody(pooledBuffers, bufs);
				writeBody.handleEvent(channel);
			}
			exchange.setResponseListener(new StageWaitForResponse(resolver, connection));
		}

		@Override
		public void failed(final IOException e) {
			release(connection);
			resolveException(resolver, new GettusError(GettusBase.this, e));
		}
	}

	private class StageWriteBody implements ChannelListener<StreamSinkChannel> {

		private final PooledByteBuffer[] pooledBuffers;
		private final ByteBuffer[] bufs;
		private boolean first = true;

		public StageWriteBody(final PooledByteBuffer[] pooledBuffers, final ByteBuffer[] bufs) {
			this.pooledBuffers = pooledBuffers;
			this.bufs = bufs;
		}

		private void clean(final StreamSinkChannel channel) {
			for (final PooledByteBuffer buffer : pooledBuffers) {
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
			try {
				final long remaining = Buffers.remaining(bufs);
				long written = 0;
				do {
					final long res;
					try {
						res = channel.write(bufs);
					} catch (final IOException e) {
						throw new GettusError(GettusBase.this, e);
					}
					written += res;
					if (res == 0) {
						if (first) {
							channel.getWriteSetter().set(this);
							channel.resumeWrites();
							first = false;
						}
						return;
					}
				} while (written < remaining);
				channel.suspendWrites();
				clean(channel);
			} catch (final Exception e) {
				logger.debug(String.format("[%s] Error sending body", index), e);
				clean(channel);
			}
		}
	}

	private class StageWaitForResponse implements ClientCallback<ClientExchange> {

		private final Object resolver;
		private final ClientConnection connection;

		public StageWaitForResponse(final Object resolver, final ClientConnection connection) {
			this.resolver = resolver;
			this.connection = connection;
		}

		@Override
		public void completed(final ClientExchange exchange) {
			resolve(resolver, createHeaders(connection, exchange, exchange.getResponse()));
		}

		@Override
		public void failed(final IOException e) {
			release(connection);
			resolveException(resolver, new GettusError(GettusBase.this, e));
		}
	}

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

		public int code() {
			return response.getResponseCode();
		}

		public String header(final String name) {
			return response.getResponseHeaders().getFirst(name);
		}

		public K check() {
			if (code() < 200 || code() >= 400) {
				close();
				throw errorForCode();
			}
			return (K) this;
		}

		public K close() {
			release(connection);
			return (K) this;
		}

		public ResponseCodeError errorForCode() {
			return new ResponseCodeError(code());
		}

		public K checkOnly(final int... codes) {
			if (Arrays.stream(codes).anyMatch(c -> code() == c)) {
				close();
				throw errorForCode();
			}
			return (K) this;
		}

		void body(final Object resolver) {
			final String contentLengthString =
					exchange.getResponse().getResponseHeaders().getFirst(io.undertow.util.Headers.CONTENT_LENGTH);
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
			if (limitSize > 0) {
				if (contentLength > limitSize) {
					throw new ResponseTooLargeError();
				}
			}
			try {
				final StreamSourceChannel channel = exchange.getResponseChannel();
				uncheck(() -> channel.setOption(READ_TIMEOUT, timeout));
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
				if (resolver == null)
					return;
				try {
					final XnioWorker worker = channel.getWorker();
					final Object resolver1 = resolver;
					worker.submit(() -> {
						try {
							resolve(resolver1, new Body(response, body));
						} catch (final Throwable t) {
							logger.error("Unhandled exception, shutting down executor", t);
							worker.shutdown();
						}
					});
				} finally {
					close();
					resolver = null;
				}
			}

			@Override
			public void handleEvent(final StreamSourceChannel channel) {
				final PooledByteBuffer pooled = bufferPool.allocate();
				final ByteBuffer buffer = pooled.getBuffer();
				try {
					do {
						try {
							buffer.clear();
							final int res = channel.read(buffer);
							if (res == -1) {
								finish(channel);
								return;
							} else if (res == 0) {
								if (first) {
									channel.getReadSetter().set(this);
									channel.resumeReads();
									first = false;
								}
								return;
							} else {
								buffer.flip();
								while (buffer.hasRemaining()) {
									body.write(buffer.get());
								}
								if (limitSize > 0 && body.size() > limitSize) {
									resolveException(resolver, new ResponseTooLargeError());
									resolver = null;
									return;
								}
							}
						} catch (final IOException e) {
							resolveException(resolver, new GettusError(GettusBase.this, e));
							return;
						}
					} while (true);
				} catch (final Exception e) {
					logger.debug(String.format("[%s] Error reading body", index), e);
				} finally {
					pooled.close();
				}
			}
		}
	}

	private static final Pattern charsetPattern = Pattern.compile("charset=([^ ]+)");

	public class Body {
		private final ClientResponse response;
		private final ByteArrayOutputStream body;

		public Body(final ClientResponse response, final ByteArrayOutputStream body) {
			this.response = response;
			this.body = body;
		}

		public int code() {
			return response.getResponseCode();
		}

		public String header(final String name) {
			return response.getResponseHeaders().getFirst(name);
		}

		public InputStream stream() {
			return new ByteArrayInputStream(body.toByteArray());
		}

		public Charset explicitCharset() {
			final String header = response.getResponseHeaders().getFirst(io.undertow.util.Headers.CONTENT_TYPE);
			if (header == null)
				return null;
			final Matcher matcher = charsetPattern.matcher(header);
			if (!matcher.find())
				return null;
			try {
				return Charset.forName(matcher.group(1));
			} catch (final UnsupportedCharsetException e) {
				return null;
			}
		}

		public Charset charset() {
			final Charset charset = explicitCharset();
			if (charset != null)
				return charset;
			return Charset.forName("ISO-8859-1");
		}

		public String text() {
			return new String(body.toByteArray(), charset());
		}

		public <T> T json(final Class<T> klass) {
			try {
				return jackson.readValue(stream(), klass);
			} catch (final IOException e) {
				throw new GettusError(GettusBase.this, e);
			}
		}

		public JsonNode json() {
			try {
				return jackson.readTree(stream());
			} catch (final IOException e) {
				throw new GettusError(GettusBase.this, e);
			}
		}

		public Body check() {
			if (code() < 200 || code() >= 400) {
				throw new ResponseCodeError(code(), new String(body.toByteArray(), StandardCharsets.UTF_8));
			}
			return this;
		}
	}

	private void release(final ClientConnection connection) {
		if (connection.isOpen())
			connectionPool.computeIfAbsent(connectionKey, k -> new FastConcurrentDirectDeque<>()).addLast(connection);
	}
}
