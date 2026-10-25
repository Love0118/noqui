package dev.noqui.paper;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

/** A transparent bossbar carries only custom-font hint text; Moundi keeps its actionbar. */
final class HintOverlay implements Listener, AutoCloseable {
    static final UUID PACK_ID = UUID.fromString("7d5243d5-c30e-4bdd-a039-71ec1ad10002");
    private final Plugin plugin;
    private final Map<UUID,View> views = new HashMap<>();
    private String url;
    private byte[] sha1;
    private boolean started, closed;
    private static final class View {
        final Player player;
        boolean ready, offered, visible;
        String lastHint = "";
        BossBar bar;
        View(Player player) { this.player = player; }
    }
    HintOverlay(Plugin plugin) { this.plugin = Objects.requireNonNull(plugin); }

    private void start() {
        if (started) return;
        started = true;
        try (var in = plugin.getResource("hint-pack.properties")) {
            if (in == null) throw new IOException("Missing hint pack descriptor");
            Properties properties = new Properties(); properties.load(in);
            URI uri = URI.create(properties.getProperty("url", ""));
            String hash = properties.getProperty("sha1", "");
            if (!"https".equals(uri.getScheme()) || !"noqui.danta.qzz.io".equals(uri.getHost()) || uri.getPort() != -1
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().matches("/packs/[a-f0-9]{64}\\.zip") || !hash.matches("[a-f0-9]{40}"))
                throw new IOException("Invalid hint pack descriptor");
            url = uri.toString(); sha1 = HexFormat.of().parseHex(hash);
        } catch (IOException | IllegalArgumentException error) {
            plugin.getLogger().warning("Hint overlay pack unavailable; hints will use chat: " + error.getMessage());
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    void render(Player player, QuizHud.Frame frame) {
        if (closed) return;
        start();
        View view = views.computeIfAbsent(player.getUniqueId(), id -> new View(player));
        if (view.player != player) { forget(view.player); render(player, frame); return; }
        if (!view.offered && url != null) {
            view.offered = true;
            try { player.addResourcePack(PACK_ID, url, sha1.clone(), "노래퀴즈 힌트", false); }
            catch (RuntimeException error) { plugin.getLogger().warning("Hint pack offer failed for " + player.getName()); }
        }
        if (!"PLAYING".equals(frame.phase()) || frame.hint().isBlank()) { clear(player); return; }
        if (!view.ready) {
            if (!frame.hint().equals(view.lastHint)) player.sendMessage(Component.text("[힌트] ", NamedTextColor.YELLOW)
                .append(Component.text(frame.hint(), NamedTextColor.WHITE)));
            view.lastHint = frame.hint();
            return;
        }
        if (view.bar == null) view.bar = BossBar.bossBar(HintGlyphs.compose(frame.hint()), 0, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);
        else if (!frame.hint().equals(view.lastHint)) view.bar.name(HintGlyphs.compose(frame.hint()));
        view.lastHint = frame.hint();
        if (!view.visible) { player.showBossBar(view.bar); view.visible = true; }
    }

    void clear(Player player) {
        View view = views.get(player.getUniqueId());
        if (view == null || view.player != player) return;
        if (view.visible) { player.hideBossBar(view.bar); view.visible = false; }
        view.lastHint = "";
    }
    private void forget(Player player) {
        View view = views.get(player.getUniqueId());
        if (view == null || view.player != player) return;
        clear(player); views.remove(player.getUniqueId());
    }
    @EventHandler public void status(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) return;
        View view = views.get(event.getPlayer().getUniqueId());
        if (view == null || view.player != event.getPlayer()) return;
        view.ready = event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        if (!view.ready) clear(view.player);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { forget(event.getPlayer()); }
    @Override public void close() {
        if (closed) return;
        for (View view : List.copyOf(views.values())) clear(view.player);
        views.clear(); closed = true;
        if (started) HandlerList.unregisterAll(this);
    }
}
