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
    // 100 accepted jobs, each with a 300s ingest limit, plus 15min delivery/restart margin.
    static final long MAX_PREPARATION_MILLIS=100L*300_000+900_000;
    private static final int MAX_REQUESTS=100;
    public record Member(UUID uuid, UUID membershipId, String nickname, String textureHash) {}
    public record BrowserPresence(UUID player,UUID membership,boolean connected) {}
    public record PlaybackProgress(UUID player,UUID membership,UUID round,UUID generation,long positionMs,long lastSeenAtMs) {}
    public interface Readiness {
        void accept(long revision, boolean any, boolean all);
        default void controlAccepted(long revision){}
        default void playbackStarted(UUID session,PlaybackProgress progress){}
        default void browsers(UUID session,List<BrowserPresence> browsers){}
        default void unavailable(UUID session){}
    }
    private final URI origin;
    private final byte[] secret;
    private final String keyId;
    private final UUID instance;
    private final Executor main;
    private final Supplier<List<Member>> roster;
    private final Readiness readiness;
    private final Consumer<String> warning;
    private final LongSupplier preparationClock;
    private final ScheduledExecutorService worker=Executors.newScheduledThreadPool(2,Thread.ofPlatform().daemon().name("noqui-bridge-",0).factory());
    private final HttpClient http;
    private final SecureRandom random=new SecureRandom();
    private final Gson gson=new GsonBuilder().serializeNulls().create();
    private static final class Job {
        final UUID id=UUID.randomUUID();
        final CompletableFuture<Media> future=new CompletableFuture<>();
        Media media;
    }
    private final Map<Request,Job> jobs=new HashMap<>();
    private QuizGame.Snapshot latest;
    private record Packet(JsonObject body,QuizGame.Snapshot state,List<Member> members,CompletableFuture<Set<String>> ack) {}
    private final Deque<Packet> pending=new ArrayDeque<>();
    private CompletableFuture<Set<String>> lastAck,inFlightAck;
    private boolean inFlight,closing;
    private volatile boolean closed;
    private long seq=System.currentTimeMillis()*1000, nextWarning;
    public BridgeClient(URI origin, byte[] secret, String keyId, UUID instance, Executor main,
                        Supplier<List<Member>> roster, Readiness readiness, Consumer<String> warning) {
        this(origin,secret,keyId,instance,main,roster,readiness,warning,System::currentTimeMillis);
    }
    BridgeClient(URI origin,byte[] secret,String keyId,UUID instance,Executor main,Supplier<List<Member>> roster,
                 Readiness readiness,Consumer<String> warning,LongSupplier preparationClock) {
        if (!"http".equals(origin.getScheme()) || !Set.of("127.0.0.1","localhost","[::1]").contains(origin.getHost())
                || origin.getPort()!=8788 || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) throw new IllegalArgumentException("Bridge must use loopback HTTP port 8788");
        if(secret.length!=32 || keyId==null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid bridge credentials");
        this.origin=origin;this.secret=secret.clone();this.keyId=keyId;this.instance=instance;this.main=main;
        this.roster=roster;this.readiness=readiness;this.warning=warning;this.preparationClock=preparationClock;
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
    @Override public synchronized CompletionStage<Media> prepare(Request request) {
        if(closed||closing)return CompletableFuture.failedFuture(new IllegalStateException("Bridge closed"));
        var existing=jobs.get(request);if(existing!=null)return existing.future;
        if(jobs.size()>=MAX_REQUESTS)return CompletableFuture.failedFuture(new IllegalStateException("Media reservation limit reached"));
        var job=new Job();jobs.put(request,job);var future=job.future;
        future.whenComplete((v,error)->{if(error!=null)synchronized(this){if(jobs.remove(request,job)&&job.media!=null)refreshPins();}});
        String videoId=request.song().youtubeUrl().substring(request.song().youtubeUrl().lastIndexOf('=')+1);
        request("POST","/internal/v1/media/jobs",object("schemaVersion",1,"requestId",job.id,"videoId",videoId,
                "song",object("title",request.song().title(),"tags",gson.toJsonTree(request.song().tags()))))
                .whenComplete((reply,error)->poll(request,future,reply,error,preparationClock.getAsLong()+MAX_PREPARATION_MILLIS));
        return future;
    }
    public CompletionStage<Integer> importCatalogue(JsonArray songs) {
        var body=object("schemaVersion",1,"songs",songs);
        if(songs.isEmpty()||songs.size()>50||gson.toJson(body).getBytes(StandardCharsets.UTF_8).length>=65536)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid catalogue import batch"));
        int expected=songs.size();
        return request("POST","/internal/v1/catalogue/import",body).thenApply(reply->{
            if(!reply.has("savedCount")||!reply.get("savedCount").isJsonPrimitive()||!reply.getAsJsonPrimitive("savedCount").isNumber()
                    ||reply.get("savedCount").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(expected))!=0)throw new CompletionException(new IllegalStateException("Catalogue import acknowledgement mismatch"));
            return expected;
        });
    }
    void poll(Request request,CompletableFuture<Media> future,JsonObject response,Throwable error,long deadline) {
        if(future.isDone() || closed)return;
        try {
            if(error!=null){
                Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                if(!"Bridge HTTP 429".equals(cause.getMessage()))throw new SubmissionFailure("SERVICE_UNAVAILABLE");
                // Conversion capacity is separate from accepted submissions. Retry with a fresh signed nonce.
                response=object("state","ENQUEUE_RETRY","jobId","","retryAfterMs",2000);
            }
            String state=response.get("state").getAsString();
            if(state.equals("READY")) {
                String id=UUID.fromString(response.get("mediaId").getAsString()).toString();
                var media=new Media(id,response.get("durationMs").getAsLong());Job job;
                synchronized(this){
                    job=jobs.get(request);
                    if(closed||closing||future.isDone()||job==null||job.future!=future||job.media!=null)return;
                    job.media=media;
                }
                main.execute(()->confirmPin(request,job));return;
            }
            if(state.equals("FAILED"))throw new SubmissionFailure(response.has("failureCode")&&!response.get("failureCode").isJsonNull()?response.get("failureCode").getAsString():null);
            if(preparationClock.getAsLong()>deadline)throw new SubmissionFailure("PREPARATION_TIMEOUT");
            String jobId=state.equals("ENQUEUE_RETRY")?"":UUID.fromString(response.get("jobId").getAsString()).toString();
            long delay=Math.max(2000,Math.min(30000,response.has("retryAfterMs")?response.get("retryAfterMs").getAsLong():2000));
            worker.schedule(()->{
                if(future.isDone())return;
                JsonObject body=null;
                if(jobId.isEmpty()){
                    Job job;synchronized(this){job=jobs.get(request);}if(job==null)return;
                    String videoId=request.song().youtubeUrl().substring(request.song().youtubeUrl().lastIndexOf('=')+1);
                    body=object("schemaVersion",1,"requestId",job.id,"videoId",videoId,
                            "song",object("title",request.song().title(),"tags",gson.toJsonTree(request.song().tags())));
                }
                request(jobId.isEmpty()?"POST":"GET",jobId.isEmpty()?"/internal/v1/media/jobs":"/internal/v1/media/jobs/"+jobId,body)
                    .whenComplete((r,e)->poll(request,future,r,e,deadline));
            },delay,TimeUnit.MILLISECONDS);
        } catch(RuntimeException failure){future.completeExceptionally(failure);}
    }
    private synchronized void confirmPin(Request request,Job job) {
        if(closed||closing||jobs.get(request)!=job||job.future.isDone())return;
        if(latest==null||!latest.session().equals(request.session())||latest.phase()==QuizGame.Phase.STOPPED){
            job.future.completeExceptionally(new IllegalStateException("No active quiz snapshot for media pin"));return;
        }
        queue(latest).whenComplete((pins,error)->{synchronized(this){
            if(closed||closing||jobs.get(request)!=job||job.future.isDone())return;
            if(error==null&&pins.contains(job.media.id()))job.future.complete(job.media);
            else job.future.completeExceptionally(new IllegalStateException("Media pin was not acknowledged",error));
        }});
    }
    private void refreshPins(){if(!closed&&!closing)main.execute(this::heartbeat);}
    private void discardRequests(Predicate<Request> discard) {
        var removed=jobs.entrySet().stream().filter(e->discard.test(e.getKey())).map(e->Map.entry(e.getKey(),e.getValue())).toList();
        for(var entry:removed)if(jobs.remove(entry.getKey(),entry.getValue()))entry.getValue().future.cancel(false);
    }
    @Override public synchronized void cancel(UUID session,long revision) {
        discardRequests(r->r.session().equals(session)&&r.revision()==revision);refreshPins();
    }
    /** Called only on the Paper thread; the resulting DTO contains no Bukkit objects. */
    public synchronized JsonObject snapshot(QuizGame.Snapshot state,List<Member> members) {
        var rosterJson=new JsonArray();
        for(Member m:members)rosterJson.add(object("uuid",m.uuid(),"membershipId",m.membershipId(),"nickname",m.nickname(),"textureHash",m.textureHash(),"roomId",state.session()));
        String phase=switch(state.phase()) {
            case STOPPED->"ENDED";case WAITING_FOR_SUBMISSION->"IDLE";case WAITING_FOR_MEDIA->"WAITING_FOR_MEDIA";
            default->state.phase().name();
        };
        if(state.phase()==QuizGame.Phase.REVEAL && state.media()==null)phase="IDLE";
        JsonObject round=null,reveal=null;
        if(state.roundId()!=null && state.media()!=null && !phase.equals("IDLE") && !phase.equals("ENDED")) {
            long end=state.media()==null?0:state.clipEndMillis();
            round=object("roundId",state.roundId(),"number",state.round(),"revision",Long.toString(state.revision()),
                    "playbackGeneration",state.playbackGeneration(),"mediaId",state.media()==null?null:state.media().id(),
                    "clipStartMs",0,"clipEndMs",end,"anchorPositionMs",state.anchorPositionMillis(),"anchorAtMs",state.startsAt(),"playing",state.playing(),"playbackRate",state.playbackRate());
        }
        if(phase.equals("REVEAL") && state.song()!=null)reveal=object("title",state.song().title(),"aliases",new JsonArray(),"tags",gson.toJsonTree(state.song().tags()),"youtubeUrl",state.song().youtubeUrl());
        var rooms=new JsonArray();rooms.add(object("roomId",state.session(),"roomRevision",Long.toString(state.revision()),"phase",phase,"round",round,"reveal",reveal,"roundNumber",state.round(),"totalRounds",state.totalRounds(),"registeredSongs",state.registeredSongs()));
        var retained=new JsonArray();
        if(state.phase()!=QuizGame.Phase.STOPPED)jobs.entrySet().stream().filter(e->e.getKey().session().equals(state.session())&&e.getValue().media!=null)
                .map(e->e.getValue().media.id()).distinct().sorted().forEach(retained::add);
        return object("schemaVersion",1,"instanceId",instance,"seq","0","sentAtMs",0,"roster",rosterJson,"rooms",rooms,"retainedMediaIds",retained);
    }
    @Override public synchronized void publish(QuizGame.Snapshot state) {
        if(closed||closing)return;
        discardRequests(r->state.phase()==QuizGame.Phase.STOPPED||!r.session().equals(state.session()));
        latest=state;queue(state);
    }
    public synchronized void heartbeat() {if(!closed&&!closing&&latest!=null)queue(latest);}
    private CompletableFuture<Set<String>> queue(QuizGame.Snapshot state) {
        List<Member> members=roster.get();var ack=new CompletableFuture<Set<String>>();
        Packet tail=pending.peekLast();
        // Coalesce heartbeats and equivalent same-phase updates, preserving phase/timeline edges.
        if(tail!=null && tail.state().phase()==state.phase() && Objects.equals(tail.state().roundId(),state.roundId()) && tail.state().playing()==state.playing()) {
            pending.removeLast();ack=tail.ack();
        }
        if(pending.size()>=128){ack.completeExceptionally(new IllegalStateException("Bridge queue full"));return ack;}
        pending.addLast(new Packet(snapshot(state,members),state,List.copyOf(members),ack));lastAck=ack;
        flush();return ack;
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
                var pins=new HashSet<String>();for(var id:body.getAsJsonArray("retainedMediaIds"))pins.add(id.getAsString());
                ack.complete(Set.copyOf(pins));
                main.execute(()->{
                    if(closed)return;
                    var ready=new HashSet<UUID>();
                    var presence=new ArrayList<BrowserPresence>();
                    var progress=new ArrayList<PlaybackProgress>();
                    long serverTime=response.has("serverTimeMs")?response.get("serverTimeMs").getAsLong():System.currentTimeMillis();
                    if(response.has("browsers")&&response.get("browsers").isJsonArray())for(JsonElement entry:response.getAsJsonArray("browsers")) {
                        var b=entry.getAsJsonObject();
                        try {
                            UUID id=UUID.fromString(b.get("uuid").getAsString()),membership=UUID.fromString(b.get("membershipId").getAsString());
                            if(members.stream().noneMatch(m->m.uuid().equals(id)&&m.membershipId().equals(membership)))continue;
                            if(!b.has("connected")&&!b.has("online"))continue;
                            boolean leased=b.has("online")&&b.get("online").getAsBoolean()&&b.has("leaseExpiresAtMs")&&!b.get("leaseExpiresAtMs").isJsonNull()&&b.get("leaseExpiresAtMs").getAsLong()>serverTime;
                            presence.add(new BrowserPresence(id,membership,b.has("connected")?b.get("connected").getAsBoolean():leased));
                            if(leased && Objects.equals(roundId,UUID.fromString(b.get("roundId").getAsString()))
                                    && Objects.equals(generation,UUID.fromString(b.get("playbackGeneration").getAsString()))
                                    && b.get("roundRevision").getAsString().equals(Long.toString(revision))) {
                                String status=b.get("status").getAsString();
                                if(status.equals("PREPARED"))ready.add(id);
                                else if(status.equals("PLAYING")&&packet.state().phase()==QuizGame.Phase.COUNTDOWN)
                                    progress.add(new PlaybackProgress(id,membership,roundId,generation,b.get("positionMs").getAsLong(),b.get("lastSeenAtMs").getAsLong()));
                            }
                        } catch(RuntimeException ignored) { /* Incomplete/unready browser status. */ }
                    }
                    readiness.browsers(packet.state().session(),List.copyOf(presence));
                    readiness.accept(revision,!ready.isEmpty(),!members.isEmpty() && ready.size()==members.size());
                    readiness.controlAccepted(revision);
                    for(var report:progress)readiness.playbackStarted(packet.state().session(),report);
                });
            } else {
                ack.completeExceptionally(error==null?new IllegalStateException("Bridge acknowledgement mismatch"):error);
                main.execute(()->{if(!closed)readiness.unavailable(packet.state().session());});
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
        closed=true;discardRequests(r->true);
        for(Packet packet:pending)packet.ack().completeExceptionally(new IllegalStateException("Bridge closed"));pending.clear();
        http.shutdownNow();worker.shutdownNow();Arrays.fill(secret,(byte)0);
    }
    public synchronized void closeAfterSync() {
        if(closed||closing)return;
        closing=true;discardRequests(r->true);
        // The final full set supersedes queued states, even if normal publications filled the queue.
        for(var packet:pending)packet.ack().completeExceptionally(new IllegalStateException("Bridge closing"));pending.clear();
        var ack=latest==null?inFlightAck:queue(latest);
        worker.schedule(this::close,10,TimeUnit.SECONDS);
        if(ack==null)close();else ack.whenComplete((ignored,error)->close());
    }
}
