package dev.noqui.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class QuizHudTest {
    private MockedStatic<Bukkit> bukkit;
    private ScoreboardManager manager;
    private final List<Board> boards = new ArrayList<>();
    private final Map<UUID, String> knownNames = new HashMap<>();
    private QuizHud hud;

    @BeforeEach void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        manager = mock(ScoreboardManager.class);
        bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
        bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> {
            OfflinePlayer offline = mock(OfflinePlayer.class);
            when(offline.getName()).thenReturn(knownNames.get(call.getArgument(0)));
            return offline;
        });
        when(manager.getNewScoreboard()).thenAnswer(call -> {
            Board board = new Board();
            boards.add(board);
            return board.board;
        });
        hud = new QuizHud();
    }

    @AfterEach void tearDown() {
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        hud.close();
        bukkit.close();
    }

    private static final class Board {
        final Scoreboard board = mock(Scoreboard.class);
        final Objective objective = mock(Objective.class);
        Objective sidebar = objective;
        final Map<String, Component> labels = new LinkedHashMap<>();
        final Map<String, Score> scores = new HashMap<>();

        Board() {
            when(board.registerNewObjective(eq("noqui_hud"), eq("dummy"), any(Component.class), eq(RenderType.INTEGER))).thenReturn(objective);
            when(board.getObjective(DisplaySlot.SIDEBAR)).thenAnswer(call -> sidebar);
            when(board.getObjective("noqui_hud")).thenReturn(objective);
            when(objective.getScore(anyString())).thenAnswer(call -> scores.computeIfAbsent(call.getArgument(0), key -> {
                Score score = mock(Score.class);
                doAnswer(write -> { labels.put(key, write.getArgument(0)); return null; }).when(score).customName(any(Component.class));
                return score;
            }));
            doAnswer(call -> { labels.remove(call.getArgument(0)); return null; }).when(board).resetScores(anyString());
        }

        String text() { return labels.values().stream().map(QuizHudTest::plain).reduce("", (a, b) -> a + "\n" + b); }
    }

    private final class Viewer {
        final Player player = mock(Player.class);
        final UUID id;
        final Scoreboard previous = mock(Scoreboard.class);
        Scoreboard current = previous;
        final Component originalTab;
        Component tab;
        final Set<BossBar> visibleBars = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Component> messages = new ArrayList<>(), actionbars = new ArrayList<>();
        final List<Title> titles = new ArrayList<>();
        final List<Sound> sounds = new ArrayList<>();

        Viewer(String name) { this(UUID.randomUUID(), name, Component.text("기존 " + name)); }
        Viewer(UUID id, String name, Component originalTab) {
            this.id = id;
            this.originalTab = originalTab;
            tab = originalTab;
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(name);
            when(player.getScoreboard()).thenAnswer(call -> current);
            doAnswer(call -> { current = call.getArgument(0); return null; }).when(player).setScoreboard(any(Scoreboard.class));
            when(player.playerListName()).thenAnswer(call -> tab);
            doAnswer(call -> { tab = call.getArgument(0); return null; }).when(player).playerListName(nullable(Component.class));
            doAnswer(call -> { visibleBars.add(call.getArgument(0)); return null; }).when(player).showBossBar(any(BossBar.class));
            doAnswer(call -> { visibleBars.remove(call.getArgument(0)); return null; }).when(player).hideBossBar(any(BossBar.class));
            doAnswer(call -> { messages.add(call.getArgument(0)); return null; }).when(player).sendMessage(any(Component.class));
            doAnswer(call -> { actionbars.add(call.getArgument(0)); return null; }).when(player).sendActionBar(any(Component.class));
            doAnswer(call -> { titles.add(call.getArgument(0)); return null; }).when(player).showTitle(any(Title.class));
            doAnswer(call -> { sounds.add(call.getArgument(0)); return null; }).when(player).playSound(any(Sound.class));
        }

        BossBar bar() { return visibleBars.iterator().next(); }
        String chat() { return messages.stream().map(QuizHudTest::plain).reduce("", (a, b) -> a + "\n" + b); }
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
    private static QuizHud.Frame frame(String phase) {
        return new QuizHud.Frame(3, 67, phase, 61_001, 115_000, "출제자", "ㄱㄴ", "비밀 제목", List.of("비밀 태그"), List.of("비밀 정답자"), 1, 3);
    }

    @Test void lifecycleRestoresOnlyOwnedUiAndRepeatedEntryDoesNotDuplicateBars() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        hud.enter(a.player);
        assertEquals(0, boards.size());
        assertEquals(1, a.visibleBars.size());
        hud.render(a.player, frame("PLAYING"), 2, 4);
        hud.render(a.player, frame("PLAYING"), 2, 4);
        assertEquals(1, boards.size());
        assertSame(boards.getFirst().board, a.current);
        assertTrue(plain(a.tab).endsWith("[2]"));
        assertTrue(boards.getFirst().text().contains("출제권 4"));
        assertEquals(2, a.actionbars.size());
        hud.leave(a.player);
        hud.leave(a.player);
        assertSame(a.previous, a.current);
        assertEquals(a.originalTab, a.tab);
        assertTrue(a.visibleBars.isEmpty());
        verify(boards.getFirst().objective, times(1)).unregister();
        hud.render(a.player, frame("PLAYING"), 99, 99);
        hud.start(a.player, 67);
        hud.countdown(a.player, 3, 4, 67);
        hud.reveal(a.player, "stale", List.of(), List.of(), false);
        assertEquals(2, a.actionbars.size());
        assertTrue(a.messages.isEmpty());
        assertTrue(a.titles.isEmpty());
        verify(a.player, never()).clearTitle();
        verify(a.player, never()).sendPlayerListHeaderAndFooter(any(Component.class), any(Component.class));
    }

    @Test void foreignScoreboardAndTabReplacementAreNeverReclaimedOrRestoredOver() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        hud.render(a.player, frame("PLAYING"), 1, 2);
        Scoreboard foreign = mock(Scoreboard.class);
        Component foreignTab = Component.text("다른 플러그인");
        a.current = foreign;
        a.tab = foreignTab;
        hud.enter(a.player);
        hud.render(a.player, frame("PLAYING"), 8, 9);
        assertSame(foreign, a.current);
        assertSame(foreignTab, a.tab);
        assertFalse(boards.getFirst().text().contains("점수 8"));
        hud.leave(a.player);
        assertSame(foreign, a.current);
        assertSame(foreignTab, a.tab);
    }

    @Test void aForeignSidebarOnTheSameBoardAlsoKeepsItsOwnership() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        hud.render(a.player, frame("PLAYING"), 1, 2);
        Board owned = boards.getFirst();
        String previousRows = owned.text();
        Objective foreign = mock(Objective.class);
        owned.sidebar = foreign;
        hud.render(a.player, frame("PLAYING"), 3, 2);
        assertEquals(previousRows, owned.text());
        hud.leave(a.player);
        assertSame(owned.board, a.current);
        assertSame(foreign, owned.sidebar);
        verify(foreign, never()).unregister();
        verify(owned.objective).unregister();
    }

    @Test void rejoinWithANewPlayerHandleReleasesOldUiAndIgnoresStaleOldCalls() {
        Viewer old = new Viewer("Alpha");
        Viewer fresh = new Viewer(old.id, "Alpha", Component.text("재입장"));
        hud.enter(old.player);
        hud.render(old.player, frame("PLAYING"), 2, 1);
        hud.enter(fresh.player);
        assertTrue(old.visibleBars.isEmpty());
        assertSame(old.previous, old.current);
        assertEquals(old.originalTab, old.tab);
        hud.leave(old.player);
        hud.render(old.player, frame("PLAYING"), 9, 9);
        assertEquals(1, fresh.visibleBars.size());
        hud.render(fresh.player, frame("PLAYING"), 5, 8);
        assertTrue(plain(fresh.tab).contains("[5]"));
        hud.leave(fresh.player);
        assertEquals(fresh.originalTab, fresh.tab);
    }

    @Test void closeReleasesOnlyItsBarsForEveryPlayerAndPreventsResurrection() {
        Viewer a = new Viewer("Alpha"), b = new Viewer("Beta");
        BossBar foreign = BossBar.bossBar(Component.text("다른 UI"), .5f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        a.visibleBars.add(foreign);
        hud.enter(a.player);
        hud.enter(b.player);
        hud.render(a.player, frame("PLAYING"), 1, 2);
        hud.render(b.player, frame("PLAYING"), 7, 8);
        assertTrue(boards.get(0).text().contains("점수 1"));
        assertTrue(boards.get(1).text().contains("점수 7"));
        assertTrue(boards.get(0).text().contains("출제권 2"));
        assertTrue(boards.get(1).text().contains("출제권 8"));
        hud.close();
        hud.close();
        assertEquals(Set.of(foreign), a.visibleBars);
        assertTrue(b.visibleBars.isEmpty());
        assertSame(a.previous, a.current);
        assertSame(b.previous, b.current);
        assertEquals(a.originalTab, a.tab);
        assertEquals(b.originalTab, b.tab);
        hud.enter(a.player);
        hud.render(a.player, frame("PLAYING"), 99, 99);
        assertEquals(2, boards.size());
        verify(a.player, never()).hideBossBar(foreign);
    }

    @Test void frameCopiesListsAndRenderNeverLeaksPrivateAnswerBeforeReveal() {
        var tags = new ArrayList<>(List.of("비밀 태그"));
        var winners = new ArrayList<>(List.of("비밀 정답자"));
        QuizHud.Frame input = new QuizHud.Frame(3, 67, "PLAYING", 60000, 115000, "출제자", "ㄱㄴ", "비밀 제목", tags, winners, 1, 3);
        tags.add("변경"); winners.clear();
        assertEquals(List.of("비밀 태그"), input.tags());
        assertEquals(List.of("비밀 정답자"), input.winners());
        assertThrows(UnsupportedOperationException.class, () -> input.tags().add("변경"));
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        for (String phase : List.of("WAITING_FOR_SUBMISSION", "WAITING_FOR_MEDIA", "PREPARING", "COUNTDOWN", "PLAYING", "PAUSED", "INTERMISSION", "STOPPED", "ENDED")) {
            hud.render(a.player, new QuizHud.Frame(3, 67, phase, 60000, 115000, input.presenter(), input.hint(), input.answerTitle(), input.tags(), input.winners(), 1, 3), 2, 4);
            String visible = boards.getFirst().text() + plain(a.bar().name()) + a.chat();
            assertFalse(visible.contains("비밀"), phase);
            assertEquals(8, boards.getFirst().labels.size());
        }
        hud.render(a.player, frame("REVEAL"), 3, 5);
        assertTrue(boards.getFirst().text().contains("비밀 제목"));
        assertTrue(boards.getFirst().text().contains("비밀 태그"));
        assertTrue(boards.getFirst().text().contains("비밀 정답자"));
        hud.render(a.player, frame("PLAYING"), 4, 6);
        assertFalse(boards.getFirst().text().contains("비밀"));
        assertFalse(plain(a.bar().name()).contains("비밀"));
    }

    @Test void progressTimeAndFiniteRoundDisplaysHandleBoundariesWithoutOverflow() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        long[][] inputs = {{5000,10000},{20000,10000},{-1,10000},{1000,0},{Long.MAX_VALUE,1}};
        float[] expected = {.5f,1,0,0,1};
        for (int i = 0; i < inputs.length; i++) {
            hud.render(a.player, new QuizHud.Frame(99, 2, "PLAYING", inputs[i][0], inputs[i][1], "A", "", "private", List.of(), List.of(), -2, -4), 0, -1);
            assertEquals(expected[i], a.bar().progress());
            assertTrue(boards.getFirst().text().contains("라운드 2 / 2"));
            assertTrue(boards.getFirst().text().contains("출제권 0"));
            assertTrue(boards.getFirst().text().contains("스킵 0 / 0"));
        }
        hud.render(a.player, frame("PLAYING"), 3, 4);
        assertTrue(boards.getFirst().text().contains("남은 시간 1:02"));
        hud.render(a.player, new QuizHud.Frame(-2, -8, "unknown", -1, -1, "", "", "private", List.of(), List.of(), 0, 0), 0, 0);
        assertTrue(boards.getFirst().text().contains("라운드 0 · 무제한"));
        assertTrue(boards.getFirst().text().contains("남은 시간 0:00"));
    }

    @Test void startCountdownAndRevealUseFramedCompleteTextAndQuietFiniteTitles() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        hud.start(a.player, 67);
        assertTrue(a.chat().contains("총 67 라운드입니다"));
        // Original quiz.sk:70–75; color/spacing and original sound event keys.
        assertEquals(List.of("=====================[노래퀴즈 시작]=====================", "              총 67 라운드입니다"),
                a.messages.stream().map(QuizHudTest::plain).toList());
        assertEquals("퀴즈를 시작합니다",plain(a.titles.getLast().title()));
        hud.countdown(a.player, 3, 2, 67);
        hud.countdown(a.player, 3, 2, 67);
        hud.countdown(a.player, 0, 2, 67);
        hud.countdown(a.player, 4, 2, 67);
        assertEquals(2, a.titles.size());
        assertEquals("3", plain(a.titles.getLast().title()));
        assertEquals("",plain(a.titles.getLast().subtitle()));
        assertTrue(a.chat().contains("라운드 2/67"));
        hud.reveal(a.player, "전체 정답 제목", List.of("첫 태그", "마지막 태그"), List.of("Alpha", "Beta"), false);
        int count = a.messages.size();
        hud.reveal(a.player, "전체 정답 제목", List.of("첫 태그", "마지막 태그"), List.of("Alpha", "Beta"), false);
        assertEquals(count, a.messages.size());
        assertTrue(a.chat().contains("정답: 『전체 정답 제목』"));
        assertTrue(a.chat().contains("#첫 태그 #마지막 태그"));
        assertTrue(a.chat().contains("정답자: Alpha, Beta"));
        assertEquals("전체 정답 제목", plain(a.titles.getLast().title()));
        assertTrue(a.titles.getLast().times().stay().toMillis() < 10000);
        assertTrue(a.sounds.stream().allMatch(sound -> sound.volume() == 1f));
        assertEquals(List.of("0select2","0click2","0win"),a.sounds.stream().map(sound->sound.name().value()).toList());
        hud.reveal(a.player, "스킵한 정답", List.of("태그"), List.of(), true);
        assertTrue(a.chat().contains("스킵한 정답"));
        assertTrue(a.chat().contains("아무도 정답을 맞히지 못했습니다"));
        assertTrue(a.sounds.stream().anyMatch(sound -> sound.name().value().equals("0heal")));
    }

    @Test void finishShowsTopTenCompetitionRanksAndUnknownNamesWithoutRawUuidThenRestores() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        hud.render(a.player, frame("PLAYING"), 12, 4);
        Map<UUID, Integer> scores = new LinkedHashMap<>();
        scores.put(a.id, 12);
        UUID beta = UUID.randomUUID(), gamma = UUID.randomUUID(), unknown = UUID.randomUUID();
        knownNames.put(beta, "Beta"); knownNames.put(gamma, "Gamma");
        scores.put(beta, 12); scores.put(gamma, 10); scores.put(unknown, 9);
        for (int i = 0; i < 8; i++) {
            UUID id = UUID.randomUUID(); knownNames.put(id, "Guest" + i); scores.put(id, 8 - i);
        }
        hud.finish(a.player, scores);
        List<String> ranking = a.messages.stream().map(QuizHudTest::plain).filter(line -> line.matches("\\d+\\. .*" )).toList();
        assertEquals(10, ranking.size());
        assertTrue(ranking.contains("1. Alpha  · · · · ·  12"));
        assertTrue(ranking.contains("1. Beta  · · · · ·  12"));
        assertTrue(ranking.contains("3. Gamma  · · · · ·  10"));
        assertTrue(ranking.contains("4. 이름 없음  · · · · ·  9"));
        assertFalse(a.chat().contains(unknown.toString()));
        assertEquals("퀴즈 종료", plain(a.titles.getLast().title()));
        assertTrue(a.visibleBars.isEmpty());
        assertSame(a.previous, a.current);
        assertEquals(a.originalTab, a.tab);
        int messageCount = a.messages.size();
        hud.leave(a.player); hud.finish(a.player, scores);
        assertEquals(messageCount, a.messages.size());
        verify(a.player, never()).clearTitle();
    }

    @Test void nullOriginalTabNameRestoresAndRepeatedScoreChangesDoNotAccumulateSuffixes() {
        Viewer a = new Viewer(UUID.randomUUID(), "Alpha", null);
        hud.enter(a.player);
        hud.render(a.player, frame("PLAYING"), 1, 2);
        hud.render(a.player, frame("PLAYING"), 2, 4);
        assertEquals("Alpha [2]", plain(a.tab));
        hud.leave(a.player);
        assertNull(a.tab);
    }

    @Test void allUiMutationsRequireTheMainThread() {
        Viewer a = new Viewer("Alpha");
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> hud.enter(a.player));
        assertThrows(IllegalStateException.class, () -> hud.setImmersive(a.player, false));
        assertThrows(IllegalStateException.class, () -> hud.render(a.player, frame("PLAYING"), 1, 2));
        assertThrows(IllegalStateException.class, () -> hud.start(a.player, 67));
        assertThrows(IllegalStateException.class, () -> hud.countdown(a.player, 3, 1, 67));
        assertThrows(IllegalStateException.class, () -> hud.reveal(a.player, "title", List.of(), List.of(), false));
        assertThrows(IllegalStateException.class, () -> hud.finish(a.player, Map.of()));
        assertThrows(IllegalStateException.class, () -> hud.leave(a.player));
        assertThrows(IllegalStateException.class, hud::close);
        assertTrue(boards.isEmpty());
    }

    @Test void defenseOverlayNeverReadsOrMutatesScoreboardTabActionbarOrCentralTitles() {
        Viewer a = new Viewer("Alpha");
        BossBar foreign = BossBar.bossBar(Component.text("디펜스"), .5f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        a.visibleBars.add(foreign);
        clearInvocations(a.player);
        hud.enter(a.player);
        hud.setImmersive(a.player, false);
        hud.enter(a.player); // Duplicate entry must not switch the existing overlay back to immersive.
        hud.render(a.player, frame("PLAYING"), 5, 8);
        assertEquals(2, a.visibleBars.size());
        hud.start(a.player, 67);
        hud.countdown(a.player, 3, 2, 67);
        hud.reveal(a.player, "공개 정답", List.of("태그"), List.of("Alpha"), false);
        assertTrue(a.chat().contains("정답: 『공개 정답』"));
        hud.finish(a.player, Map.of(a.id, 5));
        assertTrue(a.chat().contains("TOP"));
        assertEquals(Set.of(foreign), a.visibleBars);
        assertFalse(a.sounds.isEmpty());
        assertTrue(a.titles.isEmpty());
        assertTrue(a.actionbars.isEmpty());
        assertTrue(boards.isEmpty());
        assertSame(a.previous, a.current);
        assertEquals(a.originalTab, a.tab);
        verify(a.player, never()).getScoreboard();
        verify(a.player, never()).setScoreboard(any());
        verify(a.player, never()).playerListName();
        verify(a.player, never()).playerListName(nullable(Component.class));
        verify(a.player, never()).clearTitle();
        verify(a.player, never()).hideBossBar(foreign);
    }

    @Test void switchingModesRestoresOwnedStateAndCapturesFreshDefenseUiOnReturn() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        BossBar ownBar = a.bar();
        hud.render(a.player, frame("PLAYING"), 2, 4);
        assertNotSame(a.previous, a.current);
        hud.setImmersive(a.player, false);
        assertSame(a.previous, a.current);
        assertEquals(a.originalTab, a.tab);
        int actionbarCount = a.actionbars.size();
        Scoreboard defense = mock(Scoreboard.class);
        Component defenseTab = Component.text("새 디펜스 상태");
        a.current = defense;
        a.tab = defenseTab;
        hud.render(a.player, frame("PLAYING"), 6, 9);
        assertSame(defense, a.current);
        assertSame(defenseTab, a.tab);
        assertEquals(actionbarCount, a.actionbars.size());
        hud.setImmersive(a.player, true);
        assertEquals(2, boards.size());
        assertNotSame(defense, a.current);
        hud.render(a.player, frame("PLAYING"), 7, 10);
        assertEquals("새 디펜스 상태 [7]", plain(a.tab));
        assertTrue(boards.getLast().text().contains("출제권 10"));
        assertEquals(Set.of(ownBar), a.visibleBars);
        hud.setImmersive(a.player, false);
        hud.setImmersive(a.player, false);
        assertSame(defense, a.current);
        assertEquals(defenseTab, a.tab);
        hud.close();
        assertSame(defense, a.current);
        assertEquals(defenseTab, a.tab);
    }

    @Test void foreignReplacementsSurviveBothModeChangeAndFinalLeave() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        hud.render(a.player, frame("PLAYING"), 1, 2);
        Scoreboard replacement = mock(Scoreboard.class);
        Component replacementTab = Component.text("다른 소유자");
        a.current = replacement;
        a.tab = replacementTab;
        hud.setImmersive(a.player, false);
        assertSame(replacement, a.current);
        assertSame(replacementTab, a.tab);
        hud.setImmersive(a.player, true);
        hud.render(a.player, frame("PLAYING"), 3, 4);
        hud.leave(a.player);
        assertSame(replacement, a.current);
        assertSame(replacementTab, a.tab);
    }

    @Test void overlayBossbarKeepsPrivateFieldsHiddenUntilReveal() {
        Viewer a = new Viewer("Alpha");
        hud.enter(a.player);
        hud.setImmersive(a.player, false);
        hud.render(a.player, frame("PLAYING"), 1, 2);
        assertFalse(plain(a.bar().name()).contains("비밀"));
        assertTrue(plain(a.bar().name()).contains("1점"));
        assertTrue(plain(a.bar().name()).contains("출제권 2"));
        assertTrue(plain(a.bar().name()).contains("스킵 1/3"));
        assertTrue(plain(a.bar().name()).contains("출제 출제자"));
        assertTrue(plain(a.bar().name()).contains("힌트 ㄱㄴ"));
        hud.render(a.player, frame("REVEAL"), 1, 2);
        assertTrue(plain(a.bar().name()).contains("비밀 제목"));
        assertTrue(a.actionbars.isEmpty());
        assertTrue(a.titles.isEmpty());
        assertTrue(boards.isEmpty());
    }

    @Test void pausedAndEndedLabelsRenderInBothImmersiveAndOverlayModes() {
        Viewer immersive = new Viewer("Alpha"), overlay = new Viewer("Beta");
        hud.enter(immersive.player);
        hud.enter(overlay.player);
        hud.setImmersive(overlay.player, false);
        for (var expected : Map.of("PAUSED", "일시정지", "ENDED", "종료").entrySet()) {
            hud.render(immersive.player, frame(expected.getKey()), 1, 2);
            hud.render(overlay.player, frame(expected.getKey()), 3, 4);
            assertTrue(boards.getFirst().text().contains("상태 " + expected.getValue()));
            assertTrue(plain(immersive.bar().name()).contains(expected.getValue()));
            assertTrue(plain(overlay.bar().name()).contains(expected.getValue()));
            assertFalse(boards.getFirst().text().contains("비밀"));
            assertFalse(plain(overlay.bar().name()).contains("비밀"));
        }
        assertEquals(1, boards.size());
        assertTrue(overlay.titles.isEmpty());
        assertTrue(overlay.actionbars.isEmpty());
    }

    @Test void unlimitedRoundsKeepTheirActualNumberAndOriginalRevealFrameHasExactSourceText() {
        Viewer a=new Viewer("Alpha");hud.enter(a.player);hud.start(a.player,0);hud.countdown(a.player,3,19,0);
        hud.render(a.player,new QuizHud.Frame(19,0,"PLAYING",30000,115000,"출제자","","비공개",List.of("태그"),List.of(),0,2),4,2);
        assertTrue(boards.getFirst().text().contains("19 · 무제한"));assertTrue(plain(a.bar().name()).contains("19 · 무제한"));
        assertFalse(boards.getFirst().text().contains("비공개"));
        a.messages.clear();hud.reveal(a.player,"정답 제목",List.of("태그1","태그2"),List.of("Alpha"),false);
        // Original func.sk:95–138:53 equals, winner, blank, answer, hashtag keywords.
        assertEquals(List.of("=".repeat(53),"정답자: Alpha","","정답: 『정답 제목』"," #태그1 #태그2","=".repeat(53)),a.messages.stream().map(QuizHudTest::plain).toList());
        assertEquals(0x8aaae5,a.messages.getFirst().color().value());
        assertEquals(0xb2f9e8,a.messages.get(1).color().value());
        assertEquals(0xfefefe,a.messages.get(3).color().value());
    }
}
