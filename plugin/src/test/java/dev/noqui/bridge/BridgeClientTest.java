package dev.noqui.bridge;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.noqui.core.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgeClientTest {
    private final byte[] key=new byte[32];
    private final UUID player=UUID.randomUUID(),membership=UUID.randomUUID();
    private BridgeClient client(java.util.function.Supplier<List<BridgeClient.Member>> roster) {
        return new BridgeClient(URI.create("http://127.0.0.1:8788"),key,"primary",UUID.randomUUID(),Runnable::run,roster,(r,a,b)->{},s->{});
    }
    @Test void authenticatedIdlePresenceIsIndependentFromAudioAndTransportErrorsBecomeUnknown()throws Exception {
        var body=new java.util.concurrent.atomic.AtomicReference<JsonObject>();var outage=new java.util.concurrent.atomic.AtomicBoolean();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",8788),0);
        server.createContext("/internal/v1/sync",exchange->{try{
            var request=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            if(outage.get()){exchange.sendResponseHeaders(503,-1);return;}
            var response=new JsonObject();response.add("acceptedSeq",request.get("seq"));response.addProperty("serverTimeMs",1000);var browsers=new JsonArray();browsers.add(body.get());response.add("browsers",browsers);
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        var observations=new LinkedBlockingQueue<List<BridgeClient.BrowserPresence>>();var unknown=new CountDownLatch(1);var member=new BridgeClient.Member(player,membership,"Player",null);
        var state=new JsonObject();state.addProperty("uuid",player.toString());state.addProperty("membershipId",membership.toString());state.addProperty("connected",true);state.addProperty("online",false);body.set(state);
        try(var client=new BridgeClient(URI.create("http://127.0.0.1:8788"),key,"primary",UUID.randomUUID(),Runnable::run,()->List.of(member),new BridgeClient.Readiness(){
            public void accept(long revision,boolean any,boolean all){assertFalse(any);assertFalse(all);}
            public void browsers(UUID session,List<BridgeClient.BrowserPresence> browsers){observations.add(browsers);}
            public void unavailable(UUID session){unknown.countDown();}
        },s->{})) {
            var game=game(new MediaBridge(){public CompletionStage<Media> prepare(Request r){return new CompletableFuture<>();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}});game.start(List.of(player));client.publish(game.snapshot());
            var idle=observations.poll(5,TimeUnit.SECONDS);assertNotNull(idle);assertEquals(List.of(new BridgeClient.BrowserPresence(player,membership,true)),idle);
            var legacy=state.deepCopy();legacy.remove("connected");legacy.addProperty("online",true);legacy.addProperty("leaseExpiresAtMs",2000);body.set(legacy);client.heartbeat();assertTrue(Objects.requireNonNull(observations.poll(5,TimeUnit.SECONDS)).getFirst().connected());
            var disconnected=legacy.deepCopy();disconnected.addProperty("connected",false);body.set(disconnected);client.heartbeat();assertFalse(Objects.requireNonNull(observations.poll(5,TimeUnit.SECONDS)).getFirst().connected());
            var stale=state.deepCopy();stale.addProperty("membershipId",UUID.randomUUID().toString());body.set(stale);client.heartbeat();assertTrue(Objects.requireNonNull(observations.poll(5,TimeUnit.SECONDS)).isEmpty());
            outage.set(true);client.heartbeat();assertTrue(unknown.await(5,TimeUnit.SECONDS));assertTrue(observations.isEmpty());
        }finally{server.stop(0);}
    }
    @Test void queuedJobBeyondFifteenMinutesRemainsValidWithinBounded64JobBudget() {
        var now=new java.util.concurrent.atomic.AtomicLong(1000);long deadline=now.get()+BridgeClient.MAX_PREPARATION_MILLIS;
        try(var client=new BridgeClient(URI.create("http://127.0.0.1:8788"),key,"primary",UUID.randomUUID(),Runnable::run,List::of,(r,a,b)->{},s->{},now::get)) {
            var request=new MediaBridge.Request(UUID.randomUUID(),1,Song.input("https://youtu.be/BaW_jenozKc","Song",""));
            var waiting=new CompletableFuture<MediaBridge.Media>();UUID job=UUID.randomUUID(),media=UUID.randomUUID();
            JsonObject queued=JsonParser.parseString("{\"state\":\"QUEUED\",\"jobId\":\""+job+"\",\"retryAfterMs\":30000}").getAsJsonObject();
            now.addAndGet(16*60000L);client.poll(request,waiting,queued,null,deadline);assertFalse(waiting.isDone());
            now.set(64L*300000);client.poll(request,waiting,queued,null,deadline);assertFalse(waiting.isDone());
            var ready=JsonParser.parseString("{\"state\":\"READY\",\"mediaId\":\""+media+"\",\"durationMs\":115007}").getAsJsonObject();client.poll(request,waiting,ready,null,deadline);assertEquals(media.toString(),waiting.join().id());
            var expired=new CompletableFuture<MediaBridge.Media>();now.set(deadline+1);client.poll(request,expired,queued,null,deadline);assertTrue(expired.isCompletedExceptionally());
        }
    }
    @Test void cancellationAndCloseStopQueuedPollingWithoutWaitingForQueueBudget()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",8788),0);var posts=new java.util.concurrent.CountDownLatch(2);var gets=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/internal/v1/media/jobs",exchange->{try{exchange.getRequestBody().readAllBytes();if(exchange.getRequestMethod().equals("GET"))gets.incrementAndGet();
            byte[] body=("{\"state\":\"QUEUED\",\"jobId\":\""+UUID.randomUUID()+"\",\"retryAfterMs\":30000}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);posts.countDown();
        }finally{exchange.close();}});server.start();
        try(var client=client(List::of)) {
            UUID session=UUID.randomUUID();var song=Song.input("https://youtu.be/BaW_jenozKc","Song","");
            var canceled=client.prepare(new MediaBridge.Request(session,1,song)).toCompletableFuture();var closed=client.prepare(new MediaBridge.Request(session,2,song)).toCompletableFuture();
            assertTrue(posts.await(5,TimeUnit.SECONDS));client.cancel(session,1);assertTrue(canceled.isCancelled());client.close();assertTrue(closed.isCancelled());assertEquals(0,gets.get());
        }finally{server.stop(0);}
    }
    @Test void signingMatchesIndependentVectorAndEmptyGetHasNoTrailingNewline() {
        for(int i=0;i<key.length;i++)key[i]=(byte)i;
        byte[] body="{\"schemaVersion\":1,\"videoId\":\"BaW_jenozKc\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("v1=176190945017ac5ebaea23d954db70048d8f6e2bf24301a25b0cff8514170076",
                BridgeClient.signature(key,"POST","/internal/v1/media/jobs","1792594800","AAECAwQFBgcICQoLDA0ODw",body));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",BridgeClient.hash(new byte[0]));
        assertEquals("v1=6e85e80888aa7b664a50f96848d87dece72cd67e6b5886fd0e157bca11bb0f0c",
                BridgeClient.signature(key,"GET","/internal/v1/media/jobs/20000000-0000-4000-8000-000000000001","1792594800","AAECAwQFBgcICQoLDA0ODw",new byte[0]));
        assertNotEquals(BridgeClient.signature(key,"GET","/internal/v1/media/jobs/a","1","nonce",new byte[0]),BridgeClient.signature(key,"GET","/internal/v1/media/jobs/a","1","nonce","\n".getBytes(StandardCharsets.UTF_8)));
    }
    private QuizGame game(MediaBridge bridge) {
        return new QuizGame(bridge,Runnable::run,()->100000,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String e){}},115,false);
    }
    @Test void preRevealSyncCannotLeakAnswersSourceOrVideoId() {
        try(var client=client(List::of)) {
            var future=new CompletableFuture<MediaBridge.Media>();
            MediaBridge fake=new MediaBridge(){public CompletionStage<Media> prepare(Request r){return future;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
            var game=game(fake);game.start(List.of(player,membership));game.grant(player,1);
            var song=Song.input("https://youtu.be/BaW_jenozKc","Private answer","PrivateTag");game.submit(player,game.snapshot().revision(),song);
            String pending=client.snapshot(game.snapshot(),List.of()).toString();assertFalse(pending.contains("Private"));assertFalse(pending.contains("BaW_jenozKc"));
            future.complete(new MediaBridge.Media(UUID.randomUUID().toString(),60000));
            String prepared=client.snapshot(game.snapshot(),List.of()).toString();assertFalse(prepared.contains("Private"));assertFalse(prepared.contains("youtube"));
            game.browsersReady(game.snapshot().revision(),true,true);game.forceSkip(game.snapshot().roundId());String reveal=client.snapshot(game.snapshot(),List.of()).toString();
            assertTrue(reveal.contains("Private answer"));assertTrue(reveal.contains("PrivateTag"));assertTrue(reveal.contains("BaW_jenozKc"));
        }
    }
    @Test void opusPaddingMetadataProducesValidBoundedRoundTimelineAndKeepsPreparedMedia() {
        try(var client=client(List::of)) {
            var future=new CompletableFuture<MediaBridge.Media>();
            var fake=new MediaBridge(){public CompletionStage<Media> prepare(Request r){return future;}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
            var game=game(fake);game.start(List.of(player));game.grant(player,1);game.submit(player,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Secret","Tag"));
            UUID media=UUID.randomUUID();future.complete(new MediaBridge.Media(media.toString(),115007));
            JsonObject room=client.snapshot(game.snapshot(),List.of()).getAsJsonArray("rooms").get(0).getAsJsonObject();var round=room.getAsJsonObject("round");
            assertEquals("PREPARING",room.get("phase").getAsString());assertEquals(115007,game.snapshot().media().durationMillis());
            assertEquals(115000,round.get("clipEndMs").getAsLong());assertEquals(0,round.get("clipStartMs").getAsLong());assertEquals(0,round.get("anchorPositionMs").getAsLong());assertFalse(round.get("playing").getAsBoolean());
            assertEquals(media.toString(),round.get("mediaId").getAsString());assertEquals(room.get("roundNumber"),round.get("number"));assertTrue(room.get("reveal").isJsonNull());
        }
    }
    @Test void realHttpSignsExactBytesPollsEmptyGetAndIssuesHashOnlyToken() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",8788),0);
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var nonces=ConcurrentHashMap.<String>newKeySet();
        var tokenBody=new java.util.concurrent.atomic.AtomicReference<JsonObject>();var gets=new java.util.concurrent.atomic.AtomicInteger();
        UUID job=UUID.randomUUID(),media=UUID.randomUUID();
        server.createContext("/internal/v1/",exchange->{
            try {
                byte[] bytes=exchange.getRequestBody().readAllBytes();String path=exchange.getRequestURI().getRawPath(),method=exchange.getRequestMethod();var h=exchange.getRequestHeaders();
                assertTrue(nonces.add(h.getFirst("X-Noqui-Nonce")));assertEquals("primary",h.getFirst("X-Noqui-Key-Id"));
                assertEquals(BridgeClient.signature(key,method,path,h.getFirst("X-Noqui-Timestamp"),h.getFirst("X-Noqui-Nonce"),bytes),h.getFirst("X-Noqui-Signature"));
                String reply;
                if(method.equals("GET")){assertEquals(0,bytes.length);gets.incrementAndGet();reply="{\"state\":\"READY\",\"jobId\":\""+job+"\",\"mediaId\":\""+media+"\",\"durationMs\":60000}";}
                else {
                    var body=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
                    if(path.endsWith("/media/jobs")){assertEquals(Set.of("schemaVersion","requestId","videoId"),body.keySet());reply="{\"state\":\"QUEUED\",\"jobId\":\""+job+"\",\"retryAfterMs\":2000}";}
                    else if(path.endsWith("/sync"))reply="{\"acceptedSeq\":"+body.get("seq")+",\"browsers\":[]}";
                    else {tokenBody.set(body);assertFalse(body.has("token"));reply="{\"expiresInSeconds\":60}";}
                }
                byte[] output=reply.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);
            }catch(Throwable error){failure.set(error);exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
        });server.start();
        var member=new BridgeClient.Member(player,membership,"PlayerOne",null);
        try(var client=client(()->List.of(member))) {
            var ready=client.prepare(new MediaBridge.Request(UUID.randomUUID(),1,Song.input("https://youtu.be/BaW_jenozKc","Secret",""))).toCompletableFuture().get(8,TimeUnit.SECONDS);
            assertEquals(media.toString(),ready.id());assertEquals(1,gets.get());
            var fake=new MediaBridge(){public CompletionStage<Media> prepare(Request r){throw new UnsupportedOperationException();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}};
            var game=game(fake);game.start(List.of(player));client.publish(game.snapshot());
            String raw=client.joinToken(member,game.snapshot().session()).toCompletableFuture().get(8,TimeUnit.SECONDS);
            assertTrue(raw.matches("[A-Za-z0-9_-]{43}"));assertEquals(BridgeClient.hash(raw.getBytes(StandardCharsets.UTF_8)),tokenBody.get().get("tokenSha256").getAsString());
            assertFalse(tokenBody.get().toString().contains(raw));assertNull(failure.get());
        }finally{server.stop(0);}
    }
}
