package dev.noqui.core;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Single-threaded state machine. Completion callbacks must enter through the supplied executor. */
public final class QuizGame {
    public enum Phase { STOPPED, WAITING_FOR_PRESENTER, WAITING_FOR_SUBMISSION, WAITING_FOR_MEDIA, PREPARING, COUNTDOWN, PLAYING, REVEAL }
    public record Snapshot(UUID session, long revision, Phase phase, UUID presenter, UUID winner,
                           Song song, MediaBridge.Media media, long startsAt, long endsAt,
                           int round, UUID roundId, UUID playbackGeneration, boolean playing, long clipEndMillis, String hint,
                           Set<UUID> participants, Map<UUID,Integer> scores) {}
    public interface Output {
        void changed(Snapshot snapshot);
        void error(UUID player, String message);
    }
    private final MediaBridge bridge;
    private final Executor main;
    private final LongSupplier clock;
    private final Output output;
    private final long roundMillis;
    private final boolean matchSkip;
    private final LinkedHashSet<UUID> players = new LinkedHashSet<>();
    private final Map<UUID,Integer> scores = new LinkedHashMap<>();
    private UUID session = UUID.randomUUID(), presenter, winner, next;
    private long revision, operation, startsAt, endsAt, clipEndMillis;
    private UUID roundId, playbackGeneration;
    private boolean playing, anyReady, countdownAccepted;
    private Phase phase = Phase.STOPPED;
    private Song song;
    private MediaBridge.Media media;
    private int round, hints;
    private String hint = "";
    public QuizGame(MediaBridge bridge, Executor main, LongSupplier clock, Output output, int roundSeconds, boolean matchSkip) {
        if (roundSeconds < 1 || roundSeconds > 600) throw new IllegalArgumentException("Round time must be 1..600 seconds");
        this.bridge=bridge; this.main=main; this.clock=clock; this.output=output; this.roundMillis=roundSeconds*1000L; this.matchSkip=matchSkip;
    }
    public Snapshot snapshot() { return new Snapshot(session, revision, phase, presenter, winner, song, media, startsAt, endsAt, round, roundId, playbackGeneration, playing, clipEndMillis, hint, Set.copyOf(players), Map.copyOf(scores)); }
    public boolean started() { return phase != Phase.STOPPED; }
    public boolean participant(UUID id) { return players.contains(id); }
    public void start(UUID first, Collection<UUID> participants) {
        if (started()) throw new IllegalArgumentException("노래퀴즈가 이미 진행 중입니다.");
        if (first == null && !participants.isEmpty() || first != null && !participants.contains(first)) throw new IllegalArgumentException("출제할 참가자가 없습니다.");
        session=UUID.randomUUID(); players.clear(); players.addAll(participants); scores.clear(); players.forEach(id->scores.put(id,0)); round=0;
        assign(first);
    }
    public void stop() {
        invalidate(); phase=Phase.STOPPED; presenter=null; winner=null; song=null; media=null; playing=false; players.clear(); startsAt=endsAt=0; emit();
    }
    public void join(UUID player) {
        if (!started()) return;
        if (players.add(player)) scores.putIfAbsent(player,0);
        if (phase==Phase.WAITING_FOR_PRESENTER) assign(player); else emit();
    }
    public void leave(UUID player) {
        if (!players.remove(player)) return;
        if (Objects.equals(player, winner)) winner=null;
        if (Objects.equals(player, next)) next=null;
        if (Objects.equals(player, presenter)) assign(rotate(player)); else emit();
    }
    public void give(UUID player) {
        if (!started() || !players.contains(player)) throw new IllegalArgumentException("참가 중인 플레이어를 선택하세요.");
        assign(player);
    }
    private UUID rotate(UUID previous) {
        var list = new ArrayList<>(players);
        if (list.isEmpty()) return null;
        int index=list.indexOf(previous);
        if (index>=0 && list.size()>1) return list.get((index+1)%list.size());
        return list.getFirst();
    }
    private void assign(UUID id) {
        if(phase==Phase.PLAYING || phase==Phase.COUNTDOWN || phase==Phase.REVEAL && playing) {
            invalidate();phase=Phase.REVEAL;playing=false;endsAt=clock.getAsLong();emit();
        }
        invalidate(); presenter=id; winner=null; next=null; song=null; media=null; roundId=null; playbackGeneration=null; playing=false; anyReady=false; hint=""; hints=0; startsAt=endsAt=0;
        phase=id==null?Phase.WAITING_FOR_PRESENTER:Phase.WAITING_FOR_SUBMISSION; emit();
    }
    public void submit(UUID player, long expectedRevision, Song submitted) {
        if (phase!=Phase.WAITING_FOR_SUBMISSION || revision!=expectedRevision || !Objects.equals(presenter,player) || !players.contains(player))
            throw new IllegalArgumentException("현재 출제권으로 입력 창을 다시 열어 주세요.");
        song=Objects.requireNonNull(submitted); winner=null; phase=Phase.WAITING_FOR_MEDIA; roundId=UUID.randomUUID(); playbackGeneration=UUID.randomUUID(); round++; long request=++operation; emit();
        try {
            bridge.prepare(new MediaBridge.Request(session,request,submitted)).whenComplete((ready,error)->main.execute(()->{
                if (phase!=Phase.WAITING_FOR_MEDIA || operation!=request) return;
                if (error!=null || ready==null) { failPreparation(); return; }
                media=ready; clipEndMillis=Math.min(roundMillis,ready.durationMillis()); phase=Phase.PREPARING; endsAt=clock.getAsLong()+20000; emit();
            }));
        } catch (RuntimeException error) { if (phase==Phase.WAITING_FOR_MEDIA && operation==request) failPreparation(); }
    }
    private void failPreparation() {
        invalidate(); phase=Phase.WAITING_FOR_SUBMISSION; media=null; roundId=null; playbackGeneration=null;
        output.error(presenter,"오디오 준비 실패: 잠시 후 다시 제출하거나 다른 영상을 선택하세요."); emit();
    }
    public boolean answer(UUID player, String answer) {
        if (phase!=Phase.PLAYING || clock.getAsLong()>=endsAt || winner!=null || !players.contains(player) || Objects.equals(player,presenter) || !song.matches(answer)) return false;
        winner=player; scores.merge(player,1,Integer::sum); phase=Phase.REVEAL; playing=true; emit(); if(matchSkip) reveal(); return true;
    }
    public void skip() {
        if (!started()) throw new IllegalArgumentException("노래퀴즈가 진행 중이 아닙니다.");
        if (phase==Phase.PLAYING || phase==Phase.COUNTDOWN || phase==Phase.REVEAL) reveal(); else assign(rotate(presenter));
    }
    private void reveal() {
        invalidate(); next=winner!=null && players.contains(winner)?winner:rotate(presenter);
        phase=Phase.REVEAL; playing=false; endsAt=clock.getAsLong()+1000; emit();
    }
    public void tick() {
        long now=clock.getAsLong();
        if (phase==Phase.PREPARING && now>=endsAt) {
            if(anyReady) countdown(); else { failPreparation(); output.error(presenter,"브라우저에서 오디오를 켠 뒤 다시 제출하세요. /noqui skip으로 넘길 수 있습니다."); }
        }
        if (phase==Phase.COUNTDOWN && now>=startsAt) {
            if(countdownAccepted){phase=Phase.PLAYING;playing=true;emit();}
            else {phase=Phase.PREPARING;playing=false;startsAt=0;endsAt=now+20000;emit();}
        }
        if (phase==Phase.PLAYING && now>=endsAt) reveal();
        else if (phase==Phase.REVEAL && now>=endsAt) {
            if(playing) reveal(); else assign(next!=null && players.contains(next)?next:rotate(presenter));
        }
    }
    public void browsersReady(long expectedRevision, boolean any, boolean all) {
        if(phase!=Phase.PREPARING || revision!=expectedRevision)return;
        anyReady=any;
        if(any && all)countdown();
    }
    public void controlAccepted(long expectedRevision) {if(phase==Phase.COUNTDOWN && revision==expectedRevision)countdownAccepted=true;}
    private void countdown() {
        phase=Phase.COUNTDOWN;playing=true;countdownAccepted=false;
        // One second for asynchronous loopback delivery, then the required three-second countdown.
        startsAt=clock.getAsLong()+4000;endsAt=startsAt+clipEndMillis;emit();
    }
    public void hint() {
        if (phase!=Phase.PLAYING || winner!=null) throw new IllegalArgumentException("정답을 기다리는 라운드에서 사용할 수 있습니다.");
        String title=Song.normalize(song.title()); int visible=Math.min(Math.max(0,title.length()-1), hints++*(int)Math.ceil(title.length()*.25));
        var result=new StringBuilder();
        String initials="ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
        for(int i=0;i<title.length();i++) {
            char c=title.charAt(i);
            result.append(i<visible?c:c>='가'&&c<='힣'?initials.charAt((c-'가')/588):'_');
        }
        hint=result.toString(); emit();
    }
    private void invalidate() { bridge.cancel(session,operation); operation++; }
    private void emit() { revision++; Snapshot state=snapshot(); output.changed(state); bridge.publish(state); }
}
