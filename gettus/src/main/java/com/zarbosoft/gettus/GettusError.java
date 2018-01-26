package com.zarbosoft.gettus;

import java.nio.charset.StandardCharsets;

/**
 * A generic error class for Gettus-related errors.
 */
public class GettusError extends RuntimeException {
	private final GettusBase gettus;

	public GettusError(final GettusBase gettus, final String message, final Throwable e) {
		super(message, e);
		this.gettus = gettus;
	}

	public GettusError(final GettusBase gettus, final Throwable e) {
		super(e);
		this.gettus = gettus;
	}

	@Override
	public String getMessage() {
		final String bodyString = gettus.body == null ? "" : new String(gettus.body, StandardCharsets.UTF_8);
		return String.format(
				"(%s) %s\n%s %s %s",
				gettus.index,
				super.getMessage(),
				gettus.request.getMethod(),
				gettus.uri,
				bodyString.substring(0, Math.min(120, bodyString.length()))
		);
	}
}
