package com.jellypudding.fakePlayers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.util.*;

public class FakePlayers extends JavaPlugin implements Listener {

    private Map<String, PlayerSkinData> fakePlayerData;
    private Set<String> currentFakePlayers;
    private Map<String, UUID> fakePlayerUUIDs;
    private final Random random = new Random();
    private int maxPlayers;

    private static class PlayerSkinData {
        String texture;
        String signature;
        PlayerSkinData(String texture, String signature) {
            this.texture = texture;
            this.signature = signature;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        currentFakePlayers = new HashSet<>();
        fakePlayerUUIDs = new HashMap<>();

        getServer().getPluginManager().registerEvents(this, this);
        scheduleNextUpdate();

        getLogger().info("FakePlayers enabled");
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
        fakePlayerData = new HashMap<>();
        maxPlayers = getConfig().getInt("max-players", 69);

        ConfigurationSection playersSection = getConfig().getConfigurationSection("fake-players");
        if (playersSection == null) {
            // Create default config if none exists
            Map<String, PlayerSkinData> defaultPlayers = new HashMap<>();
            defaultPlayers.put("Steve", new PlayerSkinData("defaultTexture", "defaultSignature"));

            for (Map.Entry<String, PlayerSkinData> entry : defaultPlayers.entrySet()) {
                getConfig().set("fake-players." + entry.getKey() + ".texture", entry.getValue().texture);
                getConfig().set("fake-players." + entry.getKey() + ".signature", entry.getValue().signature);
            }
            getConfig().set("max-players", maxPlayers);
            saveConfig();
        } else {
            // Load existing config
            for (String name : playersSection.getKeys(false)) {
                String texture = playersSection.getString(name + ".texture");
                String signature = playersSection.getString(name + ".signature");
                if (texture != null && signature != null) {
                    fakePlayerData.put(name, new PlayerSkinData(texture, signature));
                }
            }
        }
    }

    private void scheduleNextUpdate() {
        long delay = random.nextInt(3000, 5000);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            updateFakePlayers();
            scheduleNextUpdate();
        }, delay);
    }

    private void updateFakePlayers() {
        int realCount = Bukkit.getOnlinePlayers().size();
        int fakeCount = currentFakePlayers.size();
        int totalCount = realCount + fakeCount;

        // Define an occupancy threshold as a percentage of max players.
        final double occupancyThreshold = 0.90; // 90%
        double occupancy = (double) totalCount / maxPlayers;

        // If occupancy is at or above the threshold and there is at least one fake player,
        // remove a fake player.
        if (occupancy >= occupancyThreshold && !currentFakePlayers.isEmpty()) {
            List<String> current = new ArrayList<>(currentFakePlayers);
            String playerToRemove = current.get(random.nextInt(current.size()));
            removeFakePlayer(playerToRemove, true);
            return;
        }

        // Otherwise, proceed with the normal random add/remove logic.
        boolean doAdd = random.nextBoolean();
        boolean doRemove = random.nextBoolean();

        // Only add a fake player if doing so would keep occupancy below the threshold.
        if (doAdd && fakeCount < fakePlayerData.size() && ((double) (totalCount + 1) / maxPlayers) < occupancyThreshold) {
            List<String> availablePlayers = new ArrayList<>(fakePlayerData.keySet());
            availablePlayers.removeAll(currentFakePlayers);
            if (!availablePlayers.isEmpty()) {
                String randomName = availablePlayers.get(random.nextInt(availablePlayers.size()));
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

        // Broadcast join message to all players (using Adventure Components)
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

        // Clean up from server's player list if any players are online.
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            Player anyPlayer = Bukkit.getOnlinePlayers().iterator().next();
            net.minecraft.server.MinecraftServer server = ((CraftPlayer) anyPlayer).getHandle().getServer();
            if (server != null) {
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

            PlayerSkinData skinData = fakePlayerData.get(name);
            if (skinData != null) {
                profile.getProperties().put("textures", new Property("textures", skinData.texture, skinData.signature));
            }

            // Make it about 75% chance of 5 bars (0-149ms) and 25% chance of 4 bars (150-299ms)
            int latency = random.nextInt(4) > 0 ?
                    random.nextInt(150) :
                    random.nextInt(150, 300);

            // Create a player info packet with multiple actions (add, update listed, update latency).
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
                            latency, // latency (randomized)
                            GameType.SURVIVAL,
                            null,    // displayName
                            false,   // showHat
                            0,       // listOrder
                            null     // chatSession
                    ))
            );

            // Send the packet to the receiver without adding a real ServerPlayer.
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
        // When a real player joins, send them all fake players so they see the correct tab list.
        for (String fakeName : currentFakePlayers) {
            UUID uuid = fakePlayerUUIDs.get(fakeName);
            if (uuid != null) {
                sendFakePlayerAdd(joining, fakeName, uuid);
            }
        }
    }

    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
        // In the server list ping, ensure that the displayed total never exceeds maxPlayers.
        int realCount = Bukkit.getOnlinePlayers().size();
        int allowedFake = Math.max(0, maxPlayers - realCount);

        // Build a list of fake players limited to allowedFake.
        List<PaperServerListPingEvent.ListedPlayerInfo> fakeList = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, UUID> entry : fakePlayerUUIDs.entrySet()) {
            if (count < allowedFake) {
                fakeList.add(new PaperServerListPingEvent.ListedPlayerInfo(entry.getKey(), entry.getValue()));
                count++;
            } else {
                break;
            }
        }

        // Set the displayed player count to real players plus the allowed fake ones.
        event.setNumPlayers(realCount + fakeList.size());
        event.getListedPlayers().clear();
        event.getListedPlayers().addAll(fakeList);
        event.setMaxPlayers(maxPlayers);
    }

    // Intercept certain commands; now including /list to show fake players in a formatted message.
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String lowerMessage = message.toLowerCase();

        // Only block /plugins, /pl, /help, or /? if the player does NOT have the permission "fakeplayers.showinfo"
        if ((lowerMessage.startsWith("/plugins") || lowerMessage.startsWith("/pl") ||
                lowerMessage.startsWith("/help") || lowerMessage.startsWith("/?"))
                && !event.getPlayer().hasPermission("fakeplayers.showinfo")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("This command is currently unavailable."));
            return;
        }

        // Intercept /list command to add fake players into the output.
        if (lowerMessage.startsWith("/list")) {
            event.setCancelled(true);
            int realCount = Bukkit.getOnlinePlayers().size();
            int fakeCount = currentFakePlayers.size();
            int totalCount = realCount + fakeCount;

            // Build a list of Components for player names.
            List<Component> nameComponents = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Use the player's display name (this preserves any colour formatting).
                nameComponents.add(player.displayName());
            }
            for (String fakeName : currentFakePlayers) {
                // Fake players are explicitly set to white.
                nameComponents.add(Component.text(fakeName).color(NamedTextColor.WHITE));
            }

            // Join the names with a comma and space separator using the new JoinConfiguration method.
            Component namesJoined = Component.join(JoinConfiguration.separator(Component.text(", ")), nameComponents);

            Component finalMessage = Component.text("There are ")
                    .append(Component.text(totalCount).color(NamedTextColor.WHITE))
                    .append(Component.text(" of a max of "))
                    .append(Component.text(maxPlayers).color(NamedTextColor.WHITE))
                    .append(Component.text(" players online: "))
                    .append(namesJoined);

            event.getPlayer().sendMessage(finalMessage);
        }
    }
}
