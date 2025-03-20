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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FakePlayers extends JavaPlugin implements Listener {

    private Set<String> currentFakePlayers;
    private Map<String, UUID> fakePlayerUUIDs;
    private final Random random = new Random();
    private int maxPlayers;
    private boolean enableChat;
    private ChatAI chatAI;

    // Increase the maximum recent messages to 12 and define message expiration (15 minutes)
    private static final int MAX_RECENT_MESSAGES = 12;
    private static final long MESSAGE_EXPIRATION_MS = 15 * 60 * 1000; // 15 minutes in milliseconds
    private final LinkedList<ChatMessage> recentMessages = new LinkedList<>();

    Map<String, PlayerFakeAllData> fakePlayerData;
    private final Map<String, Long> lastResponseTimes = new ConcurrentHashMap<>();

    // A simple data class to hold chat messages with their timestamp.
    public static class ChatMessage {
        public final long timestamp;
        public final String message;
        public ChatMessage(String message) {
            this.timestamp = System.currentTimeMillis();
            this.message = message;
        }
    }

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
            this.model = model != null ? model : "deepseek/deepseek-r1-distill-llama-70b:free";
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        currentFakePlayers = new HashSet<>();
        fakePlayerUUIDs = new HashMap<>();

        getServer().getPluginManager().registerEvents(this, this);

        // Register tab completer for TPA command - directly set it without checking
        getServer().getPluginCommand("tpa").setTabCompleter(this);
        getLogger().info("Registered TabCompleter for /tpa command");

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
            Map<String, PlayerFakeAllData> defaultPlayers = new HashMap<>();
            defaultPlayers.put("Steve", new PlayerFakeAllData("defaultTexture", "defaultSignature", "caustic", "perfect", "deepseek/deepseek-r1-distill-llama-70b:free"));

            for (Map.Entry<String, PlayerFakeAllData> entry : defaultPlayers.entrySet()) {
                getConfig().set("fake-players." + entry.getKey() + ".texture", entry.getValue().texture);
                getConfig().set("fake-players." + entry.getKey() + ".signature", entry.getValue().signature);
                getConfig().set("fake-players." + entry.getKey() + ".personality", entry.getValue().personality);
                getConfig().set("fake-players." + entry.getKey() + ".text-style", entry.getValue().textStyle);
                getConfig().set("fake-players." + entry.getKey() + ".model", entry.getValue().model);
            }
            getConfig().set("max-players", maxPlayers);
            saveConfig();
        } else {
            for (String name : playersSection.getKeys(false)) {
                String texture = playersSection.getString(name + ".texture");
                String signature = playersSection.getString(name + ".signature");
                String personality = playersSection.getString(name + ".personality", "cynical");
                String textStyle = playersSection.getString(name + ".text-style", "perfect");
                String model = playersSection.getString(name + ".model", "deepseek/deepseek-r1-distill-llama-70b:free");
                if (texture != null && signature != null) {
                    fakePlayerData.put(name, new PlayerFakeAllData(texture, signature, personality, textStyle, model));
                }
            }
        }
    }

    private void scheduleNextUpdate() {
        long delay = random.nextInt(3000, 12000);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            updateFakePlayers();
            scheduleNextUpdate();
        }, delay);
    }

    private void updateFakePlayers() {
        int realCount = Bukkit.getOnlinePlayers().size();
        int fakeCount = currentFakePlayers.size();
        int totalCount = realCount + fakeCount;

        final double occupancyThreshold = 0.85;
        double occupancy = (double) totalCount / maxPlayers;

        if (occupancy >= occupancyThreshold && !currentFakePlayers.isEmpty()) {
            List<String> current = new ArrayList<>(currentFakePlayers);
            String playerToRemove = current.get(random.nextInt(current.size()));
            removeFakePlayer(playerToRemove, true);
            return;
        }

        int randomAction = random.nextInt(100);

        // 35% chance to add a player (0-34)
        if (randomAction < 35) {
            if (fakeCount < fakePlayerData.size() && 
                    ((double) (totalCount + 1) / maxPlayers) < occupancyThreshold) {
                List<String> availablePlayers = new ArrayList<>(fakePlayerData.keySet());
                availablePlayers.removeAll(currentFakePlayers);
                if (!availablePlayers.isEmpty()) {
                    String randomName = availablePlayers.get(random.nextInt(availablePlayers.size()));
                    addFakePlayer(randomName);
                }
            }
        } 
        // 38% chance to remove a player (35-72)
        else if (randomAction < 73) {
            if (!currentFakePlayers.isEmpty()) {
                List<String> current = new ArrayList<>(currentFakePlayers);
                String playerToRemove = current.get(random.nextInt(current.size()));
                removeFakePlayer(playerToRemove, true);
            }
        }
        // 27% chance to do nothing (73-99)
        // No action needed here
    }

    // Adds a new chat message and cleans up old ones
    private void addRecentMessage(String message) {
        recentMessages.add(new ChatMessage(message));
        cleanupOldMessages();
        while (recentMessages.size() > MAX_RECENT_MESSAGES) {
            recentMessages.poll();
        }
    }

    // Removes messages older than MESSAGE_EXPIRATION_MS.
    private void cleanupOldMessages() {
        long now = System.currentTimeMillis();
        while (!recentMessages.isEmpty() && now - recentMessages.peek().timestamp > MESSAGE_EXPIRATION_MS) {
            recentMessages.poll();
        }
    }

    private void addFakePlayer(String name) {
        UUID uuid = UUID.randomUUID();
        currentFakePlayers.add(name);
        fakePlayerUUIDs.put(name, uuid);

        String joinAnnouncement = name + " joined the game";
        Bukkit.broadcast(
                Component.text(joinAnnouncement).color(NamedTextColor.YELLOW)
        );
        addRecentMessage(joinAnnouncement);

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
            String leaveAnnouncement = name + " left the game";
            Bukkit.broadcast(
                    Component.text(leaveAnnouncement).color(NamedTextColor.YELLOW)
            );
            addRecentMessage(leaveAnnouncement);
        }

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

            int latency = random.nextInt(4) > 0
                    ? random.nextInt(150)
                    : random.nextInt(150, 300);

            ClientboundPlayerInfoUpdatePacket infoPacket = new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(
                            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY
                    ),
                    List.of(new ClientboundPlayerInfoUpdatePacket.Entry(
                            uuid,
                            profile,
                            true,
                            latency,
                            GameType.SURVIVAL,
                            null,
                            false,
                            0,
                            null
                    ))
            );

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
        String joinMsg = joining.getName() + " joined the game";
        addRecentMessage(joinMsg);
        for (String fakeName : currentFakePlayers) {
            UUID uuid = fakePlayerUUIDs.get(fakeName);
            if (uuid != null) {
                sendFakePlayerAdd(joining, fakeName, uuid);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player leaving = event.getPlayer();
        String quitMsg = leaving.getName() + " left the game";
        addRecentMessage(quitMsg);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Component deathMessageComponent = event.deathMessage();
        if (deathMessageComponent != null) {
            String deathMessage = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(deathMessageComponent);
            addRecentMessage(deathMessage);
        }
    }

    @EventHandler
    public void onPaperServerListPing(PaperServerListPingEvent event) {
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

    private void handleBotResponse(String speaker, String response) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastResponseTimes.get(speaker);
        if (lastTime != null && currentTime - lastTime < 15000) { // 15 seconds
            return;
        }
        lastResponseTimes.put(speaker, currentTime);

        if (response != null && !response.trim().isEmpty()) {
            String cleanResponse = response
                    .replaceAll("(?i)<[^>]*>", "")
                    .replaceAll("(?i)\\*?" + speaker + "\\*?:\\s*", "")
                    .replaceAll("^[\"']|[\"']$", "")
                    .replaceAll("\n.*", "")
                    .replaceAll("\"\"", "")
                    .replaceAll("[^\\p{ASCII}]", "")
                    .trim();

            if (!cleanResponse.isEmpty() && cleanResponse.length() <= 240) {
                final String finalResponse = cleanResponse;

                int baseDelay = (int)(finalResponse.length() * (60.0/250.0) * 20); // Convert to ticks
                int randomVariation = random.nextInt(Math.max(1, baseDelay/4));
                int totalDelay = baseDelay + randomVariation;

                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (currentFakePlayers.contains(speaker)) {
                        String botMessage = String.format("<%s> %s", speaker, finalResponse);
                        addRecentMessage(botMessage);
                        Bukkit.broadcast(
                                Component.text("<")
                                        .append(Component.text(speaker).color(NamedTextColor.WHITE))
                                        .append(Component.text("> "))
                                        .append(Component.text(finalResponse))
                        );
                    }
                }, totalDelay);
            }
        }
    }

    private void scheduleRandomChat() {
        if (!enableChat || chatAI == null) {
            return;
        }

        long delay = random.nextInt(5000, 40000);
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            if (!currentFakePlayers.isEmpty() && random.nextDouble() > 0.65) {
                List<String> players = new ArrayList<>(currentFakePlayers);
                String speaker = players.get(random.nextInt(players.size()));
                handleBotResponse(speaker, chatAI.generateResponse(speaker, recentMessages));
            }
            scheduleRandomChat();
        }, delay);
    }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        if (!enableChat || chatAI == null || event.isCancelled()) return;

        String message = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
        String playerName = event.getPlayer().getName();

        String formattedMessage = String.format("<%s> %s", playerName, message);
        addRecentMessage(formattedMessage);

        if (random.nextDouble() > 0.85 && !currentFakePlayers.isEmpty()) {
            List<String> players = new ArrayList<>(currentFakePlayers);
            String speaker = players.get(random.nextInt(players.size()));

            Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
                handleBotResponse(speaker, chatAI.generateResponse(speaker, recentMessages));
            }, 20L + random.nextInt(40));
            return;
        }

        // Check if the message contains a fake player's name.
        String lowerMessage = message.toLowerCase();
        for (String fakeName : currentFakePlayers) {
            if (lowerMessage.contains(fakeName.toLowerCase())) {
                // Only respond with a 30% chance even if the name is included.
                if (random.nextDouble() > 0.7) {
                    Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
                        handleBotResponse(fakeName, chatAI.generateResponse(fakeName, recentMessages));
                    }, 20L + random.nextInt(40));
                }
                // Whether responding or not, return here so we don't trigger additional responses.
                return;
            }
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String lowerMessage = message.toLowerCase();

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
        else if (lowerMessage.startsWith("/msg ") || lowerMessage.startsWith("/tell ") || lowerMessage.startsWith("/w ")) {
            String[] parts = message.split(" ", 3);
            if (parts.length >= 3) {
                String targetName = parts[1];
                String whisperContent = parts[2];

                if (currentFakePlayers.contains(targetName)) {
                    event.setCancelled(true);

                    Component whisperFeedback = Component.text()
                            .content("You whisper to " + targetName + ": " + whisperContent)
                            .color(NamedTextColor.GRAY)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, true)
                            .build();
                    
                    event.getPlayer().sendMessage(whisperFeedback);
                    // No AI response - just let the message appear to be sent for now.
                }
            }
        }
        else if (lowerMessage.startsWith("/tpa ")) {
            String[] parts = message.split(" ", 2);
            if (parts.length >= 2) {
                String targetName = parts[1];
                
                // Case-insensitive check for fake players
                String fakePlayer = findFakePlayerIgnoreCase(targetName);
                
                if (fakePlayer != null) {
                    event.setCancelled(true);
                    Player player = event.getPlayer();
                    
                    // Calculate fake timeout similar to SimpleTPA
                    int timeoutSeconds = 120; // Default to 2 minutes like in SimpleTPA
                    int minutes = timeoutSeconds / 60;
                    int seconds = timeoutSeconds % 60;
                    String timeoutDisplay = minutes > 0 ? minutes + " minute" + (minutes > 1 ? "s" : "") : "";
                    if (seconds > 0) {
                        if (!timeoutDisplay.isEmpty()) timeoutDisplay += " and ";
                        timeoutDisplay += seconds + " second" + (seconds > 1 ? "s" : "");
                    }
                    
                    // Send fake request messages - use the correctly cased name
                    player.sendMessage(Component.text("Teleport request sent to " + fakePlayer + ".").color(NamedTextColor.GREEN));
                    player.sendMessage(Component.text("This request will expire in " + timeoutDisplay + ".").color(NamedTextColor.YELLOW));
                    
                    // Schedule a fake expiration after the timeout
                    final String finalFakePlayer = fakePlayer;
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(Component.text("Your teleport request to " + finalFakePlayer + " has expired.").color(NamedTextColor.RED));
                        }
                    }, timeoutSeconds * 20L); // Convert to ticks
                }
            }
        }
    }

    // Helper method to find a fake player ignoring case
    private String findFakePlayerIgnoreCase(String name) {
        String lowerName = name.toLowerCase();
        for (String fakePlayer : currentFakePlayers) {
            if (fakePlayer.toLowerCase().equals(lowerName)) {
                return fakePlayer;
            }
        }
        return null;
    }

    // Implement tab completion for commands
    @Override
    public List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("tpa") && args.length == 1) {
            String partialName = args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>();

            // Add real players to suggestions
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partialName)) {
                    suggestions.add(player.getName());
                }
            }

            // Add fake players to suggestions
            for (String fakeName : currentFakePlayers) {
                if (fakeName.toLowerCase().startsWith(partialName)) {
                    suggestions.add(fakeName);
                }
            }

            // Sort alphabetically (standard tab completion behavior)
            Collections.sort(suggestions);

            return suggestions;
        }

        // Return null for default behavior in other cases
        return null;
    }
}
