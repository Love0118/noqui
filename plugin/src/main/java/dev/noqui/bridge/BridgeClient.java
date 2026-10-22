package dev.noqui.bridge;

import com.google.gson.*;
import dev.noqui.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded asynchronous control client. It never downloads or proxies audio. */
public final class BridgeClient implements MediaBridge {
    public record Member(UUID uuid, UUID membershipId, String nickname, String textureHash) {}
    public interface Readiness { void accept(long revision, boolean any, boolean all); default void controlAccepted(long revision){} }
    private final URI origin;
    private final byte[] secret;
    private final String keyId;
    private final UUID instance;
    private final Executor main;
    private final Supplier<List<Member>> roster;
    private final Readiness readiness;
    private final Consumer<String> warning;
    private final ScheduledExecutorService worker=Executors.newScheduledThreadPool(2,Thread.ofPlatform().daemon().name("noqui-bridge-",0).factory());
    private final HttpClient http;
    private final SecureRandom random=new SecureRandom();
    private final Gson gson=new GsonBuilder().serializeNulls().create();
    private final Map<Request,CompletableFuture<Media>> jobs=new ConcurrentHashMap<>();
    private QuizGame.Snapshot latest;
    private record Packet(JsonObject body,QuizGame.Snapshot state,List<Member> members,CompletableFuture<Void> ack) {}
    private final Deque<Packet> pending=new ArrayDeque<>();
    private CompletableFuture<Void> lastAck,inFlightAck;
    private boolean inFlight, closed;
    private long seq=System.currentTimeMillis()*1000, nextWarning;
    public BridgeClient(URI origin, byte[] secret, String keyId, UUID instance, Executor main,
                        Supplier<List<Member>> roster, Readiness readiness, Consumer<String> warning) {
        if (!"http".equals(origin.getScheme()) || !Set.of("127.0.0.1","localhost","[::1]").contains(origin.getHost())
                || origin.getPort()!=8788 || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) throw new IllegalArgumentException("Bridge must use loopback HTTP port 8788");
        if(secret.length!=32 || keyId==null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid bridge credentials");
        this.origin=origin;this.secret=secret.clone();this.keyId=keyId;this.instance=instance;this.main=main;
        this.roster=roster;this.readiness=readiness;this.warning=warning;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).executor(worker).build();
    }
    public static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public static String signature(byte[] key,String method,String path,String timestamp,String nonce,byte[] body) {
        try {
            String canonical=method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+hash(body);
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return "v1="+HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException error){throw new IllegalStateException("HMAC unavailable",error);}
    }
    private CompletableFuture<JsonObject> request(String method,String path,JsonObject json) {
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("Bridge closed"));
        byte[] body=json==null?new byte[0]:gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        if(body.length>65536)return CompletableFuture.failedFuture(new IllegalArgumentException("Bridge body limit exceeded"));
        byte[] nonceBytes=new byte[18];random.nextBytes(nonceBytes);
        String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes),timestamp=Long.toString(System.currentTimeMillis()/1000);
        var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(8))
                .header("Content-Type","application/json; charset=utf-8").header("X-Noqui-Key-Id",keyId)
                .header("X-Noqui-Timestamp",timestamp).header("X-Noqui-Nonce",nonce)
                .header("X-Noqui-Signature",signature(secret,method,path,timestamp,nonce,body));
        builder.method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        return http.sendAsync(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response->{
            if(response.body().length()>65536)throw new CompletionException(new IllegalStateException("Bridge response too large"));
            if(response.statusCode()<200 || response.statusCode()>=300)throw new CompletionException(new IllegalStateException("Bridge HTTP "+response.statusCode()));
            return JsonParser.parseString(response.body()).getAsJsonObject();
        });
    }
    private static JsonObject object(Object... fields) {
        var json=new JsonObject();
        for(int i=0;i<fields.length;i+=2) {
            String name=(String)fields[i];Object value=fields[i+1];
            if(value==null)json.add(name,JsonNull.INSTANCE);
            else if(value instanceof Number n)json.addProperty(name,n);
            else if(value instanceof Boolean b)json.addProperty(name,b);
            else if(value instanceof JsonElement e)json.add(name,e);
            else json.addProperty(name,value.toString());
        }
        return json;
    }
    @Override public CompletionStage<Media> prepare(Request job) {
        var future=new CompletableFuture<Media>();jobs.put(job,future);
        String videoId=job.song().youtubeUrl().substring(job.song().youtubeUrl().lastIndexOf('=')+1);
        request("POST","/internal/v1/media/jobs",object("schemaVersion",1,"requestId",UUID.randomUUID(),"videoId",videoId))
                .whenComplete((reply,error)->poll(job,future,reply,error,System.currentTimeMillis()+900000));
        future.whenComplete((v,e)->jobs.remove(job,future));return future;
    }
    private void poll(Request request,CompletableFuture<Media> future,JsonObject response,Throwable error,long deadline) {
        if(future.isDone() || closed)return;
        try {
            if(error!=null)throw new IllegalStateException("Media service unavailable");
            String state=response.get("state").getAsString();
            if(state.equals("READY")) {
                String id=UUID.fromString(response.get("mediaId").getAsString()).toString();
                future.complete(new Media(id,response.get("durationMs").getAsLong()));return;
            }
            if(state.equals("FAILED") || System.currentTimeMillis()>deadline)throw new IllegalStateException("Media preparation failed");
            String jobId=UUID.fromString(response.get("jobId").getAsString()).toString();
            long delay=Math.max(2000,Math.min(30000,response.has("retryAfterMs")?response.get("retryAfterMs").getAsLong():2000));
            worker.schedule(()->{if(!future.isDone())request("GET","/internal/v1/media/jobs/"+jobId,null)
                    .whenComplete((r,e)->poll(request,future,r,e,deadline));},delay,TimeUnit.MILLISECONDS);
        } catch(RuntimeException failure){future.completeExceptionally(failure);}
    }
    @Override public void cancel(UUID session,long revision) {
        jobs.forEach((request,future)->{if(request.session().equals(session) && request.revision()==revision)future.cancel(false);});
    }
    /** Called only on the Paper thread; the resulting DTO contains no Bukkit objects. */
    public JsonObject snapshot(QuizGame.Snapshot state,List<Member> members) {
        var rosterJson=new JsonArray();
        for(Member m:members)rosterJson.add(object("uuid",m.uuid(),"membershipId",m.membershipId(),"nickname",m.nickname(),"textureHash",m.textureHash(),"roomId",state.session()));
        String phase=switch(state.phase()) {
            case STOPPED->"ENDED";case WAITING_FOR_PRESENTER,WAITING_FOR_SUBMISSION->"IDLE";case WAITING_FOR_MEDIA->"WAITING_FOR_MEDIA";
            default->state.phase().name();
        };
        JsonObject round=null,reveal=null;
        if(state.roundId()!=null && state.media()!=null && !phase.equals("IDLE") && !phase.equals("ENDED")) {
            long end=state.media()==null?0:state.clipEndMillis();
            round=object("roundId",state.roundId(),"number",state.round(),"revision",Long.toString(state.revision()),
                    "playbackGeneration",state.playbackGeneration(),"mediaId",state.media()==null?null:state.media().id(),
                    "clipStartMs",0,"clipEndMs",end,"anchorPositionMs",0,"anchorAtMs",state.startsAt(),"playing",state.playing());
        }
        if(phase.equals("REVEAL") && state.song()!=null)reveal=object("title",state.song().title(),"aliases",new JsonArray(),"tags",gson.toJsonTree(state.song().tags()),"youtubeUrl",state.song().youtubeUrl());
        var rooms=new JsonArray();rooms.add(object("roomId",state.session(),"roomRevision",Long.toString(state.revision()),"phase",phase,"round",round,"reveal",reveal));
        return object("schemaVersion",1,"instanceId",instance,"seq","0","sentAtMs",0,"roster",rosterJson,"rooms",rooms);
    }
    @Override public synchronized void publish(QuizGame.Snapshot state) {
        latest=state;queue(state);
    }
    public synchronized void heartbeat() {if(latest!=null)queue(latest);}
    private void queue(QuizGame.Snapshot state) {
        List<Member> members=roster.get();var ack=new CompletableFuture<Void>();
        Packet tail=pending.peekLast();
        // Coalesce heartbeats and equivalent same-phase updates, preserving phase/timeline edges.
        if(tail!=null && tail.state().phase()==state.phase() && Objects.equals(tail.state().roundId(),state.roundId()) && tail.state().playing()==state.playing()) {
            pending.removeLast();ack=tail.ack();
        }
        if(pending.size()>=128){ack.completeExceptionally(new IllegalStateException("Bridge queue full"));return;}
        pending.addLast(new Packet(snapshot(state,members),state,List.copyOf(members),ack));lastAck=ack;
        flush();
    }
    private synchronized void flush() {
        if(closed || inFlight || pending.isEmpty())return;
        Packet packet=pending.removeFirst();JsonObject body=packet.body();var ack=packet.ack();inFlightAck=ack;inFlight=true;
        body.addProperty("seq",Long.toString(++seq));body.addProperty("sentAtMs",System.currentTimeMillis());
        long revision=packet.state().revision();UUID roundId=packet.state().roundId(),generation=packet.state().playbackGeneration();
        List<Member> members=packet.members();
        request("POST","/internal/v1/sync",body).whenComplete((response,error)->{
            synchronized(this){inFlight=false;}
            if(error==null && response.has("acceptedSeq") && response.get("acceptedSeq").getAsString().equals(body.get("seq").getAsString())) {
                ack.complete(null);
                main.execute(()->{
                    if(closed)return;
                    var ready=new HashSet<UUID>();
                    if(response.has("browsers"))for(JsonElement entry:response.getAsJsonArray("browsers")) {
                        var b=entry.getAsJsonObject();
                        try {
                            if(b.get("online").getAsBoolean() && b.get("leaseExpiresAtMs").getAsLong()>System.currentTimeMillis()
                                    && b.get("status").getAsString().equals("PREPARED") && Objects.equals(roundId,UUID.fromString(b.get("roundId").getAsString()))
                                    && Objects.equals(generation,UUID.fromString(b.get("playbackGeneration").getAsString()))
                                    && b.get("roundRevision").getAsString().equals(Long.toString(revision))) {
                                UUID id=UUID.fromString(b.get("uuid").getAsString()),membership=UUID.fromString(b.get("membershipId").getAsString());
                                if(members.stream().anyMatch(m->m.uuid().equals(id)&&m.membershipId().equals(membership)))ready.add(id);
                            }
                        } catch(RuntimeException ignored) { /* Incomplete/unready browser status. */ }
                    }
                    readiness.accept(revision,!ready.isEmpty(),!members.isEmpty() && ready.size()==members.size());
                    readiness.controlAccepted(revision);
                });
            } else {
                ack.completeExceptionally(error==null?new IllegalStateException("Bridge acknowledgement mismatch"):error);
                if(System.currentTimeMillis()>=nextWarning){nextWarning=System.currentTimeMillis()+30000;main.execute(()->warning.accept("Noqui control service unavailable; check bridge settings/service."));}
            }
            synchronized(this){flush();}
        });
    }
    public synchronized CompletionStage<String> joinToken(Member member,UUID room) {
        if(latest==null || !latest.participants().contains(member.uuid()))return CompletableFuture.failedFuture(new IllegalStateException("Not a participant"));
        // A full roster must be accepted before minting a token for a new membership.
        queue(latest);var ack=lastAck;
        byte[] bytes=new byte[32];random.nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        JsonObject body=object("schemaVersion",1,"requestId",UUID.randomUUID(),"instanceId",instance,"uuid",member.uuid(),
                "membershipId",member.membershipId(),"roomId",room,"tokenSha256",hash(raw.getBytes(StandardCharsets.UTF_8)));
        return ack.thenCompose(ignored->request("POST","/internal/v1/join-tokens",body)).thenApply(reply->raw);
    }
    @Override public synchronized void close() {
        closed=true;jobs.values().forEach(f->f.cancel(false));jobs.clear();
        for(Packet packet:pending)packet.ack().completeExceptionally(new IllegalStateException("Bridge closed"));pending.clear();
        http.shutdownNow();worker.shutdownNow();Arrays.fill(secret,(byte)0);
    }
    public synchronized void closeAfterSync() {
        if(closed)return;
        var ack=lastAck==null?inFlightAck:lastAck;
        worker.schedule(this::close,10,TimeUnit.SECONDS);
        if(ack==null)close();else ack.whenComplete((ignored,error)->close());
    }
}
