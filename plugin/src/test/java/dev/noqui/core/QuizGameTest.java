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
    private final List<Integer> rewards=new ArrayList<>();
    private int roundEnds;
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
            public void correctAnswer(UUID round,UUID player,int points){rewards.add(points);}public void roundEnded(){roundEnds++;}
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
    private void play(QuizGame g,long duration){media(g,duration);g.browsersReady(g.snapshot().revision(),true,true);g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();confirm(g);assertEquals(QuizGame.Phase.PLAYING,g.snapshot().phase());}
    private void confirm(QuizGame g){now[0]++;var s=g.snapshot();assertTrue(g.confirmPlayback(s.participants().iterator().next(),s.roundId(),s.playbackGeneration(),s.anchorPositionMillis()+s.playbackRate(),now[0]));}
    private void finish(QuizGame g){now[0]=g.snapshot().endsAt();g.tick();now[0]+=5000;g.tick();}
    @Test void fortySecondRoundCapsOpusPaddingAndKeepsShortSongsShort() {
        var g=new QuizGame(bridge,callbacks::add,()->now[0],new QuizGame.Output(){
            public void changed(QuizGame.Snapshot s){}public void error(UUID id,String e){fail(e);}
        },40,false);
        for(long duration:new long[]{40007,17000}) {
            g.start(List.of(author,second));g.grant(author,1);submit(g,author);play(g,duration);
            long clip=Math.min(40000,duration);
            assertEquals(clip,g.snapshot().clipEndMillis());
            assertEquals(clip-1,g.snapshot().endsAt()-now[0]);
            now[0]=g.snapshot().endsAt();g.tick();assertEquals(QuizGame.Phase.REVEAL,g.snapshot().phase());g.stop();
        }
    }
    @Test void refundedUnplayedRoundDoesNotLeaveAPhantomRoundNumber() {
        var g=start();submit(g,author);assertEquals(1,g.snapshot().round());g.forceSkip(g.snapshot().roundId());
        assertEquals(0,g.snapshot().round());assertEquals(5,g.credits(author));
    }
    @Test void transportAcknowledgementCannotSpendOrStartWithoutPlaybackProgress() {
        var g=start();submit(g,author);media(g,60000);g.browsersReady(g.snapshot().revision(),true,true);g.controlAccepted(g.snapshot().revision());
        now[0]=g.snapshot().startsAt()+120000;g.tick();assertEquals(QuizGame.Phase.COUNTDOWN,g.snapshot().phase());
        assertTrue(g.pending(author));assertEquals(60000,g.snapshot().remainingMillis());assertFalse(g.answer(second,"bad apple"));assertEquals(0,roundEnds);
        g.stop();assertEquals(5,g.credits(author));
    }
    @Test void mediaAndBrowserGatesDoNotStartClockEarlyAndDurationCapsRound() {
        var g=start();submit(g,author);now[0]+=50000;g.tick();assertEquals(QuizGame.Phase.WAITING_FOR_MEDIA,g.snapshot().phase());assertEquals(0,g.snapshot().startsAt());
        media(g,10000);assertFalse(g.answer(second,"bad apple"));g.browsersReady(g.snapshot().revision()-1,true,true);assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());
        g.browsersReady(g.snapshot().revision(),true,true);assertEquals(now[0]+4000,g.snapshot().startsAt());g.controlAccepted(g.snapshot().revision());
        now[0]+=3999;g.tick();assertFalse(g.answer(second,"bad apple"));now[0]++;g.tick();confirm(g);assertTrue(g.answer(second,"bad apple"));
        assertEquals(10000,g.snapshot().clipEndMillis());
    }
    @Test void firstCorrectScoresOnceContinuesAudioAndNeverGrantsCreditsOrPresenterRights() {
        var g=start();submit(g,author);play(g,200000);assertEquals(115000,g.snapshot().clipEndMillis());
        assertFalse(g.answer(UUID.randomUUID(),"bad apple"));
        assertTrue(g.answer(second,"I think BAD APPLE!!!"));assertFalse(g.answer(third,"동방"));assertEquals(1,g.snapshot().scores().get(second));assertEquals(5,g.credits(second));
        assertTrue(g.snapshot().playing());finish(g);assertNull(g.snapshot().presenter());assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());
    }
    @Test void titleBonusAndImmediateMatchSkipAreConfigurable() {
        var g=game(67,true,false,false);submit(g,author);play(g,90000);assertTrue(g.answer(second,"배드애플"));assertFalse(g.snapshot().playing());assertEquals(1,g.snapshot().scores().get(second));assertEquals(now[0]+1000,g.snapshot().endsAt());
    }
    @Test void winnerCanSkipAfterVotingWithoutAnotherVoteRewardOrRefundAndEndsOnlyOnce() {
        var g=start();submit(g,author);play(g,60000);UUID round=g.snapshot().roundId();
        assertTrue(g.voteSkip(second,round));assertFalse(g.canVoteSkip(second));assertTrue(g.answer(second,"bad apple"));
        assertTrue(g.snapshot().canWinnerSkip(second));assertFalse(g.skipAsWinner(second,UUID.randomUUID()));assertFalse(g.skipAsWinner(UUID.randomUUID(),round));
        assertTrue(g.skipAsWinner(second,round));assertFalse(g.snapshot().playing());assertTrue(g.snapshot().skipped());assertEquals(1,g.snapshot().skipVotes());
        assertEquals(List.of(2),rewards);assertEquals(1,g.snapshot().scores().get(second));assertEquals(4,g.credits(author));assertEquals(5,g.credits(second));assertEquals(1,roundEnds);
        long end=g.snapshot().endsAt();assertEquals(now[0]+1000,end);now[0]+=999;
        assertFalse(g.canVoteSkip(second));assertFalse(g.skipAsWinner(second,round));assertFalse(g.skipAsWinner(third,round));g.forceSkip(round);g.tick();
        assertEquals(end,g.snapshot().endsAt());assertEquals(1,roundEnds);assertEquals(QuizGame.Phase.REVEAL,g.snapshot().phase());
        now[0]++;g.tick();assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());assertFalse(g.skipAsWinner(second,round));
        assertEquals(List.of(2),rewards);assertEquals(4,g.credits(author));assertEquals(5,g.credits(second));
    }
    @Test void anyNoncompetitiveWinnerCanSkipIncludingASecondAnswererWhoAlreadyVoted() {
        var g=start();g.modes(g.modes().with(QuizModes.Mode.NONCOMPETITIVE,true));submit(g,author);play(g,60000);UUID round=g.snapshot().roundId();
        assertTrue(g.voteSkip(third,round));assertTrue(g.answer(second,"bad apple"));assertTrue(g.answer(third,"동방"));assertEquals(second,g.snapshot().winner());
        assertTrue(g.skipAsWinner(third,round));assertFalse(g.snapshot().playing());assertEquals(List.of(second,third),g.snapshot().winners());assertEquals(List.of(2,1),rewards);
        assertEquals(1,g.snapshot().skipVotes());assertEquals(1,roundEnds);assertFalse(g.answer(second,"동방"));assertFalse(g.skipAsWinner(second,round));assertFalse(g.answer(author,"동방"));
    }
    @Test void winnerCanSkipWhilePaused(){skipPausedWinner(false);}
    @Test void presenterSelfAnswerIsRejectedInEveryModeWithoutScoreRewardWinnerOrRoundChange() {
        for(QuizModes.Mode mode:List.of(QuizModes.Mode.NONCOMPETITIVE,QuizModes.Mode.MATCH_SKIP,QuizModes.Mode.TITLE_ONLY,QuizModes.Mode.TITLE_BONUS)){
            var g=start();g.modes(g.modes().with(mode,true));submit(g,author);play(g,60000);
            UUID round=g.snapshot().roundId();var before=g.snapshot();
            assertFalse(g.answer(author,"bad apple"));assertFalse(g.answer(author,"동방"));
            assertEquals(before,g.snapshot());assertEquals(QuizGame.Phase.PLAYING,g.snapshot().phase());assertTrue(g.snapshot().playing());
            assertNull(g.snapshot().winner());assertTrue(g.snapshot().winners().isEmpty());assertEquals(0,g.snapshot().scores().get(author));
            assertEquals(1,g.snapshot().round());assertTrue(rewards.isEmpty());assertEquals(0,roundEnds);
            assertFalse(g.snapshot().canWinnerSkip(author));assertFalse(g.skipAsWinner(author,round));
        }
    }
    @Test void winnerCanSkipDuringResumeCountdown(){skipPausedWinner(true);}
    private void skipPausedWinner(boolean resume) {
        var g=start();submit(g,author);play(g,60000);assertTrue(g.answer(second,"bad apple"));now[0]+=2000;g.pause();long position=g.snapshot().anchorPositionMillis();
        now[0]+=60000;if(resume){g.resume();assertEquals(QuizGame.Phase.COUNTDOWN,g.snapshot().phase());}
        assertTrue(g.skipAsWinner(second,g.snapshot().roundId()));assertFalse(g.snapshot().playing());assertEquals(QuizGame.Phase.REVEAL,g.snapshot().phase());
        assertEquals(position,g.snapshot().anchorPositionMillis());assertEquals(now[0]+1000,g.snapshot().endsAt());assertEquals(4,g.credits(author));assertEquals(5,g.credits(second));
        g.controlAccepted(g.snapshot().revision());assertFalse(g.snapshot().playing());g.stop();
    }
    @Test void nonwinnerStillNeedsStrictMajorityAfterSomeoneAnswers() {
        var g=start();g.join(UUID.randomUUID());submit(g,author);play(g,60000);assertTrue(g.answer(second,"bad apple"));UUID round=g.snapshot().roundId();
        assertTrue(g.voteSkip(author,round));assertFalse(g.skipAsWinner(author,round));assertFalse(g.voteSkip(author,round));assertTrue(g.voteSkip(third,round));
        assertEquals(2,g.snapshot().skipVotes());assertEquals(3,g.snapshot().skipRequired());assertTrue(g.snapshot().playing());
        UUID fourth=g.snapshot().participants().stream().filter(id->!List.of(author,second,third).contains(id)).findFirst().orElseThrow();
        assertTrue(g.voteSkip(fourth,round));assertFalse(g.snapshot().playing());assertEquals(1,roundEnds);assertEquals(List.of(2),rewards);
    }
    @Test void previousWinnerCannotSkipAfterLeavingOrCarryPrivilegeIntoAnotherRoundOrSession() {
        var g=start();submit(g,author);play(g,60000);assertTrue(g.answer(second,"bad apple"));UUID old=g.snapshot().roundId();
        g.leave(second);assertFalse(g.canVoteSkip(second));assertFalse(g.skipAsWinner(second,old));g.join(second);assertTrue(g.skipAsWinner(second,old));
        now[0]+=1000;g.tick();submit(g,author);play(g,60000);assertFalse(g.skipAsWinner(second,old));assertFalse(g.skipAsWinner(second,g.snapshot().roundId()));assertTrue(g.voteSkip(second,g.snapshot().roundId()));assertTrue(g.snapshot().playing());
        g.stop();assertFalse(g.skipAsWinner(second,old));assertFalse(g.canVoteSkip(second));g.start(List.of(author,second,third));assertFalse(g.skipAsWinner(second,null));
        submit(g,author);assertFalse(g.skipAsWinner(second,old));assertTrue(g.voteSkip(second,g.snapshot().roundId()));assertEquals(QuizGame.Phase.WAITING_FOR_MEDIA,g.snapshot().phase());
    }
    @Test void noncompetitionWithoutSkipKeepsAnswersPrivateUntilDeadlineAndAwardsEachPlayerOnce() {
        var g=start();g.modes(g.modes().with(QuizModes.Mode.NONCOMPETITIVE,true));submit(g,author);play(g,60000);long end=g.snapshot().endsAt();
        assertTrue(g.answer(second,"BAD APPLE"));assertTrue(g.answer(third,"동방"));assertFalse(g.answer(second,"배드애플"));assertEquals(QuizGame.Phase.PLAYING,g.snapshot().phase());assertEquals(end,g.snapshot().endsAt());
        assertEquals(List.of(second,third),g.snapshot().winners());assertEquals(1,g.snapshot().scores().get(second));assertEquals(1,g.snapshot().scores().get(third));
        now[0]=end;g.tick();assertEquals(QuizGame.Phase.REVEAL,g.snapshot().phase());assertFalse(g.snapshot().playing());assertEquals(List.of(second,third),g.snapshot().winners());assertFalse(g.answer(author,"동방"));
    }
    @Test void noncompetitionMatchSkipUsesFirstAnswersTwoSecondWindowAndPausesItsRemainingTime() {
        var g=game(67,true,true,false);UUID fourth=UUID.randomUUID();g.join(fourth);g.modes(g.modes().with(QuizModes.Mode.NONCOMPETITIVE,true));submit(g,author);play(g,60000);assertTrue(g.answer(second,"BAD APPLE"));
        now[0]+=1000;assertTrue(g.answer(third,"동방"));g.pause();now[0]+=60000;g.tick();assertEquals(QuizGame.Phase.PAUSED,g.snapshot().phase());assertFalse(g.answer(fourth,"배드애플"));
        g.resume();g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();confirm(g);assertEquals(QuizGame.Phase.PLAYING,g.snapshot().phase());
        now[0]+=999;g.tick();assertTrue(g.snapshot().playing());assertTrue(g.answer(fourth,"배드애플"));now[0]++;g.tick();assertFalse(g.snapshot().playing());assertEquals(3,g.snapshot().winners().size());
        long deadline=g.snapshot().endsAt();g.forceSkip(g.snapshot().roundId());assertEquals(deadline,g.snapshot().endsAt());
    }
    @Test void speedModeUsesMediaPositionAtDoubleRateAndPreservesPauseAndResumeTiming() {
        var g=start();g.modes(g.modes().with(QuizModes.Mode.SPEED_X2,true));submit(g,author);play(g,115007);assertEquals(2,g.snapshot().playbackRate());assertEquals(115000,g.snapshot().clipEndMillis());assertEquals(57499,g.snapshot().endsAt()-g.snapshot().startsAt());
        now[0]+=10000;g.pause();assertEquals(20002,g.snapshot().anchorPositionMillis());assertEquals(47499,g.snapshot().remainingMillis());now[0]+=60000;g.resume();
        assertEquals(47499,g.snapshot().remainingMillis());g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();confirm(g);assertEquals(47498,g.snapshot().remainingMillis());
        now[0]=g.snapshot().endsAt();g.tick();assertFalse(g.snapshot().playing());assertEquals(115000,g.snapshot().anchorPositionMillis());
    }
    @Test void doubleRateRoundsOddSourceMillisecondsUpWithoutEndingOneTickEarly() {
        var g=start();g.modes(g.modes().with(QuizModes.Mode.SPEED_X2,true));submit(g,author);play(g,60001);
        assertEquals(60001,g.snapshot().clipEndMillis());assertEquals(30000,g.snapshot().endsAt()-g.snapshot().startsAt());assertEquals(30000,g.snapshot().remainingMillis());
        now[0]=g.snapshot().endsAt()-1;g.tick();assertTrue(g.snapshot().playing());now[0]++;g.tick();assertFalse(g.snapshot().playing());assertEquals(60001,g.snapshot().anchorPositionMillis());
    }
    @Test void titleOnlyAndBannedKeywordsKeepOriginalSubstringRulesAndIndependentTitleBonus() {
        var modes=QuizModes.legacy(false,true,false).with(QuizModes.Mode.NO_EASY,true).withBanned(Set.of("동방","bad apple"));
        assertEquals(0,modes.points(song,"이거 동방 노래"));assertEquals(1,modes.points(song,"배드 애플!"));assertEquals(0,modes.points(song,"BAD APPLE"));
        modes=modes.with(QuizModes.Mode.TITLE_ONLY,true);assertEquals(0,modes.points(song,"배드애플"));assertEquals(2,modes.points(song,"I think BAD APPLE!!"));
        assertEquals(1,modes.with(QuizModes.Mode.TITLE_BONUS,false).points(song,"bad apple"));assertEquals(0,modes.points(song,"apple"));
    }
    @Test void modeChangesOnlyAffectNextRoundAndCannotRevealAnExistingNoncompetitionRound() {
        var g=start();g.modes(g.modes().with(QuizModes.Mode.NONCOMPETITIVE,true));submit(g,author);submit(g,second);play(g,60000);assertTrue(g.answer(third,"bad apple"));
        g.modes(g.modes().with(QuizModes.Mode.NONCOMPETITIVE,false).with(QuizModes.Mode.MATCH_SKIP,true).with(QuizModes.Mode.SPEED_X2,true).with(QuizModes.Mode.TITLE_ONLY,true));
        assertTrue(g.snapshot().modes().has(QuizModes.Mode.NONCOMPETITIVE));assertEquals(1,g.snapshot().playbackRate());assertTrue(g.answer(second,"동방"));assertEquals(QuizGame.Phase.PLAYING,g.snapshot().phase());
        g.forceSkip(g.snapshot().roundId());now[0]+=5000;g.tick();play(g,60000);assertFalse(g.snapshot().modes().has(QuizModes.Mode.NONCOMPETITIVE));assertEquals(2,g.snapshot().playbackRate());assertFalse(g.answer(author,"동방"));assertTrue(g.answer(author,"bad apple"));assertFalse(g.snapshot().playing());
    }
    @Test void everyRoundEndEmitsOneStopCueAndAdvancesAfterOneSecond() {
        for(String cause:List.of("timeout","manual","vote","match-skip")){
            int[] ended={0};var future=new CompletableFuture<MediaBridge.Media>();
            var g=new QuizGame(new MediaBridge(){public CompletionStage<Media> prepare(Request r){return future;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}},Runnable::run,()->now[0],new QuizGame.Output(){
                public void changed(QuizGame.Snapshot s){}public void error(UUID id,String message){}public void roundEnded(){ended[0]++;}
            },115,cause.equals("match-skip"));g.start(List.of(author));g.grant(author,1);if(cause.equals("match-skip"))g.join(second);g.submit(author,g.snapshot().revision(),song);future.complete(new MediaBridge.Media("media",10000));
            g.browsersReady(g.snapshot().revision(),true,true);g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();confirm(g);
            switch(cause){case "timeout"->{now[0]=g.snapshot().endsAt();g.tick();}case "manual"->g.forceSkip(g.snapshot().roundId());case "vote"->g.voteSkip(author,g.snapshot().roundId());default->g.answer(second,"bad apple");}
            assertEquals(1,ended[0]);assertFalse(g.snapshot().playing());assertEquals(now[0]+1000,g.snapshot().endsAt());g.forceSkip(g.snapshot().roundId());assertEquals(1,ended[0]);
            now[0]+=999;g.tick();assertEquals(QuizGame.Phase.REVEAL,g.snapshot().phase());now[0]++;g.tick();assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());
        }
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
        assertEquals(5,g.credits(author));assertEquals(second,g.snapshot().presenter());assertEquals(1,g.snapshot().round());assertTrue(errors.isEmpty());
        play(g,1000);assertEquals(1,g.snapshot().round());assertEquals(4,g.credits(second));finish(g);assertEquals(1,g.snapshot().round());
    }
    @Test void queuedSubmissionCanBeCanceledOrRefundedOnLeaveAndStop() {
        var g=start();submit(g,author);submit(g,second);submit(g,third);g.cancel(second);g.cancel(second);assertEquals(5,g.credits(second));g.leave(third);assertEquals(5,g.credits(third));g.stop();assertEquals(5,g.credits(author));
    }
    @Test void playbackSpendsExactlyOnceEvenAcrossPauseResumeStopAndPresenterLeave() {
        var g=start();submit(g,author);play(g,60000);g.pause();g.resume();g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();confirm(g);
        g.leave(author);g.stop();assertEquals(4,g.credits(author));
    }
    @Test void absentBrowserKeepsPreparedSongReservedAndWaitsWithoutSpinningOrMisreportingConversionFailure() {
        var g=start();submit(g,author);media(g,115007);var prepared=g.snapshot();g.browsersReady(prepared.revision()-1,true,true);
        now[0]+=20000;g.tick();assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());assertEquals(4,g.credits(author));assertTrue(g.pending(author));
        assertEquals(prepared.roundId(),g.snapshot().roundId());assertEquals(prepared.media(),g.snapshot().media());assertEquals(115000,g.snapshot().clipEndMillis());
        assertFalse(g.snapshot().playing());assertEquals(0,g.snapshot().remainingMillis());assertTrue(errors.isEmpty());
        long revision=g.snapshot().revision();for(int i=0;i<100;i++){now[0]+=60000;g.tick();}assertEquals(revision,g.snapshot().revision());assertTrue(errors.isEmpty());assertEquals(1,bridge.futures.size());
        g.browsersReady(prepared.revision()-1,true,true);assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());
        g.browsersReady(g.snapshot().revision(),true,false);assertEquals(QuizGame.Phase.COUNTDOWN,g.snapshot().phase());
        g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();confirm(g);assertEquals(QuizGame.Phase.PLAYING,g.snapshot().phase());assertEquals(4,g.credits(author));
    }
    @Test void browserWaitRemainsCancellableRefundableAndKeepsPrewarmedQueue() {
        var g=start();submit(g,author);submit(g,second);media(g,60000);media(g,50000);now[0]+=20000;g.tick();
        assertEquals(author,g.snapshot().presenter());assertEquals(2,bridge.futures.size());g.cancel(author);assertEquals(5,g.credits(author));assertEquals(second,g.snapshot().presenter());assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());
        assertEquals(50000,g.snapshot().media().durationMillis());now[0]+=20000;g.tick();g.stop();assertEquals(5,g.credits(second));
    }
    @Test void onePreparedBrowserStartsImmediatelyAndUnacceptedCountdownRequiresFreshReadiness() {
        var g=start();submit(g,author);media(g,60000);g.browsersReady(g.snapshot().revision(),true,false);assertEquals(QuizGame.Phase.COUNTDOWN,g.snapshot().phase());
        now[0]=g.snapshot().startsAt();g.tick();assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());now[0]+=20000;g.tick();assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());assertFalse(g.snapshot().playing());
        g.browsersReady(g.snapshot().revision(),true,false);assertEquals(QuizGame.Phase.COUNTDOWN,g.snapshot().phase());
    }
    @Test void finiteRoundCapRejectsExcessQueueAndAutoStopsAfterOneSecondReveal() {
        var g=game(1,false,true,false);submit(g,author);assertThrows(IllegalArgumentException.class,()->submit(g,second));play(g,1000);
        assertEquals(1,g.snapshot().round());now[0]=g.snapshot().endsAt();g.tick();assertTrue(g.started());assertEquals(now[0]+1000,g.snapshot().endsAt());now[0]+=999;g.tick();assertTrue(g.started());now[0]++;g.tick();assertFalse(g.started());assertEquals(1,g.snapshot().round());assertTrue(g.snapshot().participants().isEmpty());
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
        var g=game(67,false,true,true);submit(g,author);play(g,60000);now[0]+=4000;g.pause();assertEquals(4001,g.snapshot().anchorPositionMillis());assertEquals(55999,g.snapshot().remainingMillis());
        now[0]+=90000;g.tick();assertEquals(55999,g.snapshot().remainingMillis());assertFalse(g.answer(second,"bad apple"));assertThrows(IllegalArgumentException.class,g::hint);
        g.resume();assertEquals(now[0]+4000,g.snapshot().startsAt());g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();confirm(g);assertEquals(55998,g.snapshot().remainingMillis());
        now[0]+=4999;g.tick();assertTrue(g.snapshot().hint().isEmpty());now[0]++;g.tick();assertEquals("________",g.snapshot().hint());
    }
    @Test void initialHintPreservesOriginalHangulInitialsAndRandomRevealsLeaveOneHidden() {
        var h=new HintMask("사랑해 Ab9!",new Random(7));assertEquals("ㅅㄹㅎ___",h.next());String first=h.next();assertEquals(2,first.chars().filter(c->"사랑해Ab9".indexOf(c)>=0).count());
        for(int i=0;i<20;i++)first=h.next();assertNotEquals("사랑해Ab9",first);assertEquals(5,first.chars().filter(c->"사랑해Ab9".indexOf(c)>=0).count());
    }
    @Test void automaticHintsAreOffByDefaultAndUnacknowledgedCountdownNeverSpends() {
        var g=start();submit(g,author);media(g,60000);g.browsersReady(g.snapshot().revision(),true,true);now[0]=g.snapshot().startsAt();g.tick();assertEquals(QuizGame.Phase.PREPARING,g.snapshot().phase());assertEquals(4,g.credits(author));
        g.browsersReady(g.snapshot().revision(),true,true);g.controlAccepted(g.snapshot().revision());now[0]=g.snapshot().startsAt();g.tick();confirm(g);now[0]+=9000;g.tick();assertTrue(g.snapshot().hint().isEmpty());
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
        Path blocked=block(file);now[0]=g.snapshot().startsAt()+1;var pending=g.snapshot();assertFalse(g.confirmPlayback(author,pending.roundId(),pending.playbackGeneration(),1,now[0]));assertFalse(g.snapshot().playing());assertEquals(QuizGame.Phase.WAITING_FOR_SUBMISSION,g.snapshot().phase());assertFalse(g.answer(second,"bad apple"));
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
