package com.kael.playtime;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

public final class PlaytimeStore {

    private static final long NO_SESSION = -1L;
    private static final String SERVER_PREFIX = "server.";
    private static final String SERVER_SUFFIX = ".millis";
    private static final String SESSION_HEADER = "loginMillis\tlogoutMillis\tdurationMillis\tloginTime\tlogoutTime";

    private final Path playersDir;
    private final Path sessionsDir;
    private final ZoneId zoneId;
    private final Logger logger;
    private final Map<UUID, Record> records = new HashMap<>();
    private final Map<String, UUID> nameIndex = new HashMap<>();

    public PlaytimeStore(Path dataDirectory, PlaytimeConfig config, Logger logger) {
        this.playersDir = dataDirectory.resolve("players");
        this.sessionsDir = dataDirectory.resolve("sessions");
        this.zoneId = config.getZoneId();
        this.logger = logger;
        try {
            Files.createDirectories(playersDir);
            Files.createDirectories(sessionsDir);
            loadAll();
            closeStaleSessions(System.currentTimeMillis());
        } catch (IOException e) {
            logger.warn("游玩数据目录初始化失败：{}", e.getMessage());
        }
    }

    public synchronized void startSession(UUID uuid, String name, long now) {
        Record record = records.computeIfAbsent(uuid, id -> new Record(id, name, now));
        if (record.sessionStartMillis != NO_SESSION) {
            return;
        }
        updateName(record, name);
        record.sessionStartMillis = now;
        record.lastSeenMillis = now;
        record.updatedAtMillis = now;
        save(record);
        logger.info("玩家 {} 登录代理端，开始记录时长。", name);
    }

    public synchronized void serverConnected(UUID uuid, String name, String server, long now) {
        Record record = records.computeIfAbsent(uuid, id -> new Record(id, name, now));
        updateName(record, name);
        if (record.sessionStartMillis == NO_SESSION) {
            record.sessionStartMillis = now;
            record.lastSeenMillis = now;
        } else {
            closeCurrentServer(record, now);
        }
        record.currentServer = server;
        record.currentServerStartMillis = now;
        save(record);
        logger.info("玩家 {} 进入服务器 {}。", name, server);
    }

    public synchronized void heartbeat(UUID uuid, long now) {
        Record record = records.get(uuid);
        if (record == null || record.sessionStartMillis == NO_SESSION || now <= record.lastSeenMillis) {
            return;
        }
        long delta = now - record.lastSeenMillis;
        record.totalMillis += delta;
        if (hasText(record.currentServer)) {
            record.serverMillis.merge(record.currentServer, delta, Long::sum);
        }
        record.lastSeenMillis = now;
        record.updatedAtMillis = now;
        save(record);
    }

    public synchronized SessionResult endSession(UUID uuid, String name, long now) {
        Record record = records.get(uuid);
        if (record == null || record.sessionStartMillis == NO_SESSION) {
            return null;
        }
        updateName(record, name);
        long login = record.sessionStartMillis;
        closeCurrentServer(record, now);
        long logout = record.lastSeenMillis;
        long duration = Math.max(0L, logout - login);
        record.sessionStartMillis = NO_SESSION;
        record.currentServer = null;
        record.currentServerStartMillis = NO_SESSION;
        record.updatedAtMillis = logout;
        save(record);
        appendSession(record, login, logout, duration);
        logger.info("玩家 {} 登出代理端，本次游玩 {}，累计 {}。",
                name, TimeFormat.formatMillis(duration), TimeFormat.formatMillis(record.totalMillis));
        return new SessionResult(uuid, login, logout, duration, record.totalMillis);
    }

    public synchronized void flushAll(long now) {
        for (Record record : records.values()) {
            if (record.sessionStartMillis == NO_SESSION) {
                continue;
            }
            long login = record.sessionStartMillis;
            closeCurrentServer(record, now);
            long logout = record.lastSeenMillis;
            long duration = Math.max(0L, logout - login);
            record.sessionStartMillis = NO_SESSION;
            record.currentServer = null;
            record.currentServerStartMillis = NO_SESSION;
            record.updatedAtMillis = logout;
            save(record);
            appendSession(record, login, logout, duration);
            logger.info("代理端关闭，已结算玩家 {} 的在线时长。", record.name);
        }
    }

    public synchronized Optional<PlayerRecord> findByName(String name, long now) {
        UUID uuid = nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (uuid == null) {
            return Optional.empty();
        }
        Record record = records.get(uuid);
        return record == null ? Optional.empty() : Optional.of(snapshot(record, now));
    }

    public synchronized int playerCount() {
        return records.size();
    }

    public synchronized List<PlayerRecord> top(int limit, long now) {
        List<PlayerRecord> result = new ArrayList<>();
        for (Record record : records.values()) {
            result.add(snapshot(record, now));
        }
        result.sort(Comparator.comparingLong(PlayerRecord::getTotalMillis).reversed());
        int end = Math.min(limit, result.size());
        return new ArrayList<>(result.subList(0, end));
    }

    public synchronized List<SessionRecord> recentSessions(UUID uuid, int limit) {
        Path file = sessionsDir.resolve(uuid + ".tsv");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<SessionRecord> sessions = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\t", -1);
                if (parts.length < 5) {
                    continue;
                }
                try {
                    sessions.add(new SessionRecord(
                            Long.parseLong(parts[0]),
                            Long.parseLong(parts[1]),
                            Long.parseLong(parts[2])
                    ));
                } catch (NumberFormatException ignored) {
                    // 跳过损坏的一行日志
                }
            }
        } catch (IOException e) {
            logger.warn("读取玩家登录记录失败：{}", e.getMessage());
        }
        if (sessions.size() <= limit) {
            return sessions;
        }
        return new ArrayList<>(sessions.subList(sessions.size() - limit, sessions.size()));
    }

    private void loadAll() throws IOException {
        try (Stream<Path> paths = Files.list(playersDir)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .forEach(this::loadPlayerFile);
        }
    }

    private void loadPlayerFile(Path file) {
        String fileName = file.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - ".properties".length());
        try {
            UUID uuid = UUID.fromString(base);
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            String name = properties.getProperty("name", "");
            if (name.isBlank()) {
                return;
            }
            Map<String, Long> serverMillis = new HashMap<>();
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith(SERVER_PREFIX) && key.endsWith(SERVER_SUFFIX)
                        && key.length() > SERVER_PREFIX.length() + SERVER_SUFFIX.length()) {
                    String server = key.substring(SERVER_PREFIX.length(),
                            key.length() - SERVER_SUFFIX.length());
                    serverMillis.put(server, parseLong(properties, key, 0L));
                }
            }
            Record record = new Record(
                    uuid,
                    name,
                    parseLong(properties, "totalMillis", 0L),
                    parseLong(properties, "sessionStartMillis", NO_SESSION),
                    parseLong(properties, "lastSeenMillis", 0L),
                    parseLong(properties, "updatedAtMillis", 0L),
                    properties.getProperty("currentServer", ""),
                    parseLong(properties, "currentServerStartMillis", NO_SESSION),
                    serverMillis
            );
            records.put(uuid, record);
            nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        } catch (Exception e) {
            logger.warn("跳过损坏的玩家数据 {}：{}", fileName, e.getMessage());
        }
    }

    private void closeStaleSessions(long now) {
        for (Record record : records.values()) {
            if (record.sessionStartMillis == NO_SESSION) {
                continue;
            }
            long login = record.sessionStartMillis;
            long logout = Math.max(login, record.lastSeenMillis);
            closeCurrentServer(record, record.lastSeenMillis);
            record.sessionStartMillis = NO_SESSION;
            record.currentServer = null;
            record.currentServerStartMillis = NO_SESSION;
            record.lastSeenMillis = logout;
            record.updatedAtMillis = now;
            save(record);
            appendSession(record, login, logout, logout - login);
            logger.info("检测到异常断开的会话，已结算到最后一次心跳：{}", record.name);
        }
    }

    private void closeCurrentServer(Record record, long now) {
        long previous = record.lastSeenMillis;
        long end = Math.max(previous, now);
        long delta = end - previous;
        record.lastSeenMillis = end;
        if (delta <= 0) {
            return;
        }
        record.totalMillis += delta;
        if (hasText(record.currentServer)) {
            record.serverMillis.merge(record.currentServer, delta, Long::sum);
        }
    }

    private void updateName(Record record, String name) {
        String oldKey = record.name.toLowerCase(Locale.ROOT);
        String newKey = name.toLowerCase(Locale.ROOT);
        if (!oldKey.equals(newKey)) {
            nameIndex.remove(oldKey);
        }
        record.name = name;
        nameIndex.put(newKey, record.uuid);
    }

    private PlayerRecord snapshot(Record record, long now) {
        long activeMillis = record.sessionStartMillis == NO_SESSION
                ? 0L
                : Math.max(0L, now - record.lastSeenMillis);
        Map<String, Long> serverMillis = new HashMap<>(record.serverMillis);
        if (activeMillis > 0 && hasText(record.currentServer)) {
            serverMillis.merge(record.currentServer, activeMillis, Long::sum);
        }
        return new PlayerRecord(
                record.uuid,
                record.name,
                record.totalMillis + activeMillis,
                record.sessionStartMillis,
                record.lastSeenMillis,
                record.updatedAtMillis,
                record.currentServer,
                record.currentServerStartMillis,
                serverMillis
        );
    }

    private void save(Record record) {
        Path file = playersDir.resolve(record.uuid + ".properties");
        Path tmp = playersDir.resolve(record.uuid + ".properties.tmp");
        Properties properties = new Properties();
        properties.setProperty("name", record.name);
        properties.setProperty("totalMillis", Long.toString(record.totalMillis));
        properties.setProperty("sessionStartMillis", Long.toString(record.sessionStartMillis));
        properties.setProperty("lastSeenMillis", Long.toString(record.lastSeenMillis));
        properties.setProperty("updatedAtMillis", Long.toString(record.updatedAtMillis));
        properties.setProperty("currentServer", record.currentServer == null ? "" : record.currentServer);
        properties.setProperty("currentServerStartMillis", Long.toString(record.currentServerStartMillis));
        for (Map.Entry<String, Long> entry : record.serverMillis.entrySet()) {
            properties.setProperty(SERVER_PREFIX + entry.getKey() + SERVER_SUFFIX,
                    Long.toString(entry.getValue()));
        }
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                properties.store(writer, "KaelorvynPlaytime player data");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("保存玩家 {} 的时长失败：{}", record.name, e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不影响主流程
            }
        }
    }

    private void appendSession(Record record, long login, long logout, long duration) {
        long end = Math.max(login, logout);
        long safeDuration = Math.max(0L, end - login);
        Path file = sessionsDir.resolve(record.uuid + ".tsv");
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, SESSION_HEADER + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            String line = login + "\t" + end + "\t" + safeDuration + "\t"
                    + TimeFormat.formatInstant(login, zoneId) + "\t"
                    + TimeFormat.formatInstant(end, zoneId) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("写入玩家 {} 的登录记录失败：{}", record.name, e.getMessage());
        }
    }

    private long parseLong(Properties properties, String key, long fallback) {
        try {
            return Long.parseLong(properties.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static final class PlayerRecord {

        private final UUID uuid;
        private final String name;
        private final long totalMillis;
        private final long sessionStartMillis;
        private final long lastSeenMillis;
        private final long updatedAtMillis;
        private final String currentServer;
        private final long currentServerStartMillis;
        private final Map<String, Long> serverMillis;

        private PlayerRecord(UUID uuid, String name, long totalMillis, long sessionStartMillis,
                             long lastSeenMillis, long updatedAtMillis, String currentServer,
                             long currentServerStartMillis, Map<String, Long> serverMillis) {
            this.uuid = uuid;
            this.name = name;
            this.totalMillis = totalMillis;
            this.sessionStartMillis = sessionStartMillis;
            this.lastSeenMillis = lastSeenMillis;
            this.updatedAtMillis = updatedAtMillis;
            this.currentServer = currentServer;
            this.currentServerStartMillis = currentServerStartMillis;
            this.serverMillis = Collections.unmodifiableMap(new HashMap<>(serverMillis));
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public long getTotalMillis() {
            return totalMillis;
        }

        public boolean isOnline() {
            return sessionStartMillis != NO_SESSION;
        }

        public long getSessionStartMillis() {
            return sessionStartMillis;
        }

        public long getLastSeenMillis() {
            return lastSeenMillis;
        }

        public long getUpdatedAtMillis() {
            return updatedAtMillis;
        }

        public String getCurrentServer() {
            return currentServer == null ? "" : currentServer;
        }

        public boolean hasCurrentServer() {
            return currentServer != null && !currentServer.isBlank();
        }

        public long getCurrentServerStartMillis() {
            return currentServerStartMillis;
        }

        public Map<String, Long> getServerMillis() {
            return serverMillis;
        }
    }

    public static final class SessionResult {

        private final UUID uuid;
        private final long loginMillis;
        private final long logoutMillis;
        private final long durationMillis;
        private final long totalMillis;

        private SessionResult(UUID uuid, long loginMillis, long logoutMillis,
                              long durationMillis, long totalMillis) {
            this.uuid = uuid;
            this.loginMillis = loginMillis;
            this.logoutMillis = logoutMillis;
            this.durationMillis = durationMillis;
            this.totalMillis = totalMillis;
        }

        public UUID getUuid() {
            return uuid;
        }

        public long getLoginMillis() {
            return loginMillis;
        }

        public long getLogoutMillis() {
            return logoutMillis;
        }

        public long getDurationMillis() {
            return durationMillis;
        }

        public long getTotalMillis() {
            return totalMillis;
        }
    }

    public static final class SessionRecord {

        private final long loginMillis;
        private final long logoutMillis;
        private final long durationMillis;

        private SessionRecord(long loginMillis, long logoutMillis, long durationMillis) {
            this.loginMillis = loginMillis;
            this.logoutMillis = logoutMillis;
            this.durationMillis = durationMillis;
        }

        public long getLoginMillis() {
            return loginMillis;
        }

        public long getLogoutMillis() {
            return logoutMillis;
        }

        public long getDurationMillis() {
            return durationMillis;
        }
    }

    private static final class Record {

        private final UUID uuid;
        private String name;
        private long totalMillis;
        private long sessionStartMillis;
        private long lastSeenMillis;
        private long updatedAtMillis;
        private String currentServer;
        private long currentServerStartMillis;
        private final Map<String, Long> serverMillis;

        private Record(UUID uuid, String name, long now) {
            this(uuid, name, 0L, NO_SESSION, now, now, "", NO_SESSION, new HashMap<>());
        }

        private Record(UUID uuid, String name, long totalMillis, long sessionStartMillis,
                       long lastSeenMillis, long updatedAtMillis, String currentServer,
                       long currentServerStartMillis, Map<String, Long> serverMillis) {
            this.uuid = uuid;
            this.name = name;
            this.totalMillis = totalMillis;
            this.sessionStartMillis = sessionStartMillis;
            this.lastSeenMillis = lastSeenMillis;
            this.updatedAtMillis = updatedAtMillis;
            this.currentServer = (currentServer != null && !currentServer.isBlank()) ? currentServer : null;
            this.currentServerStartMillis = currentServerStartMillis;
            this.serverMillis = new HashMap<>(serverMillis);
        }
    }
}
