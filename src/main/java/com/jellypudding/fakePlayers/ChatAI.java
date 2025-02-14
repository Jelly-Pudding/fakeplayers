package com.jellypudding.fakePlayers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ChatAI {
    private final String apiKey;
    private final HttpClient client;
    private final Gson gson;
    private final Logger logger;
    private final Map<String, FakePlayers.PlayerFakeAllData> fakePlayerData;
    // Track consecutive failures per model
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    // Track "disabled until" per model
    private final Map<String, Long> modelDisabledUntil = new ConcurrentHashMap<>();
    private static final int MAX_CONSECUTIVE_FAILURES = 4;
    // How long to disable a model if we exceed consecutive failures, in milliseconds (24 hours)
    private static final long DISABLE_DURATION_MS = 24 * 60 * 60 * 1000L;
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";

    public ChatAI(String apiKey, Logger logger, Map<String, FakePlayers.PlayerFakeAllData> fakePlayerData) {
        this.apiKey = apiKey;
        this.client = HttpClient.newHttpClient();
        this.gson = new Gson();
        this.logger = logger;
        this.fakePlayerData = fakePlayerData;
    }

    // Note: We now expect recentMessages to be a queue of FakePlayers.ChatMessage objects.
    public String generateResponse(String playerName, Queue<FakePlayers.ChatMessage> recentMessages) {
        // Join only the message texts from recentMessages (which have been cleaned already)
        String context = recentMessages.stream()
                .map(chatMsg -> chatMsg.message)
                .collect(Collectors.joining("\n"));

        FakePlayers.PlayerFakeAllData playerData = fakePlayerData.get(playerName);

        if (playerData == null) {
            logger.warning("No data found for player: " + playerName);
            return null;
        }

        // The model you want to use
        String model = playerData.model;

        if (isModelDisabled(model)) {
            // Try fallback
            for (String possibleFallback : List.of(
                    "deepseek/deepseek-r1-distill-llama-70b:free",
                    "mistralai/ministral-8b" // not free, but we likely exceeded free usage cap.
            )) {
                if (!isModelDisabled(possibleFallback)) {
                    logger.info("Using fallback model '" + possibleFallback + "' for " + playerName);
                    model = possibleFallback;
                    break;
                }
            }
            if (isModelDisabled(model)) {
                return null;
            }
        }

        // 5% chance for off-topic comment
        boolean makeOffTopic = Math.random() < 0.05;

        String prompt = makeOffTopic ?
                String.format(
                        "You are %s, a minecraft player. Generate ONE short message like you're chatting on a game server. " +
                                "Be %s. Talk about: your day, games, random thoughts, or complaints. " +
                                "Write like a casual gamer - use abbreviations occasionally, be informal.",
                        playerName, playerData.personality
                ) :
                String.format(
                        "You are roleplaying as a player named %s. \n\nRecent chat:\n%s\n\n" +
                                "Now respond with a short, casual message that follows naturally. Do not use any emojis or special characters. " +
                                "Be %s. IMPORTANT: You must respond as %s, not as any other player.",
                        playerName, context, playerData.personality, playerName
                );

        try {
            Map<String, Object> requestBody;
            if (model.equals("mistralai/ministral-8b")) {
                requestBody = Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of(
                                        "role", "user",
                                        "content", prompt
                                )
                        ),
                        "temperature", 0.6,  // Using Ministral's recommended median
                        "top_p", 1.0,
                        "repetition_penalty", 1.0,
                        "max_tokens", 50
                );
            } else {
                requestBody = Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of(
                                        "role", "user",
                                        "content", prompt
                                )
                        ),
                        "temperature", 0.83
                );
            }

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
                        handleFailure(model);
                        logger.warning("ChatAI: JSON response is null despite status 200.");
                        return null;
                    }
                    if (!jsonResponse.has("choices")) {
                        handleFailure(model);
                        logger.warning("ChatAI: 'choices' field missing in JSON response.");
                        return null;
                    }

                    JsonArray choices = jsonResponse.getAsJsonArray("choices");
                    if (choices.isEmpty()) {
                        handleFailure(model);
                        logger.warning("ChatAI: 'choices' array is empty in JSON response.");
                        return null;
                    }

                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    if (firstChoice == null || !firstChoice.has("message")) {
                        handleFailure(model);
                        logger.warning("ChatAI: 'message' object missing in first choice.");
                        return null;
                    }

                    JsonObject message = firstChoice.getAsJsonObject("message");
                    if (message == null || !message.has("content")) {
                        handleFailure(model);
                        logger.warning("ChatAI: 'content' missing in 'message' object.");
                        return null;
                    }

                    String content = message.get("content").getAsString().trim();

                    if (content.isEmpty()) {
                        handleFailure(model);
                        logger.warning("ChatAI: Model '" + model + "' returned empty content. Returning null.");
                        return null;
                    }

                    if (model.equals("deepseek/deepseek-r1-distill-llama-70b:free")) {
                        content = extractFinalAnswer(content);
                    }

                    String lower = content.toLowerCase();
                    if (lower.contains("i cannot generate a response") ||
                            lower.contains("i cannot provide") ||
                            lower.contains("i cannot generate") ||
                            lower.contains("i cannot comply") ||
                            lower.contains("i refuse to") ||
                            lower.contains("i cannot do that") ||
                            lower.contains("as an ai") ||
                            lower.contains("in this game chat scenario") ||
                            lower.contains("i need to figure out how to respond as") ||
                            lower.contains("is there anything else i can help you with")) {
                        logger.info("ChatAI: Model '" + model + "' returned a disclaimer/refusal text. Returning null instead.");
                        return null;
                    }

                    resetFailureCount(model);
                    return content;
                } catch (Exception e) {
                    handleFailure(model);
                    logger.warning("ChatAI: Error parsing API response: " + e.getMessage());
                }
            } else {
                handleFailure(model);
                logger.warning("ChatAI: API request failed, status code " + response.statusCode());
            }
        } catch (Exception e) {
            handleFailure(model);
            logger.warning("ChatAI: Error generating response: " + e.getMessage());
        }
        return null;
    }

    private String extractFinalAnswer(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }
        // Split the response into lines (handling both \r\n and \n)
        String[] lines = response.split("\\r?\\n");
        List<String> nonEmptyLines = new ArrayList<>();
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                nonEmptyLines.add(line.trim());
            }
        }
        if (nonEmptyLines.isEmpty()) {
            return response.trim();
        }
        // Option 1: If the last two lines are identical, assume that's the final answer.
        int size = nonEmptyLines.size();
        if (size >= 2 && nonEmptyLines.get(size - 1).equals(nonEmptyLines.get(size - 2))) {
            return nonEmptyLines.get(size - 1);
        }
        // Option 2: Otherwise, simply return the last non-empty line.
        return nonEmptyLines.get(size - 1);
    }

    private void handleFailure(String model) {
        consecutiveFailures.merge(model, 1, Integer::sum);
        int newCount = consecutiveFailures.get(model);

        if (newCount >= MAX_CONSECUTIVE_FAILURES) {
            long disableUntil = System.currentTimeMillis() + DISABLE_DURATION_MS;
            modelDisabledUntil.put(model, disableUntil);
            logger.warning("Model '" + model + "' disabled until " + new java.util.Date(disableUntil)
                    + " after " + newCount + " consecutive failures.");
        }
    }

    private void resetFailureCount(String model) {
        consecutiveFailures.remove(model);
    }

    private boolean isModelDisabled(String model) {
        Long disabledUntil = modelDisabledUntil.getOrDefault(model, 0L);
        long now = System.currentTimeMillis();
        if (now < disabledUntil) {
            return true;
        }
        if (disabledUntil != 0L) {
            modelDisabledUntil.remove(model);
            consecutiveFailures.remove(model);
        }
        return false;
    }
}
