package drizmans.mineDiscord;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONObject; // For JSON construction
import org.json.simple.JSONArray; // For JSON array construction

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit; // For time unit conversion
import java.util.stream.Collectors;

public final class MineDiscord extends JavaPlugin implements Listener, CommandExecutor {

    private String discordWebhookUrl;
    private List<UUID> ignoredPlayers; // Whitelist for players to ignore from logging
    private Map<UUID, PlayerStats> playerSessionStats; // Tracks stats for currently online players

    // Inner class to hold player specific stats for a session
    private static class PlayerStats {
        long blocksPlaced;
        long blocksBroken;
        double distanceTraveled;
        long chestsOpened; // Using long for potential high counts
        Instant joinTime;

        PlayerStats() {
            this.blocksPlaced = 0;
            this.blocksBroken = 0;
            this.distanceTraveled = 0.0;
            this.chestsOpened = 0;
            this.joinTime = Instant.now();
        }

        public long getPlaytimeMinutes() {
            return Duration.between(joinTime, Instant.now()).toMinutes();
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("MineDiscord plugin enabled!");

        // --------------------------------------------------------------------
        // Configuration Setup:
        // This section handles loading and saving of configuration and the ignored player list.
        // --------------------------------------------------------------------
        // Saves the default config.yml if it doesn't exist. This ensures we have a base file.
        saveDefaultConfig();
        // Load the Discord webhook URL from config.
        discordWebhookUrl = getConfig().getString("discord-webhook-url", "");
        if (discordWebhookUrl.isEmpty() || discordWebhookUrl.equals("YOUR_DISCORD_WEBHOOK_URL_HERE")) {
            getLogger().severe("Discord webhook URL is not set in config.yml! Please set it for the plugin to function.");
        }

        // Initialize ignoredPlayers to an empty list before attempting to load from config.
        // This prevents NullPointerExceptions if loading fails or returns null.
        ignoredPlayers = new ArrayList<>();
        // Load the list of ignored player UUIDs from the plugin's config.
        ignoredPlayers.addAll(loadIgnoredPlayers());

        // Initialize the map for tracking player session stats.
        playerSessionStats = new HashMap<>();

        // --------------------------------------------------------------------
        // Event and Command Registration:
        // This section registers the necessary components for the plugin to function.
        // --------------------------------------------------------------------
        // Register this class as an event listener for Bukkit events.
        Bukkit.getPluginManager().registerEvents(this, this);
        // Register this class as the executor for the "minediscord" command.
        getCommand("minediscord").setExecutor(this);

        // --------------------------------------------------------------------
        // Schedule Periodic Updates:
        // This runnable sends activity updates every 5 minutes (6000 ticks).
        // --------------------------------------------------------------------
        new BukkitRunnable() {
            @Override
            public void run() {
                sendActivityUpdates();
            }
        }.runTaskTimerAsynchronously(this, 20L * 60 * 5, 20L * 60 * 5); // 5 minutes in ticks (20 ticks/sec * 60 sec/min * 5 min)
    }

    @Override
    public void onDisable() {
        getLogger().info("MineDiscord plugin disabled!");
        // Save the current list of ignored player UUIDs to the plugin's config.
        saveIgnoredPlayers();
    }

    /**
     * Loads the list of ignored player UUIDs from the plugin's config.yml.
     * Stored as a list of strings and converted to UUIDs.
     * @return A List of UUIDs of ignored players. Returns an empty list if none found or error.
     */
    private List<UUID> loadIgnoredPlayers() {
        List<String> uuidStrings = getConfig().getStringList("ignored-players");
        return uuidStrings.stream()
                .map(uuidString -> {
                    try {
                        return UUID.fromString(uuidString);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Invalid UUID found in config for ignored-players: " + uuidString);
                        return null; // Return null for invalid UUIDs
                    }
                })
                .filter(java.util.Objects::nonNull) // Filter out any null UUIDs
                .collect(Collectors.toList());
    }

    /**
     * Saves the current list of ignored player UUIDs to the plugin's config.yml.
     * UUIDs are converted to strings for storage.
     */
    private void saveIgnoredPlayers() {
        List<String> uuidStrings = ignoredPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
        getConfig().set("ignored-players", uuidStrings);
        saveConfig(); // Persist changes to the disk.
    }

    /**
     * Sends a JSON payload to the configured Discord webhook URL.
     * This method runs asynchronously to avoid blocking the main server thread.
     * @param jsonPayload The JSON string to send (e.g., {"content": "message"}).
     */
    private void sendDiscordMessage(String jsonPayload) {
        if (discordWebhookUrl.isEmpty() || discordWebhookUrl.equals("YOUR_DISCORD_WEBHOOK_URL_HERE")) {
            getLogger().warning("Discord webhook URL is not configured. Cannot send message.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL(discordWebhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    getLogger().info("Successfully sent message to Discord webhook. Response Code: " + responseCode);
                } else {
                    getLogger().warning("Failed to send message to Discord webhook. Response Code: " + responseCode + ", Message: " + connection.getResponseMessage());
                }
                connection.disconnect();

            } catch (Exception e) {
                getLogger().severe("Error sending Discord message: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Sends a Discord alert for a player joining the server.
     * @param player The player who joined.
     */
    private void sendJoinAlert(Player player) {
        if (ignoredPlayers.contains(player.getUniqueId())) {
            return; // Player is on the whitelist, do not log.
        }

        JSONObject json = new JSONObject();
        json.put("username", "Server Alerts"); // Custom username for the webhook message
        json.put("avatar_url", "https://i.imgur.com/gK2Jp20.png"); // Optional: custom avatar
        json.put("content", player.getName() + " has joined the server!");

        // Embeds for detailed information
        JSONArray embeds = new JSONArray();
        JSONObject embed = new JSONObject();
        embed.put("title", "Player Join Details");
        embed.put("color", 65280); // Green color
        embed.put("description", "A player has connected to the server.");

        JSONArray fields = new JSONArray();
        JSONObject nameField = new JSONObject();
        nameField.put("name", "Username");
        nameField.put("value", player.getName());
        nameField.put("inline", true);
        fields.add(nameField);

        JSONObject uuidField = new JSONObject();
        uuidField.put("name", "UUID");
        uuidField.put("value", player.getUniqueId().toString());
        uuidField.put("inline", true);
        fields.add(uuidField);

        JSONObject ipField = new JSONObject();
        ipField.put("name", "IP Address");
        // Get the player's IP address. Handle potential null if not connected via socket.
        String ipAddress = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "N/A";
        ipField.put("value", ipAddress);
        ipField.put("inline", true);
        fields.add(ipField);

        embed.put("fields", fields);
        embeds.add(embed);
        json.put("embeds", embeds);

        sendDiscordMessage(json.toJSONString());
    }

    /**
     * Sends a Discord alert for a player leaving the server.
     * @param player The player who left.
     */
    private void sendQuitAlert(Player player) {
        if (ignoredPlayers.contains(player.getUniqueId())) {
            return; // Player is on the whitelist, do not log.
        }

        JSONObject json = new JSONObject();
        json.put("username", "Server Alerts");
        json.put("avatar_url", "https://i.imgur.com/gK2Jp20.png");
        json.put("content", player.getName() + " has left the server.");

        // Embeds for detailed information
        JSONArray embeds = new JSONArray();
        JSONObject embed = new JSONObject();
        embed.put("title", "Player Quit Details");
        embed.put("color", 16711680); // Red color
        embed.put("description", "A player has disconnected from the server.");

        JSONArray fields = new JSONArray();
        JSONObject nameField = new JSONObject();
        nameField.put("name", "Username");
        nameField.put("value", player.getName());
        nameField.put("inline", true);
        fields.add(nameField);

        JSONObject uuidField = new JSONObject();
        uuidField.put("name", "UUID");
        uuidField.put("value", player.getUniqueId().toString());
        uuidField.put("inline", true);
        fields.add(uuidField);

        embed.put("fields", fields);
        embeds.add(embed);
        json.put("embeds", embeds);

        sendDiscordMessage(json.toJSONString());
    }

    /**
     * Collects and sends periodic activity updates for online players not on the whitelist.
     * This method is called by the BukkitRunnable.
     */
    private void sendActivityUpdates() {
        JSONArray embeds = new JSONArray();
        boolean hasUpdates = false; // Flag to check if there are any updates to send

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ignoredPlayers.contains(player.getUniqueId())) {
                continue; // Skip whitelisted players
            }

            // Retrieve or initialize PlayerStats for this session
            PlayerStats stats = playerSessionStats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStats());

            // Get updated statistics
            long currentBlocksPlaced = stats.blocksPlaced; // Use tracked stats for blocks placed/broken
            long currentBlocksBroken = stats.blocksBroken;
            double currentDistanceTraveled = stats.distanceTraveled;
            long currentChestsOpened = stats.chestsOpened; // Use tracked stats for chests opened

            // Get playtime using Bukkit's Statistic for consistency, converted to minutes
            long playtimeMinutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;

            // If there's meaningful activity (or it's just a general update for online player)
            // or if the stats are not all zero (meaning player has done something)
            if (currentBlocksPlaced > 0 || currentBlocksBroken > 0 || currentDistanceTraveled > 0 || currentChestsOpened > 0 || playtimeMinutes > 0) {
                hasUpdates = true;
                JSONObject embed = new JSONObject();
                embed.put("title", "Activity Update: " + player.getName());
                embed.put("color", 3447003); // Blue color

                JSONArray fields = new JSONArray();
                fields.add(createField("Blocks Placed", String.valueOf(currentBlocksPlaced), true));
                fields.add(createField("Blocks Broken", String.valueOf(currentBlocksBroken), true));
                fields.add(createField("Distance Travelled", String.format("%.2f blocks", currentDistanceTraveled), true));
                fields.add(createField("Chests Opened", String.valueOf(currentChestsOpened), true));
                fields.add(createField("Playtime", playtimeMinutes + " minutes", true));
                fields.add(createField("UUID", player.getUniqueId().toString(), false));

                embed.put("fields", fields);
                embeds.add(embed);

                // Reset session-specific stats after sending, to log new activity in next interval
                // Or you might accumulate if you want total over a longer period, but for "updates", resetting is common.
                // For this implementation, we will reset the *session-specific* tracked stats but use Bukkit's total playtime.
                playerSessionStats.put(player.getUniqueId(), new PlayerStats()); // Reset for next interval
            }
        }

        if (hasUpdates) {
            JSONObject json = new JSONObject();
            json.put("username", "Activity Tracker");
            json.put("avatar_url", "https://i.imgur.com/L7517hU.png"); // Another optional avatar
            json.put("content", "Here are the latest player activity updates:");
            json.put("embeds", embeds);
            sendDiscordMessage(json.toJSONString());
        }
    }

    /** Helper to create an embed field */
    private JSONObject createField(String name, String value, boolean inline) {
        JSONObject field = new JSONObject();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);
        return field;
    }

    // --------------------------------------------------------------------
    // Event Handlers for Player Activity Tracking:
    // --------------------------------------------------------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sendJoinAlert(player); // Send join alert first
        if (!ignoredPlayers.contains(player.getUniqueId())) {
            // Start tracking stats for non-ignored players
            playerSessionStats.put(player.getUniqueId(), new PlayerStats());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sendQuitAlert(player); // Send quit alert
        // Remove player from session stats tracking
        playerSessionStats.remove(player.getUniqueId());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (ignoredPlayers.contains(player.getUniqueId())) return;
        playerSessionStats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStats()).blocksPlaced++;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (ignoredPlayers.contains(player.getUniqueId())) return;
        playerSessionStats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStats()).blocksBroken++;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (ignoredPlayers.contains(player.getUniqueId())) return;
        if (event.getFrom().getWorld() != event.getTo().getWorld()) {
            return; // Ignore movement between worlds
        }
        double distance = event.getFrom().distance(event.getTo());
        if (distance > 0.1) { // Only count significant movement
            playerSessionStats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStats()).distanceTraveled += distance;
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (ignoredPlayers.contains(player.getUniqueId())) return;
        if (event.hasBlock() && event.getClickedBlock().getState() instanceof Container) {
            playerSessionStats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStats()).chestsOpened++;
        }
    }


    // --------------------------------------------------------------------
    // Command Handler for Whitelist Management:
    // --------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // --------------------------------------------------------------------
        // Permission Check:
        // Verify if the sender has the necessary permission to use this command.
        // --------------------------------------------------------------------
        if (!sender.hasPermission("minediscord.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        // --------------------------------------------------------------------
        // Argument Validation:
        // Check for correct command usage (sub-command and player name if applicable).
        // --------------------------------------------------------------------
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /minediscord <add|remove|list> [player_name]");
            return true;
        }

        String subCommand = args[0].toLowerCase(); // Get the sub-command (add, remove, list).

        switch (subCommand) {
            case "add":
                if (args.length != 2) {
                    sender.sendMessage("§cUsage: /minediscord add <player_name>");
                    return true;
                }
                handleAddCommand(sender, args[1]);
                break;
            case "remove":
                if (args.length != 2) {
                    sender.sendMessage("§cUsage: /minediscord remove <player_name>");
                    return true;
                }
                handleRemoveCommand(sender, args[1]);
                break;
            case "list":
                handleListCommand(sender);
                break;
            default:
                sender.sendMessage("§cUnknown sub-command. Usage: /minediscord <add|remove|list>");
                break;
        }

        return true; // Indicate that the command was successfully handled.
    }

    /**
     * Handles the "add" sub-command, adding a player to the ignored list.
     * @param sender The command sender.
     * @param targetPlayerName The name of the player to add.
     */
    private void handleAddCommand(CommandSender sender, String targetPlayerName) {
        OfflinePlayer targetOfflinePlayer = Bukkit.getOfflinePlayer(targetPlayerName);

        if (targetOfflinePlayer == null || !targetOfflinePlayer.hasPlayedBefore()) {
            sender.sendMessage("§cPlayer '" + targetPlayerName + "' has never played on this server or does not exist.");
            return;
        }

        UUID targetUUID = targetOfflinePlayer.getUniqueId();

        if (ignoredPlayers.contains(targetUUID)) {
            sender.sendMessage("§cPlayer '" + targetPlayerName + "' is already on the ignored list.");
            return;
        }

        ignoredPlayers.add(targetUUID);
        saveIgnoredPlayers(); // Save the updated list to config.
        sender.sendMessage("§aPlayer '" + targetPlayerName + "' has been added to the Discord logging ignored list.");
        getLogger().info("Player " + targetPlayerName + " added to ignored list.");
    }

    /**
     * Handles the "remove" sub-command, removing a player from the ignored list.
     * @param sender The command sender.
     * @param targetPlayerName The name of the player to remove.
     */
    private void handleRemoveCommand(CommandSender sender, String targetPlayerName) {
        OfflinePlayer targetOfflinePlayer = Bukkit.getOfflinePlayer(targetPlayerName);

        if (targetOfflinePlayer == null || !targetOfflinePlayer.hasPlayedBefore()) {
            sender.sendMessage("§cPlayer '" + targetPlayerName + "' has never played on this server or does not exist.");
            return;
        }

        UUID targetUUID = targetOfflinePlayer.getUniqueId();

        if (!ignoredPlayers.contains(targetUUID)) {
            sender.sendMessage("§cPlayer '" + targetPlayerName + "' is not currently on the ignored list.");
            return;
        }

        ignoredPlayers.remove(targetUUID);
        saveIgnoredPlayers(); // Save the updated list to config.
        sender.sendMessage("§aPlayer '" + targetPlayerName + "' has been removed from the Discord logging ignored list.");
        getLogger().info("Player " + targetPlayerName + " removed from ignored list.");
    }

    /**
     * Handles the "list" sub-command, listing all players currently on the ignored list.
     * @param sender The command sender.
     */
    private void handleListCommand(CommandSender sender) {
        if (ignoredPlayers.isEmpty()) {
            sender.sendMessage("§eNo players are currently on the Discord logging ignored list.");
            return;
        }

        sender.sendMessage("§ePlayers on Discord logging ignored list:");
        for (UUID uuid : ignoredPlayers) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String playerName = (offlinePlayer != null && offlinePlayer.getName() != null) ? offlinePlayer.getName() : uuid.toString();
            sender.sendMessage("§7- " + playerName + " (" + (offlinePlayer.isOnline() ? "Online" : "Offline") + ")");
        }
    }
}
