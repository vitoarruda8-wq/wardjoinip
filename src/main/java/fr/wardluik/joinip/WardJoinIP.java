package fr.wardluik.joinip;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class WardJoinIP extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String FALLBACK_MESSAGE =
            "<gold>✦</gold> <rank> <white><player></white> <gray>a rejoint via <yellow><host></yellow></gray>";

    /** Une adresse speciale : message d'annonce (null = aucune annonce) et mode stream automatique. */
    private record Entry(String message, boolean stream) {}

    /** Adresse utilisee par chaque joueur connecte. */
    private final Map<UUID, String> hostnames = new ConcurrentHashMap<>();
    /** Joueurs actuellement en mode stream. */
    private final Set<UUID> streaming = ConcurrentHashMap.newKeySet();
    /** adresse -> parametres. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private String publicHost;
    private String defaultMessage;
    private String defaultRank;
    private boolean preferPrefix;
    private boolean luckPerms;

    private boolean hideJoinQuit;
    private Set<String> blockedCommands = Set.of();
    private String msgStreamOn;
    private String msgStreamOff;
    private String msgBlocked;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        luckPerms = getServer().getPluginManager().isPluginEnabled("LuckPerms");
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info(entries.size() + " adresse(s) speciale(s) chargee(s). LuckPerms : " + (luckPerms ? "oui" : "non"));
    }

    // ------------------------------------------------------------------ config

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        publicHost = normalizeHost(c.getString("public-host", ""));
        defaultMessage = c.getString("default-message", FALLBACK_MESSAGE);
        defaultRank = c.getString("default-rank", "&7Joueur");
        preferPrefix = !"group".equalsIgnoreCase(c.getString("rank-source", "prefix"));

        hideJoinQuit = c.getBoolean("stream-mode.hide-join-quit-messages", true);
        blockedCommands = c.getStringList("stream-mode.blocked-commands").stream()
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toSet());
        msgStreamOn = c.getString("stream-mode.messages.enabled", "<red>●</red> <white>Mode stream activé.</white>");
        msgStreamOff = c.getString("stream-mode.messages.disabled", "<gray>Mode stream désactivé.</gray>");
        msgBlocked = c.getString("stream-mode.messages.blocked",
                "<red><player> est en mode stream : impossible de le contacter ainsi.</red>");

        entries.clear();
        for (Map<?, ?> raw : c.getMapList("entries")) {
            Object host = raw.get("host");
            if (host == null) continue;
            String normalized = normalizeHost(String.valueOf(host));
            if (normalized.isEmpty()) continue;

            Object s = raw.get("stream");
            boolean stream = s != null && Boolean.parseBoolean(String.valueOf(s));

            String message;
            if (!raw.containsKey("message")) {
                message = stream ? null : defaultMessage; // adresse media sans "message" = pas d'annonce
            } else {
                Object m = raw.get("message");
                message = (m == null || String.valueOf(m).isBlank()) ? null : String.valueOf(m);
            }
            entries.put(normalized, new Entry(message, stream));
        }
    }

    private void saveEntries() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("host", e.getKey());
            item.put("stream", e.getValue().stream());
            item.put("message", e.getValue().message() == null ? "" : e.getValue().message());
            list.add(item);
        }
        getConfig().set("entries", list);
        saveConfig();
    }

    /** Retire le port, le suffixe Forge, le point final et met en minuscules. */
    private static String normalizeHost(String raw) {
        if (raw == null) return "";
        String h = raw;
        int nul = h.indexOf('\0');
        if (nul >= 0) h = h.substring(0, nul);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) h = h.substring(0, colon); // pas d'IPv6
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.toLowerCase(Locale.ROOT).trim();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        hostnames.put(event.getPlayer().getUniqueId(), normalizeHost(event.getHostname()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String host = hostnames.get(player.getUniqueId());
        Entry entry = host == null ? null : entries.get(host);
        if (entry == null) return; // adresse publique ou inconnue : rien a faire

        if (entry.stream()) {
            streaming.add(player.getUniqueId());
            if (hideJoinQuit) event.joinMessage(null); // anti stream-sniping
            say(player, msgStreamOn);
        }

        if (entry.message() != null && !player.hasPermission("wardjoin.silent")) {
            getServer().broadcast(build(player, host, entry.message()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        hostnames.remove(id);
        if (streaming.remove(id) && hideJoinQuit) event.quitMessage(null);
    }

    /** Bloque les messages prives / demandes de teleportation vers un joueur en mode stream. */
    @EventHandler(ignoreCancelled = true)
    public void onCommandUse(PlayerCommandPreprocessEvent event) {
        if (streaming.isEmpty() || blockedCommands.isEmpty()) return;
        Player sender = event.getPlayer();
        if (sender.hasPermission("wardjoin.stream.bypass")) return;

        String raw = event.getMessage();
        if (raw.length() < 2) return;
        String[] parts = raw.substring(1).trim().split("\\s+");
        if (parts.length < 2) return;

        String cmd = parts[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':'); // ex: essentials:msg
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (!blockedCommands.contains(cmd)) return;

        Player target = getServer().getPlayerExact(parts[1]);
        if (target == null || target.equals(sender) || !streaming.contains(target.getUniqueId())) return;

        event.setCancelled(true);
        say(sender, msgBlocked, Placeholder.unparsed("player", target.getName()));
    }

    // ------------------------------------------------------------------ message

    private Component build(Player player, String host, String template) {
        RankInfo info = rankOf(player);
        String group = info.group() == null ? "" : info.group();

        Component rank;
        boolean hasPrefix = info.prefix() != null && !info.prefix().isBlank();
        if (preferPrefix && hasPrefix) {
            rank = LEGACY.deserialize(info.prefix().replace('§', '&').trim());
        } else if (!group.isEmpty() && !group.equalsIgnoreCase("default")) {
            rank = Component.text(Character.toUpperCase(group.charAt(0)) + group.substring(1));
        } else {
            rank = LEGACY.deserialize(defaultRank);
        }

        return MM.deserialize(template,
                Placeholder.unparsed("player", player.getName()),
                Placeholder.component("rank", rank),
                Placeholder.unparsed("group", group),
                Placeholder.unparsed("host", host));
    }

    private RankInfo rankOf(Player player) {
        if (luckPerms) {
            try {
                return LuckPermsHook.lookup(player);
            } catch (Exception | LinkageError ex) {
                getLogger().warning("Lecture du grade LuckPerms impossible : " + ex.getMessage());
            }
        }
        return new RankInfo(null, null);
    }

    private static void say(CommandSender to, String mini, TagResolver... resolvers) {
        to.sendMessage(MM.deserialize(mini, resolvers));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stream")) return toggleStream(sender);

        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                say(sender, "<gold>Adresse publique : <yellow><host></yellow> <gray>(aucune annonce)</gray>",
                        Placeholder.unparsed("host", publicHost.isEmpty() ? "(non definie)" : publicHost));
                if (entries.isEmpty()) {
                    say(sender, "<gray>Aucune adresse speciale. Ajoutes-en une avec <yellow>/" + label + " add</yellow>.");
                    return true;
                }
                say(sender, "<gold>Adresses speciales (" + entries.size() + ") :");
                entries.forEach((host, e) -> say(sender,
                        "<gray>• <yellow><host></yellow> <type> <dark_gray>→</dark_gray> <white><msg></white>",
                        Placeholder.unparsed("host", host),
                        Placeholder.parsed("type", e.stream() ? "<red>[STREAM]</red>" : "<green>[normal]</green>"),
                        Placeholder.unparsed("msg", e.message() == null ? "(aucune annonce)" : e.message())));
            }
            case "add" -> {
                if (args.length < 3) {
                    say(sender, "<red>Usage : /" + label + " add <adresse> <normal|stream> [message]");
                    return true;
                }
                String host = normalizeHost(args[1]);
                String type = args[2].toLowerCase(Locale.ROOT);
                if (host.isEmpty() || (!type.equals("normal") && !type.equals("stream"))) {
                    say(sender, "<red>Usage : /" + label + " add <adresse> <normal|stream> [message]");
                    return true;
                }
                boolean stream = type.equals("stream");
                String message = args.length > 3
                        ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                        : (stream ? null : defaultMessage);
                boolean existed = entries.containsKey(host);
                entries.put(host, new Entry(message, stream));
                saveEntries();
                say(sender, (existed ? "<green>Adresse mise a jour : " : "<green>Adresse ajoutee : ")
                                + "<yellow><host></yellow> <gray>(" + (stream ? "mode stream" : "normal") + ")</gray>",
                        Placeholder.unparsed("host", host));
            }
            case "remove" -> {
                if (args.length < 2) {
                    say(sender, "<red>Usage : /" + label + " remove <adresse>");
                    return true;
                }
                String host = normalizeHost(args[1]);
                if (entries.remove(host) == null) {
                    say(sender, "<red>Cette adresse n'est pas dans la liste.");
                    return true;
                }
                saveEntries();
                say(sender, "<green>Adresse supprimee : <yellow><host></yellow>", Placeholder.unparsed("host", host));
            }
            case "test" -> {
                if (!(sender instanceof Player player)) {
                    say(sender, "<red>Cette commande est reservee aux joueurs.");
                    return true;
                }
                if (args.length < 2) {
                    say(sender, "<red>Usage : /" + label + " test <adresse>");
                    return true;
                }
                String host = normalizeHost(args[1]);
                Entry e = entries.get(host);
                String template = e != null ? e.message() : defaultMessage;
                if (template == null) {
                    say(sender, "<gray>Cette adresse n'envoie aucune annonce" + (e != null && e.stream() ? " (mode stream)." : "."));
                    return true;
                }
                player.sendMessage(build(player, host, template));
            }
            case "reload" -> {
                loadSettings();
                say(sender, "<green>Configuration rechargee (" + entries.size() + " adresse(s)).");
            }
            default -> say(sender, "<gold>/" + label + " <yellow>list</yellow> | <yellow>add</yellow> <adresse> <normal|stream> [message] | "
                    + "<yellow>remove</yellow> <adresse> | <yellow>test</yellow> <adresse> | <yellow>reload</yellow>");
        }
        return true;
    }

    private boolean toggleStream(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            say(sender, "<red>Cette commande est reservee aux joueurs.");
            return true;
        }
        if (streaming.remove(player.getUniqueId())) {
            say(player, msgStreamOff);
        } else {
            streaming.add(player.getUniqueId());
            say(player, msgStreamOn);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("stream")) return List.of();
        if (args.length == 1) {
            return List.of("list", "add", "remove", "test", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("test"))) {
            return entries.keySet().stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            return List.of("normal", "stream").stream()
                    .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
