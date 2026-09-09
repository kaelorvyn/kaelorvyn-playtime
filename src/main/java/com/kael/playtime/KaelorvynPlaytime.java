package com.kael.playtime;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Plugin(
        id = "kaelorvynplaytime",
        name = "KaelorvynPlaytime",
        version = "1.0.0",
        description = "Velocity 代理端游玩时长与分服务器时长记录插件",
        authors = {"Kael"}
)
public final class KaelorvynPlaytime {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private PlaytimeStore store;
    private ScheduledTask heartbeatTask;

    @Inject
    public KaelorvynPlaytime(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        migrateLegacyData();
        PlaytimeConfig config = new PlaytimeConfig(dataDirectory, logger).load();
        store = new PlaytimeStore(dataDirectory, config, logger);
        server.getEventManager().register(this, new PlaytimeListener(store));

        CommandMeta meta = server.getCommandManager().metaBuilder("playtime")
                .aliases("pt")
                .plugin(this)
                .build();
        server.getCommandManager().register(meta, new PlaytimeCommand(server, store, config.getZoneId()));

        heartbeatTask = server.getScheduler()
                .buildTask(this, this::heartbeat)
                .delay(config.getSaveIntervalSeconds(), TimeUnit.SECONDS)
                .repeat(config.getSaveIntervalSeconds(), TimeUnit.SECONDS)
                .schedule();

        logger.info("KaelorvynPlaytime 已启动，已加载 {} 名玩家记录。", store.playerCount());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (store != null) {
            store.flushAll(System.currentTimeMillis());
        }
        logger.info("KaelorvynPlaytime 已关闭，在线玩家时长已保存。");
    }

    private void migrateLegacyData() {
        Path legacy = dataDirectory.getParent().resolve("playtime");
        if (!Files.isDirectory(legacy) || Files.exists(dataDirectory.resolve("config.properties"))) {
            return;
        }
        try {
            Files.createDirectories(dataDirectory);
            copyDirectory(legacy.resolve("players"), dataDirectory.resolve("players"));
            copyDirectory(legacy.resolve("sessions"), dataDirectory.resolve("sessions"));
            Path legacyConfig = legacy.resolve("config.properties");
            if (Files.isRegularFile(legacyConfig)) {
                Files.copy(legacyConfig, dataDirectory.resolve("config.properties"));
            }
            logger.info("已迁移旧 BloodPlaytime 数据到 plugins/kaelorvynplaytime。");
        } catch (IOException e) {
            logger.warn("旧 BloodPlaytime 数据迁移失败：{}", e.getMessage());
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void heartbeat() {
        if (store == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : server.getAllPlayers()) {
            store.heartbeat(player.getUniqueId(), now);
        }
    }
}
