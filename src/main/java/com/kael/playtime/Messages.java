package com.kael.playtime;

import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Messages {

    public static final String PREFIX = "§8[§6KaelorvynPlaytime§8] §7";

    private Messages() {
    }

    public static Component component(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    public static void send(CommandSource source, String legacy) {
        source.sendMessage(component(legacy));
    }
}
