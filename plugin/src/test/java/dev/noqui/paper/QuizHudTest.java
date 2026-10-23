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
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class QuizHudTest {
    MockedStatic<Bukkit> bukkit;
    QuizHud hud;
    final List<Viewer> viewers=new ArrayList<>();
    final Map<UUID,String> knownNames=new HashMap<>();
    @BeforeEach void setup() {
        bukkit=mockStatic(Bukkit.class);bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(()->Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(c->{
            OfflinePlayer p=mock(OfflinePlayer.class);when(p.getName()).thenReturn(knownNames.get(c.getArgument(0)));return p;
        });hud=new QuizHud();
    }
    @AfterEach void cleanup() {
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);hud.close();
        // No lifecycle or mode may read, replace, or register a scoreboard.
        for(Viewer v:viewers){verify(v.p,never()).getScoreboard();verify(v.p,never()).setScoreboard(any());verify(v.p,never()).clearTitle();}
        bukkit.verify(Bukkit::getScoreboardManager,never());bukkit.close();
    }
    final class Viewer {
        final Player p=mock(Player.class);final UUID id;final Component originalTab;Component tab;
        final Set<BossBar> bars=Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Component> messages=new ArrayList<>(),actions=new ArrayList<>();
        final List<Title> titles=new ArrayList<>();final List<Sound> sounds=new ArrayList<>();
        Viewer(String name){this(UUID.randomUUID(),name,Component.text("기존 "+name));}
        Viewer(UUID id,String name,Component originalTab) {
            this.id=id;this.originalTab=originalTab;tab=originalTab;viewers.add(this);
            when(p.getUniqueId()).thenReturn(id);when(p.getName()).thenReturn(name);when(p.playerListName()).thenAnswer(c->tab);
            doAnswer(c->{tab=c.getArgument(0);return null;}).when(p).playerListName(nullable(Component.class));
            doAnswer(c->{bars.add(c.getArgument(0));return null;}).when(p).showBossBar(any());
            doAnswer(c->{bars.remove(c.getArgument(0));return null;}).when(p).hideBossBar(any());
            doAnswer(c->{messages.add(c.getArgument(0));return null;}).when(p).sendMessage(any(Component.class));
            doAnswer(c->{actions.add(c.getArgument(0));return null;}).when(p).sendActionBar(any(Component.class));
            doAnswer(c->{titles.add(c.getArgument(0));return null;}).when(p).showTitle(any(Title.class));
            doAnswer(c->{sounds.add(c.getArgument(0));return null;}).when(p).playSound(any(Sound.class));
        }
        BossBar bar(){return bars.iterator().next();}
        String chat(){return messages.stream().map(QuizHudTest::plain).reduce("",(a,b)->a+"\n"+b);}
    }
    static String plain(Component c){return PlainTextComponentSerializer.plainText().serialize(c);}
    static QuizHud.Frame frame(String phase){return new QuizHud.Frame(3,67,phase,61001,115000,"출제자","ㄱㄴ","비밀 제목",List.of("비밀 태그"),List.of("비밀 정답자"),1,3);}

    @Test void lifecycleRestoresOwnedTabAndDoesNotDuplicateBars() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);hud.enter(a.p);assertEquals(1,a.bars.size());
        hud.render(a.p,frame("PLAYING"),2,4);hud.render(a.p,frame("PLAYING"),2,4);
        assertEquals("기존 Alpha [2]",plain(a.tab));assertEquals(2,a.actions.size());
        hud.leave(a.p);hud.leave(a.p);assertEquals(a.originalTab,a.tab);assertTrue(a.bars.isEmpty());
    }
    @Test void externalTabReplacementIsNeverOverwritten() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);hud.render(a.p,frame("PLAYING"),1,0);
        Component foreign=Component.text("디펜스");a.tab=foreign;
        hud.render(a.p,frame("PLAYING"),9,0);hud.render(a.p,frame("PLAYING"),10,0);hud.leave(a.p);assertSame(foreign,a.tab);
    }
    @Test void immersionRestoresThenCapturesFreshTabState() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);BossBar bar=a.bar();hud.render(a.p,frame("PLAYING"),1,0);
        hud.setImmersive(a.p,false);assertEquals(a.originalTab,a.tab);
        Component defense=Component.text("새 디펜스");a.tab=defense;int actions=a.actions.size();
        hud.render(a.p,frame("PLAYING"),2,99);assertSame(defense,a.tab);assertEquals(actions,a.actions.size());
        hud.setImmersive(a.p,true);hud.render(a.p,frame("PLAYING"),3,99);
        assertEquals("새 디펜스 [3]",plain(a.tab));assertEquals(Set.of(bar),a.bars);
        hud.setImmersive(a.p,false);hud.setImmersive(a.p,false);hud.close();assertSame(defense,a.tab);
    }
    @Test void nullTabUsesPlayerNameAndIsRestored() {
        Viewer a=new Viewer(UUID.randomUUID(),"Alpha",null);hud.enter(a.p);hud.render(a.p,frame("PLAYING"),5,0);
        assertEquals("Alpha [5]",plain(a.tab));hud.leave(a.p);assertNull(a.tab);
    }
    @Test void reconnectionReleasesOldHandleAndRejectsStaleCalls() {
        Viewer a=new Viewer("Alpha"),b=new Viewer(a.id,"Alpha",Component.text("재접속"));
        hud.enter(a.p);hud.render(a.p,frame("PLAYING"),1,0);hud.enter(b.p);
        assertTrue(a.bars.isEmpty());assertEquals(a.originalTab,a.tab);hud.render(b.p,frame("PLAYING"),2,0);
        hud.leave(a.p);hud.render(a.p,frame("PLAYING"),9,0);hud.setImmersive(a.p,false);
        assertEquals("재접속 [2]",plain(b.tab));assertEquals(1,b.bars.size());
    }
    @Test void closeReleasesOnlyOwnedBarsAndSubsequentCallsAreInert() {
        Viewer a=new Viewer("Alpha"),b=new Viewer("Beta");hud.enter(a.p);hud.enter(b.p);
        BossBar foreign=BossBar.bossBar(Component.text("디펜스"),1,BossBar.Color.BLUE,BossBar.Overlay.PROGRESS);a.bars.add(foreign);
        hud.render(a.p,frame("PLAYING"),1,0);hud.close();hud.close();assertEquals(Set.of(foreign),a.bars);assertTrue(b.bars.isEmpty());
        clearInvocations(a.p);hud.enter(a.p);hud.render(a.p,frame("PLAYING"),5,0);hud.start(a.p,5);hud.countdown(a.p,3,1,5);
        hud.reveal(a.p,"x",List.of(),List.of(),false);hud.finish(a.p,Map.of());hud.leave(a.p);hud.setImmersive(a.p,true);
        verify(a.p,never()).showBossBar(any());verify(a.p,never()).showTitle(any(Title.class));verify(a.p,never()).sendMessage(any(Component.class));
        verify(a.p,never()).playSound(any(Sound.class));verify(a.p,never()).hideBossBar(foreign);
    }
    @Test void noPrivateAnswerOrTicketBalanceBeforeReveal() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);
        for(String phase:List.of("PREPARING","WAITING_FOR_MEDIA","COUNTDOWN","PLAYING","PAUSED","INTERMISSION","ENDED")) {
            hud.render(a.p,frame(phase),2,123456);String text=plain(a.bar().name());
            assertFalse(text.contains("비밀"));assertFalse(text.contains("123456"));assertFalse(text.contains("출제권"));
        }
        assertFalse(a.chat().contains("비밀"));assertTrue(a.actions.stream().noneMatch(c->plain(c).contains("비밀")));
        hud.render(a.p,frame("REVEAL"),2,123456);assertTrue(plain(a.bar().name()).contains("비밀 제목"));
    }
    @Test void defenseOverlayUsesOnlyBossbarWithoutReadingTabOrSendingTitlesActionbars() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);hud.setImmersive(a.p,false);hud.start(a.p,67);hud.countdown(a.p,3,1,67);
        hud.render(a.p,frame("PLAYING"),7,99);String text=plain(a.bar().name());
        for(String value:List.of("7점","스킵 1/3","출제 출제자","힌트 ㄱㄴ"))assertTrue(text.contains(value));
        hud.reveal(a.p,"정답",List.of(),List.of("Alpha"),false);hud.finish(a.p,Map.of(a.id,7));
        verify(a.p,never()).playerListName();verify(a.p,never()).playerListName(nullable(Component.class));
        assertTrue(a.titles.isEmpty());assertTrue(a.actions.isEmpty());assertTrue(a.chat().contains("정답"));
    }
    @Test void preparationOnlyShowsQuietWaitingWithoutTimeProgressOrMessages() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);
        for(String phase:List.of("PREPARING","WAITING_FOR_MEDIA")) {
            hud.render(a.p,frame(phase),2,0);assertEquals("3 / 67 · 대기 · 2점",plain(a.bar().name()));assertEquals(0,a.bar().progress());
            assertTrue(a.messages.isEmpty());assertTrue(a.actions.isEmpty());assertTrue(a.titles.isEmpty());assertTrue(a.sounds.isEmpty());
        }
    }
    @Test void progressRoundBoundsAndMissingStateAreSafe() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);hud.render(a.p,frame("PLAYING"),1,0);
        assertEquals(61001f/115000,a.bar().progress(),.00001);assertTrue(plain(a.bar().name()).contains("1:02"));
        hud.render(a.p,new QuizHud.Frame(999,67,null,-1,0,null,null,null,null,null,-1,-2),0,0);
        assertEquals(0,a.bar().progress());assertTrue(plain(a.bar().name()).contains("67 / 67 · 종료 · 0:00"));
        hud.render(a.p,new QuizHud.Frame(-1,-1,"unknown",999,100,"","","",List.of(),List.of(),0,0),0,0);
        assertEquals(1,a.bar().progress());assertTrue(plain(a.bar().name()).contains("0 · 무제한 · 대기"));
    }
    @Test void originalStartCountdownFramesSoundsAndDeduplication() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);hud.start(a.p,67);
        assertEquals("=====================[노래퀴즈 시작]=====================",plain(a.messages.getFirst()));
        assertEquals("              총 67 라운드입니다",plain(a.messages.get(1)));
        for(int tick:new int[]{4,3,3,2,2,1,1,0})hud.countdown(a.p,tick,1,67);
        assertEquals(List.of("minecraft:0select2","minecraft:0click2","minecraft:0click2","minecraft:0click2"),a.sounds.stream().map(s->s.name().asString()).toList());
        assertTrue(a.sounds.stream().allMatch(s->s.volume()==1));
        assertEquals(List.of("퀴즈를 시작합니다","3","2","1"),a.titles.stream().map(t->plain(t.title())).toList());
        assertTrue(a.titles.stream().allMatch(t->t.times()!=null&&t.times().stay().toSeconds()<=4));
        assertEquals(List.of("=".repeat(53),"라운드 1/67","=".repeat(53)),a.messages.subList(2,5).stream().map(QuizHudTest::plain).toList());
        hud.countdown(a.p,3,2,67);assertEquals(5,a.sounds.size());
    }
    @Test void originalRevealColorsTextAndUnlimitedRound() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);hud.start(a.p,0);hud.countdown(a.p,3,19,0);
        hud.render(a.p,new QuizHud.Frame(19,0,"PLAYING",30000,115000,"출제자","","비공개",List.of(),List.of(),0,2),4,2);
        assertTrue(plain(a.bar().name()).contains("19 · 무제한"));assertFalse(plain(a.bar().name()).contains("비공개"));
        a.messages.clear();a.sounds.clear();hud.reveal(a.p,"정답 제목",List.of("태그1","태그2"),List.of("Alpha"),false);
        assertEquals(List.of("=".repeat(53),"정답자: Alpha","","정답: 『정답 제목』"," #태그1 #태그2","=".repeat(53)),a.messages.stream().map(QuizHudTest::plain).toList());
        assertEquals(0x8aaae5,a.messages.getFirst().color().value());assertEquals(0xb2f9e8,a.messages.get(1).color().value());assertEquals(0xfefefe,a.messages.get(3).color().value());
        assertEquals("minecraft:0win",a.sounds.getFirst().name().asString());
        hud.reveal(a.p,"정답 제목",List.of("태그1","태그2"),List.of("Alpha"),false);assertEquals(6,a.messages.size());
    }
    @Test void skippedNoWinnerRevealHasOriginalEmptyResultAndBothCues() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);hud.reveal(a.p,"노래",List.of(),List.of(),true);
        assertEquals("아무도 정답을 맞히지 못했습니다",plain(a.messages.get(1)));assertEquals("",plain(a.messages.get(4)));
        assertEquals(List.of("minecraft:0win","minecraft:0heal"),a.sounds.stream().map(s->s.name().asString()).toList());
    }
    @Test void rankingUsesCompetitionTiesTopTenAndNoUuidFallback() {
        Viewer a=new Viewer("Alpha");hud.enter(a.p);Map<UUID,Integer> scores=new LinkedHashMap<>();scores.put(a.id,12);
        UUID tie=UUID.randomUUID();knownNames.put(tie,"Beta");scores.put(tie,12);UUID unknown=UUID.randomUUID();scores.put(unknown,11);
        for(int i=0;i<11;i++){UUID id=UUID.randomUUID();knownNames.put(id,"참가자"+i);scores.put(id,10-i);}
        hud.finish(a.p,scores);assertTrue(a.chat().contains("1. Alpha"));assertTrue(a.chat().contains("1. Beta"));assertTrue(a.chat().contains("3. 이름 없음"));
        assertFalse(a.chat().contains(unknown.toString()));assertFalse(a.chat().contains("참가자10"));
        assertTrue(a.chat().contains("퀴즈가 종료되었습니다"));assertTrue(a.chat().contains("=".repeat(30)));assertTrue(a.bars.isEmpty());
    }
    @Test void frameDefensivelyCopiesPrivateLists() {
        List<String> tags=new ArrayList<>(List.of("태그")),winners=new ArrayList<>(List.of("이름"));
        var frame=new QuizHud.Frame(1,2,"playing",1,2,null,null,null,tags,winners,0,0);tags.clear();winners.clear();
        assertEquals(List.of("태그"),frame.tags());assertEquals(List.of("이름"),frame.winners());assertEquals("PLAYING",frame.phase());
        assertEquals("",frame.presenter());assertEquals("",frame.hint());assertEquals("",frame.answerTitle());
    }
    @Test void everyEntryPointRequiresMainThread() {
        Viewer a=new Viewer("Alpha");bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        for(Runnable call:List.<Runnable>of(()->hud.enter(a.p),()->hud.setImmersive(a.p,false),()->hud.render(a.p,frame("PLAYING"),1,0),
                ()->hud.start(a.p,2),()->hud.countdown(a.p,3,1,2),()->hud.reveal(a.p,"",List.of(),List.of(),false),
                ()->hud.finish(a.p,Map.of()),()->hud.leave(a.p),hud::close))assertThrows(IllegalStateException.class,call::run);
    }
}
