package vn.modcheck;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * ModCheck - xem client brand và các plugin channel mà client đăng ký,
 * từ đó đoán người chơi đang cài mod nào. Chỉ thấy được những gì client để lộ ra.
 */
public class ModCheck extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Những mục đã báo cho staff rồi (để không báo lặp). */
    private final Map<UUID, Set<String>> alerted = new HashMap<>();
    /** Người chơi đang chờ quét (gộp nhiều sự kiện thành 1 lần quét). */
    private final Set<UUID> pending = new HashSet<>();
    /** Nguoi choi da bi ban tu dong trong phien nay (tranh chay lenh ban 2 lan). */
    private final Set<UUID> punished = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("mods");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("ModCheck da bat.");
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        scheduleScan(e.getPlayer(), getConfig().getInt("scan-delay-ticks", 60));
    }

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        scheduleScan(e.getPlayer(), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        alerted.remove(id);
        pending.remove(id);
        punished.remove(id);
    }

    private void scheduleScan(Player p, long delay) {
        final UUID id = p.getUniqueId();
        if (!pending.add(id)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            pending.remove(id);
            if (p.isOnline()) {
                scan(p);
            }
        }, delay);
    }

    // -------------------------------------------------------------------- scan

    private void scan(Player p) {
        String brand = brandOf(p);
        Set<String> channels = channelsOf(p);

        // Mod gian lan -> ban tu dong (neu bat trong config)
        String banReason = banReason(brand, channels);
        if (banReason != null
                && getConfig().getBoolean("auto-ban.enabled", false)
                && !p.hasPermission("modcheck.bypass")
                && punished.add(p.getUniqueId())) {
            punish(p, banReason);
            return;
        }

        List<String> hits = suspiciousChannels(channels);
        Set<String> done = alerted.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());

        boolean newBrand = getConfig().getBoolean("alert-on-unknown-brand", true)
                && brand != null
                && !isIgnoredBrand(brand)
                && done.add("brand:" + brand.toLowerCase(Locale.ROOT));

        List<String> fresh = new ArrayList<>();
        for (String ch : hits) {
            if (done.add("ch:" + ch)) {
                fresh.add(ch);
            }
        }
        if (!newBrand && fresh.isEmpty()) {
            return;
        }

        String format = getConfig().getString("alert-format",
                "%prefix% &f%player% &7dung client &c%brand% &7| kenh dang ngo: &c%channels%");
        String msg = color(format
                .replace("%prefix%", getConfig().getString("prefix", "&4&lModCheck &8>>"))
                .replace("%player%", p.getName())
                .replace("%brand%", brand == null ? "khong ro" : brand)
                .replace("%channels%", fresh.isEmpty() ? "-" : String.join(", ", fresh)));

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("modcheck.alert")) {
                staff.sendMessage(msg);
            }
        }
        getLogger().info(ChatColor.stripColor(msg));
    }

    // ----------------------------------------------------------------- auto ban

    /** Tra ve ly do neu client/kenh khop danh sach mod gian lan, nguoc lai tra ve null. */
    private String banReason(String brand, Set<String> channels) {
        if (brand != null) {
            String b = brand.toLowerCase(Locale.ROOT);
            for (String k : getConfig().getStringList("auto-ban.ban-brands")) {
                if (!k.isEmpty() && b.contains(k.toLowerCase(Locale.ROOT))) {
                    return "client " + brand;
                }
            }
        }
        List<String> keywords = getConfig().getStringList("auto-ban.ban-keywords");
        for (String ch : channels) {
            String lower = ch.toLowerCase(Locale.ROOT);
            for (String k : keywords) {
                if (!k.isEmpty() && lower.contains(k.toLowerCase(Locale.ROOT))) {
                    return "kenh " + ch;
                }
            }
        }
        return null;
    }

    private void punish(Player p, String reason) {
        String name = p.getName();
        for (String cmd : getConfig().getStringList("auto-ban.commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("%player%", name).replace("%reason%", reason));
        }
        String msg = color(getConfig().getString("auto-ban.staff-message",
                "%prefix% &f%player% &7bi ban vi dung mod gian lan: &c%reason%")
                .replace("%prefix%", getConfig().getString("prefix", "&4&lModCheck &8>>"))
                .replace("%player%", name)
                .replace("%reason%", reason));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("modcheck.alert")) {
                staff.sendMessage(msg);
            }
        }
        getLogger().info(ChatColor.stripColor(msg));
    }

    // ----------------------------------------------------------------- helpers

    private String brandOf(Player p) {
        try {
            return p.getClientBrandName();
        } catch (Throwable t) {
            return null; // server khong ho tro hoac client chua gui brand
        }
    }

    private boolean isIgnoredBrand(String brand) {
        String b = brand.toLowerCase(Locale.ROOT);
        for (String s : getConfig().getStringList("ignore-brands")) {
            if (b.equals(s.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> channelsOf(Player p) {
        Set<String> ignore = new HashSet<>();
        for (String s : getConfig().getStringList("ignore-channels")) {
            ignore.add(s.toLowerCase(Locale.ROOT));
        }
        Set<String> out = new TreeSet<>();
        for (String ch : p.getListeningPluginChannels()) {
            if (!ignore.contains(ch.toLowerCase(Locale.ROOT))) {
                out.add(ch);
            }
        }
        return out;
    }

    private List<String> suspiciousChannels(Set<String> channels) {
        List<String> keywords = getConfig().getStringList("alert-keywords");
        List<String> out = new ArrayList<>();
        for (String ch : channels) {
            String lower = ch.toLowerCase(Locale.ROOT);
            for (String k : keywords) {
                if (lower.contains(k.toLowerCase(Locale.ROOT))) {
                    out.add(ch);
                    break;
                }
            }
        }
        return out;
    }

    private Set<String> knownMods(Set<String> channels) {
        Set<String> out = new TreeSet<>();
        ConfigurationSection sec = getConfig().getConfigurationSection("known-mods");
        if (sec == null) {
            return out;
        }
        for (String keyword : sec.getKeys(false)) {
            for (String ch : channels) {
                if (ch.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                    out.add(sec.getString(keyword, keyword));
                    break;
                }
            }
        }
        return out;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static void send(CommandSender to, String s) {
        to.sendMessage(color(s));
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("modcheck.use")) {
            send(sender, "&cBan khong co quyen dung lenh nay!");
            return true;
        }
        if (args.length == 0) {
            send(sender, "&7Cu phap: &c/mods <nguoi choi> &8| &c/mods reload");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("modcheck.admin")) {
                send(sender, "&cBan khong co quyen dung lenh nay!");
                return true;
            }
            reloadConfig();
            send(sender, "&aDa tai lai cau hinh ModCheck!");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(sender, "&cKhong tim thay nguoi choi &f" + args[0] + "&c (phai dang online).");
            return true;
        }

        String brand = brandOf(target);
        Set<String> channels = channelsOf(target);
        Set<String> mods = knownMods(channels);
        List<String> hits = suspiciousChannels(channels);

        send(sender, "&8&m-----&r &cModCheck: &f" + target.getName() + " &8&m-----");
        send(sender, "&7Client brand: &f" + (brand == null ? "khong ro" : brand));
        send(sender, "&7Mod doan duoc: &f" + (mods.isEmpty() ? "khong thay" : String.join(", ", mods)));
        send(sender, "&7Kenh dang ky (&f" + channels.size() + "&7): &f"
                + (channels.isEmpty() ? "-" : String.join(", ", channels)));
        if (!hits.isEmpty()) {
            send(sender, "&cKenh dang ngo: &f" + String.join(", ", hits));
        }
        send(sender, "&8Luu y: chi thay duoc mod co dang ky kenh; mod chi chay o client thi khong thay.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) {
            return out;
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if ("reload".startsWith(prefix)) {
            out.add("reload");
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(p.getName());
            }
        }
        return out;
    }
}
