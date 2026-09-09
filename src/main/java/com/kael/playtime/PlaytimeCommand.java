package com.kael.playtime;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class PlaytimeCommand implements SimpleCommand {

    private static final String ADMIN_PERMISSION = "playtime.admin";

    private final ProxyServer server;
    private final PlaytimeStore store;
    private final ZoneId zoneId;

    public PlaytimeCommand(ProxyServer server, PlaytimeStore store, ZoneId zoneId) {
        this.server = server;
        this.store = store;
        this.zoneId = zoneId;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            if (source instanceof Player player) {
                showPlayer(source, player.getUsername());
            } else {
                sendUsage(source);
            }
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("log")) {
            logCommand(source, args);
        } else if (sub.equals("top")) {
            topCommand(source, args);
        } else if (sub.equals("servers")) {
            serversCommand(source, args);
        } else if (args.length == 1) {
            showPlayer(source, args[0]);
        } else {
            sendUsage(source);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        List<String> suggestions = new ArrayList<>();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            suggestions.add("log");
            suggestions.add("top");
            suggestions.add("servers");
            addOnlinePlayers(suggestions, "");
        } else if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if ("log".startsWith(prefix)) {
                suggestions.add("log");
            }
            if ("top".startsWith(prefix)) {
                suggestions.add("top");
            }
            if ("servers".startsWith(prefix)) {
                suggestions.add("servers");
            }
            addOnlinePlayers(suggestions, prefix);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("log") || args[0].equalsIgnoreCase("servers"))) {
            addOnlinePlayers(suggestions, args[1].toLowerCase(Locale.ROOT));
        }
        return CompletableFuture.completedFuture(suggestions);
    }

    private void showPlayer(CommandSource source, String name) {
        if (!canView(source, name)) {
            Messages.send(source, Messages.PREFIX + "§c你没有查询其他玩家时长的权限。");
            return;
        }
        long now = System.currentTimeMillis();
        Optional<PlaytimeStore.PlayerRecord> record = store.findByName(name, now);
        if (record.isEmpty()) {
            Messages.send(source, Messages.PREFIX + "§c没有找到玩家 §e" + name + " §c的游玩记录。");
            return;
        }
        PlaytimeStore.PlayerRecord player = record.get();
        StringBuilder text = new StringBuilder(Messages.PREFIX + "§e" + player.getName()
                + " §7累计游玩：§a" + TimeFormat.formatMillis(player.getTotalMillis()));
        if (player.isOnline()) {
            long current = Math.max(0L, now - player.getSessionStartMillis());
            text.append("\n§7状态：§a在线 §8| §7本次已玩：§e")
                    .append(TimeFormat.formatMillis(current));
            if (player.hasCurrentServer()) {
                text.append(" §8| §7当前服务器：§e").append(player.getCurrentServer());
            }
        } else if (player.getLastSeenMillis() > 0) {
            text.append("\n§7状态：§7离线 §8| §7上次在线：§e")
                    .append(TimeFormat.formatInstant(player.getLastSeenMillis(), zoneId));
        }
        Messages.send(source, text.toString());
    }

    private void logCommand(CommandSource source, String[] args) {
        if (!hasAdminPermission(source)) {
            Messages.send(source, Messages.PREFIX + "§c该操作需要 OP 权限（playtime.admin）。");
            return;
        }
        if (args.length < 2) {
            sendUsage(source);
            return;
        }
        int limit = args.length >= 3 ? parseLimit(args[2], 100) : 10;
        long now = System.currentTimeMillis();
        Optional<PlaytimeStore.PlayerRecord> record = store.findByName(args[1], now);
        if (record.isEmpty()) {
            Messages.send(source, Messages.PREFIX + "§c没有找到玩家 §e" + args[1] + " §c的游玩记录。");
            return;
        }
        List<PlaytimeStore.SessionRecord> sessions = store.recentSessions(record.get().getUuid(), limit);
        if (sessions.isEmpty()) {
            Messages.send(source, Messages.PREFIX + "§7玩家 §e" + record.get().getName()
                    + " §7还没有完整的登录记录。");
            return;
        }
        Messages.send(source, Messages.PREFIX + "§e" + record.get().getName()
                + " §7最近 §e" + sessions.size() + " §7次登录：");
        for (PlaytimeStore.SessionRecord session : sessions) {
            Messages.send(source, Messages.PREFIX + "§8- §7登录：§e"
                    + TimeFormat.formatInstant(session.getLoginMillis(), zoneId)
                    + " §8→ §7登出：§e"
                    + TimeFormat.formatInstant(session.getLogoutMillis(), zoneId)
                    + " §8| §7本次：§a"
                    + TimeFormat.formatMillis(session.getDurationMillis()));
        }
    }

    private void serversCommand(CommandSource source, String[] args) {
        if (!hasAdminPermission(source)) {
            Messages.send(source, Messages.PREFIX + "§c该操作需要 OP 权限（playtime.admin）。");
            return;
        }
        if (args.length < 2) {
            sendUsage(source);
            return;
        }
        int limit = args.length >= 3 ? parseLimit(args[2], 50) : 20;
        long now = System.currentTimeMillis();
        Optional<PlaytimeStore.PlayerRecord> record = store.findByName(args[1], now);
        if (record.isEmpty()) {
            Messages.send(source, Messages.PREFIX + "§c没有找到玩家 §e" + args[1] + " §c的游玩记录。");
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(record.get().getServerMillis().entrySet());
        if (entries.isEmpty()) {
            Messages.send(source, Messages.PREFIX + "§7玩家 §e" + record.get().getName()
                    + " §7还没有分服务器时长记录。");
            return;
        }
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int end = Math.min(limit, entries.size());
        Messages.send(source, Messages.PREFIX + "§e" + record.get().getName()
                + " §7分服务器游玩时长（前 " + end + "）：");
        for (int i = 0; i < end; i++) {
            Map.Entry<String, Long> entry = entries.get(i);
            Messages.send(source, Messages.PREFIX + "§8- §e" + entry.getKey()
                    + " §8- §a" + TimeFormat.formatMillis(entry.getValue()));
        }
    }

    private void topCommand(CommandSource source, String[] args) {
        if (!hasAdminPermission(source)) {
            Messages.send(source, Messages.PREFIX + "§c该操作需要 OP 权限（playtime.admin）。");
            return;
        }
        int limit = args.length >= 2 ? parseLimit(args[1], 50) : 10;
        List<PlaytimeStore.PlayerRecord> top = store.top(limit, System.currentTimeMillis());
        if (top.isEmpty()) {
            Messages.send(source, Messages.PREFIX + "§7还没有任何游玩记录。");
            return;
        }
        Messages.send(source, Messages.PREFIX + "§e累计游玩排行（前 " + top.size() + "）：");
        for (int i = 0; i < top.size(); i++) {
            PlaytimeStore.PlayerRecord player = top.get(i);
            Messages.send(source, Messages.PREFIX + "§8" + (i + 1) + ". §e" + player.getName()
                    + " §8- §a" + TimeFormat.formatMillis(player.getTotalMillis()));
        }
    }

    private boolean canView(CommandSource source, String targetName) {
        if (source instanceof ConsoleCommandSource) {
            return true;
        }
        if (source instanceof Player player && player.getUsername().equalsIgnoreCase(targetName)) {
            return true;
        }
        return hasAdminPermission(source);
    }

    private boolean hasAdminPermission(CommandSource source) {
        return source instanceof ConsoleCommandSource || source.hasPermission(ADMIN_PERMISSION);
    }

    private int parseLimit(String input, int max) {
        try {
            return Math.max(1, Math.min(max, Integer.parseInt(input)));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    private void addOnlinePlayers(List<String> suggestions, String prefix) {
        for (Player player : server.getAllPlayers()) {
            if (player.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                suggestions.add(player.getUsername());
            }
        }
    }

    private void sendUsage(CommandSource source) {
        Messages.send(source, Messages.PREFIX + "§c用法：§e/playtime [玩家] | /playtime log <玩家> [条数] | "
                + "/playtime servers <玩家> [条数] | /playtime top [条数]");
    }
}
