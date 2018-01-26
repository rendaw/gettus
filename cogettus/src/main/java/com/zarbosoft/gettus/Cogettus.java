package com.zarbosoft.gettus;

import com.zarbosoft.coroutines.Coroutine;
import com.zarbosoft.coroutinescore.SuspendExecution;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientResponse;
import org.xnio.XnioWorker;

import java.net.URI;

public class Cogettus extends GettusBase<Cogettus> {
	public Cogettus(final URI uri) {
		super(uri);
	}

	@Override
	protected void resolve(final Object resolver, final Object value) {
		((Coroutine) resolver).process(value);
	}

	@Override
	protected void resolveException(final Object resolver, final RuntimeException exception) {
		((Coroutine) resolver).processThrow(exception);
	}

	public class Headers extends GettusBase<Cogettus>.Headers<Headers> {
		Headers(
				final ClientConnection connection, final ClientExchange exchange, final ClientResponse response
		) {
			super(connection, exchange, response);
		}

		public Body body() throws SuspendExecution {
			final Coroutine coroutine = Coroutine.getActiveCoroutine();
			return Coroutine.yieldThen(() -> {
				body(coroutine);
			});
		}
	}

	@Override
	protected GettusBase<Cogettus>.Headers<Headers> createHeaders(
			final ClientConnection connection, final ClientExchange exchange, final ClientResponse response
	) {
		return new Headers(connection, exchange, response);
	}

	public Headers send(final XnioWorker worker) throws SuspendExecution {
		final Coroutine coroutine = Coroutine.getActiveCoroutine();
		return Coroutine.yieldThen(() -> {
			send(worker, coroutine);
		});
	}
}
