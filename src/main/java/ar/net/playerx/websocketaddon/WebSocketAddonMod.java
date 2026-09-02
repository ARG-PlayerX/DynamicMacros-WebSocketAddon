package ar.net.playerx.websocketaddon;

import ar.net.playerx.dynamicmacros.api.DynamicMacrosApi;
import ar.net.playerx.dynamicmacros.api.EventDefinition;
import ar.net.playerx.dynamicmacros.api.DynamicCallable;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone third-party Addon Mod for DynamicMacros.
 * Adds full client-side WebSocket support to DynamicMacros scripting API.
 */
@Mod("websocketaddon")
public class WebSocketAddonMod {

    private static final Map<String, WebSocket> activeConnections = new ConcurrentHashMap<>();
    private static final Map<String, String> connectionStatuses = new ConcurrentHashMap<>();
    private static final Map<String, StringBuilder> messageBuffers = new ConcurrentHashMap<>();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public WebSocketAddonMod(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            System.out.println("[WebSocketAddon] Registering WebSocket events & functions with DynamicMacros API...");

            registerEvents();
            registerFunctions();
        });
    }

    private static void registerEvents() {
        DynamicMacrosApi.registerEvent(new EventDefinition("onWebSocketConnect", "Fires when a WebSocket connection is successfully established.", "WebSocket Addon")
                .addField("connectionId", "string", "ID of the WebSocket connection")
                .addField("url", "string", "Target WebSocket URL"));

        DynamicMacrosApi.registerEvent(new EventDefinition("onWebSocketMessage", "Fires when a text/JSON message is received over a WebSocket.", "WebSocket Addon")
                .addField("connectionId", "string", "ID of the WebSocket connection")
                .addField("message", "string", "Received message payload"));

        DynamicMacrosApi.registerEvent(new EventDefinition("onWebSocketClose", "Fires when a WebSocket connection is closed.", "WebSocket Addon")
                .addField("connectionId", "string", "ID of the WebSocket connection")
                .addField("code", "number", "Close status code (e.g. 1000)")
                .addField("reason", "string", "Close reason provided by server"));

        DynamicMacrosApi.registerEvent(new EventDefinition("onWebSocketError", "Fires when a WebSocket error or network failure occurs.", "WebSocket Addon")
                .addField("connectionId", "string", "ID of the WebSocket connection")
                .addField("error", "string", "Description of the error"));
    }

    private static void registerFunctions() {
        // websocketConnect(url, [connectionId])
        DynamicCallable connectFunc = new DynamicCallable() {
            @Override
            public int arity() { return -1; }

            @Override
            public Object call(Object context, List<Object> arguments) {
                if (arguments.isEmpty()) {
                    throw new RuntimeException("websocketConnect requires at least a URL argument.");
                }
                String url = String.valueOf(arguments.get(0));
                String connId = arguments.size() > 1 ? String.valueOf(arguments.get(1)) : "default";
                return connect(url, connId);
            }
        };

        // websocketSend(message, [connectionId])
        DynamicCallable sendFunc = new DynamicCallable() {
            @Override
            public int arity() { return -1; }

            @Override
            public Object call(Object context, List<Object> arguments) {
                if (arguments.isEmpty()) {
                    throw new RuntimeException("websocketSend requires at least a message argument.");
                }
                String msg = String.valueOf(arguments.get(0));
                String connId = arguments.size() > 1 ? String.valueOf(arguments.get(1)) : "default";
                return send(connId, msg);
            }
        };

        // websocketDisconnect([connectionId])
        DynamicCallable disconnectFunc = new DynamicCallable() {
            @Override
            public int arity() { return -1; }

            @Override
            public Object call(Object context, List<Object> arguments) {
                String connId = !arguments.isEmpty() ? String.valueOf(arguments.get(0)) : "default";
                return disconnect(connId);
            }
        };

        // websocketStatus([connectionId])
        DynamicCallable statusFunc = new DynamicCallable() {
            @Override
            public int arity() { return -1; }

            @Override
            public Object call(Object context, List<Object> arguments) {
                String connId = !arguments.isEmpty() ? String.valueOf(arguments.get(0)) : "default";
                return connectionStatuses.getOrDefault(connId, "DISCONNECTED");
            }
        };

        DynamicMacrosApi.registerFunction("websocketConnect", connectFunc);
        DynamicMacrosApi.registerFunction("webSocketConnect", connectFunc);
        DynamicMacrosApi.registerFunctionDoc("websocketConnect", "websocketConnect(url, [connectionId])", "WebSocket",
                "Connects to a remote or local WebSocket server (ws:// or wss://).",
                "url (string), connectionId (string, optional, default 'default')",
                "boolean - true if connection attempt started.",
                "websocketConnect(\"wss://echo.websocket.org\", \"myConn\");", "WebSocket Addon");

        DynamicMacrosApi.registerFunction("websocketSend", sendFunc);
        DynamicMacrosApi.registerFunction("webSocketSend", sendFunc);
        DynamicMacrosApi.registerFunctionDoc("websocketSend", "websocketSend(message, [connectionId])", "WebSocket",
                "Sends a text message or JSON payload over an open WebSocket.",
                "message (string), connectionId (string, optional, default 'default')",
                "boolean - true if message was queued/sent.",
                "websocketSend(\"Hello World\", \"myConn\");", "WebSocket Addon");

        DynamicMacrosApi.registerFunction("websocketDisconnect", disconnectFunc);
        DynamicMacrosApi.registerFunction("webSocketDisconnect", disconnectFunc);
        DynamicMacrosApi.registerFunctionDoc("websocketDisconnect", "websocketDisconnect([connectionId])", "WebSocket",
                "Closes the specified active WebSocket connection.",
                "connectionId (string, optional, default 'default')",
                "boolean - true if disconnect initiated.",
                "websocketDisconnect(\"myConn\");", "WebSocket Addon");

        DynamicMacrosApi.registerFunction("websocketStatus", statusFunc);
        DynamicMacrosApi.registerFunction("webSocketStatus", statusFunc);
        DynamicMacrosApi.registerFunctionDoc("websocketStatus", "websocketStatus([connectionId])", "WebSocket",
                "Returns the current state of a WebSocket connection ('CONNECTED', 'CONNECTING', 'DISCONNECTED', 'CLOSED', 'ERROR').",
                "connectionId (string, optional, default 'default')",
                "string - Connection status.",
                "if (websocketStatus(\"myConn\") == \"CONNECTED\") { ... }", "WebSocket Addon");
    }

    private static boolean connect(String url, String connId) {
        try {
            WebSocket existing = activeConnections.get(connId);
            if (existing != null && !existing.isOutputClosed()) {
                existing.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting");
            }

            connectionStatuses.put(connId, "CONNECTING");

            httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            activeConnections.put(connId, webSocket);
                            connectionStatuses.put(connId, "CONNECTED");
                            webSocket.request(1);

                            Map<String, Object> data = new HashMap<>();
                            data.put("connectionId", connId);
                            data.put("url", url);
                            DynamicMacrosApi.triggerEvent("onWebSocketConnect", data, false);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            StringBuilder sb = messageBuffers.computeIfAbsent(connId, k -> new StringBuilder());
                            sb.append(data);

                            if (last) {
                                String message = sb.toString();
                                messageBuffers.remove(connId);

                                Map<String, Object> eventData = new HashMap<>();
                                eventData.put("connectionId", connId);
                                eventData.put("message", message);
                                DynamicMacrosApi.triggerEvent("onWebSocketMessage", eventData, false);
                            }

                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            activeConnections.remove(connId);
                            connectionStatuses.put(connId, "CLOSED");

                            Map<String, Object> data = new HashMap<>();
                            data.put("connectionId", connId);
                            data.put("code", statusCode);
                            data.put("reason", reason != null ? reason : "");
                            DynamicMacrosApi.triggerEvent("onWebSocketClose", data, false);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            activeConnections.remove(connId);
                            connectionStatuses.put(connId, "ERROR");

                            Map<String, Object> data = new HashMap<>();
                            data.put("connectionId", connId);
                            data.put("error", error != null && error.getMessage() != null ? error.getMessage() : "WebSocket error");
                            DynamicMacrosApi.triggerEvent("onWebSocketError", data, false);
                        }
                    });
            return true;
        } catch (Exception e) {
            connectionStatuses.put(connId, "ERROR");
            Map<String, Object> data = new HashMap<>();
            data.put("connectionId", connId);
            data.put("error", e.getMessage());
            DynamicMacrosApi.triggerEvent("onWebSocketError", data, false);
            return false;
        }
    }

    private static boolean send(String connId, String message) {
        WebSocket ws = activeConnections.get(connId);
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendText(message, true);
            return true;
        }
        return false;
    }

    private static boolean disconnect(String connId) {
        WebSocket ws = activeConnections.remove(connId);
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "User disconnected");
            connectionStatuses.put(connId, "CLOSED");
            return true;
        }
        return false;
    }
}