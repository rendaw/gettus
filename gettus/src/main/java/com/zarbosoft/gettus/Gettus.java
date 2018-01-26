package com.zarbosoft.gettus;

import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientResponse;
import org.xnio.XnioWorker;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public class Gettus extends GettusBase<Gettus> {
	public Gettus(final URI uri) {
		super(uri);
	}

	@Override
	protected void resolve(final Object resolver, final Object value) {
		((CompletableFuture) resolver).complete(value);
	}

	@Override
	protected void resolveException(final Object resolver, final RuntimeException exception) {
		((CompletableFuture) resolver).completeExceptionally(exception);
	}

	public class Headers extends GettusBase<Gettus>.Headers<Headers> {
		Headers(
				final ClientConnection connection, final ClientExchange exchange, final ClientResponse response
		) {
			super(connection, exchange, response);
		}

		public CompletableFuture<Body> body() {
			final CompletableFuture<Body> out = new CompletableFuture<>();
			body(out);
			return out;
		}
	}

	@Override
	protected GettusBase<Gettus>.Headers<Headers> createHeaders(
			final ClientConnection connection, final ClientExchange exchange, final ClientResponse response
	) {
		return new Headers(connection, exchange, response);
	}

	public CompletableFuture<Headers> send(final XnioWorker worker) {
		final CompletableFuture<Headers> out = new CompletableFuture<>();
		send(worker, out);
		return out;
	}
}
