package com.crystalball.spectate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.crystalball.CrystalBallMod;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code /spectate} command tree.
 *
 * <pre>
 * /spectate watch &lt;player&gt;                  start watching somebody (no item required)
 * /spectate stop                             stop watching
 * /spectate viewers                          list the players that are watching you
 * /spectate list                             (admin) list every current watching relation
 * /spectate permission get                   (admin) show the configured permission levels
 * /spectate permission set &lt;cmd&gt; &lt;level&gt;     (admin) change a command's permission level
 * /spectate permission reset                 (admin) restore the defaults
 * </pre>
 *
 * The three player facing commands default to permission level 0 (everybody) and can be raised to
 * 0..4, where 2 is the usual "server operator" level.
 */
@Mod.EventBusSubscriber(modid = CrystalBallMod.MODID)
public final class SpectateCommands {

    private static final DynamicCommandExceptionType ERROR_UNKNOWN_COMMAND = new DynamicCommandExceptionType(
            argument -> new TranslatableComponent("crystal_ball.command.permission.unknown", argument));

    private SpectateCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("crystalball")
                // 6.1: watch somebody
                .then(Commands.literal("watch")
                        .then(Commands.argument("player", EntityArgument.player()).executes(SpectateCommands::executeWatch)))
                // 6.2: stop watching
                .then(Commands.literal("stop").executes(SpectateCommands::executeStop))
                // 6.3: who is watching me?
                .then(Commands.literal("viewers").executes(SpectateCommands::executeViewers))
                // 6.5: (admin) all watching relations
                .then(Commands.literal("list").requires(source -> source.hasPermission(SpectatePermissions.ADMIN_LEVEL))
                        .executes(SpectateCommands::executeList))
                // 6.4: (admin) configure the permission levels of the three commands above
                .then(Commands.literal("permission").requires(source -> source.hasPermission(SpectatePermissions.ADMIN_LEVEL))
                        .then(Commands.literal("get").executes(SpectateCommands::executePermissionGet))
                        .then(Commands.literal("reset").executes(SpectateCommands::executePermissionReset))
                        .then(Commands.literal("set")
                                .then(Commands.argument("command", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider
                                                .suggest(SpectatePermissions.MUTABLE_COMMANDS, builder))
                                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 4))
                                                .executes(SpectateCommands::executePermissionSet)))));
        dispatcher.register(root);
    }

    // ------------------------------------------------------------------
    // Player facing commands
    // ------------------------------------------------------------------

    private static int executeWatch(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer viewer = source.getPlayerOrException();
        if (!ensurePermission(source, SpectatePermissions.WATCH)) {
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        return SpectateManager.start(viewer, target) ? 1 : 0;
    }

    private static int executeStop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer viewer = source.getPlayerOrException();
        if (!ensurePermission(source, SpectatePermissions.STOP)) {
            return 0;
        }
        if (!SpectateManager.isWatching(viewer.getUUID())) {
            source.sendSuccess(new TranslatableComponent("crystal_ball.message.not_watching"), false);
            return 0;
        }
        SpectateManager.stop(viewer.getUUID());
        source.sendSuccess(new TranslatableComponent("crystal_ball.message.stopped"), false);
        return 1;
    }

    private static int executeViewers(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer viewer = source.getPlayerOrException();
        if (!ensurePermission(source, SpectatePermissions.VIEWERS)) {
            return 0;
        }
        Set<UUID> watchers = SpectateManager.watchersOf(viewer.getUUID());
        if (watchers.isEmpty()) {
            source.sendSuccess(new TranslatableComponent("crystal_ball.message.viewers_none"), false);
            return 0;
        }
        source.sendSuccess(new TranslatableComponent("crystal_ball.message.viewers_header", watchers.size()), false);
        MinecraftServer server = source.getServer();
        for (UUID watcherId : watchers) {
            ServerPlayer watcher = server.getPlayerList().getPlayer(watcherId);
            Component name = watcher != null ? watcher.getDisplayName() : new TextComponent(watcherId.toString());
            source.sendSuccess(new TranslatableComponent("crystal_ball.message.viewers_entry", name), false);
        }
        return watchers.size();
    }

    // ------------------------------------------------------------------
    // Administration commands
    // ------------------------------------------------------------------

    private static int executeList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Collection<WatchSession> sessions = SpectateManager.sessions();
        if (sessions.isEmpty()) {
            source.sendSuccess(new TranslatableComponent("crystal_ball.message.relations_none"), false);
            return 0;
        }
        source.sendSuccess(new TranslatableComponent("crystal_ball.message.relations_header", sessions.size()), false);
        for (WatchSession session : sessions) {
            source.sendSuccess(new TranslatableComponent("crystal_ball.message.relations_entry", session.viewerName(), session.targetName()),
                    false);
        }
        return sessions.size();
    }

    private static int executePermissionGet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        for (String command : SpectatePermissions.MUTABLE_COMMANDS) {
            source.sendSuccess(new TranslatableComponent("crystal_ball.message.permission_get", command, SpectatePermissions.level(server, command)),
                    false);
        }
        return SpectatePermissions.MUTABLE_COMMANDS.length;
    }

    private static int executePermissionSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String command = StringArgumentType.getString(context, "command").toLowerCase(Locale.ROOT);
        if (!SpectatePermissions.isKnownCommand(command)) {
            throw ERROR_UNKNOWN_COMMAND.create(command);
        }
        int level = IntegerArgumentType.getInteger(context, "level");
        SpectatePermissions.data(source.getServer()).setPermission(command, level);
        source.sendSuccess(new TranslatableComponent("crystal_ball.message.permission_set", command, level), false);
        return level;
    }

    private static int executePermissionReset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SpectatePermissions.data(source.getServer()).clearPermissions();
        source.sendSuccess(new TranslatableComponent("crystal_ball.message.permission_reset", SpectatePermissions.DEFAULT_LEVEL), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean ensurePermission(CommandSourceStack source, String commandId) {
        int required = SpectatePermissions.level(source.getServer(), commandId);
        if (source.hasPermission(required)) {
            return true;
        }
        source.sendFailure(new TranslatableComponent("crystal_ball.message.no_permission", required));
        return false;
    }
}
