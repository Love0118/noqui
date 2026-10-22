package dev.noqui.core;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class QuizGameTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    private final UUID author=UUID.randomUUID(),second=UUID.randomUUID(),third=UUID.randomUUID();
    private final long[] now={100000};
    private final List<String> errors=new ArrayList<>();
    private final Queue<Runnable> callbacks=new ArrayDeque<>();
    private final Song song=Song.input("https://youtu.be/BaW_jenozKc","Bad Apple!!","배드애플;동방");
    private static final class Bridge implements MediaBridge {
        final List<CompletableFuture<Media>> futures=new ArrayList<>();
        final List<QuizGame.Snapshot> states=new ArrayList<>();
        final List<Long> requests=new ArrayList<>();
        final Set<Long> canceled=new HashSet<>();
        public CompletionStage<Media> prepare(Request r){var f=new CompletableFuture<Media>();futures.add(f);requests.add(r.revision());return f;}
        public void cancel(UUID s,long r){canceled.add(r);}
        public void publish(QuizGame.Snapshot s){states.add(s);}
    }
    private final Bridge bridge=new Bridge();
    private QuizGame game(int rounds,boolean skip,boolean bonus,boolean auto) {
        var g=new QuizGame(bridge,callbacks::add,()->now[0],new QuizGame.Output(){
            public void changed(QuizGame.Snapshot s){}public void error(UUID id,String e){errors.add(e);}
        },new CreditLedger(),115,rounds,skip,bonus,auto,new Random(42));
        g.start(List.of(author,second,third),rounds);g.grant(author,5);g.grant(second,5);g.grant(third,5);return g;
    }
    private QuizGame start(){return game(67,false,true,false);}
    private void submit(QuizGame g,UUID id){g.submit(id,g.snapshot().revision(),song);}
    private void media(QuizGame g,long duration){
        for(int i=0;i<bridge.futures.size();i++)if(!bridge.futures.get(i).isDone()&&!bridge.canceled.contains(bridge.requests.get(i))){
            bridge.futures.get(i).complete(new MediaBridge.Media(UUID.randomUUID().toString(),duration));callbacks.remove().run();return;
        }
        fail("No live media job");
    }
    private void play(QuizGame g,long duration){media(g,duration);g.browsersReady(g.snapshot().revision(),true,true);g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();assertEquals(QuizGame.Phase.PLAYING,g.snapshot().phase());}
    private void finish(QuizGame g){now[0]=g.snapshot().endsAt();g.tick();now[0]+=5000;g.tick();}
    @Test void mediaAndBrowserGatesDoNotStartClockEarlyAndDurationCapsRound() {
        var g=start();submit(g,author);now[0]+=50000;g.tick();assertEquals(QuizGame.Phase.WAITING_FOR_MEDIA,g.snapshot().phase());assertEquals(0,g.snapshot().startsAt());
        media(g,10000);assertFalse(g.answer(second,"bad apple"));g.browsersReady(g.snapshot().revision()-1,true,true);assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());
        g.browsersReady(g.snapshot().revision(),true,true);assertEquals(now[0]+4000,g.snapshot().startsAt());g.controlAccepted(g.snapshot().revision());
        now[0]+=3999;g.tick();assertFalse(g.answer(second,"bad apple"));now[0]++;g.tick();assertTrue(g.answer(second,"bad apple"));
        assertEquals(10000,g.snapshot().clipEndMillis());
    }
    @Test void firstCorrectScoresOnceContinuesAudioAndNeverGrantsCreditsOrPresenterRights() {
        var g=start();submit(g,author);play(g,200000);assertEquals(115000,g.snapshot().clipEndMillis());
        assertFalse(g.answer(author,"bad apple"));assertFalse(g.answer(UUID.randomUUID(),"bad apple"));
        assertTrue(g.answer(second,"I think BAD APPLE!!!"));assertFalse(g.answer(third,"동방"));assertEquals(1,g.snapshot().scores().get(second));assertEquals(5,g.credits(second));
        assertTrue(g.snapshot().playing());finish(g);assertNull(g.snapshot().presenter());assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());
    }
    @Test void titleBonusAndImmediateMatchSkipAreConfigurable() {
        var g=game(67,true,false,false);submit(g,author);play(g,90000);assertTrue(g.answer(second,"배드애플"));assertFalse(g.snapshot().playing());assertEquals(1,g.snapshot().scores().get(second));assertEquals(now[0]+5000,g.snapshot().endsAt());
    }
    @Test void staleFormNonmemberNoCreditAndDuplicateAreRejectedWithoutFurtherReservation() {
        var g=start();long revision=g.snapshot().revision();g.join(UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->g.submit(author,revision,song));
        assertThrows(IllegalArgumentException.class,()->g.submit(UUID.randomUUID(),g.snapshot().revision(),song));
        UUID empty=UUID.randomUUID();g.join(empty);assertThrows(IllegalArgumentException.class,()->g.submit(empty,g.snapshot().revision(),song));
        submit(g,author);assertEquals(4,g.credits(author));assertThrows(IllegalArgumentException.class,()->submit(g,author));assertEquals(4,g.credits(author));assertEquals(1,bridge.futures.size());
    }
    @Test void stoppedSkippedCanceledAndDisconnectedPreparationCannotBeRevivedAndRefundOnce() {
        for(String action:List.of("stop","skip","leave","cancel")) {
            var g=start();submit(g,author);var pending=bridge.futures.getLast();
            switch(action){case "stop"->g.stop();case "skip"->g.forceSkip(g.snapshot().roundId());case "leave"->g.leave(author);default->g.cancel(author);}
            var state=g.snapshot();pending.complete(new MediaBridge.Media(UUID.randomUUID().toString(),60000));callbacks.remove().run();
            assertEquals(state.phase(),g.snapshot().phase());assertEquals(state.revision(),g.snapshot().revision());assertEquals(5,g.credits(author));g.stop();assertEquals(5,g.credits(author));
        }
    }
    @Test void failedPreparationRefundsAndAutomaticallyAdvancesQueueWithoutUsingRound() {
        var g=start();submit(g,author);submit(g,second);bridge.futures.getFirst().completeExceptionally(new IllegalStateException("private stderr"));callbacks.remove().run();
        assertEquals(5,g.credits(author));assertEquals(second,g.snapshot().presenter());assertEquals(1,g.snapshot().round());assertFalse(errors.getFirst().contains("private stderr"));
        play(g,1000);assertEquals(1,g.snapshot().round());assertEquals(4,g.credits(second));finish(g);assertEquals(1,g.snapshot().round());
    }
    @Test void queuedSubmissionCanBeCanceledOrRefundedOnLeaveAndStop() {
        var g=start();submit(g,author);submit(g,second);submit(g,third);g.cancel(second);g.cancel(second);assertEquals(5,g.credits(second));g.leave(third);assertEquals(5,g.credits(third));g.stop();assertEquals(5,g.credits(author));
    }
    @Test void playbackSpendsExactlyOnceEvenAcrossPauseResumeStopAndPresenterLeave() {
        var g=start();submit(g,author);play(g,60000);g.pause();g.resume();g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();
        g.leave(author);g.stop();assertEquals(4,g.credits(author));
    }
    @Test void browserWaitIsBoundedAndReadinessNeedsCurrentRevision() {
        var g=start();submit(g,author);media(g,60000);g.browsersReady(g.snapshot().revision()-1,true,true);now[0]+=20000;g.tick();assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());assertEquals(5,g.credits(author));
        submit(g,author);media(g,60000);g.browsersReady(g.snapshot().revision(),true,false);now[0]+=20000;g.tick();assertEquals(QuizGame.Phase.COUNTDOWN,g.snapshot().phase());
    }
    @Test void finiteRoundCapRejectsExcessQueueAndAutoStopsAfterFiveSecondReveal() {
        var g=game(1,false,true,false);submit(g,author);assertThrows(IllegalArgumentException.class,()->submit(g,second));play(g,1000);
        assertEquals(1,g.snapshot().round());now[0]=g.snapshot().endsAt();g.tick();assertTrue(g.started());assertEquals(now[0]+5000,g.snapshot().endsAt());now[0]+=4999;g.tick();assertTrue(g.started());now[0]++;g.tick();assertFalse(g.started());assertEquals(1,g.snapshot().round());assertTrue(g.snapshot().participants().isEmpty());
    }
    @Test void queueAdvancesAutomaticallyAndEmptyQueueWaitsWithoutAssigningRights() {
        var g=game(2,false,true,false);submit(g,author);submit(g,second);play(g,1000);finish(g);assertEquals(second,g.snapshot().presenter());assertEquals(2,g.snapshot().round());play(g,1000);finish(g);assertFalse(g.started());
        var empty=start();empty.stop();empty.start(List.of());empty.join(author);assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,empty.snapshot().phase());assertNull(empty.snapshot().presenter());
    }
    @Test void skipVoteUsesStrictMajorityOneUuidPerRoundAndRecalculatesOnLeave() {
        var g=start();submit(g,author);play(g,60000);UUID round=g.snapshot().roundId();
        assertTrue(g.voteSkip(second,round));assertFalse(g.voteSkip(second,round));assertFalse(g.voteSkip(UUID.randomUUID(),round));assertFalse(g.voteSkip(third,UUID.randomUUID()));assertTrue(g.snapshot().playing());
        g.leave(author);assertEquals(2,g.snapshot().skipRequired());g.leave(third);assertFalse(g.snapshot().playing());assertTrue(g.snapshot().skipped());assertEquals(5,g.credits(second));
    }
    @Test void halfOfEvenRosterIsInsufficientAndRoundChangeClearsVotes() {
        var g=start();UUID fourth=UUID.randomUUID();g.join(fourth);submit(g,author);play(g,1000);UUID old=g.snapshot().roundId();g.voteSkip(author,old);g.voteSkip(second,old);assertTrue(g.snapshot().playing());g.voteSkip(third,old);assertFalse(g.snapshot().playing());
        now[0]+=5000;g.tick();submit(g,second);assertEquals(0,g.snapshot().skipVotes());assertFalse(g.voteSkip(author,old));
    }
    @Test void pauseFreezesPositionDeadlineAndHintScheduleAndRejectsAnswers() {
        var g=game(67,false,true,true);submit(g,author);play(g,60000);now[0]+=4000;g.pause();assertEquals(4000,g.snapshot().anchorPositionMillis());assertEquals(56000,g.snapshot().remainingMillis());
        now[0]+=90000;g.tick();assertEquals(56000,g.snapshot().remainingMillis());assertFalse(g.answer(second,"bad apple"));assertThrows(IllegalArgumentException.class,g::hint);
        g.resume();assertEquals(now[0]+4000,g.snapshot().startsAt());g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();assertEquals(56000,g.snapshot().remainingMillis());
        now[0]+=4999;g.tick();assertTrue(g.snapshot().hint().isEmpty());now[0]++;g.tick();assertEquals("________",g.snapshot().hint());
    }
    @Test void initialHintPreservesOriginalHangulInitialsAndRandomRevealsLeaveOneHidden() {
        var h=new HintMask("사랑해 Ab9!",new Random(7));assertEquals("ㅅㄹㅎ___",h.next());String first=h.next();assertEquals(2,first.chars().filter(c->"사랑해Ab9".indexOf(c)>=0).count());
        for(int i=0;i<20;i++)first=h.next();assertNotEquals("사랑해Ab9",first);assertEquals(5,first.chars().filter(c->"사랑해Ab9".indexOf(c)>=0).count());
    }
    @Test void automaticHintsAreOffByDefaultAndUnacknowledgedCountdownNeverSpends() {
        var g=start();submit(g,author);media(g,60000);g.browsersReady(g.snapshot().revision(),true,true);now[0]=g.snapshot().startsAt();g.tick();assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());assertEquals(4,g.credits(author));
        g.browsersReady(g.snapshot().revision(),true,true);g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();now[0]+=9000;g.tick();assertTrue(g.snapshot().hint().isEmpty());
    }
    @Test void limitValidationIsExplicit(){assertThrows(IllegalArgumentException.class,()->new QuizGame(bridge,Runnable::run,()->0,null,116,false));assertThrows(IllegalArgumentException.class,()->game(-1,false,true,false));}
    @Test void everyAcceptedQueuedSongBeginsConversionImmediatelyAndReadySongsAdvanceWithoutNewJob() {
        var g=start();submit(g,author);submit(g,second);submit(g,third);assertEquals(3,bridge.futures.size());
        bridge.futures.get(1).complete(new MediaBridge.Media("second-ready",1000));callbacks.remove().run();
        bridge.futures.get(2).complete(new MediaBridge.Media("third-ready",1000));callbacks.remove().run();
        play(g,1000);finish(g);assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());assertEquals("second-ready",g.snapshot().media().id());assertEquals(3,bridge.futures.size());
    }
    @Test void queuedConversionFailureRefundsBeforeItsTurnAndNeverExposesItsSong() {
        var g=start();submit(g,author);submit(g,second);long revision=g.snapshot().revision();
        bridge.futures.get(1).completeExceptionally(new IllegalStateException("private queued failure"));callbacks.remove().run();
        assertEquals(5,g.credits(second));assertFalse(g.pending(second));assertEquals(author,g.snapshot().presenter());assertTrue(g.snapshot().revision()>revision);
        play(g,1000);finish(g);assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());assertEquals(1,g.snapshot().round());
    }
    @Test void unlimitedDefaultAndLiveLimitTrimRefundExcessWithoutConsumingRounds() {
        var g=game(0,false,true,false);assertEquals(0,g.snapshot().totalRounds());submit(g,author);submit(g,second);submit(g,third);
        g.limit(2);assertEquals(5,g.credits(third));assertFalse(g.pending(third));g.limit(0);assertEquals(0,g.snapshot().totalRounds());play(g,1000);
        assertThrows(IllegalArgumentException.class,()->g.limit(-1));g.limit(1);assertEquals(5,g.credits(second));assertTrue(g.started());finish(g);assertFalse(g.started());
        g.start(List.of(author));assertEquals(0,g.snapshot().totalRounds());submit(g,author);play(g,1000);finish(g);assertTrue(g.started());g.limit(1);assertFalse(g.started());
    }
    @Test void limitCannotFallBelowStartedRoundsAndLimitCanExceedOneThousand() {
        var g=game(2000,false,true,false);submit(g,author);play(g,1000);submit(g,second);finish(g);play(g,1000);
        assertThrows(IllegalArgumentException.class,()->g.limit(1));assertEquals(2000,g.snapshot().totalRounds());g.limit(Integer.MAX_VALUE);assertEquals(Integer.MAX_VALUE,g.snapshot().totalRounds());
    }
    @Test void terminalRevealDeadlineDoesNotExtendWhenVotersLeaveOrForceSkipRepeats() {
        var g=game(1,false,true,false);submit(g,author);play(g,1000);UUID round=g.snapshot().roundId();g.voteSkip(author,round);g.voteSkip(second,round);
        long deadline=g.snapshot().endsAt();now[0]=deadline-1;g.leave(third);g.forceSkip(round);assertEquals(deadline,g.snapshot().endsAt());now[0]=deadline;g.tick();assertFalse(g.started());
    }
    private QuizGame durable(Path file,int[] ended)throws Exception {
        var ledger=new CreditLedger(file);ledger.grant(author,1);
        var g=new QuizGame(bridge,callbacks::add,()->now[0],new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String e){errors.add(e);}public void ended(Map<UUID,Integer> s){ended[0]++;}},ledger,115,1,false,true,false,new Random(1));
        g.start(List.of(author,second),1);return g;
    }
    private Path block(Path file)throws Exception {Path temp=file.resolveSibling(file.getFileName()+".tmp");Files.createDirectory(temp);return temp;}
    @Test void failedSpendRetractsPlayingTimelineAndRetainsRecoverableReservation()throws Exception {
        Path file=directory.resolve("credits.json");var g=durable(file,new int[1]);submit(g,author);media(g,60000);g.browsersReady(g.snapshot().revision(),true,true);g.controlAccepted(g.snapshot().revision());
        Path blocked=block(file);now[0]=g.snapshot().startsAt();assertDoesNotThrow(g::tick);assertFalse(g.snapshot().playing());assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());assertFalse(g.answer(second,"bad apple"));
        assertEquals(0,g.credits(author));Files.delete(blocked);now[0]+=1000;g.tick();assertEquals(1,g.credits(author));assertEquals(1,new CreditLedger(file).balance(author));
    }
    @Test void refundWriteFailureDoesNotStrandPreparationOrBlockStopAndRetriesAfterStorageRecovers()throws Exception {
        Path file=directory.resolve("credits.json");int[] ended={0};var g=durable(file,ended);submit(g,author);Path blocked=block(file);
        bridge.futures.getFirst().completeExceptionally(new IllegalStateException("failed"));assertDoesNotThrow(()->callbacks.remove().run());
        assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());assertFalse(errors.stream().anyMatch(e->e.contains("반환했습니다")));
        assertDoesNotThrow(g::stop);assertEquals(1,ended[0]);assertFalse(g.started());Files.delete(blocked);g.tick();assertEquals(1,g.credits(author));g.stop();assertEquals(1,ended[0]);
    }
    @Test void stopWithUnwritableRefundStillEndsAndRestartRefundsOnce()throws Exception {
        Path file=directory.resolve("credits.json");int[] ended={0};var g=durable(file,ended);submit(g,author);Path blocked=block(file);
        assertDoesNotThrow(g::stop);assertFalse(g.started());assertEquals(1,ended[0]);Files.delete(blocked);assertEquals(1,new CreditLedger(file).balance(author));assertEquals(1,new CreditLedger(file).balance(author));
    }
    @Test void persistentAnswerPointsUseTitleBonusWhileCompetitiveScoreCountsAnswersOnly() {
        var awards=new ArrayList<Integer>();var transactions=new ArrayList<String>();
        var g=new QuizGame(bridge,callbacks::add,()->now[0],new QuizGame.Output(){
            public void changed(QuizGame.Snapshot s){}public void error(UUID id,String e){}
            public void correctAnswer(UUID round,UUID id,int points){awards.add(points);transactions.add(round+":"+id);}
        },115,false);
        g.start(List.of(author,second));g.grant(author,2);submit(g,author);play(g,1000);assertTrue(g.answer(second,"BAD APPLE"));assertFalse(g.answer(second,"BAD APPLE"));
        assertEquals(List.of(2),awards);assertEquals(1,g.snapshot().scores().get(second));finish(g);submit(g,author);play(g,1000);assertTrue(g.answer(second,"동방"));
        assertEquals(List.of(2,1),awards);assertEquals(2,g.snapshot().scores().get(second));assertNotEquals(transactions.getFirst(),transactions.getLast());assertEquals(0,g.credits(second));
    }
}
