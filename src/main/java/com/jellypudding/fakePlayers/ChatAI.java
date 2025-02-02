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
import java.util.logging.Logger;

public class ChatAI {
    private final String apiKey;
    private final HttpClient client;
    private final Gson gson;
    private final Logger logger;
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";

    public ChatAI(String apiKey, Logger logger) {
        this.apiKey = apiKey;
        this.client = HttpClient.newHttpClient();
        this.gson = new Gson();
        this.logger = logger;
    }

    public String generateResponse(String playerName, String context) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", "deepseek/deepseek-r1:free",
                    "messages", List.of(
                            Map.of(
                                    "role", "user",
                                    "content", String.format(
                                            "You are %s, a Minecraft player. Someone mentioned your name in chat. " +
                                                    "Generate a response in 1-2 short sentences. You have to appear human. " +
                                                    "You can be sarcastic, serious, whatever. Just try not to appear as a bot. " +
                                                    "Do not include your name or any chat formatting in the response. " +
                                                    "Just write the message you want to say. Context: %s",
                                            playerName, context
                                    )
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
                    if (jsonResponse != null && jsonResponse.has("choices")) {
                        JsonArray choices = jsonResponse.getAsJsonArray("choices");
                        if (choices != null && !choices.isEmpty()) {
                            JsonObject firstChoice = choices.get(0).getAsJsonObject();
                            if (firstChoice != null && firstChoice.has("message")) {
                                JsonObject message = firstChoice.getAsJsonObject("message");
                                if (message != null && message.has("content")) {
                                    return message.get("content").getAsString();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warning("Error parsing API response: " + e.getMessage());
                }
            } else {
                logger.warning("API request failed with status code: " + response.statusCode());
            }
        } catch (Exception e) {
            logger.warning("Error generating response: " + e.getMessage());
        }
        return null;
    }
}