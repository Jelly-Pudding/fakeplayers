package com.jellypudding.fakePlayers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.server.level.ClientInformation;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;

import com.mojang.authlib.GameProfile;

import java.util.*;

public class FakePlayers extends JavaPlugin implements Listener {

    private List<String> fakePlayerNames;
    private Set<String> currentFakePlayers;
    private Map<String, UUID> fakePlayerUUIDs;
    private final Random random = new Random();
    private int maxPlayers;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        currentFakePlayers = new HashSet<>();
        fakePlayerUUIDs = new HashMap<>();

        getServer().getPluginManager().registerEvents(this, this);
        scheduleNextUpdate();

        getLogger().info("FakePlayers enabled - current fake players: " + currentFakePlayers.size());
    }

    @Override
    public void onDisable() {
        for (String name : new ArrayList<>(currentFakePlayers)) {
            removeFakePlayer(name, false);
        }
        currentFakePlayers.clear();
        fakePlayerUUIDs.clear();
    }

    private void loadConfig() {
        fakePlayerNames = getConfig().getStringList("fake-players");
        maxPlayers = getConfig().getInt("max-players", 69);

        if (fakePlayerNames.isEmpty()) {
            fakePlayerNames = new ArrayList<>();
            fakePlayerNames.add("Steve");
            fakePlayerNames.add("Alex");
            fakePlayerNames.add("Notch");

            getConfig().set("fake-players", fakePlayerNames);
            getConfig().set("max-players", maxPlayers);
            saveConfig();
        }
    }

    private void scheduleNextUpdate() {
        long delay = random.nextInt(300, 601);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            updateFakePlayers();
            getLogger().info("Updated fake players - current count: " + currentFakePlayers.size());
            scheduleNextUpdate();
        }, delay);
    }

    private void updateFakePlayers() {
        boolean doAdd = random.nextBoolean();
        boolean doRemove = random.nextBoolean();

        if (doAdd && currentFakePlayers.size() < fakePlayerNames.size()) {
            String randomName = fakePlayerNames.get(random.nextInt(fakePlayerNames.size()));
            if (!currentFakePlayers.contains(randomName)) {
                addFakePlayer(randomName);
            }
        } else if (doRemove && !currentFakePlayers.isEmpty()) {
            List<String> current = new ArrayList<>(currentFakePlayers);
            String playerToRemove = current.get(random.nextInt(current.size()));
            removeFakePlayer(playerToRemove, true);
        }
    }

    private void addFakePlayer(String name) {
        UUID uuid = UUID.randomUUID();
        currentFakePlayers.add(name);
        fakePlayerUUIDs.put(name, uuid);

        Bukkit.broadcast(
                Component.text(name)
                        .append(Component.text(" joined the game").color(NamedTextColor.YELLOW))
        );

        for (Player realPlayer : Bukkit.getOnlinePlayers()) {
            sendFakePlayerAdd(realPlayer, name, uuid);
        }
    }

    private void removeFakePlayer(String name, boolean broadcastLeave) {
        if (!currentFakePlayers.contains(name)) {
            return;
        }
        currentFakePlayers.remove(name);
        UUID uuid = fakePlayerUUIDs.remove(name);

        if (broadcastLeave) {
            Bukkit.broadcast(
                    Component.text(name)
                            .append(Component.text(" left the game").color(NamedTextColor.YELLOW))
            );
        }

        // Clean up from server's player list if any players are online
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            Player anyPlayer = Bukkit.getOnlinePlayers().iterator().next();
            net.minecraft.server.MinecraftServer server = ((CraftPlayer)anyPlayer).getHandle().getServer();
            if (server != null) {  // Only check server for null
                server.getPlayerList().getPlayers().removeIf(player -> player.getUUID().equals(uuid));
            }
        }

        for (Player realPlayer : Bukkit.getOnlinePlayers()) {
            sendFakePlayerRemove(realPlayer, uuid);
        }
    }

    private void sendFakePlayerAdd(Player receiver, String name, UUID uuid) {
        try {
            CraftPlayer craftPlayer = (CraftPlayer) receiver;
            if (craftPlayer.getHandle() == null) return;

            net.minecraft.server.MinecraftServer server = craftPlayer.getHandle().getServer();
            if (server == null) return;

            net.minecraft.server.level.ServerLevel level = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return;

            GameProfile profile = new GameProfile(uuid, name);

            // Player info packet with multiple actions
            ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(
                            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY
                    ),
                    List.of(new ClientboundPlayerInfoUpdatePacket.Entry(
                            uuid,
                            profile,
                            true,    // listed
                            0,       // latency
                            GameType.SURVIVAL,
                            null,    // displayName
                            false,   // showHat
                            0,       // listOrder
                            null     // chatSession
                    ))
            );

            // Instead of creating a real ServerPlayer, we'll just send the packets
            craftPlayer.getHandle().connection.send(infoPacket);
        } catch (Exception e) {
            getLogger().severe("Error adding fake player " + name + ": " + e.getMessage());
            if (e.getCause() != null) {
                getLogger().severe("Caused by: " + e.getCause().getMessage());
            }
        }
    }

    private void sendFakePlayerRemove(Player receiver, UUID uuid) {
        try {
            CraftPlayer craftPlayer = (CraftPlayer) receiver;

            ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(
                    Collections.singletonList(uuid)
            );

            craftPlayer.getHandle().connection.send(packet);
        } catch (Exception e) {
            getLogger().severe("Error removing fake player with UUID " + uuid + ": " + e.getMessage());
            if (e.getCause() != null) {
                getLogger().severe("Caused by: " + e.getCause().getMessage());
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        for (String fakeName : currentFakePlayers) {
            UUID uuid = fakePlayerUUIDs.get(fakeName);
            if (uuid != null) {
                sendFakePlayerAdd(joining, fakeName, uuid);
            }
        }
    }

    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
        for (Map.Entry<String, UUID> entry : fakePlayerUUIDs.entrySet()) {
            event.getListedPlayers().add(
                    new PaperServerListPingEvent.ListedPlayerInfo(
                            entry.getKey(),
                            entry.getValue()
                    )
            );
        }
        event.setNumPlayers(event.getNumPlayers() + currentFakePlayers.size());
        event.setMaxPlayers(maxPlayers);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().toLowerCase();
        if (cmd.startsWith("/plugins") || cmd.startsWith("/pl") ||
                cmd.startsWith("/help") || cmd.startsWith("/?")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Unknown command. Type \"/help\" for help.");
        }
    }
}