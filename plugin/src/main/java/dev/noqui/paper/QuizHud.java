package dev.noqui.paper;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.time.Duration;
import java.util.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;

/** Main-thread presentation only. The game owns timing; this class never schedules work. */
final class QuizHud implements AutoCloseable {
    record Frame(int round, int totalRounds, String phase, long remainingMs, long durationMs,
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

    private static final String OBJECTIVE = "noqui_hud";
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
        Pane pane;
        final BossBar bar = BossBar.bossBar(Component.text("노래퀴즈"), 0, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        int round, total, countdown = -1, countdownRound = -1, countdownTotal = -1;
        String phase = "";
        Reveal lastReveal;

        View(Player player) { this.player = player; }
    }

    /** One immersive ownership interval; a later interval captures fresh external state. */
    private static final class Pane {
        final Scoreboard previousBoard, board;
        final Objective objective;
        final Component previousListName;
        Component listName;
        boolean listAbandoned;
        List<Component> rows = List.of();

        Pane(Player player, Scoreboard board, Objective objective) {
            this.previousBoard = player.getScoreboard();
            this.previousListName = player.playerListName();
            this.board = board;
            this.objective = objective;
        }
    }

    private record Reveal(int round, int total, String title, List<String> tags, List<String> winners, boolean skipped) {}
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
        player.showBossBar(view.bar);
    }

    void setImmersive(Player player, boolean immersive) {
        mainThread();
        View view = view(player);
        if (view == null || view.immersive == immersive) return;
        view.immersive = immersive;
        view.countdown = -1;
        if (immersive) claimPane(view);
        else releasePane(view);
    }

    void render(Player player, Frame frame, int score, long credits) {
        mainThread();
        View view = view(player);
        if (view == null) return;
        Objects.requireNonNull(frame, "frame");
        view.total = Math.max(0, frame.totalRounds());
        view.round = round(frame.round(), view.total);
        if (!frame.phase().equals(view.phase)) view.countdown = -1;
        view.phase = frame.phase();
        boolean revealed = frame.phase().equals("REVEAL");
        String rounds = rounds(view.round, view.total);
        String time = time(frame.remainingMs());
        String label = phase(frame.phase());
        Component bar = white(rounds).append(gray(" · " + label + " · " + time));
        if (revealed && !frame.answerTitle().isBlank()) bar = bar.append(gray(" · ")).append(white(shortText(frame.answerTitle(), 40)));
        if (!view.immersive) {
            bar = bar.append(gray(" · " + score + "점 · 출제권 " + Math.max(0, credits)
                    + " · 스킵 " + Math.max(0, frame.skipVotes()) + "/" + Math.max(0, frame.skipRequired())));
            if (!frame.presenter().isBlank()) bar = bar.append(gray(" · 출제 " + shortText(frame.presenter(), 16)));
            if (frame.phase().equals("PLAYING") && !frame.hint().isBlank())
                bar = bar.append(gray(" · 힌트 ")).append(white(shortText(frame.hint(), 24)));
        }
        view.bar.name(bar);
        view.bar.progress(frame.durationMs() <= 0 ? 0 : (float) Math.clamp((double) frame.remainingMs() / frame.durationMs(), 0, 1));
        if (!view.immersive) return;
        claimPane(view);

        var rows = new ArrayList<Component>();
        rows.add(line("라운드", rounds));
        rows.add(line("상태", label));
        rows.add(line("남은 시간", time));
        rows.add(line("점수", Integer.toString(score)));
        rows.add(line("출제권", Long.toString(Math.max(0, credits))));
        rows.add(line("스킵", Math.max(0, frame.skipVotes()) + " / " + Math.max(0, frame.skipRequired())));
        rows.add(line("출제자", frame.presenter().isBlank() ? "대기" : shortText(frame.presenter(), 24)));
        rows.add(line("힌트", frame.hint().isBlank() ? "—" : shortText(frame.hint(), 40)));
        // Private answer fields are deliberately never read into pre-reveal components.
        if (revealed) {
            rows.add(line("정답", shortText(frame.answerTitle(), 40)));
            rows.add(line("정답자", frame.winners().isEmpty() ? "없음" : shortText(String.join(", ", frame.winners()), 40)));
            if (!frame.tags().isEmpty()) rows.add(line("태그", shortText(String.join(" · ", frame.tags()), 40)));
        }
        if (ownsBoard(view)) updateRows(view.pane, rows);
        updateListName(view, score);
        if (frame.phase().equals("PLAYING") && !frame.hint().isBlank())
            player.sendActionBar(Component.text("[힌트] ",NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
                    .append(white(frame.hint()).decorate(TextDecoration.BOLD)));
    }

    void start(Player player, int totalRounds) {
        mainThread();
        View view = view(player);
        if (view == null) return;
        view.round = 0;
        view.total = Math.max(0, totalRounds);
        view.countdown = -1;
        view.lastReveal = null;
        if (view.immersive) player.showTitle(Title.title(original("퀴즈를 시작합니다"), Component.empty(), SHORT_TITLE));
        view.bar.name(white("노래퀴즈").append(gray(" · " + total(view.total))));
        player.sendMessage(Component.text("=====================[", ORIGINAL_FRAME).append(Component.text("노래퀴즈 시작", ORIGINAL_WINNER).decorate(TextDecoration.BOLD)).append(Component.text("]=====================", ORIGINAL_FRAME)));
        player.sendMessage(original("              " + total(view.total) + "입니다"));
        cue(player, "0select2", 1.0f);
    }

    void countdown(Player player, int remaining, int round, int totalRounds) {
        mainThread();
        View view = view(player);
        if (view == null || remaining < 1 || remaining > 3) return;
        int total = Math.max(0, totalRounds), current = round(round, total);
        if (view.countdown == remaining && view.countdownRound == current && view.countdownTotal == total) return;
        view.round = current;
        view.total = total;
        view.countdown = remaining;
        view.countdownRound = current;
        view.countdownTotal = total;
        view.bar.name(white(rounds(current, total)).append(gray(" · 시작 " + remaining)));
        if (remaining == 3) framed(player,List.of(original("라운드 " + originalRounds(current,total))));
        if (view.immersive) player.showTitle(Title.title(Component.text(Integer.toString(remaining),NamedTextColor.YELLOW).decorate(TextDecoration.BOLD), Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
        cue(player, "0click2", 1.0f);
    }

    void reveal(Player player, String title, List<String> tags, List<String> winners, boolean skipped) {
        mainThread();
        View view = view(player);
        if (view == null) return;
        Reveal reveal = new Reveal(view.round, view.total, Objects.requireNonNullElse(title, ""),
                tags == null ? List.of() : List.copyOf(tags), winners == null ? List.of() : List.copyOf(winners), skipped);
        if (reveal.equals(view.lastReveal)) return;
        view.lastReveal = reveal;
        var lines = new ArrayList<Component>();
        lines.add(Component.text(reveal.winners().isEmpty() ? "아무도 정답을 맞히지 못했습니다" : "정답자: " + String.join(", ", reveal.winners()),ORIGINAL_WINNER).decorate(TextDecoration.BOLD));
        lines.add(Component.empty());
        lines.add(original("정답: 『" + reveal.title() + "』"));
        lines.add(reveal.tags().isEmpty()?Component.empty():Component.text(" #"+String.join(" #",reveal.tags()),TextColor.color(0xcfcfc4)));
        framed(player, lines);
        view.bar.name(white(rounds(view.round, view.total)).append(gray(" · 정답 ")).append(white(shortText(reveal.title(), 64))));
        if (view.immersive) player.showTitle(Title.title(white(reveal.title()), gray("라운드 " + rounds(view.round, view.total)), REVEAL_TITLE));
        cue(player, "0win", 1.0f);
        if (skipped) cue(player, "0heal", 1.0f);
    }

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
            lines.add(Component.text(rank+". ",color).decorate(TextDecoration.BOLD).append(white(entry.name())).append(gray("  · · · · ·  ")).append(Component.text(Integer.toString(entry.score()),NamedTextColor.YELLOW)));
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

    private static boolean ownsBoard(View view) {
        Pane pane = view.pane;
        return pane != null && view.player.getScoreboard() == pane.board && pane.board.getObjective(DisplaySlot.SIDEBAR) == pane.objective;
    }

    private static void claimPane(View view) {
        if (view.pane != null) return;
        Scoreboard board = Objects.requireNonNull(Bukkit.getScoreboardManager(), "Scoreboard manager unavailable").getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE, "dummy", white("노래퀴즈"), RenderType.INTEGER);
        objective.numberFormat(NumberFormat.blank());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        view.pane = new Pane(view.player, board, objective);
        view.player.setScoreboard(board);
    }

    private static void updateRows(Pane view, List<Component> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (i < view.rows.size() && rows.get(i).equals(view.rows.get(i))) continue;
            var score = view.objective.getScore("noqui_row_" + i);
            score.customName(rows.get(i));
            score.setScore(15 - i);
        }
        for (int i = rows.size(); i < view.rows.size(); i++) view.board.resetScores("noqui_row_" + i);
        view.rows = List.copyOf(rows);
    }

    private static void updateListName(View view, int score) {
        Pane pane = view.pane;
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
        view.player.hideBossBar(view.bar);
        releasePane(view);
        // Titles/actionbars have no readable ownership handle: let their finite lifetime expire.
    }

    private static void releasePane(View view) {
        Pane pane = view.pane;
        if (pane == null) return;
        if (ownsBoard(view)) view.player.setScoreboard(pane.previousBoard);
        if (pane.listName != null && Objects.equals(view.player.playerListName(), pane.listName))
            view.player.playerListName(pane.previousListName);
        if (pane.board.getObjective(OBJECTIVE) == pane.objective) pane.objective.unregister();
        view.pane = null;
    }

    private String playerName(UUID id) {
        View view = views.get(id);
        String name = view == null ? null : view.player.getName();
        if (name == null) {
            var offline = Bukkit.getOfflinePlayer(id);
            if (offline != null) name = offline.getName();
        }
        return name == null || name.isBlank() ? "이름 없음" : shortText(name, 24);
    }

    private static String phase(String phase) {
        return switch (phase) {
            case "PLAYING" -> "정답 대기";
            case "PAUSED" -> "일시정지";
            case "REVEAL" -> "정답 공개";
            case "WAITING_FOR_MEDIA" -> "오디오 준비";
            case "PREPARING" -> "재생 준비";
            case "COUNTDOWN" -> "시작 대기";
            case "WAITING_FOR_CREDITS" -> "출제권 대기";
            case "WAITING_FOR_SUBMISSION", "WAITING_FOR_PRESENTER" -> "출제 대기";
            case "INTERMISSION" -> "다음 라운드";
            case "STOPPED", "FINISHED", "ENDED" -> "종료";
            default -> "대기";
        };
    }

    private static int round(int round, int total) { return total <= 0 ? Math.max(0,round) : Math.clamp(round, 0, total); }
    private static String rounds(int round, int total) { return total<=0?round+" · 무제한":round + " / " + total; }
    private static String originalRounds(int round,int total){return total<=0?round+" · 무제한":round+"/"+total;}
    private static String total(int total){return total<=0?"무제한":"총 "+total+" 라운드";}
    private static String time(long millis) {
        long seconds = millis <= 0 ? 0 : 1 + (millis - 1) / 1000;
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }
    private static String shortText(String text, int limit) {
        String value = text.codePoints().filter(c -> !Character.isISOControl(c)).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return value.codePointCount(0, value.length()) <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit)) + "…";
    }
    private static Component line(String label, String value) { return gray(label + " ").append(white(value)); }
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
