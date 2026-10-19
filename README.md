# 🤖 Ai For Minecraft

A lightweight **Paper** plugin that lets players talk to an AI directly inside Minecraft. It works with any **OpenAI-compatible** chat API.

🌐 **Persian version:** [README.FA.md](https://github.com/BenyVK/Ai-For-Minecraft/blob/main/README.FA.md)

---

## ✨ Features

- 💬 Ask the AI with `/ai <question>`
- 🔒 **Private conversations**: only the player who asks sees the question and the answer
- 🧠 **Conversation memory** for every player (the AI remembers the recent messages)
- 💾 Each player's chat is saved to a file named after the player
- 🗑️ Players can delete their own history with `/ai history delete`
- 📝 Optional `log.log` file that records everything the plugin does
- ⌨️ Optional chat trigger: write `ai hello` in chat without a slash (the message stays private)
- ⏱️ Cooldown between requests and a limit on reply length
- ⚡ All network and file work is **asynchronous**, so the server never lags

---

## 📋 Requirements

| | |
|---|---|
| **Server** | Paper 1.21.11 |
| **Java** | 21 or newer |
| **API** | Any OpenAI-compatible `/chat/completions` endpoint and an API key |

---

## 📥 Installation

1. Download the plugin `.jar` (or [build it yourself](#-building-from-source)).
2. Put it in your server's `plugins` folder.
3. Start the server once. The plugin creates `plugins/ai-server/configvk.yml`.
4. Stop the server, open `configvk.yml` and set your `api-key`.
5. Start the server again and type `/ai hello` in game.

---

## 🎮 Commands

| Command | Description |
|---|---|
| `/ai <question>` | Ask the AI a question |
| `/ai history` | Show how many of your messages are saved |
| `/ai history delete` | Delete your saved chat history and the AI's memory of you |

**Permission:** `aiserver.use` (given to everyone by default)

---

## ⚙️ Configuration

File: `plugins/ai-server/configvk.yml`

| Key | Default | Description |
|---|---|---|
| `base-url` | `https://` | Base URL of the OpenAI-compatible API |
| `api-key` | `PUT_YOUR_API_KEY_HERE` | Your API key |
| `model` | `MODEL_AI` | Model name |
| `temperature` | `0.7` | Creativity of the answers |
| `max-tokens` | `400` | Maximum length of one AI answer (in tokens) |
| `max-reply-chars` | `1000` | Answers longer than this are cut off |
| `system-prompt` | *(see file)* | Instructions that tell the AI how to behave |
| `history-size` | `10` | How many recent messages are sent to the AI as context |
| `max-saved-messages` | `200` | How many messages are kept in each player's file |
| `cooldown-seconds` | `5` | Delay between two requests from the same player |
| `log` | `true` | Write `log.log` when `true` |
| `chat.enabled` | `false` | Enable the chat trigger (no slash needed) |
| `chat.prefix` | `ai ` | Messages starting with this prefix go to the AI |

> ⚠️ After changing `configvk.yml` you need to **restart the server**.

---

## 📁 Files created by the plugin

```
plugins/ai-server/
├── configvk.yml          ← settings
├── log.log               ← activity log (only if log: true)
└── chats/
    ├── Steve.json        ← saved chat of player "Steve"
    └── Alex.json
```

Each chat file is a JSON list. Every entry has a `role` (`user` or `assistant`), the `content`, and a `time`.

### About the log

When `log: true`, `log.log` records: plugin start/stop, who asked what, the AI's replies (shortened to 200 characters), history deletions and all errors. Because it contains players' questions, you may want to tell your players about it or turn it off. **The API key is never written to the log.**

---

## 🔨 Building from source

Requirements: JDK 21 or newer.

```bash
git clone https://github.com/BenyVK/Ai-For-Minecraft.git
cd Ai-For-Minecraft
./gradlew build
```

The jar will be in `build/libs/`. (On Windows use `gradlew.bat build`.)

The plugin is compiled with `options.release = 21`, so it runs on servers using Java 21 or newer even if you build it with a newer JDK.

---

## 🛠️ Troubleshooting

| Problem | Solution |
|---|---|
| `The API key is not set in configvk.yml.` | Put your real key in `api-key` and restart the server |
| `Error communicating with the AI (code 401)` | The API key is wrong or expired |
| `Error communicating with the AI (code 404)` | Check `base-url` and `model` |
| `UnsupportedClassVersionError` in console | Run your server with Java 21 or newer |
| `ClassNotFoundException` on startup | The `main:` line in `plugin.yml` must match the package of `AiServer.java` |
| No answer, no error | Set `log: true` and read `plugins/ai-server/log.log` |

---

## 🔐 Security tips

- Never share your API key or upload `configvk.yml` with the key inside it to GitHub.
- If a key was shared by mistake, delete it in your provider's panel and create a new one.

---

## 👤 Credits

| | |
|---|---|
| **Plugin** | Ai For Minecraft |
| **Author** | Benyamin Gharri |
| **YouTube** | [BenyVKx](https://www.youtube.com/@BenyVKx) |
| **Tutorial Video** | Coming soon |
| **Persian README.md** | [README.FA.md](https://github.com/BenyVK/Ai-For-Minecraft/blob/main/README.FA.md) |
