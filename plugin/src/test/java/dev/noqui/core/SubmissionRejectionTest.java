package dev.noqui.core;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubmissionRejectionTest {
    final UUID author=UUID.randomUUID();
    final Song song=Song.input("https://youtu.be/BaW_jenozKc","Song","");

    @Test void eachServiceLimitShowsItsReasonAfterOneRefundOnTheMainExecutor(){
        for(var entry:Map.of("TOO_LONG","6분","TOO_LARGE","용량","FORMAT_UNSUPPORTED","형식","SOURCE_AUTH_REQUIRED","인증",
                "SOURCE_BLOCKED","차단","SOURCE_UNAVAILABLE","링크","INGEST_TIMEOUT","시간","DISK_FULL","공간","VERIFY_FAILED","검증","QUEUE_FULL","대기열").entrySet()){
            var ledger=new CreditLedger();ledger.grant(author,3);var work=new CompletableFuture<MediaBridge.Media>();var callbacks=new ArrayDeque<Runnable>();var messages=new ArrayList<String>();
            var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){return work;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
            var game=new QuizGame(bridge,callbacks::add,()->1000,new QuizGame.Output(){
                public void changed(QuizGame.Snapshot s){}public void error(UUID id,String text){assertEquals(author,id);assertEquals(3,ledger.balance(author));messages.add(text);}
            },ledger,40,0,false,true,false,new Random(1));
            game.start(List.of(author));game.submit(author,game.snapshot().revision(),song);assertEquals(2,ledger.balance(author));
            work.completeExceptionally(new CompletionException(new SubmissionFailure(entry.getKey())));assertEquals(2,ledger.balance(author));assertTrue(messages.isEmpty());
            callbacks.remove().run();assertEquals(3,ledger.balance(author));assertEquals(1,messages.size());assertTrue(messages.getFirst().contains(entry.getValue()));assertTrue(messages.getFirst().contains("출제권 1장을 반환했습니다"));
            game.cancel(author);game.stop();assertEquals(3,ledger.balance(author));assertEquals(1,messages.size());assertEquals(0,game.snapshot().round());
        }
    }
    @Test void synchronousServiceFailureAlsoRefundsAndNeverLeaksPrivateOutput(){
        var ledger=new CreditLedger();ledger.grant(author,1);var messages=new ArrayList<String>();
        var bridge=new MediaBridge(){public CompletionStage<Media> prepare(Request r){throw new IllegalStateException("cookie=private-secret");}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
        var game=new QuizGame(bridge,Runnable::run,()->1000,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String text){messages.add(text);}},ledger,40,0,false,true,false,new Random(1));
        game.start(List.of(author));game.submit(author,game.snapshot().revision(),song);
        assertEquals(1,ledger.balance(author));assertEquals(1,messages.size());assertFalse(messages.getFirst().contains("private-secret"));assertTrue(messages.getFirst().contains("반환했습니다"));
        assertFalse(SubmissionFailure.reason(new IllegalStateException("private-secret")).contains("private-secret"));
    }
}
