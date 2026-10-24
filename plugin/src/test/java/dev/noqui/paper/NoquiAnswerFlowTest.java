package dev.noqui.paper;

import dev.noqui.api.LobbyService;
import dev.noqui.bridge.BridgeClient;
import dev.noqui.core.*;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class NoquiAnswerFlowTest {
    private static void set(NoquiPlugin plugin,String field,Object value){try{var f=NoquiPlugin.class.getDeclaredField(field);f.setAccessible(true);f.set(plugin,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static final class Fixture {
        final UUID author=UUID.randomUUID(),other=UUID.randomUUID();
        final Player player=mock(Player.class);
        final NoquiPlugin plugin=mock(NoquiPlugin.class,CALLS_REAL_METHODS);
        final NoquiListener listener=new NoquiListener(plugin);
        final LobbyService wallet=mock(LobbyService.class);
        final Queue<Runnable> main=new ArrayDeque<>();
        final Map<UUID,BridgeClient.Member> members=new HashMap<>();
        final long[] now={100000};
        int roundEnds;
        final CompletableFuture<MediaBridge.Media> media=new CompletableFuture<>();
        final QuizGame game;
        Fixture(String title,boolean solo){this(title,solo,false);}
        Fixture(String title,boolean solo,boolean noncompetitive) {
            when(player.getUniqueId()).thenReturn(author);when(player.getName()).thenReturn("Presenter");when(player.isOnline()).thenReturn(true);
            members.put(author,new BridgeClient.Member(author,UUID.randomUUID(),"Presenter",null));members.put(other,new BridgeClient.Member(other,UUID.randomUUID(),"Other",null));set(plugin,"members",members);
            doAnswer(c->{main.add(c.getArgument(0));return null;}).when(plugin).main(any());
            var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request request){return media;}public void cancel(UUID session,long revision){}public void publish(QuizGame.Snapshot state){}};
            game=new QuizGame(bridge,main::add,()->now[0],new QuizGame.Output(){
                public void changed(QuizGame.Snapshot state){set(plugin,"answerState",new NoquiPlugin.AnswerState(state,Map.copyOf(members)));}
                public void error(UUID player,String message){}
                public void correctAnswer(UUID round,UUID player,int points){wallet.rewardAnswerPoints(player,"noqui-answer:"+round+":"+player,points);}
                public void roundEnded(){roundEnds++;}
            },115,false);set(plugin,"game",game);
            if(noncompetitive)game.modes(game.modes().with(QuizModes.Mode.NONCOMPETITIVE,true));
            game.start(solo?List.of(author):List.of(author,other));game.grant(author,1);game.submit(author,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc",title,"태그"));
            media.complete(new MediaBridge.Media("fixture",60000));drain();game.browsersReady(game.snapshot().revision(),true,true);game.controlAccepted(game.snapshot().revision());now[0]=game.snapshot().startsAt();game.tick();
        }
        void drain(){while(!main.isEmpty())main.remove().run();}
        AsyncPlayerChatEvent legacy(String text){return new AsyncPlayerChatEvent(true,player,text,new HashSet<>(Set.of(player)));}
        AsyncChatEvent modern(String original,String converted){return new AsyncChatEvent(true,player,new HashSet<Audience>(Set.of(player)),mock(ChatRenderer.class),Component.text(converted),Component.text(original),mock(SignedMessage.class));}
    }
    @Test void kakcNormalConversionIsCheckedAfterConversionAndOwnSoloAnswerUsesNormalPoints()throws Exception {
        assertEquals(EventPriority.HIGH,NoquiListener.class.getDeclaredMethod("legacy",AsyncPlayerChatEvent.class).getAnnotation(EventHandler.class).priority());
        var f=new Fixture("뱀파이어",true);var event=f.legacy("qoavkdldj");
        // KAKC's installed NORMAL handler calls setMessage after transliteration; the quiz runs at HIGH.
        event.setMessage("뱀파이어");f.listener.legacy(event);assertTrue(event.isCancelled());assertTrue(event.getRecipients().isEmpty());assertEquals("",event.getMessage());
        assertEquals(0,f.game.snapshot().scores().get(f.author));event.setMessage("뱀파이어");event.setCancelled(false);event.getRecipients().add(f.player);f.listener.legacyGuard(event);assertTrue(event.isCancelled());assertTrue(event.getRecipients().isEmpty());
        f.drain();assertEquals(1,f.game.snapshot().scores().get(f.author));assertEquals(List.of(f.author),f.game.snapshot().winners());assertEquals(0,f.game.credits(f.author));
        verify(f.wallet).rewardAnswerPoints(f.author,"noqui-answer:"+f.game.snapshot().roundId()+":"+f.author,2);
    }
    @Test void modernCurrentMessageAcceptsConvertedAnswerAndDuplicateLegacyModernCallbacksAwardOnce() {
        var f=new Fixture("뱀파이어",false);var modern=f.modern("qoavkdldj","뱀파이어");f.listener.chat(modern);assertTrue(modern.isCancelled());assertTrue(modern.viewers().isEmpty());
        var legacy=f.legacy("뱀파이어");f.listener.legacy(legacy);assertEquals(2,f.main.size());f.drain();assertEquals(1,f.game.snapshot().scores().get(f.author));verify(f.wallet,times(1)).rewardAnswerPoints(eq(f.author),anyString(),eq(2L));
    }
    @Test void plainOwnAnswerWorksWithConverterOffAndUnrelatedChatIsNotHidden() {
        var f=new Fixture("test",true);var ordinary=f.legacy("ordinary conversation");f.listener.legacy(ordinary);assertFalse(ordinary.isCancelled());assertEquals("ordinary conversation",ordinary.getMessage());
        var event=f.legacy("I think TEST!");f.listener.legacy(event);f.drain();assertEquals(1,f.game.snapshot().scores().get(f.author));verify(f.wallet).rewardAnswerPoints(eq(f.author),anyString(),eq(2L));
    }
    @Test void queuedCorrectMessageCannotScoreAfterPauseLeaveOrNewRound() {
        for(String change:List.of("pause","leave","new-round")){
            var f=new Fixture("test",false);f.listener.legacy(f.legacy("test"));
            switch(change){case "pause"->f.game.pause();case "leave"->f.game.leave(f.author);default->{f.game.forceSkip(f.game.snapshot().roundId());f.now[0]+=5000;f.game.tick();}}
            f.drain();verifyNoInteractions(f.wallet);assertEquals(0,f.game.snapshot().scores().get(f.author));
        }
    }
    @Test void convertedWinnerSkipIsHiddenInBothChatFormatsAndDuplicateCallbacksEndOnce()throws Exception {
        assertEquals(EventPriority.HIGH,NoquiListener.class.getDeclaredMethod("chat",AsyncChatEvent.class).getAnnotation(EventHandler.class).priority());
        var f=new Fixture("test",false);assertFalse(f.player.isOp());assertTrue(f.game.voteSkip(f.author,f.game.snapshot().roundId()));assertTrue(f.game.answer(f.author,"test"));
        var legacy=f.legacy("tmzlq");legacy.setMessage("  스킵  ");f.listener.legacy(legacy);assertTrue(legacy.isCancelled());assertEquals("",legacy.getMessage());assertTrue(legacy.getRecipients().isEmpty());
        var modern=f.modern("tmzlq","스킵");f.listener.chat(modern);assertTrue(modern.isCancelled());assertEquals(Component.empty(),modern.message());assertTrue(modern.viewers().isEmpty());assertEquals(2,f.main.size());
        legacy.setCancelled(false);legacy.setMessage("스킵");legacy.getRecipients().add(f.player);f.listener.legacyGuard(legacy);assertTrue(legacy.isCancelled());assertTrue(legacy.getRecipients().isEmpty());
        modern.setCancelled(false);modern.message(Component.text("스킵"));modern.viewers().add(f.player);f.listener.chatGuard(modern);assertTrue(modern.isCancelled());assertTrue(modern.viewers().isEmpty());
        f.drain();assertFalse(f.game.snapshot().playing());assertEquals(1,f.roundEnds);assertEquals(1,f.game.snapshot().skipVotes());assertEquals(f.now[0]+1000,f.game.snapshot().endsAt());
        assertEquals(1,f.game.snapshot().scores().get(f.author));assertEquals(0,f.game.credits(f.author));verify(f.wallet,times(1)).rewardAnswerPoints(eq(f.author),anyString(),eq(2L));
        long revision=f.game.snapshot().revision();assertFalse(f.plugin.captureAnswer(f.author,"스킵"));assertEquals(revision,f.game.snapshot().revision());
    }
    @Test void bothChatFormatsAllowWinnerSkipWhilePausedOrResuming() {
        for(boolean modern:List.of(false,true))for(boolean resume:List.of(false,true)){
            var f=new Fixture("test",false);assertTrue(f.game.answer(f.author,"test"));f.now[0]+=2000;f.game.pause();long position=f.game.snapshot().anchorPositionMillis();
            f.now[0]+=60000;if(resume)f.game.resume();
            if(modern){var event=f.modern("스킵","스킵");f.listener.chat(event);assertTrue(event.isCancelled());}
            else{var event=f.legacy("스킵");f.listener.legacy(event);assertTrue(event.isCancelled());}
            f.drain();assertFalse(f.game.snapshot().playing());assertEquals(position,f.game.snapshot().anchorPositionMillis());assertEquals(1,f.roundEnds);assertEquals(f.now[0]+1000,f.game.snapshot().endsAt());
        }
    }
    @Test void eitherNoncompetitiveWinnerCanSkipBeforePrivateChatDelivery() {
        for(boolean second:List.of(false,true)){
            var f=new Fixture("test",false,true);assertTrue(f.game.answer(f.author,"test"));assertTrue(f.game.answer(f.other,"태그"));
            if(second)when(f.player.getUniqueId()).thenReturn(f.other);
            var event=f.modern("tmzlq","스킵");f.listener.chat(event);assertTrue(event.isCancelled());f.drain();
            assertFalse(f.game.snapshot().playing());assertEquals(1,f.roundEnds);assertEquals(List.of(f.author,f.other),f.game.snapshot().winners());verify(f.player,never()).sendMessage(any(Component.class));
            verify(f.wallet).rewardAnswerPoints(eq(f.author),anyString(),eq(2L));verify(f.wallet).rewardAnswerPoints(eq(f.other),anyString(),eq(1L));verifyNoMoreInteractions(f.wallet);
        }
    }
    @Test void nonwinnerSkipStaysChatAndAnAnswerNamedSkipStillScoresNormally() {
        for(boolean modern:List.of(false,true)){
            var f=new Fixture("test",false);
            if(modern){var event=f.modern("tmzlq","스킵");f.listener.chat(event);assertFalse(event.isCancelled());assertEquals(Component.text("스킵"),event.message());}
            else{var event=f.legacy("스킵");f.listener.legacy(event);assertFalse(event.isCancelled());assertEquals("스킵",event.getMessage());}
            assertTrue(f.main.isEmpty());assertEquals(0,f.roundEnds);assertTrue(f.game.snapshot().playing());
            var named=new Fixture("스킵",false);
            if(modern)named.listener.chat(named.modern("tmzlq","스킵"));else named.listener.legacy(named.legacy("스킵"));
            named.drain();assertEquals(1,named.game.snapshot().scores().get(named.author));assertTrue(named.game.snapshot().playing());assertEquals(0,named.roundEnds);
        }
    }
    @Test void winnerPhrasesAndEnglishAliasesDoNotSkipAndNonmembersCannotUseTheShortcut() {
        var f=new Fixture("test",false);assertTrue(f.game.answer(f.author,"test"));
        for(String text:List.of("스킵할까","스킵!","지금 스킵","skip","tmzlq")){
            var legacy=f.legacy(text);f.listener.legacy(legacy);assertFalse(legacy.isCancelled());var modern=f.modern(text,text);f.listener.chat(modern);assertFalse(modern.isCancelled());
        }
        assertTrue(f.main.isEmpty());assertTrue(f.game.snapshot().playing());f.game.leave(f.author);
        assertFalse(f.plugin.captureAnswer(f.author,"스킵"));assertFalse(f.plugin.captureAnswer(UUID.randomUUID(),"스킵"));assertTrue(f.main.isEmpty());
    }
    @Test void delayedWinnerSkipCannotSurviveRoundSessionMembershipOrTerminalChanges() {
        for(boolean modern:List.of(false,true))for(String change:List.of("next-round","new-session","leave","new-membership","terminal","stop")){
            var f=new Fixture("test",false);assertTrue(f.game.answer(f.author,"test"));
            if(modern)f.listener.chat(f.modern("스킵","스킵"));else f.listener.legacy(f.legacy("스킵"));assertEquals(1,f.main.size());var delayed=f.main.remove();
            switch(change){
                case "next-round"->{f.game.forceSkip(f.game.snapshot().roundId());f.now[0]+=1000;f.game.tick();}
                case "new-session"->{f.game.stop();f.game.start(List.of(f.author,f.other));}
                case "leave"->{f.game.leave(f.author);f.members.remove(f.author);}
                case "new-membership"->{f.game.leave(f.author);f.members.put(f.author,new BridgeClient.Member(f.author,UUID.randomUUID(),"Presenter",null));f.game.join(f.author);}
                case "terminal"->f.game.forceSkip(f.game.snapshot().roundId());
                default->f.game.stop();
            }
            if(change.equals("next-round")||change.equals("new-session")){
                f.game.grant(f.author,1);f.game.submit(f.author,f.game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","test","태그"));f.drain();
                f.game.browsersReady(f.game.snapshot().revision(),true,true);f.game.controlAccepted(f.game.snapshot().revision());f.now[0]=f.game.snapshot().startsAt();f.game.tick();
                assertFalse(f.plugin.captureAnswer(f.author,"스킵"));assertTrue(f.game.answer(f.author,"test"));
            }
            var before=f.game.snapshot();int ended=f.roundEnds;long credits=f.game.credits(f.author);clearInvocations(f.wallet);delayed.run();
            assertEquals(before,f.game.snapshot());assertEquals(ended,f.roundEnds);assertEquals(credits,f.game.credits(f.author));verifyNoInteractions(f.wallet);
        }
    }
}
