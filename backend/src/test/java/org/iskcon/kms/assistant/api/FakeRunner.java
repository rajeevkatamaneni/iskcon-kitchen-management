package org.iskcon.kms.assistant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A stand-in for the runner's {@code POST /run} (E10-T9's contract): answers each call with a
 * scripted list of NDJSON lines, a line at a time with a pause between, and remembers every request
 * it was sent. A real HTTP server on a real port, so the api's client, its streaming and its
 * cancellation are exercised exactly as against the real runner.
 */
final class FakeRunner implements AutoCloseable {

	/** What the next calls are answered with. */
	record Script(int status, List<String> lines, long pauseMs) {
	}

	private final HttpServer server;
	private final ObjectMapper json = new ObjectMapper();
	final List<JsonNode> requests = new CopyOnWriteArrayList<>();
	volatile Script script = new Script(200, List.of(), 0);
	/** Set when the api went away while lines were still being written. */
	volatile boolean callerLeft;

	FakeRunner() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/run", this::run);
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	void reset(Script next) {
		requests.clear();
		callerLeft = false;
		script = next;
	}

	private void run(HttpExchange exchange) throws IOException {
		requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
		Script now = script;
		if (now.status() != 200) {
			byte[] body = "{\"type\":\"runner_error\",\"code\":\"busy\",\"message\":\"busy\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(now.status(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
			return;
		}
		exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
		exchange.sendResponseHeaders(200, 0);
		OutputStream out = exchange.getResponseBody();
		try {
			for (String line : now.lines()) {
				if (now.pauseMs() > 0) {
					Thread.sleep(now.pauseMs());
				}
				out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		} catch (IOException e) {
			callerLeft = true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			try {
				exchange.close();
			} catch (RuntimeException ignored) {
				// The caller has gone; nothing to close cleanly.
			}
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
