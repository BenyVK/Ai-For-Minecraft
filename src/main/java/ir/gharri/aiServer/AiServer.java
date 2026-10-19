package ir.gharri.aiServer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class AiServer extends JavaPlugin implements Listener, CommandExecutor {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private HttpClient http;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private final Map<String, List<JsonObject>> histories = new ConcurrentHashMap<>();

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private File chatsDir;
    private File logFile;

    private File configFile;
    private FileConfiguration customConfig;

    @Override
    public FileConfiguration getConfig() {
        return customConfig;
    }

    @Override
    public void saveDefaultConfig() {
        if (!configFile.exists()) {
            saveResource("configvk.yml", false);
        }
    }

    @Override
    public void reloadConfig() {
        customConfig = YamlConfiguration.loadConfiguration(configFile);
    }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        configFile = new File(getDataFolder(), "configvk.yml");
        saveDefaultConfig();
        reloadConfig();

        chatsDir = new File(getDataFolder(), "chats");
        chatsDir.mkdirs();
        logFile = new File(getDataFolder(), "log.log");

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        getCommand("ai").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        log("INFO", "Plugin enabled (model=" + getConfig().getString("model") + ")");
        getLogger().info("AI plugin enabled.");
    }

    @Override
    public void onDisable() {
        log("INFO", "Plugin disabled");
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- /ai <question> | /ai history | /ai history delete ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /ai <question>  |  /ai history  |  /ai history delete",
                    NamedTextColor.YELLOW));
            return true;
        }
        String name = nameOf(sender);

        // "/ai history" and "/ai history delete" (anything longer is treated as a normal question)
        if (args[0].equalsIgnoreCase("history")
                && (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("delete")))) {
            if (args.length == 2) {
                deleteHistory(name);
                sender.sendMessage(Component.text("Your chat history has been deleted.", NamedTextColor.GREEN));
            } else {
                int n = getHistory(name).size();
                sender.sendMessage(Component.text(
                        "Saved messages: " + n + "  (to delete: /ai history delete)",
                        NamedTextColor.YELLOW));
            }
            return true;
        }

        if (onCooldown(sender)) return true;
        String question = String.join(" ", args);
        sender.sendMessage(formatYou(question));
        ask(name, question, answer -> send(sender, answer));
        return true;
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("chat.enabled", false)) return;
        String prefix = getConfig().getString("chat.prefix", "ai ");
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (!msg.toLowerCase().startsWith(prefix.toLowerCase())) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("aiserver.use")) return;

        e.setCancelled(true);

        String question = msg.substring(prefix.length()).trim();
        if (question.isEmpty()) return;
        if (onCooldown(p)) return;

        p.sendMessage(formatYou(question));
        ask(nameOf(p), question, answer -> send(p, answer));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        histories.remove(nameOf(e.getPlayer()));
        cooldowns.remove(e.getPlayer().getUniqueId());
    }

    private String nameOf(CommandSender s) {
        return s.getName().replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("^\\.+", "_");
    }

    private boolean onCooldown(CommandSender sender) {
        if (!(sender instanceof Player p)) return false;
        long now = System.currentTimeMillis();
        long wait = getConfig().getLong("cooldown-seconds", 5) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < wait) {
            long left = (wait - (now - last)) / 1000 + 1;
            p.sendMessage(Component.text("Please wait " + left + " second(s).", NamedTextColor.RED));
            return true;
        }
        cooldowns.put(p.getUniqueId(), now);
        return false;
    }

    private Component formatYou(String text) {
        return Component.text("You: ", NamedTextColor.GREEN)
                .append(Component.text(text, NamedTextColor.WHITE));
    }

    private Component format(String text) {
        return Component.text("[AI] ", NamedTextColor.AQUA)
                .append(Component.text(text, NamedTextColor.WHITE));
    }

    private void send(CommandSender sender, String answer) {
        for (String line : answer.split("\n")) {
            if (!line.isBlank()) sender.sendMessage(format(line));
        }
    }

    private String shorten(String s) {
        String one = s.replace("\n", " ");
        return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }

    private void log(String level, String message) {
        if (customConfig == null || !getConfig().getBoolean("log", false)) return;
        String line = "[" + LocalDateTime.now().format(TS) + "] [" + level + "] " + message
                + System.lineSeparator();
        try {
            io.execute(() -> {
                try {
                    Files.writeString(logFile.toPath(), line, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    getLogger().warning("Could not write log.log: " + ex.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private File chatFile(String name) {
        return new File(chatsDir, name + ".json");
    }

    private List<JsonObject> getHistory(String name) {
        return histories.computeIfAbsent(name, this::loadHistory);
    }

    private List<JsonObject> loadHistory(String name) {
        List<JsonObject> list = Collections.synchronizedList(new ArrayList<>());
        File f = chatFile(name);
        if (f.exists()) {
            try {
                JsonArray arr = JsonParser.parseString(
                        Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonArray();
                arr.forEach(el -> list.add(el.getAsJsonObject()));
            } catch (Exception ex) {
                getLogger().warning("Could not read chat file of " + name + ": " + ex.getMessage());
                log("ERROR", "Could not read chat file of " + name + ": " + ex.getMessage());
            }
        }
        return list;
    }

    private void saveHistory(String name, List<JsonObject> hist) {
        List<JsonObject> copy;
        synchronized (hist) {
            copy = new ArrayList<>(hist);
        }
        try {
            io.execute(() -> {
                try {
                    Files.writeString(chatFile(name).toPath(), GSON.toJson(copy), StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    getLogger().warning("Could not save chat of " + name + ": " + ex.getMessage());
                    log("ERROR", "Could not save chat of " + name + ": " + ex.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void deleteHistory(String name) {
        histories.remove(name);
        try {
            io.execute(() -> {
                try {
                    Files.deleteIfExists(chatFile(name).toPath());
                } catch (IOException ex) {
                    getLogger().warning("Could not delete chat of " + name + ": " + ex.getMessage());
                    log("ERROR", "Could not delete chat of " + name + ": " + ex.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
        log("INFO", name + " deleted their chat history");
    }

    private JsonObject record(String role, String content, String time) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        o.addProperty("time", time);
        return o;
    }

    private JsonObject apiMessage(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private void ask(String name, String question, Consumer<String> callback) {
        String baseUrl = getConfig().getString("base-url", "https://api.gapgpt.app/v1");
        String key = getConfig().getString("api-key", "");
        String model = getConfig().getString("model", "gemma-3-27b-it");
        String system = getConfig().getString("system-prompt", "");
        double temperature = getConfig().getDouble("temperature", 0.7);
        int maxTokens = getConfig().getInt("max-tokens", 400);
        int maxChars = getConfig().getInt("max-reply-chars", 1000);
        int historySize = getConfig().getInt("history-size", 10);
        int maxSaved = getConfig().getInt("max-saved-messages", 200);

        log("INFO", name + " asked: " + shorten(question));

        if (key.isBlank() || key.startsWith("PUT_")) {
            log("ERROR", "api-key is not set in configvk.yml");
            callback.accept("The API key is not set in configvk.yml.");
            return;
        }

        List<JsonObject> hist = getHistory(name);
        List<JsonObject> context;
        synchronized (hist) {
            int from = Math.max(0, hist.size() - Math.max(0, historySize - 1));
            if (from % 2 != 0 && from < hist.size()) from++;
            context = new ArrayList<>(hist.subList(from, hist.size()));
        }

        JsonArray messages = new JsonArray();
        if (!system.isBlank()) messages.add(apiMessage("system", system));
        for (JsonObject m : context) {
            messages.add(apiMessage(m.get("role").getAsString(), m.get("content").getAsString()));
        }
        messages.add(apiMessage("user", question));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    String result;
                    boolean ok = false;
                    if (resp.statusCode() / 100 != 2) {
                        getLogger().warning("API error " + resp.statusCode() + ": " + resp.body());
                        log("ERROR", "API error " + resp.statusCode() + " for " + name + ": " + shorten(resp.body()));
                        result = "Error communicating with the AI (code " + resp.statusCode() + ")";
                    } else {
                        try {
                            result = JsonParser.parseString(resp.body()).getAsJsonObject()
                                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                                    .getAsJsonObject("message").get("content").getAsString().trim();
                            if (result.length() > maxChars) result = result.substring(0, maxChars) + "...";
                            ok = true;
                        } catch (Exception ex) {
                            getLogger().warning("Bad API response: " + resp.body());
                            log("ERROR", "Bad API response for " + name + ": " + shorten(resp.body()));
                            result = "Invalid response from the AI server.";
                        }
                    }

                    if (ok) {
                        log("INFO", "AI replied to " + name + " (" + result.length() + " chars): " + shorten(result));
                        if (histories.get(name) == hist) {
                            String now = LocalDateTime.now().format(TS);
                            synchronized (hist) {
                                hist.add(record("user", question, now));
                                hist.add(record("assistant", result, now));
                                while (hist.size() > maxSaved) hist.remove(0);
                            }
                            saveHistory(name, hist);
                        }
                    }

                    String finalResult = result;
                    if (isEnabled()) {
                        Bukkit.getScheduler().runTask(this, () -> callback.accept(finalResult));
                    }
                })
                .exceptionally(ex -> {
                    getLogger().warning("Request failed: " + ex.getMessage());
                    log("ERROR", "Request failed for " + name + ": " + ex.getMessage());
                    if (isEnabled()) {
                        Bukkit.getScheduler().runTask(this,
                                () -> callback.accept("Failed to connect to the AI."));
                    }
                    return null;
                });
    }
}