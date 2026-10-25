package dev.noqui.paper;

import java.time.Duration;
import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Main-thread presentation only. The game owns timing; this class never schedules work. */
final class QuizHud implements AutoCloseable {
    record Frame(int round, int registeredSongs, String phase, long remainingMs, long durationMs,
                 String presenter, String hint, String answerTitle, List<String> tags,
                 List<String> winners, int skipVotes, int skipRequired) {
        Frame {
            phase = Objects.requireNonNullElse(phase, "STOPPED").toUpperCase(Locale.ROOT);
            presenter = Objects.requireNonNullElse(presenter, "");
            hint = Objects.requireNonNullElse(hint, "");
            answerTitle = Objects.requireNonNullElse(answerTitle, "");
            tags = tags == null ? List.of() : List.copyOf(tags);
            winners = winners == null ? List.of() : List.copyOf(winners);
        }
    }

    private static final TextColor ORIGINAL_FRAME = TextColor.color(0x8aaae5);
    private static final TextColor ORIGINAL_WINNER = TextColor.color(0xb2f9e8);
    private static final TextColor ORIGINAL_WHITE = TextColor.color(0xfefefe);
    private static final Component RULE = Component.text("=====================================================", ORIGINAL_FRAME);
    private static final Title.Times SHORT_TITLE = Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(300));
    private static final Title.Times REVEAL_TITLE = Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(4), Duration.ofMillis(400));
    private final Map<UUID, View> views = new LinkedHashMap<>();
    private boolean closed;

    private static final class View {
        final Player player;
        boolean immersive = true;
        Tab tab;
        int round, total, countdown = -1, countdownRound = -1, countdownTotal = -1;
        String phase = "";
        Reveal lastReveal;
        boolean endCue;

        View(Player player) { this.player = player; }
    }

    /** A fresh TAB ownership interval starts whenever immersion resumes. */
    private static final class Tab {
        final Component previousListName;
        Component listName;
        boolean listAbandoned;
        Tab(Player player) { previousListName=player.playerListName(); }
    }
    private record Reveal(int round, UUID presenter, String title, List<String> tags, List<String> winners) {}
    private record Ranked(UUID id, String name, int score) {}

    QuizHud() {}

    void enter(Player player) {
        mainThread();
        if (closed) return;
        View existing = views.get(player.getUniqueId());
        if (existing != null) {
            if (existing.player == player) return;
            leave(existing.player);
        }
        View view = new View(player);
        views.put(player.getUniqueId(), view);
    }

    void setImmersive(Player player, boolean immersive) {
        mainThread();
        View view = view(player);
        if (view == null || view.immersive == immersive) return;
        view.immersive = immersive;
        view.countdown = -1;
        claimTab(view);
    }

    void render(Player player, Frame frame, int score, long credits) {
        mainThread();
        View view = view(player);
        if (view == null) return;
        Objects.requireNonNull(frame, "frame");
        view.total = Math.max(0, frame.registeredSongs());
        if(view.round!=round(frame.round(),view.total)){view.endCue=false;view.lastReveal=null;}
        view.round = round(frame.round(), view.total);
        if (!frame.phase().equals(view.phase)) view.countdown = -1;
        view.phase = frame.phase();
        claimTab(view);
        updateListName(view,score);
        if(!view.immersive){
            var services=Bukkit.getServicesManager();
            var defense=services==null?null:services.load(dev.noqui.api.DefenseService.class);
            var mining=services==null?null:services.load(dev.noqui.api.MiningService.class);
            if(defense!=null&&defense.active(player)&&(mining==null||!mining.ownsInventory(player)))return;
        }
        if (frame.phase().equals("PLAYING") && !frame.hint().isBlank())
            player.sendActionBar(Component.text("[힌트] ",NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
                    .append(white(frame.hint()).decorate(TextDecoration.BOLD)));
    }

    void start(Player player, int currentRound, int registeredSongs) {
        mainThread();
        View view = view(player);
        if (view == null) return;
        view.total = Math.max(0, registeredSongs);
        view.round = round(currentRound,view.total);
        view.countdown = -1;
        view.lastReveal = null;
        view.endCue=false;
        if (view.immersive) player.showTitle(Title.title(original("퀴즈를 시작합니다"), Component.empty(), SHORT_TITLE));
        player.sendMessage(Component.text("=====================[", ORIGINAL_FRAME).append(Component.text("노래퀴즈 시작", ORIGINAL_WINNER).decorate(TextDecoration.BOLD)).append(Component.text("]=====================", ORIGINAL_FRAME)));
        player.sendMessage(original("라운드 " + rounds(view.round,view.total)));
        cue(player, "0select2", 1.0f);
    }

    void countdown(Player player, int remaining, int round, int registeredSongs) {
        mainThread();
        View view = view(player);
        if (view == null || remaining < 1 || remaining > 3) return;
        int total = Math.max(0, registeredSongs), current = round(round, total);
        if (view.countdown == remaining && view.countdownRound == current && view.countdownTotal == total) return;
        view.round = current;
        view.total = total;
        view.countdown = remaining;
        view.countdownRound = current;
        view.countdownTotal = total;
        if (remaining == 3) framed(player,List.of(original("라운드 " + rounds(current,total))));
        if (view.immersive) player.showTitle(Title.title(Component.text(Integer.toString(remaining),NamedTextColor.YELLOW).decorate(TextDecoration.BOLD), Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
        cue(player, "0click2", 1.0f);
    }

    void reveal(Player player, UUID presenter, String title, List<String> tags, List<String> winners) {
        mainThread();
        View view = view(player);
        if (view == null) return;
        Reveal reveal = new Reveal(view.round, presenter, Objects.requireNonNullElse(title, ""),
                tags == null ? List.of() : List.copyOf(tags), winners == null ? List.of() : List.copyOf(winners));
        if (reveal.equals(view.lastReveal)) return;
        view.lastReveal = reveal;
        String presenterName = playerName(reveal.presenter());
        var lines = new ArrayList<Component>();
        lines.add(Component.text(reveal.winners().isEmpty() ? "아무도 정답을 맞히지 못했습니다" : "정답자: " + String.join(", ", reveal.winners()),ORIGINAL_WINNER).decorate(TextDecoration.BOLD));
        lines.add(Component.empty());
        lines.add(original("정답: 『" + reveal.title() + "』  출제자: " + presenterName));
        lines.add(reveal.tags().isEmpty()?Component.empty():Component.text(" #"+String.join(" #",reveal.tags()),TextColor.color(0xcfcfc4)));
        framed(player, lines);
        if (view.immersive) player.showTitle(Title.title(white(reveal.title()), gray("라운드 " + rounds(view.round, view.total) + "  출제자: " + presenterName), REVEAL_TITLE));
        cue(player, "0win", 1.0f);
    }
    void roundEnd(Player player){mainThread();View view=view(player);if(view==null||view.endCue)return;view.endCue=true;cue(player,"0heal",1.0f);}

    /** Publishes the final result, then releases owned UI. Finite result titles remain visible. */
    void finish(Player player, Map<UUID, Integer> scores) {
        mainThread();
        View view = view(player);
        if (view == null) return;
        var ranking = new ArrayList<Ranked>();
        Objects.requireNonNull(scores, "scores").forEach((id, score) -> ranking.add(new Ranked(id, playerName(id), score)));
        ranking.sort(Comparator.comparingInt(Ranked::score).reversed().thenComparing(Ranked::name).thenComparing(Ranked::id));
        var lines = new ArrayList<Component>();
        lines.add(Component.empty());
        lines.add(Component.text("            TOP",ORIGINAL_WINNER).decorate(TextDecoration.BOLD));
        lines.add(Component.empty());
        int rank = 0, previous = 0;
        for (int i = 0; i < Math.min(10, ranking.size()); i++) {
            Ranked entry = ranking.get(i);
            if (i == 0 || entry.score() != previous) rank = i + 1;
            previous = entry.score();
            TextColor color=rank==1?TextColor.color(0xffd700):rank==2?TextColor.color(0xc0c0c0):rank==3?TextColor.color(0xcd7f32):NamedTextColor.DARK_GRAY;
            lines.add(Component.text(rank+". ",color).decorate(TextDecoration.BOLD).append(white(entry.name())).append(gray("  ")).append(Component.text(Integer.toString(entry.score()),NamedTextColor.YELLOW)));
        }
        if (ranking.isEmpty()) lines.add(gray("기록 없음"));
        framed(player,List.of(Component.text("퀴즈가 종료되었습니다",ORIGINAL_WINNER).decorate(TextDecoration.BOLD)));
        Component rankRule=Component.text("=".repeat(30),ORIGINAL_FRAME);
        player.sendMessage(rankRule);lines.forEach(player::sendMessage);player.sendMessage(Component.empty());player.sendMessage(rankRule);
        if (view.immersive) player.showTitle(Title.title(white("퀴즈 종료"), gray("TOP 10"), REVEAL_TITLE));
        leave(player);
    }

    void leave(Player player) {
        mainThread();
        View view = views.get(player.getUniqueId());
        if (view == null || view.player != player) return;
        views.remove(player.getUniqueId());
        release(view);
    }

    @Override public void close() {
        mainThread();
        if (closed) return;
        closed = true;
        var owned = List.copyOf(views.values());
        views.clear();
        for (View view : owned) release(view);
    }

    private View view(Player player) {
        if (closed) return null;
        View view = views.get(player.getUniqueId());
        return view != null && view.player == player ? view : null;
    }

    private static void claimTab(View view) {
        if(view.tab==null)view.tab=new Tab(view.player);
    }
    private static void updateListName(View view, int score) {
        Tab pane = view.tab;
        if (pane.listAbandoned) return;
        Component expected = pane.listName == null ? pane.previousListName : pane.listName;
        if (!Objects.equals(view.player.playerListName(), expected)) {
            pane.listAbandoned = true;
            return;
        }
        Component base = pane.previousListName == null ? white(view.player.getName()) : pane.previousListName;
        Component next = base.append(gray(" [" + score + "]"));
        if (!next.equals(pane.listName)) {
            view.player.playerListName(next);
            pane.listName = next;
        }
    }

    private static void release(View view) {
        releaseTab(view);
        // Titles/actionbars have no readable ownership handle: let their finite lifetime expire.
    }

    private static void releaseTab(View view) {
        Tab tab=view.tab;
        if(tab==null)return;
        if(tab.listName!=null && Objects.equals(view.player.playerListName(),tab.listName))
            view.player.playerListName(tab.previousListName);
        view.tab=null;
    }
    private String playerName(UUID id) {
        if (id == null) return "이름 없음";
        View view = views.get(id);
        String name = view == null ? null : view.player.getName();
        if (name == null) {
            var offline = Bukkit.getOfflinePlayer(id);
            if (offline != null) name = offline.getName();
        }
        return name == null || name.isBlank() ? "이름 없음" : shortText(name, 24);
    }

    private static int round(int round, int total) { return Math.clamp(round, 0, Math.max(0,total)); }
    private static String rounds(int round, int total) { return round + "/" + total; }
    private static String shortText(String text, int limit) {
        String value = text.codePoints().filter(c -> !Character.isISOControl(c)).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return value.codePointCount(0, value.length()) <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit)) + "…";
    }
    private static Component white(String text) { return Component.text(text, NamedTextColor.WHITE); }
    private static Component gray(String text) { return Component.text(text, NamedTextColor.GRAY); }
    private static Component original(String text){return Component.text(text,ORIGINAL_WHITE).decorate(TextDecoration.BOLD);}
    private static void framed(Player player, List<Component> lines) {
        player.sendMessage(RULE);
        lines.forEach(player::sendMessage);
        player.sendMessage(RULE);
    }
    private static void cue(Player player, String key, float pitch) {
        player.playSound(Sound.sound(Key.key(key), Sound.Source.PLAYER, key.startsWith("0")?1f:0.18f, pitch));
    }
    private static void mainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Quiz HUD requires the main thread");
    }
}
