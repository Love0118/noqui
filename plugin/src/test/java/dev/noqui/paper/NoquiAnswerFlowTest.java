package dev.noqui.paper;

import dev.noqui.api.LobbyService;
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
        final long[] now={100000};
        final CompletableFuture<MediaBridge.Media> media=new CompletableFuture<>();
        final QuizGame game;
        Fixture(String title,boolean solo) {
            when(player.getUniqueId()).thenReturn(author);when(player.getName()).thenReturn("Presenter");when(player.isOnline()).thenReturn(true);
            doAnswer(c->{main.add(c.getArgument(0));return null;}).when(plugin).main(any());
            var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request request){return media;}public void cancel(UUID session,long revision){}public void publish(QuizGame.Snapshot state){}};
            game=new QuizGame(bridge,main::add,()->now[0],new QuizGame.Output(){
                public void changed(QuizGame.Snapshot state){set(plugin,"answerState",state);}
                public void error(UUID player,String message){}
                public void correctAnswer(UUID round,UUID player,int points){wallet.rewardAnswerPoints(player,"noqui-answer:"+round+":"+player,points);}
            },115,false);set(plugin,"game",game);
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
}
