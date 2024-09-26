package dev.dfonline.codeclient.command.impl;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.dfonline.codeclient.ChatType;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.command.Command;
import dev.dfonline.codeclient.location.Dev;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.regex.Pattern;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class CommandSearch extends Command {
    @Override
    public String name() {
        return "ccsearch";
    }

    @Override
    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        super.register(dispatcher);

        // Add 'search' alias if recode is not loaded.
        if (!FabricLoader.getInstance().isModLoaded("recode")) {
            dispatcher.register(create(literal("search")));
        }
    }

    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> create(LiteralArgumentBuilder<FabricClientCommandSource> cmd) {
        return cmd.then(argument("query", greedyString()).suggests((context, builder) -> CommandJump.suggestJump(CommandJump.JumpType.ANY, context, builder)).executes(context -> {
            if (CodeClient.location instanceof Dev dev) {
                var query = context.getArgument("query", String.class);
                var results = dev.scanForSigns(CommandJump.JumpType.ANY.pattern, Pattern.compile("^.*" + Pattern.quote(query) + ".*$", Pattern.CASE_INSENSITIVE));

                if (results == null || results.isEmpty()) {
                    Utility.sendMessage(Text.translatable("codeclient.search.no_results"), ChatType.INFO);
                    return 0;
                }

                var message = Text.translatable("codeclient.search.results");
                results.forEach((pos, text) -> {
                    var type = text.getMessage(0, false).getString();
                    var name = text.getMessage(1, false).getString();

                    String sub;
                    if (CommandJump.JumpType.PLAYER_EVENT.pattern.matcher(type).matches()) sub = "player";
                    else if (CommandJump.JumpType.ENTITY_EVENT.pattern.matcher(type).matches()) sub = "entity";
                    else if (CommandJump.JumpType.FUNCTION.pattern.matcher(type).matches()) sub = "func";
                    else if (CommandJump.JumpType.PROCESS.pattern.matcher(type).matches()) sub = "proc";
                    else return;

                    var action = Text.empty().append(" [⏼]").setStyle(Style.EMPTY
                            .withColor(Formatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, String.format("/jump %s %s", sub, name)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("codeclient.search.hover.teleport", pos.getX(), pos.getY(), pos.getZ())))
                    );
                    var entry = Text.empty().append("\n • ").formatted(Formatting.GREEN)
                            .append(Text.empty().append(name).formatted(Formatting.WHITE))
                            .append(action);
                    message.append(entry);
                });

                Utility.sendMessage(message, ChatType.SUCCESS);
            } else {
                Utility.sendMessage(Text.translatable("codeclient.warning.dev_mode"), ChatType.FAIL);
            }
            return 0;
        }));
    }
}