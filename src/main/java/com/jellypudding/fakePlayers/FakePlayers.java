package com.jellypudding.fakePlayers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;

import io.papermc.paper.event.player.AsyncChatEvent;

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

    private Set<String> currentFakePlayers;
    private Map<String, UUID> fakePlayerUUIDs;
    private final Random random = new Random();
    private int maxPlayers;
    private boolean enableChat;
    private ChatAI chatAI;
    private final Queue<String> recentMessages = new LinkedList<>();
    private static final int MAX_RECENT_MESSAGES = 8;
    Map<String, PlayerFakeAllData> fakePlayerData;

    public static class PlayerFakeAllData {
        String texture;
        String signature;
        String personality;
        String textStyle;
        String model;

        PlayerFakeAllData(String texture, String signature, String personality, String textStyle, String model) {
            this.texture = texture;
            this.signature = signature;
            this.personality = personality != null ? personality : "sarcastic and insulting";
            this.textStyle = textStyle != null ? textStyle : "normal";
            this.model = model != null ? model : "deepseek/deepseek-r1:free";
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        currentFakePlayers = new HashSet<>();
        fakePlayerUUIDs = new HashMap<>();

        getServer().getPluginManager().registerEvents(this, this);

        // Schedules both your random add/remove AND random chat tasks
        scheduleNextUpdate();
        scheduleRandomChat();

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
        enableChat = getConfig().getBoolean("enable-chat", false);

        if (enableChat) {
            String apiKey = getConfig().getString("openrouter-api-key", "");
            if (!apiKey.isEmpty()) {
                chatAI = new ChatAI(apiKey, getLogger(), fakePlayerData);
            } else {
                getLogger().warning("Chat is enabled but no OpenRouter API key provided!");
                enableChat = false;
            }
        }

        ConfigurationSection playersSection = getConfig().getConfigurationSection("fake-players");
        if (playersSection == null) {
            // Create default config if none exists
            Map<String, PlayerFakeAllData> defaultPlayers = new HashMap<>();
            defaultPlayers.put("Steve", new PlayerFakeAllData("defaultTexture", "defaultSignature", "caustic", "perfect", "deepseek/deepseek-r1:free"));

            for (Map.Entry<String, PlayerFakeAllData> entry : defaultPlayers.entrySet()) {
                getConfig().set("fake-players." + entry.getKey() + ".texture", entry.getValue().texture);
                getConfig().set("fake-players." + entry.getKey() + ".signature", entry.getValue().signature);
                getConfig().set("fake-players." + entry.getKey() + ".personality", entry.getValue().personality);
                getConfig().set("fake-players." + entry.getKey() + ".text-style", entry.getValue().textStyle);
            }
            getConfig().set("max-players", maxPlayers);
            saveConfig();
        } else {
            // Load existing config
            for (String name : playersSection.getKeys(false)) {
                String texture = playersSection.getString(name + ".texture");
                String signature = playersSection.getString(name + ".signature");
                String personality = playersSection.getString(name + ".personality", "cynical");
                String textStyle = playersSection.getString(name + ".text-style", "perfect");
                String model = playersSection.getString(name + ".model", "deepseek/deepseek-r1:free");
                if (texture != null && signature != null) {
                    fakePlayerData.put(name, new PlayerFakeAllData(texture, signature, personality, textStyle, model));
                }
            }
        }
    }

    private void scheduleNextUpdate() {
        long delay = random.nextInt(3000, 6000);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            updateFakePlayers();
            scheduleNextUpdate();
        }, delay);
    }

    private void updateFakePlayers() {
        int realCount = Bukkit.getOnlinePlayers().size();
        int fakeCount = currentFakePlayers.size();
        int totalCount = realCount + fakeCount;

        final double occupancyThreshold = 0.90; // 90% of maxPlayers
        double occupancy = (double) totalCount / maxPlayers;

        // If occupancy >= threshold and there's at least one fake player, remove a fake player
        if (occupancy >= occupancyThreshold && !currentFakePlayers.isEmpty()) {
            List<String> current = new ArrayList<>(currentFakePlayers);
            String playerToRemove = current.get(random.nextInt(current.size()));
            removeFakePlayer(playerToRemove, true);
            return;
        }

        boolean doAdd = random.nextDouble() < 0.48;
        boolean doRemove = random.nextDouble() < 0.52;

        if (doAdd && fakeCount < fakePlayerData.size() &&
                ((double) (totalCount + 1) / maxPlayers) < occupancyThreshold) {
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

        // Broadcast join message to all players
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

            PlayerFakeAllData skinData = fakePlayerData.get(name);
            if (skinData != null) {
                profile.getProperties().put(
                        "textures",
                        new Property("textures", skinData.texture, skinData.signature)
                );
            }

            // Random latency
            int latency = random.nextInt(4) > 0
                    ? random.nextInt(150)
                    : random.nextInt(150, 300);

            // Create a player info packet
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
                            latency, // latency
                            GameType.SURVIVAL,
                            null,
                            false,
                            0,
                            null
                    ))
            );

            // Send the packet
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
        // Send all fake players to the newly joined real player
        for (String fakeName : currentFakePlayers) {
            UUID uuid = fakePlayerUUIDs.get(fakeName);
            if (uuid != null) {
                sendFakePlayerAdd(joining, fakeName, uuid);
            }
        }
    }

    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
        // Keep displayed total from exceeding maxPlayers
        int realCount = Bukkit.getOnlinePlayers().size();
        int allowedFake = Math.max(0, maxPlayers - realCount);

        List<PaperServerListPingEvent.ListedPlayerInfo> fakeList = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, UUID> entry : fakePlayerUUIDs.entrySet()) {
            if (count < allowedFake) {
                fakeList.add(
                        new PaperServerListPingEvent.ListedPlayerInfo(
                                entry.getKey(),
                                entry.getValue()
                        )
                );
                count++;
            } else {
                break;
            }
        }

        event.setNumPlayers(realCount + fakeList.size());
        event.getListedPlayers().clear();
        event.getListedPlayers().addAll(fakeList);
        event.setMaxPlayers(maxPlayers);
    }

    private void scheduleRandomChat() {
        // Only bail out entirely if chat is disabled or ChatAI is null
        if (!enableChat || chatAI == null) {
            return;
        }

        // We always schedule the next check, even if no fake players are currently online.
        long delay = random.nextInt(3000, 7000);
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            // If we have at least one fake player, pick one at random to speak
            if (!currentFakePlayers.isEmpty() && random.nextDouble() > 0.3) {
                List<String> players = new ArrayList<>(currentFakePlayers);
                String speaker = players.get(random.nextInt(players.size()));

                String response = chatAI.generateResponse(speaker, recentMessages);
                if (response != null && !response.trim().isEmpty()) {
                    String cleanResponse = response
                            .replaceAll("(?i)\\*?" + speaker + "\\*?:\\s*", "")
                            // Remove any leading or trailing quotes, single or double
                            .replaceAll("^['\"]+", "")
                            .replaceAll("['\"]+$", "")
                            .replaceAll("\n.*", "")
                            .replaceAll("[^\\p{ASCII}]", "")
                            .trim();
                    if (!cleanResponse.isEmpty()) {
                        final String finalResponse = cleanResponse;
                        Bukkit.getScheduler().runTask(this, () -> {
                            // Check if that fake player still exists
                            if (currentFakePlayers.contains(speaker)) {
                                String formattedMessage = String.format("<%s> %s", speaker, finalResponse);
                                recentMessages.add(formattedMessage);

                                // Keep recentMessages from growing too large
                                while (recentMessages.size() > MAX_RECENT_MESSAGES) {
                                    recentMessages.poll();
                                }

                                // Broadcast in chat
                                Bukkit.broadcast(
                                        Component.text("<")
                                                .append(Component.text(speaker).color(NamedTextColor.WHITE))
                                                .append(Component.text("> "))
                                                .append(Component.text(finalResponse))
                                );
                            }
                        });
                    }
                }
            }

            scheduleRandomChat();
        }, delay);
    }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        if (!enableChat || chatAI == null) return;

        String message = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
        String playerName = event.getPlayer().getName();

        // Save recent message
        String formattedMessage = String.format("<%s> %s", playerName, message);
        recentMessages.add(formattedMessage);
        while (recentMessages.size() > MAX_RECENT_MESSAGES) {
            recentMessages.poll();
        }

        // Check if the message mentions any fake player's name
        String lowerMessage = message.toLowerCase();
        for (String fakeName : currentFakePlayers) {
            if (lowerMessage.contains(fakeName.toLowerCase())) {
                // Wait 1-3 seconds before responding
                Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
                    String response = chatAI.generateResponse(fakeName, recentMessages);
                    if (response != null && !response.trim().isEmpty()) {
                        String cleanResponse = response
                                .replaceAll("(?i)\\*?" + fakeName + "\\*?:\\s*", "")
                                .replaceAll("^\"(.+)\"$", "$1")
                                .replaceAll("\n.*", "")
                                .replaceAll("[^\\p{ASCII}]", "")
                                .trim();

                        if (!cleanResponse.isEmpty()) {
                            final String finalResponse = cleanResponse;
                            Bukkit.getScheduler().runTask(this, () -> {
                                if (currentFakePlayers.contains(fakeName)) {
                                    // Add bot's message to recent messages
                                    String botMessage = String.format("<%s> %s", fakeName, finalResponse);
                                    recentMessages.add(botMessage);
                                    while (recentMessages.size() > MAX_RECENT_MESSAGES) {
                                        recentMessages.poll();
                                    }

                                    Bukkit.broadcast(
                                            Component.text("<")
                                                    .append(Component.text(fakeName).color(NamedTextColor.WHITE))
                                                    .append(Component.text("> "))
                                                    .append(Component.text(finalResponse))
                                    );
                                }
                            });
                        }
                    }
                }, 20L + random.nextInt(40));
            }
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String lowerMessage = message.toLowerCase();

        // Block commands like /plugins, /pl, etc. if no permission
        if ((lowerMessage.startsWith("/plugins") || lowerMessage.startsWith("/pl") ||
                lowerMessage.startsWith("/help")    || lowerMessage.startsWith("/?"))
                && !event.getPlayer().hasPermission("fakeplayers.showinfo")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("This command is currently unavailable."));
            return;
        }

        // Intercept /list to show fake players
        if (lowerMessage.startsWith("/list")) {
            event.setCancelled(true);
            int realCount = Bukkit.getOnlinePlayers().size();
            int fakeCount = currentFakePlayers.size();
            int totalCount = realCount + fakeCount;

            List<Component> nameComponents = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                nameComponents.add(player.displayName());
            }
            for (String fakeName : currentFakePlayers) {
                nameComponents.add(Component.text(fakeName).color(NamedTextColor.WHITE));
            }

            Component namesJoined = Component.join(
                    JoinConfiguration.separator(Component.text(", ")),
                    nameComponents
            );

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
