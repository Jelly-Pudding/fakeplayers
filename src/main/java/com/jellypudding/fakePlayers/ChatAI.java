package com.jellypudding.fakePlayers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Logger;

public class ChatAI {
    private final String apiKey;
    private final HttpClient client;
    private final Gson gson;
    private final Logger logger;
    private final Map<String, FakePlayers.PlayerFakeAllData> fakePlayerData;
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";

    public ChatAI(String apiKey, Logger logger, Map<String, FakePlayers.PlayerFakeAllData> fakePlayerData) {
        this.apiKey = apiKey;
        this.client = HttpClient.newHttpClient();
        this.gson = new Gson();
        this.logger = logger;
        this.fakePlayerData = fakePlayerData;
    }

    public String generateResponse(String playerName, Queue<String> recentMessages) {
        String context = String.join("\n", recentMessages);
        FakePlayers.PlayerFakeAllData playerData = fakePlayerData.get(playerName);

        // Log recent messages
        logger.info("Recent messages for " + playerName + ":");
        logger.info(context);

        if (playerData == null) {
            logger.warning("No data found for player: " + playerName);
            return null;
        }

        // 20% chance for off-topic comment
        boolean makeOffTopic = Math.random() < 0.20;

        String prompt = makeOffTopic ?
                String.format(
                        "You are %s, an in-game player. Generate ONE off-topic comment in 1-2 short sentences that a player might randomly say. " +
                                "You have a %s personality. Write your response in a %s style - this affects your spelling, grammar, and punctuation. " +
                                "Topics can include: politics, life events, in-game players, random thoughts. " +
                                "Do not include your name or any chat formatting in the response. Just write the message you want to say.",
                        playerName, playerData.personality, playerData.textStyle
                ) :
                String.format(
                        "You are %s, an in-game Minecraft player. Here's the recent chat history:\n\n%s\n\n" +
                                "Generate ONE response in 1-2 short sentences. You have to appear human. " +
                                "You have a %s personality. Write your response in a %s style - this affects your spelling, grammar, and punctuation. " +
                                "Do not include your name or any chat formatting in the response. " +
                                "Just write the message you want to say.",
                        playerName, context, playerData.personality, playerData.textStyle
                );

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", "deepseek/deepseek-r1:free",
                    "messages", List.of(
                            Map.of(
                                    "role", "user",
                                    "content", prompt
                            )
                    )
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                try {
                    JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                    if (jsonResponse == null) {
                        logger.warning("ChatAI: JSON response is null despite status 200.");
                        return null;
                    }
                    if (!jsonResponse.has("choices")) {
                        logger.warning("ChatAI: 'choices' field missing in JSON response.");
                        return null;
                    }

                    JsonArray choices = jsonResponse.getAsJsonArray("choices");
                    if (choices.isEmpty()) {
                        logger.warning("ChatAI: 'choices' array is empty in JSON response.");
                        return null;
                    }

                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    if (firstChoice == null || !firstChoice.has("message")) {
                        logger.warning("ChatAI: 'message' object missing in first choice.");
                        return null;
                    }

                    JsonObject message = firstChoice.getAsJsonObject("message");
                    if (message == null || !message.has("content")) {
                        logger.warning("ChatAI: 'content' missing in 'message' object.");
                        return null;
                    }

                    return message.get("content").getAsString();
                } catch (Exception e) {
                    logger.warning("ChatAI: Error parsing API response: " + e.getMessage());
                }
            } else {
                logger.warning("ChatAI: API request failed, status code " + response.statusCode());
            }
        } catch (Exception e) {
            logger.warning("ChatAI: Error generating response: " + e.getMessage());
        }
        return null;
    }
}