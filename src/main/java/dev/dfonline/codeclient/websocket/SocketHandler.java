package dev.dfonline.codeclient.websocket;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.action.None;
import dev.dfonline.codeclient.action.impl.*;
import dev.dfonline.codeclient.location.Plot;
import dev.dfonline.codeclient.websocket.scope.AuthScope;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.java_websocket.WebSocket;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.*;

public class SocketHandler {
    public static final int PORT = 31375;
    private final ArrayList<Action> actionQueue = new ArrayList<>();
    // Current connection
    @Nullable
    private WebSocket connection = null;
    // Default scopes
    private static final List<AuthScope> defaultAuthScopes = List.of(AuthScope.DEFAULT);
    // Unapproved scopes that the user needs to approve
    private List<AuthScope> unapprovedAuthScopes = List.of();
    // Approved scopes the application has access to
    private List<AuthScope> authScopes = defaultAuthScopes;
    // Map of tokens and their scopes
    private final HashMap<String, List<AuthScope>> tokenMap = new HashMap<>();
    // The active token, empty if none
    private String activeToken = null;
    private SocketServer websocket;

    public void start() {
        try {
            websocket = new SocketServer(new InetSocketAddress("localhost", PORT), this);
            Thread socketThread = new Thread(websocket, "CodeClient-API");
            socketThread.start();
            CodeClient.LOGGER.info("Socket opened");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        try {
            websocket.stop();
        } catch (Exception ignored) {
        }
    }

    public void setAcceptedScopes(boolean accepted) {
        if(connection == null) return;
        actionQueue.clear();
        if (accepted) {
            // Add the unapproved scopes & the default scopes
            unapprovedAuthScopes.addAll(defaultAuthScopes);
            authScopes = unapprovedAuthScopes;
            connection.send("auth");
        } else {
            // Set to default scopes
            authScopes = defaultAuthScopes;
            connection.send("removed");
        }
        unapprovedAuthScopes = List.of();
    }

    public void setConnection(WebSocket socket) {
        if (socket != null) actionQueue.clear();
        if (connection != null) {
            connection.close(); // Close the old connection
            if (activeToken != null) {
                // If we were using a token, save the scopes
                tokenMap.put(activeToken, authScopes);
            }
            // Reset the active token and scopes
            activeToken = null;
            authScopes = defaultAuthScopes;
            unapprovedAuthScopes = List.of();
        }
        connection = socket;
    }


    public void onMessage(String message) {
        assert connection != null;
        String[] arguments = message.split(" ");
        Action topAction = getTopAction();
        if (arguments[0] == null) return;
        String content = arguments.length > 1 ? message.substring(arguments[0].length() + 1) : "";
        if (topAction != null && arguments.length > 1 && Objects.equals(topAction.name, arguments[0])) {
            topAction.message(connection, content);
            return;
        }
        switch (arguments[0]) {
            case "scopes" -> handleScopeRequest(arguments);
            case "token" -> handleTokenRequest(arguments);
            case "clear" -> assertScopeLevel(new Clear());
            case "spawn" -> assertScopeLevel(new Spawn());
            case "size" -> assertScopeLevel(new Size());
            case "scan" -> assertScopeLevel(new Scan());
            case "place" -> assertScopeLevel(new Place());
            case "break" -> assertScopeLevel(new Break());
            case "inv" -> assertScopeLevel(new SendInventory());
            case "setinv" -> assertScopeLevel(new SetInventory(content));
            case "give" -> assertScopeLevel(new Give(content));
            case "mode" -> assertScopeLevel(new Mode(content));
            default -> connection.send("invalid");
        }
        topAction = getTopAction();
        if (topAction != null && arguments.length > 1 && Objects.equals(topAction.name, arguments[0])) {
            topAction.message(connection, content);
        }
        if (actionQueue.isEmpty()) return;
        Action firstAction = actionQueue.get(0);
        if (firstAction == null) return;
        if (firstAction.active) return;
        next();
    }

    private Action getTopAction() {
        if (actionQueue.isEmpty()) return null;
        return actionQueue.get(actionQueue.size() - 1);
    }

    private void handleScopeRequest(String[] arguments) {
        assert connection != null;
        List<String> args = Arrays.asList(arguments).subList(1, arguments.length);

        // Send the currently approved scopes if no args are provided
        if (args.isEmpty()) {
            String scopesString = String.join(" ", authScopes.stream().map(authScope -> authScope.translationKey).toArray(String[]::new));
            connection.send(scopesString);
            return;
        }

        List<AuthScope> scopes = new ArrayList<>();
        List<String> invalidScopes = new ArrayList<>();

        // Add scopes to the list if they are valid
        for (String arg : args) {
            AuthScope scope;
            try {
                scope = AuthScope.valueOf(arg.toUpperCase());
            } catch (IllegalArgumentException e) {
                invalidScopes.add(arg);
                continue;
            }
            scopes.add(scope);
        }

        // Send the invalid scopes if any are found, and alert the user
        if (!invalidScopes.isEmpty()) {
            connection.send("invalid scope " + String.join(" ", invalidScopes));
            Utility.sendMessage(Text.translatable("codeclient.api.scope.invalid"));
            return;
        }

        unapprovedAuthScopes = scopes;

        promptUserAcceptScopes();
    }

    private void handleTokenRequest(String[] arguments) {
        assert connection != null;
        List<String> args = Arrays.asList(arguments).subList(1, arguments.length);

        if (args.isEmpty()) {
            // Send the active token if one is active, otherwise regenerate it.
            String token = activeToken == null ? Utility.genAuthToken() : activeToken;
            connection.send("token " + token);
            tokenMap.put(token, authScopes);
            activeToken = token;
        } else {
            String token = args.get(0);
            if (!tokenMap.containsKey(token)) {
                connection.send("invalid token");
                return;
            }
            // Restore the scopes
            authScopes = tokenMap.get(token);
            connection.send("auth");
            activeToken = token;
        }
    }

    private void assertScopeLevel(SocketHandler.Action commandClass) {
        if (!authScopes.contains(commandClass.authScope)) {
            if(connection != null) connection.send("unauthed");
            return;
        }
        actionQueue.add(commandClass);
    }

    private void promptUserAcceptScopes() {
        if (unapprovedAuthScopes.isEmpty()) return;
        if (CodeClient.MC.player == null) return;

        ClientPlayerEntity player = CodeClient.MC.player;

        // Send the user the scopes to approve
        Utility.sendMessage(Text.translatable("codeclient.api.scope.prompt"));
        for (AuthScope scope : unapprovedAuthScopes) {
            player.sendMessage(
                    Text.empty()
                            .append(
                                    Text.literal("- ")
                                            .formatted(Formatting.DARK_GRAY)
                            )
                            .append(
                                    Text.translatable("codeclient.api.scope.type." + scope.translationKey)
                                            .formatted(Formatting.WHITE)
                                            .append(" ")
                                            .append(
                                                    Text.literal("(")
                                                            .append(Text.translatable("codeclient.api.danger." + scope.dangerLevel.translationKey))
                                                            .append(Text.literal(")"))
                                                            .formatted(scope.dangerLevel.color, Formatting.ITALIC)
                                            )
                            )
                            .setStyle(Style.EMPTY.withHoverEvent(
                                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("codeclient.api.danger." + scope.dangerLevel.translationKey + ".description"))
                            )), false
            );
        }
        Utility.sendMessage(Text.translatable("codeclient.api.run_auth"));
    }

    private void next() {
        if (actionQueue.isEmpty()) return;
        Action firstAction = actionQueue.get(0);
        if (firstAction == null) return;
        if (firstAction.active) {
            actionQueue.remove(0);
            CodeClient.LOGGER.info(firstAction.name + " done");
            next();
            return;
        }
        CodeClient.LOGGER.info("starting " + firstAction.name);
        firstAction.active = true;
        firstAction.start(connection);
    }

    public void abort() {
        actionQueue.clear();
        if(connection != null) connection.send("aborted");
    }

    private abstract static class Action {
        public final String name;
        public final AuthScope authScope;
        boolean active = false;

        Action(String name, AuthScope authScope) {
            this.name = name;
            this.authScope = authScope;
        }

        /**
         * When the action is added to the queue
         */
        public abstract void set(WebSocket responder);

        /**
         * When the queue reaches the action
         */
        public abstract void start(WebSocket responder);

        /**
         * If a message is sent when this is the last set
         */
        public abstract void message(WebSocket responder, String message);
    }

    private class Clear extends SocketHandler.Action {
        Clear() {
            super("clear", AuthScope.CLEAR_PLOT);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            CodeClient.currentAction = new ClearPlot(SocketHandler.this::next);
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Spawn extends SocketHandler.Action {
        Spawn() {
            super("spawn", AuthScope.MOVEMENT);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            CodeClient.currentAction = new MoveToSpawn(SocketHandler.this::next);
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Size extends SocketHandler.Action {
        Size() {
            super("size", AuthScope.READ_PLOT);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (CodeClient.location instanceof Plot plot) {
                if (plot.getSize() != null) {
                    if (responder.isOpen()) responder.send(plot.getSize().name());
                    next();
                } else {
                    CodeClient.currentAction = new GetPlotSize(() -> {
                        if (responder.isOpen()) responder.send(plot.getSize().name());
                        next();
                    });
                }
            }
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Scan extends SocketHandler.Action {
        Scan() {
            super("scan", AuthScope.READ_PLOT);
        }

        @Override
        public void set(WebSocket responder) {

        }

        @Override
        public void start(WebSocket responder) {
            ArrayList<ItemStack> items = new ArrayList<>();
            CodeClient.currentAction = new ScanPlot(() -> {
                if(items.isEmpty()) {
                    responder.send("empty");
                    CodeClient.currentAction = new None();
                    next();
                    return;
                }
                var builder = new StringBuilder();
                for (var item : items) {
                    var data = Utility.templateDataItem(item);
                    if (data == null) continue;
                    builder.append(data).append("\n");
                }
                builder.deleteCharAt(builder.length() - 1);
                responder.send(builder.toString());
                CodeClient.currentAction = new None();
                next();
            }, items);
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {

        }
    }

    private class Place extends SocketHandler.Action {
        private final ArrayList<ItemStack> templates = new ArrayList<>();
        public boolean ready = false;
        private Method method = Method.DEFAULT;
        Place() {
            super("place", AuthScope.WRITE_CODE);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (!ready) return;
            var placer = method.createPlacer.run(templates, SocketHandler.this::next, responder);
            if (placer == null) return;
            CodeClient.currentAction = placer;
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {
            switch (message) {
                case "compact" -> {
                    this.method = Method.COMPACT;
                    return;
                }
                case "swap" -> {
                    this.method = Method.SWAP;
                    return;
                }
                case "go" -> {
                    this.ready = true;
                    if (Objects.equals(actionQueue.get(0), this)) {
                        this.start(responder);
                    }
                    return;
                }
            }
            templates.add(Utility.makeTemplate(message));
        }

        private enum Method {
            DEFAULT((ArrayList<ItemStack> templates, Callback next, WebSocket responder) -> PlaceTemplates.createPlacer(templates, () -> {
                CodeClient.currentAction = new None();
                if (responder.isOpen()) responder.send("place done");
                next.run();
            })),
            COMPACT((ArrayList<ItemStack> templates, Callback next, WebSocket responder) -> PlaceTemplates.createPlacer(templates, () -> {
                CodeClient.currentAction = new None();
                if (responder.isOpen()) responder.send("place done");
                next.run();
            }, true)),
            SWAP((ArrayList<ItemStack> templates, Callback next, WebSocket responder) -> PlaceTemplates.createSwapper(templates, () -> {
                CodeClient.currentAction = new None();
                if (responder.isOpen()) responder.send("place done");
                next.run();
            }).swap()),
            ;

            public final CreatePlacer createPlacer;

            Method(CreatePlacer createPlacer) {
                this.createPlacer = createPlacer;
            }
        }

        private interface CreatePlacer {
            PlaceTemplates run(ArrayList<ItemStack> templates, Callback next, WebSocket responder);
        }
    }

    private class Break extends SocketHandler.Action {
        private final ArrayList<String> targets = new ArrayList<>();
        public boolean ready = false;

        Break(){
            super("break", AuthScope.WRITE_CODE);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (!ready) return;
            PlaceTemplates breaker = PlaceTemplates.breakTemplates(targets, SocketHandler.this::next);
            if(breaker == null) return;
            CodeClient.currentAction = breaker;
            breaker.init();
        }

        @Override
        public void message(WebSocket responder, String message) {
            if(message.isEmpty() && !targets.isEmpty()) {
                ready = true;
                if (Objects.equals(actionQueue.getFirst(), this)) {
                    this.start(responder);
                }
            } else {
                targets.add(message);
            }
        }
    }

    private class SendInventory extends SocketHandler.Action {
        SendInventory() {
            super("inv", AuthScope.INVENTORY);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (CodeClient.MC.player == null) return;
            NbtCompound nbt = new NbtCompound();
            CodeClient.MC.player.writeNbt(nbt);
            responder.send(String.valueOf(nbt.get("Inventory")));
            next();
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class SetInventory extends SocketHandler.Action {
        private final String content;

        SetInventory(String content) {
            super("setinv", AuthScope.INVENTORY);
            this.content = content;
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (CodeClient.MC.player == null) return;
            if (!CodeClient.MC.player.isCreative()) {
                responder.send("not creative mode");
                next();
                return;
            }
            try {
                NbtCompound nbt = new NbtCompound();
                CodeClient.MC.player.writeNbt(nbt);
                nbt.put("Inventory", StringNbtReader.parse("{Inventory:" + content + "}").getList("Inventory", NbtElement.COMPOUND_TYPE));
                CodeClient.MC.player.readNbt(nbt);
                Utility.sendInventory();
            } catch (CommandSyntaxException e) {
                responder.send("invalid nbt");
            } finally {
                next();
            }
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Give extends SocketHandler.Action {
        private final String content;

        Give(String content) {
            super("give", AuthScope.DEFAULT);
            this.content = content;
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (CodeClient.MC.player == null) return;
            if (!CodeClient.MC.player.isCreative()) {
                responder.send("not creative mode");
                next();
                return;
            }
            try {
                if (CodeClient.MC.world == null) return;
                Optional<ItemStack> itemStack = ItemStack.fromNbt(CodeClient.MC.world.getRegistryManager(), StringNbtReader.parse(content));
                if (itemStack.isEmpty()) return;
                CodeClient.MC.player.giveItemStack(itemStack.get());
                Utility.sendInventory();
            } catch (CommandSyntaxException e) {
                responder.send("invalid nbt");
            } finally {
                next();
            }
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Mode extends SocketHandler.Action {
        private static final List<String> commands = List.of("play", "build", "code", "dev");
        private final String command;

        Mode(String command) {
            super("mode", AuthScope.MOVEMENT);
            this.command = commands.contains(command) ? command : "";
        }

        @Override
        public void set(WebSocket responder) {

        }

        @Override
        public void start(WebSocket responder) {
            if (CodeClient.location instanceof Plot && !command.isEmpty() && CodeClient.MC.getNetworkHandler() != null) {
                CodeClient.MC.getNetworkHandler().sendCommand(command);
            } else {
                responder.send(CodeClient.location.name());
            }
            next();
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }
}
