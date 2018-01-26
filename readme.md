# Gettus

Gettus is an asynchronous HTTP client for use with XNIO built with Undertow routines.  It never starts or blocks
threads.  There are currently two client flavors:

1. Gettus - an interface based on `CompletableFuture`s
2. Cogettus (separate artifact) - an interface for use with coroutines

This project is fairly incomplete - there's no built in session management, proxy support, or redirects
but it is entirely usable.  I've interacted with AWS via their HTTP API and a number of websites using Gettus and
encountered no issues so far.  If you'd like to try your hand at implementing these features though PRs welcome.

## Example (Cogettus)

```
final SensorDataArray response = new Cogettus(formatURI("http://%s/recent", sensorHost))
		.bodyJson(w -> {
			w.writeStartObject();
			w.writeNumberField("count", 100);
			w.writeEndObject();
		})
		.send(worker)
		.body()
		.check()
		.json(SensorDataArray.class);
```

## Maven

Normal Gettus:
```
<dependency>
    <groupId>com.zarbosoft</groupId>
    <artifactId>gettus</artifactId>
    <version>0.0.1</version>
</dependency>
```

Cogettus
```
<dependency>
    <groupId>com.zarbosoft</groupId>
    <artifactId>cogettus</artifactId>
    <version>0.0.1</version>
</dependency>
```