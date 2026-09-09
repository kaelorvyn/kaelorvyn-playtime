package com.kael.playtime;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Properties;

public final class PlaytimeConfig {

    private final Path dataDirectory;
    private final Logger logger;
    private int saveIntervalSeconds = 60;
    private String timezone = "Asia/Shanghai";

    public PlaytimeConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public PlaytimeConfig load() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.properties");
            if (!Files.exists(file)) {
                Files.writeString(file, defaultConfig(), StandardCharsets.UTF_8);
                logger.info("已创建默认配置：{}", file);
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file);
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            saveIntervalSeconds = Math.max(10,
                    Integer.parseInt(value(properties, "save-interval-seconds", "60")));
            timezone = value(properties, "timezone", "Asia/Shanghai");
        } catch (IOException | NumberFormatException e) {
            logger.warn("读取配置失败，使用默认配置：{}", e.getMessage());
        }
        return this;
    }

    private String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key, fallback);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String defaultConfig() {
        return "save-interval-seconds=60\n"
                + "timezone=Asia/Shanghai\n";
    }

    public int getSaveIntervalSeconds() {
        return saveIntervalSeconds;
    }

    public ZoneId getZoneId() {
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
