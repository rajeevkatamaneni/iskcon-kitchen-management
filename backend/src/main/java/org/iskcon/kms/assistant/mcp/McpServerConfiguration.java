package org.iskcon.kms.assistant.mcp;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

/**
 * The Kitchen Assistant's MCP server, {@code ikms}, at {@code /mcp} (E10-T6, plan §1 row B).
 *
 * <h2>SDK and transport</h2>
 *
 * <p>The official Java SDK, {@code io.modelcontextprotocol.sdk:mcp-core} 1.1.4, with its plain
 * servlet <strong>Streamable HTTP</strong> transport in its <strong>stateless</strong> form
 * ({@link HttpServletStatelessServerTransport}). Two choices in that, each for a reason:
 *
 * <ul>
 *   <li><strong>Not the SDK's Spring WebMVC adapter.</strong> From 1.0 that adapter left the SDK for
 *       Spring AI and is built against Spring 6.2; this application is on Boot 3.3 / Spring 6.1. The
 *       last SDK-owned copy (0.18.4) was also compiled against Spring 6.2.1. The servlet transport in
 *       {@code mcp-core} needs nothing from Spring at all.
 *   <li><strong>Stateless rather than session-based.</strong> A session-based server keeps each
 *       client's session in the memory of the instance that answered {@code initialize}, and Cloud
 *       Run spreads one client's requests across however many api instances are running. The second
 *       request would land on an instance that has never heard of the session. Stateless costs us
 *       nothing we use: these tools answer one request with one JSON response, and need no
 *       server-initiated messages. {@code claude -p} talks to it as {@code "type": "http"}.
 * </ul>
 *
 * <h2>Mounted through Spring MVC, not as a second servlet</h2>
 *
 * <p>The transport is an {@code HttpServlet}, and the obvious move is a
 * {@code ServletRegistrationBean}. It is not done, because a second servlet in the container makes
 * Spring Security refuse every plain-string {@code requestMatchers("/health")} in
 * {@code SecurityConfiguration}: it can no longer tell whether a pattern is a Spring MVC path. So
 * {@link McpEndpoint} is an ordinary controller that hands the request to the transport, and the
 * security filter chain sees {@code /mcp} exactly as it sees every other path.
 *
 * <h2>Security is not decided here</h2>
 *
 * <p>The agent-token filter (T5) owns {@code /mcp/**}: it verifies the token, sets the temple and
 * the caller's authorities, and refuses anything else with a 401. This class only carries what that
 * filter established onto the thread the tool runs on — see {@link AgentCallScope}, and its comment
 * on why that hop exists.
 */
@Configuration
public class McpServerConfiguration {

	private static final Logger log = LoggerFactory.getLogger(McpServerConfiguration.class);

	static final String SERVER_NAME = "ikms";
	static final String SERVER_VERSION = "0.1.0";
	static final String ENDPOINT = "/mcp";

	// ---- Tool documentation ----------------------------------------------
	//
	// These strings are the whole of what the model knows about each tool: when to call it, what
	// comes back, and in what units. They are written as API documentation for that reader, and a
	// change to one is a change to the assistant's behaviour — the eval set (S5) is what tells you
	// whether it was a good one.

	static final String GET_INVENTORY = "get_inventory";

	static final String GET_INVENTORY_DESCRIPTION = """
			Current stock of the temple kitchen's consumables (rice, dal, ghee, vegetables, spices...), \
			from the temple's own stock ledger.

			Use it when the person asks how much of something there is, whether there is enough of \
			something, how long something will last, or what is on order. Pass item_name to look up \
			one ingredient; leave it out to list everything. For "what is running low / what should we \
			buy", call list_low_stock instead.

			item_name matches any part of the name, ignoring case, in English or Kannada: "dal" finds \
			"Toor Dal" and "Moong Dal". If nothing matches, items is empty; try a shorter or more \
			general word, or an English name, before saying the temple does not stock it.

			Returns JSON: {items: [...], returned, total_matching, truncated}. At most 50 items are \
			returned, sorted by name; truncated is true when more matched, and total_matching says how \
			many. Each item:
			- name: the English name as the temple files it. Some are written catalogue-style, \
			"Water, hot", meaning hot water.
			- name_kn: the Kannada name from the temple's glossary. Absent when the temple has not \
			recorded one; do not invent one in that case, translate if you must and say it is yours.
			- category: e.g. "Pulses", "Dairy".
			- on_hand: what is physically on the shelf now, in unit. Never negative.
			- available: on_hand minus what saved meal plans have already claimed, in unit. Can be \
			negative, meaning the plans need more than the store holds. This is the figure to use for \
			"do we have enough".
			- unit: one of "Kg", "gm", "L", "ml", "pieces". Every quantity on the item is in this unit.
			- reorder_level: the level the temple set for buying more, in unit. Absent if never set.
			- low: true when available is below reorder_level, or below zero. The same judgement as \
			the Low badge on the stock screen. Always false for things the temple never buys (water).
			- on_order: sent to a vendor and not yet delivered, in unit. Absent when nothing is on \
			order. Draft orders are not counted.
			- cover: how long the stock lasts at the rate the temple has been using it over the last \
			90 days. {known: false} means there is not enough history to estimate; say so rather than \
			guessing. Otherwise {known: true, days, more_than, runs_out_on, use_per_day}: days is \
			rounded down; more_than true means "more than days days" (the estimate stops at 90); \
			runs_out_on is a date (YYYY-MM-DD), absent when more_than is true; use_per_day is in unit. \
			It is an estimate: festivals and deliveries are not in it.""";

	static final String GET_INVENTORY_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "item_name": {
			      "type": "string",
			      "description": "Part of an ingredient name, in English or Kannada, e.g. \\"rice\\" or \\"ತುಪ್ಪ\\". Leave out to list every item."
			    }
			  },
			  "additionalProperties": false
			}""";

	static final String LIST_LOW_STOCK = "list_low_stock";

	static final String LIST_LOW_STOCK_DESCRIPTION = """
			The temple kitchen's consumables that are running low: everything whose available stock \
			is below the reorder level the temple set, or below zero because saved meal plans need \
			more than the store holds. It is the same list the dashboard and the daily low-stock \
			message show.

			Use it when the person asks what is running low, what is short, what needs buying, or \
			what to put on a shopping list. Takes no arguments. To check one ingredient, call \
			get_inventory with item_name instead.

			Returns JSON in exactly the shape get_inventory returns ({items, returned, total_matching, \
			truncated}, at most 50 items, sorted by name, every item with low: true), with the same \
			fields and units: name, name_kn (absent when the glossary has none), category, on_hand, \
			available, unit ("Kg", "gm", "L", "ml" or "pieces"), reorder_level, on_order (absent when \
			nothing is on order: worth saying, because something low that is already on order may \
			not need buying again) and cover. An empty items list means nothing is low.""";

	static final String LIST_LOW_STOCK_SCHEMA = """
			{
			  "type": "object",
			  "properties": {},
			  "additionalProperties": false
			}""";

	// ---- What the model is told when a call cannot be answered -----------
	//
	// Plain words, because the model will relay them to a cook. Nothing technical: the detail goes
	// to the log under an incident id.

	static final String NO_TEMPLE_MESSAGE =
			"I could not tell which temple this request is for, so I cannot read its stock. "
					+ "Ask the person to sign in again and retry.";
	static final String NOT_ALLOWED_MESSAGE =
			"This person's role does not include the stock screen, so I cannot read stock for them. "
					+ "A Temple Admin can say who should see stock.";
	static final String FAILED_MESSAGE =
			"The stock could not be read just now. Try again in a minute; if it keeps failing, "
					+ "tell the person to quote reference %s to their Temple Admin.";

	// ---- Beans -----------------------------------------------------------

	@Bean
	HttpServletStatelessServerTransport mcpTransport() {
		return HttpServletStatelessServerTransport.builder()
				.jsonMapper(protocolMapper())
				.messageEndpoint(ENDPOINT)
				.contextExtractor(AgentCallScope::capture)
				.build();
	}

	@Bean(destroyMethod = "close")
	McpStatelessSyncServer mcpServer(
			HttpServletStatelessServerTransport mcpTransport, InventoryTools inventoryTools,
			ObjectMapper objectMapper) {
		return McpServer.sync(mcpTransport)
				.serverInfo(SERVER_NAME, SERVER_VERSION)
				.jsonMapper(protocolMapper())
				.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
				.tools(toolSpecifications(inventoryTools, objectMapper))
				.build();
	}

	/**
	 * Every tool the server offers. A method of its own so the handler tests call exactly what the
	 * server registers, rather than a copy of it.
	 */
	static List<SyncToolSpecification> toolSpecifications(InventoryTools inventoryTools, ObjectMapper objectMapper) {
		// The application's own mapper, copied so the change below stays here: the JSON the model
		// reads gets ISO dates from it (Boot turns timestamps off) and plain decimals from this.
		ObjectMapper payloadMapper = objectMapper.copy().enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
		McpJsonMapper protocol = protocolMapper();
		return List.of(
				tool(protocol, GET_INVENTORY, "Stock on hand", GET_INVENTORY_DESCRIPTION, GET_INVENTORY_SCHEMA,
						payloadMapper,
						request -> () -> inventoryTools.getInventory(stringArgument(request, "item_name"))),
				tool(protocol, LIST_LOW_STOCK, "Low stock", LIST_LOW_STOCK_DESCRIPTION, LIST_LOW_STOCK_SCHEMA,
						payloadMapper,
						request -> inventoryTools::listLowStock));
	}

	/**
	 * The SDK's own protocol mapper: a plain Jackson {@code ObjectMapper}, exactly what the SDK's
	 * default supplier builds. Not the application's mapper, whose settings are tuned for our REST
	 * responses and are no business of the MCP wire format.
	 */
	private static McpJsonMapper protocolMapper() {
		return new JacksonMcpJsonMapper(new ObjectMapper());
	}

	/**
	 * One tool: its documentation, and a handler that runs the read inside {@link AgentCallScope} and
	 * turns every outcome into something the model can relay.
	 *
	 * @param read given the request, the read to perform — a supplier, so that the read itself runs
	 *             inside the scope and not while the arguments are being picked out
	 */
	static SyncToolSpecification tool(
			McpJsonMapper protocol, String name, String title, String description, String inputSchema,
			ObjectMapper payloadMapper, Function<CallToolRequest, Supplier<Object>> read) {
		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name(name)
				.title(title)
				.description(description)
				.inputSchema(protocol, inputSchema)
				.build();
		return SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((McpTransportContext context, CallToolRequest request) ->
						call(name, context, read.apply(request), payloadMapper))
				.build();
	}

	static CallToolResult call(
			String toolName, McpTransportContext context, Supplier<Object> read, ObjectMapper payloadMapper) {
		try {
			Object payload = AgentCallScope.run(context, read);
			return CallToolResult.builder()
					.addTextContent(payloadMapper.writeValueAsString(payload))
					.isError(false)
					.build();
		} catch (AgentCallScope.NoTempleException e) {
			// T5's filter refuses a request with no temple before it gets here, so this is a defect
			// somewhere between the two, and worth a warning rather than a debug line.
			log.warn("MCP tool {} called with no tenant in context", toolName);
			return refusal(NO_TEMPLE_MESSAGE);
		} catch (AccessDeniedException | AuthenticationException e) {
			log.info("MCP tool {} refused: caller lacks the permission", toolName);
			return refusal(NOT_ALLOWED_MESSAGE);
		} catch (JsonProcessingException | RuntimeException e) {
			String incidentId = UUID.randomUUID().toString().substring(0, 8);
			log.error("MCP tool {} failed incident={}", toolName, incidentId, e);
			return refusal(FAILED_MESSAGE.formatted(incidentId));
		}
	}

	private static CallToolResult refusal(String message) {
		return CallToolResult.builder().addTextContent(message).isError(true).build();
	}

	/**
	 * A string argument, or null when absent, blank or not a string. Lenient on purpose: a model
	 * that sends {@code "item_name": null} meant "everything", and refusing it would cost a turn.
	 */
	static String stringArgument(CallToolRequest request, String key) {
		if (request.arguments() == null) {
			return null;
		}
		Object value = request.arguments().get(key);
		return value instanceof String text && !text.isBlank() ? text : null;
	}
}
