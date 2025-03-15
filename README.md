# FakePlayers Plugin
**FakePlayers** is a Minecraft Paper 1.21.4 plugin that adds realistic fake players to your server with AI-powered chat. These fake players join and leave randomly, and can interact with real players through chat.

## Features
- Configurable fake players with custom skins
- AI-powered chat using OpenRouter API
- Customisable chat personalities for each fake player
- Automatic joining and leaving of fake players
- Fake players appear in the tab and server list
- Fake players respond to chat messages from real players
- Support for player commands like `/list` and `/msg`

## Installation
1. Download the latest release from the releases page
2. Place the `.jar` file in your Minecraft server's `plugins` folder
3. Restart your server
4. Configure the plugin in the `config.yml` file

## Configuration
In `config.yml`, you can configure:
```yaml
# Maximum number of players (real + fake) on the server
max-players: 69

# Enable or disable AI chat for fake players
enable-chat: true

# Your OpenRouter API key (required for chat)
openrouter-api-key: "your-api-key-here"

# Configure your fake players
fake-players:
  PlayerName:
    texture: "base64-texture-data"
    signature: "base64-signature-data"
    personality: "friendly"
    text-style: "perfect"
    model: "deepseek/deepseek-r1-distill-llama-70b:free"
```

### Chat Models
The plugin supports various AI models through OpenRouter. The default is:
- `deepseek/deepseek-r1-distill-llama-70b:free`

## Support Me
Donations will help me with the development of this project.

One-off donation: https://ko-fi.com/lolwhatyesme

Patreon: https://www.patreon.com/lolwhatyesme 