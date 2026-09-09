package com.kael.playtime;

import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class SmokeTest {

    private SmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("kaelorvynplaytime-test");
        try {
            PlaytimeConfig config = new PlaytimeConfig(dir, LoggerFactory.getLogger(SmokeTest.class)).load();
            PlaytimeStore store = new PlaytimeStore(dir, config, LoggerFactory.getLogger(SmokeTest.class));

            UUID steve = UUID.fromString("6a0b1aa6-4d16-3ecb-9d4a-6a6a6a6a6a6a");
            long now = 1_700_000_000_000L;
            store.startSession(steve, "Steve", now);
            store.serverConnected(steve, "Steve", "Lobby", now);
            store.heartbeat(steve, now + 30_000L);
            store.serverConnected(steve, "Steve", "Survival", now + 60_000L);
            store.heartbeat(steve, now + 90_000L);
            store.endSession(steve, "Steve", now + 120_000L);

            PlaytimeStore.PlayerRecord record = store.findByName("steve", now + 120_000L).orElseThrow();
            assertEquals(120_000L, record.getTotalMillis());
            assertEquals(60_000L, record.getServerMillis().get("Lobby"));
            assertEquals(60_000L, record.getServerMillis().get("Survival"));
            assertFalse(record.isOnline());
            assertEquals(1, store.recentSessions(steve, 10).size());

            UUID crash = UUID.fromString("7b0b1aa6-4d16-3ecb-9d4a-7b7b7b7b7b7b");
            store.startSession(crash, "Crash", now + 200_000L);
            store.serverConnected(crash, "Crash", "Lobby", now + 200_000L);
            store.heartbeat(crash, now + 260_000L);
            PlaytimeStore restarted = new PlaytimeStore(dir, config, LoggerFactory.getLogger(SmokeTest.class));
            PlaytimeStore.PlayerRecord crashRecord = restarted.findByName("crash", now + 300_000L).orElseThrow();
            assertEquals(60_000L, crashRecord.getTotalMillis());
            assertEquals(60_000L, crashRecord.getServerMillis().get("Lobby"));
            assertFalse(crashRecord.isOnline());
            assertEquals(1, restarted.recentSessions(crash, 10).size());

            List<PlaytimeStore.PlayerRecord> top = restarted.top(10, now + 300_000L);
            assertEquals(2, top.size());
            assertEquals("Steve", top.get(0).getName());

            assertEquals("1 小时 0 分", TimeFormat.formatMillis(3_600_000L));
            assertEquals("1 天 2 小时 23 分", TimeFormat.formatMillis(94_980_000L));
            assertEquals("2 分", TimeFormat.formatMillis(120_000L));
            assertEquals("30 秒", TimeFormat.formatMillis(30_000L));
            assertFalse(TimeFormat.formatInstant(now, config.getZoneId()).isBlank());

            System.out.println("SmokeTest OK");
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // 清理失败不影响测试结论
                    }
                });
            }
        }
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            throw new IllegalStateException("期望 " + expected + "，实际 " + actual);
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new IllegalStateException("期望 " + expected + "，实际 " + actual);
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("期望 " + expected + "，实际 " + actual);
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new IllegalStateException("期望为 false");
        }
    }
}
