package com.kael.playtime;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;

public final class PlaytimeListener {

    private final PlaytimeStore store;

    public PlaytimeListener(PlaytimeStore store) {
        this.store = store;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        store.startSession(player.getUniqueId(), player.getUsername(), System.currentTimeMillis());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String server = event.getServer().getServerInfo().getName();
        store.serverConnected(player.getUniqueId(), player.getUsername(), server, System.currentTimeMillis());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            return;
        }
        Player player = event.getPlayer();
        store.endSession(player.getUniqueId(), player.getUsername(), System.currentTimeMillis());
    }
}
