package dev.noqui.core;

import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/** Paper-thread authority. Private queue jobs start on acceptance and never enter public snapshots. */
public final class QuizGame {
    public enum Phase { STOPPED, WAITING_FOR_SUBMISSION, WAITING_FOR_MEDIA, PREPARING, COUNTDOWN, PLAYING, REVEAL, PAUSED }
    public record Snapshot(UUID session,long revision,Phase phase,UUID presenter,UUID winner,Song song,
            MediaBridge.Media media,long startsAt,long endsAt,int round,UUID roundId,UUID playbackGeneration,
            boolean playing,long clipEndMillis,String hint,Set<UUID> participants,Map<UUID,Integer> scores,
            int totalRounds,int registeredSongs,long anchorPositionMillis,long remainingMillis,int skipVotes,int skipRequired,boolean skipped,
            List<UUID> winners,QuizModes modes,int playbackRate) {
        /** The presenter already knows the answer, so their own message is never a competing answer. */
        public boolean accepts(UUID player,String input){return phase==Phase.PLAYING&&participants.contains(player)&&!Objects.equals(player,presenter)&&!winners.contains(player)&&song!=null&&modes.points(song,input)>0;}
        public boolean canWinnerSkip(UUID player){return roundId!=null&&phase!=Phase.STOPPED&&!(phase==Phase.REVEAL&&!playing)&&participants.contains(player)&&winners.contains(player);}
    }
    public interface Output {
        void changed(Snapshot snapshot);
        void error(UUID player,String message);
        default void correctAnswer(UUID round,UUID player,int answerPoints) {}
        default void answerAccepted(UUID player){}
        default void roundEnded(){}
        default void ended(Map<UUID,Integer> scores) {}
    }
    private static final int MAX_SUBMISSIONS=100;
    private static final long PLAYBACK_REPORT_FRESHNESS_MILLIS=5000,START_CORRECTION_WINDOW_MILLIS=2000;
    private static final class Submission {
        final UUID id=UUID.randomUUID(),author;
        final Song song;
        final long request;
        MediaBridge.Media media;
        boolean live=true;
        Submission(UUID author,Song song,long request){this.author=author;this.song=song;this.request=request;}
    }
    private final MediaBridge bridge;
    private final Executor main;
    private final LongSupplier clock;
    private final Output output;
    private final CreditLedger credits;
    private long roundMillis;
    private int totalRounds;
    private QuizModes modes,roundModes;
    private final Set<UUID> winners=new LinkedHashSet<>();
    private int playbackRate=1;
    private long answerSkipAt,skipRemaining=-1;
    private final RandomGenerator random;
    private final LinkedHashSet<UUID> players=new LinkedHashSet<>();
    private final Map<UUID,Integer> scores=new LinkedHashMap<>();
    private final Deque<Submission> queue=new ArrayDeque<>();
    // Reservations remain durable in the ledger even if a refund write fails or the process stops.
    private final Map<UUID,UUID> refundRetries=new LinkedHashMap<>();
    private final Set<UUID> votes=new HashSet<>();
    private UUID session=UUID.randomUUID(),winner,roundId,playbackGeneration;
    private long revision,operation,startsAt,endsAt,clipEndMillis,anchorPosition,pausedRemaining,nextHintAt,hintRemaining=9000,nextRefundRetry;
    private int roundsStarted;
    private boolean playing,countdownAccepted,roundStarted,skipped;
    private Phase phase=Phase.STOPPED;
    private Submission current;
    private MediaBridge.Media media;
    private HintMask hints;
    private String hint="";
    public QuizGame(MediaBridge bridge,Executor main,LongSupplier clock,Output output,int seconds,boolean matchSkip) {
        this(bridge,main,clock,output,new CreditLedger(),seconds,0,matchSkip,true,false,RandomGenerator.getDefault());
    }
    public QuizGame(MediaBridge bridge,Executor main,LongSupplier clock,Output output,CreditLedger credits,int seconds,
                    int totalRounds,boolean matchSkip,boolean titleBonus,boolean autoHint,RandomGenerator random) {
        if(seconds<1||seconds>120||totalRounds<0)throw new IllegalArgumentException("Invalid quiz limits");
        this.bridge=bridge;this.main=main;this.clock=clock;this.output=output;this.credits=credits;roundMillis=seconds*1000L;
        this.totalRounds=totalRounds;this.modes=QuizModes.legacy(matchSkip,titleBonus,autoHint);this.roundModes=modes;this.random=random;
    }
    public QuizModes modes(){return modes;}
    public void modes(QuizModes value){modes=Objects.requireNonNull(value);if(started())emit();}
    public Snapshot snapshot() {
        long remaining=phase==Phase.PAUSED?pausedRemaining:phase==Phase.COUNTDOWN?wallRemaining():Math.max(0,endsAt-clock.getAsLong());
        return new Snapshot(session,revision,phase,current==null?null:current.author,winner,current==null?null:current.song,media,
                startsAt,endsAt,roundsStarted+(unplayedCurrent()?1:0),roundId,playbackGeneration,
                playing,clipEndMillis,hint,Set.copyOf(players),Map.copyOf(scores),totalRounds,registeredSongs(),anchorPosition,remaining,votes.size(),players.size()/2+1,skipped,List.copyOf(winners),current==null?modes:roundModes,playbackRate);
    }
    private boolean unplayedCurrent(){return current!=null&&current.live&&!roundStarted;}
    private int registeredSongs(){return roundsStarted+queue.size()+(unplayedCurrent()?1:0);}
    public boolean started(){return phase!=Phase.STOPPED;}
    public boolean participant(UUID id){return players.contains(id);}
    public long credits(UUID id){return credits.balance(id);}
    public boolean pending(UUID id){return current!=null&&current.live&&!roundStarted&&current.author.equals(id)||queue.stream().anyMatch(s->s.author.equals(id));}
    public boolean canSubmit(UUID id){
        if(!started()||!participant(id)||credits(id)<1||!hasSubmissionSpace())return false;
        int waiting=unplayedCurrent()&&current.author.equals(id)?1:0;
        for(var submission:queue)if(submission.author.equals(id))waiting++;
        return waiting<10;
    }
    public boolean canVoteSkip(UUID id){return participant(id)&&roundId!=null&&!(phase==Phase.REVEAL&&!playing)&&!votes.contains(id);}
    private boolean hasSubmissionSpace(){return queue.size()+(current==null?0:1)<MAX_SUBMISSIONS
            &&(totalRounds==0||registeredSongs()<totalRounds);}
    public void grant(UUID id,int amount){credits.grant(id,amount);if(started())emit();}
    public void validateGrant(UUID id,int amount){credits.validateGrant(id,amount);}
    public void giveOnce(UUID id,long amount,String transactionId){credits.giveOnce(id,amount,transactionId);if(started())emit();}
    public void start(Collection<UUID> participants){start(participants,0);}
    public void start(Collection<UUID> participants,int limit) {
        if(started())throw new IllegalArgumentException("노래퀴즈가 이미 진행 중입니다.");
        if(limit<0)throw new IllegalArgumentException("라운드 제한은 0(무제한) 또는 양의 정수입니다.");
        totalRounds=limit;session=UUID.randomUUID();players.clear();players.addAll(participants);scores.clear();players.forEach(id->scores.put(id,0));
        roundsStarted=0;clearRound();phase=Phase.WAITING_FOR_SUBMISSION;emit();
    }
    public void timeLimit(int seconds) {
        if(seconds<1||seconds>120)throw new IllegalArgumentException("시간 제한은 1~120초입니다.");
        roundMillis=seconds*1000L;
    }
    public void limit(int limit) {
        if(!started())throw new IllegalArgumentException("노래퀴즈가 진행 중이 아닙니다.");
        if(limit<0||limit>0&&limit<roundsStarted)throw new IllegalArgumentException("이미 시작한 라운드보다 작은 제한은 사용할 수 없습니다.");
        totalRounds=limit;
        if(limit>0) {
            if(roundsStarted==limit&&(current==null||!roundStarted)){stop();return;}
            int capacity=limit-roundsStarted-(unplayedCurrent()?1:0);
            while(queue.size()>capacity){var s=queue.removeLast();cancelSubmission(s);refund(s);}
        }
        emit();
    }
    public void stop() {
        if(!started())return;
        if(current!=null){cancelSubmission(current);if(!roundStarted)refund(current);}
        for(var s:queue){cancelSubmission(s);refund(s);}queue.clear();
        clearRound();phase=Phase.STOPPED;players.clear();emit();output.ended(Map.copyOf(scores));
    }
    public void join(UUID id){if(started()&&players.add(id)){scores.putIfAbsent(id,0);emit();}}
    public void leave(UUID id) {
        if(!players.remove(id))return;
        for(var it=queue.iterator();it.hasNext();){var s=it.next();if(s.author.equals(id)){cancelSubmission(s);refund(s);it.remove();}}
        votes.remove(id);
        if(current!=null&&!roundStarted&&current.author.equals(id)&&!(phase==Phase.REVEAL&&!playing)){abortPreparation();return;}
        if(votes.size()>=players.size()/2+1&&!votes.isEmpty())finishRound(true);else emit();
    }
    public void cancel(UUID id) {
        if(current!=null&&!roundStarted&&current.author.equals(id)&&!(phase==Phase.REVEAL&&!playing)){abortPreparation();return;}
        for(var it=queue.iterator();it.hasNext();){var s=it.next();if(s.author.equals(id)){cancelSubmission(s);refund(s);it.remove();emit();return;}}
    }
    public void submit(UUID id,long expectedRevision,Song song) {
        if(!started()||revision!=expectedRevision||!players.contains(id))throw new IllegalArgumentException("참가 메뉴를 다시 열어 주세요.");
        int waiting=unplayedCurrent()&&current.author.equals(id)?1:0;
        for(var submission:queue)if(submission.author.equals(id))waiting++;
        if(waiting>=10)throw new IllegalArgumentException("대기곡은 한 사람당 10곡까지 등록할 수 있습니다.");
        if(!hasSubmissionSpace())
            throw new IllegalArgumentException("남은 라운드의 제출 대기열이 가득 찼습니다.");
        var s=new Submission(id,Objects.requireNonNull(song),++operation);credits.reserve(s.id,id);queue.add(s);
        if(current==null)next();else emit();
        // All accepted submissions begin preparation now. The bounded queue also bounds outstanding jobs.
        UUID submittedSession=session;
        try{bridge.prepare(new MediaBridge.Request(session,s.request,s.song)).whenComplete((ready,error)->main.execute(()->{
            if(!s.live||!session.equals(submittedSession))return;
            if(error!=null||ready==null){
                try{preparationFailed(s);}finally{output.error(s.author,SubmissionFailure.reason(error)+(refundRetries.containsKey(s.id)?" 출제권 반환 대기 중입니다.":" 출제권 1장을 반환했습니다."));}
                return;
            }
            s.media=ready;if(current==s&&phase==Phase.WAITING_FOR_MEDIA)acceptMedia();
        }));}catch(RuntimeException error){if(s.live){
            try{preparationFailed(s);}finally{output.error(s.author,SubmissionFailure.reason(error)+(refundRetries.containsKey(s.id)?" 출제권 반환 대기 중입니다.":" 출제권 1장을 반환했습니다."));}
        }}
    }
    private void next() {
        clearRound();
        if(totalRounds>0&&roundsStarted>=totalRounds){stop();return;}
        while(!queue.isEmpty()) {
            var s=queue.remove();if(!players.contains(s.author)){cancelSubmission(s);refund(s);continue;}
            current=s;roundModes=modes;roundId=s.id;playbackGeneration=UUID.randomUUID();hints=new HintMask(s.song.title(),random);
            phase=Phase.WAITING_FOR_MEDIA;
            if(s.media!=null)acceptMedia();else emit();return;
        }
        phase=Phase.WAITING_FOR_SUBMISSION;emit();
    }
    private void acceptMedia(){media=current.media;clipEndMillis=Math.min(roundMillis,media.durationMillis());playbackRate=roundModes.has(QuizModes.Mode.SPEED_X2)?2:1;phase=Phase.PREPARING;endsAt=0;emit();}
    private void cancelSubmission(Submission s){s.live=false;try{bridge.cancel(session,s.request);}catch(RuntimeException ignored){/* The live flag still rejects late completion. */}}
    private boolean refund(Submission s) {
        try{credits.refund(s.id);refundRetries.remove(s.id);return true;}
        catch(UncheckedIOException error){refundRetries.put(s.id,s.author);return false;}
    }
    private void preparationFailed(Submission s) {

        if(current==s){cancelSubmission(s);refund(s);next();}
        else {queue.remove(s);cancelSubmission(s);refund(s);emit();}
    }
    private void abortPreparation(){cancelSubmission(current);refund(current);next();}
    private void clearRound() {
        current=null;winner=null;roundId=null;playbackGeneration=null;media=null;hints=null;hint="";votes.clear();winners.clear();answerSkipAt=0;skipRemaining=-1;playbackRate=1;
        startsAt=endsAt=clipEndMillis=anchorPosition=pausedRemaining=nextHintAt=0;hintRemaining=9000;
        playing=countdownAccepted=roundStarted=skipped=false;
    }
    public boolean answer(UUID id,String answer) {
        long now=clock.getAsLong();if(!snapshot().accepts(id,answer)||now>=endsAt||answerSkipAt>0&&now>=answerSkipAt)return false;
        output.correctAnswer(roundId,id,roundModes.points(current.song,answer));
        boolean first=winners.isEmpty();winners.add(id);if(first)winner=id;scores.merge(id,1,Integer::sum);
        if(roundModes.has(QuizModes.Mode.NONCOMPETITIVE)) {
            if(first&&roundModes.has(QuizModes.Mode.MATCH_SKIP))answerSkipAt=now+2000;
            output.answerAccepted(id);emit();
        }else{phase=Phase.REVEAL;emit();if(roundModes.has(QuizModes.Mode.MATCH_SKIP))finishRound(false);}
        return true;
    }
    public boolean skipAsWinner(UUID id,UUID expectedRound) {
        if(!Objects.equals(roundId,expectedRound)||!snapshot().canWinnerSkip(id))return false;
        finishRound(true);return true;
    }
    public boolean voteSkip(UUID id,UUID expectedRound) {
        if(!canVoteSkip(id)||!Objects.equals(roundId,expectedRound))return false;
        if(!votes.add(id))return false;
        if(votes.size()>=players.size()/2+1)finishRound(true);else emit();return true;
    }
    public void forceSkip(UUID expectedRound) {
        if(roundId==null||!Objects.equals(expectedRound,roundId))throw new IllegalArgumentException("현재 라운드 메뉴를 다시 열어 주세요.");
        finishRound(true);
    }
    private long position(long now){return Math.min(clipEndMillis,anchorPosition+(playing&&phase!=Phase.COUNTDOWN?Math.max(0,now-startsAt)*playbackRate:0));}
    private long wallRemaining(){return (clipEndMillis-anchorPosition+playbackRate-1)/playbackRate;}
    private void finishRound(boolean skipped) {
        if(current==null||phase==Phase.REVEAL&&!playing)return;
        anchorPosition=position(clock.getAsLong());cancelSubmission(current);if(!roundStarted)refund(current);
        phase=Phase.REVEAL;playing=false;this.skipped=skipped;answerSkipAt=0;skipRemaining=-1;endsAt=clock.getAsLong()+1000;emit();output.roundEnded();
    }
    public void pause() {
        if(phase!=Phase.PLAYING&&phase!=Phase.COUNTDOWN&&!(phase==Phase.REVEAL&&playing))throw new IllegalArgumentException("재생 중에 일시정지할 수 있습니다.");
        long now=clock.getAsLong();anchorPosition=position(now);pausedRemaining=Math.max(0,wallRemaining());
        if(answerSkipAt>0){skipRemaining=Math.max(0,answerSkipAt-now);answerSkipAt=0;}
        if(phase!=Phase.COUNTDOWN)hintRemaining=nextHintAt==0?9000:Math.max(0,nextHintAt-now);
        playing=false;phase=Phase.PAUSED;emit();
    }
    public void resume(){if(phase!=Phase.PAUSED)throw new IllegalArgumentException("일시정지 상태가 아닙니다.");countdown();}
    public void tick() {
        long now=clock.getAsLong();
        if(!refundRetries.isEmpty()&&now>=nextRefundRetry){nextRefundRetry=now+1000;boolean changed=false;
            for(var it=refundRetries.entrySet().iterator();it.hasNext();){var entry=it.next();try{boolean returned=credits.refund(entry.getKey());it.remove();changed=true;if(returned)output.error(entry.getValue(),"출제권 1장을 반환했습니다.");}catch(UncheckedIOException ignored){}}
            if(changed)emit();
        }
        if(phase==Phase.COUNTDOWN&&now>=startsAt) {
            if(!countdownAccepted){playing=false;startsAt=0;if(roundStarted){phase=Phase.PAUSED;pausedRemaining=wallRemaining();}else{phase=Phase.PREPARING;endsAt=0;}emit();}
            return;
        }
        if(phase==Phase.PLAYING&&answerSkipAt>0&&now>=answerSkipAt)finishRound(false);
        else if((phase==Phase.PLAYING||phase==Phase.REVEAL&&playing)&&now>=endsAt)finishRound(false);
        else if(phase==Phase.REVEAL&&!playing&&now>=endsAt)next();
        else if(roundModes.has(QuizModes.Mode.AUTO_HINT)&&phase==Phase.PLAYING&&now>=nextHintAt){hint();nextHintAt=now+9000;}
    }
    public void browsersReady(long expectedRevision,boolean any,boolean all) {
        if(phase==Phase.PREPARING&&revision==expectedRevision&&any)countdown();
    }
    public void controlAccepted(long expectedRevision){if(phase==Phase.COUNTDOWN&&revision==expectedRevision)countdownAccepted=true;}
    /** PLAYING means natural browser progress, not a pending play promise or a seek. */
    public boolean confirmPlayback(UUID player,UUID expectedRound,UUID expectedGeneration,long positionMillis,long observedAtMillis) {
        long now=clock.getAsLong(),age=now-observedAtMillis;
        if(phase!=Phase.COUNTDOWN||!countdownAccepted||!participant(player)||!Objects.equals(roundId,expectedRound)
                ||!Objects.equals(playbackGeneration,expectedGeneration)||now<startsAt||observedAtMillis<startsAt
                ||age<0||age>PLAYBACK_REPORT_FRESHNESS_MILLIS||positionMillis<=anchorPosition||positionMillis>clipEndMillis)return false;
        if(!roundStarted)try{credits.spend(current.id);roundStarted=true;roundsStarted++;}
        catch(UncheckedIOException error){
            UUID author=current.author;playing=false;startsAt=0;phase=Phase.PREPARING;emit();abortPreparation();
            output.error(author,"출제권 사용을 저장하지 못해 재생을 취소했습니다. 미확정 출제권은 반환됩니다.");return false;
        }
        long correction=Math.min(positionMillis-anchorPosition,START_CORRECTION_WINDOW_MILLIS*playbackRate);
        anchorPosition=Math.min(clipEndMillis,anchorPosition+correction+Math.min(START_CORRECTION_WINDOW_MILLIS,age)*playbackRate);startsAt=now;endsAt=now+wallRemaining();
        if(skipRemaining>=0){answerSkipAt=now+skipRemaining;skipRemaining=-1;}
        nextHintAt=now+hintRemaining;phase=winner!=null&&!roundModes.has(QuizModes.Mode.NONCOMPETITIVE)?Phase.REVEAL:Phase.PLAYING;
        emit();if(anchorPosition==clipEndMillis)finishRound(false);return true;
    }
    private void countdown() {
        phase=Phase.COUNTDOWN;playing=true;countdownAccepted=false;playbackGeneration=UUID.randomUUID();
        startsAt=clock.getAsLong()+4000;endsAt=0;nextHintAt=0;emit();
    }
    public void hint(){if(phase!=Phase.PLAYING)throw new IllegalArgumentException("정답을 기다리는 라운드에서 사용할 수 있습니다.");hint=hints.next();emit();}
    private void emit(){revision++;Snapshot state=snapshot();output.changed(state);bridge.publish(state);}
}
