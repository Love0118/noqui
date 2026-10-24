package dev.noqui.bridge;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.noqui.core.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgePinsTest {
    private static final class Harness implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers=Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        final BlockingQueue<JsonObject> packets=new LinkedBlockingQueue<>();
        final BlockingQueue<CompletableFuture<JsonObject>> replies=new LinkedBlockingQueue<>();
        final BlockingQueue<CompletableFuture<Void>> syncGates=new LinkedBlockingQueue<>();
        final BlockingQueue<String> requests=new LinkedBlockingQueue<>();
        final AtomicInteger posts=new AtomicInteger();
        final AtomicInteger rejectEnqueues=new AtomicInteger();
        final BlockingQueue<String> enqueueNonces=new LinkedBlockingQueue<>(),enqueueIds=new LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean failPinSync=new java.util.concurrent.atomic.AtomicBoolean();
        final UUID media=UUID.randomUUID(),owner=UUID.randomUUID(),instance=UUID.randomUUID();
        final BridgeClient client;
        final QuizGame game;
        Harness()throws Exception{this(Runnable::run);}
        Harness(Executor main)throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",8788),0);server.setExecutor(workers);
            server.createContext("/internal/v1/media/jobs",exchange->{try{
                var input=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();posts.incrementAndGet();requests.add(exchange.getRequestMethod());
                enqueueNonces.add(exchange.getRequestHeaders().getFirst("X-Noqui-Nonce"));enqueueIds.add(input.get("requestId").getAsString());
                if(rejectEnqueues.getAndUpdate(n->Math.max(0,n-1))>0){exchange.sendResponseHeaders(429,-1);return;}
                var held=replies.poll();JsonObject response=held==null?ready(media):held.get(8,TimeUnit.SECONDS);
                byte[] body=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}});
            server.createContext("/internal/v1/sync",exchange->{try{
                var packet=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();packets.add(packet);
                var gate=syncGates.poll();if(gate!=null)gate.get(8,TimeUnit.SECONDS);
                if(failPinSync.get()&&!pins(packet).isEmpty()){exchange.sendResponseHeaders(503,-1);return;}
                byte[] body=("{\"acceptedSeq\":\""+packet.get("seq").getAsString()+"\",\"browsers\":[]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}});server.start();
            client=new BridgeClient(URI.create("http://127.0.0.1:8788"),new byte[32],"primary",instance,main,List::of,(r,a,b)->{},s->{});
            game=new QuizGame(new MediaBridge(){public CompletionStage<Media> prepare(Request r){throw new UnsupportedOperationException();}public void cancel(UUID s,long r){}public void publish(QuizGame.Snapshot s){}},Runnable::run,()->100000,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){}public void error(UUID id,String m){fail(m);}},115,false);
            game.start(List.of(owner));
            client.publish(game.snapshot());nextPacket();
        }
        MediaBridge.Request request(long operation){return new MediaBridge.Request(game.snapshot().session(),operation,Song.input("https://youtu.be/BaW_jenozKc","Private queued title","private tag"));}
        CompletableFuture<MediaBridge.Media> prepare(long operation){return client.prepare(request(operation)).toCompletableFuture();}
        JsonObject snapshot(){return client.snapshot(game.snapshot(),List.of());}
        JsonObject nextPacket()throws Exception{var packet=packets.poll(5,TimeUnit.SECONDS);assertNotNull(packet,"Expected sync packet");return packet;}
        JsonObject awaitPins(Set<String> expected)throws Exception{for(int i=0;i<12;i++){var packet=nextPacket();if(pins(packet).equals(expected))return packet;}throw new AssertionError("Expected pin set not published");}
        public void close(){client.close();server.stop(0);workers.shutdownNow();}
    }
    private static JsonObject ready(UUID media){var response=new JsonObject();response.addProperty("state","READY");response.addProperty("mediaId",media.toString());response.addProperty("durationMs",60000);return response;}
    private static Set<String> pins(JsonObject packet){assertTrue(packet.has("retainedMediaIds"),"Full internal pin list must always be present");var result=new LinkedHashSet<String>();for(var id:packet.getAsJsonArray("retainedMediaIds")){assertTrue(result.add(UUID.fromString(id.getAsString()).toString()),"Duplicate pin");}assertTrue(result.size()<=100);return result;}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(2);assertTrue(condition.getAsBoolean(),"Timed out waiting for test condition");}
    @Test void readyResultRemainsPinnedAfterItsFutureCompletes()throws Exception{
        try(var h=new Harness()){
            assertEquals(h.media.toString(),h.prepare(1).get(5,TimeUnit.SECONDS).id());assertEquals(Set.of(h.media.toString()),pins(h.snapshot()));
            h.client.cancel(h.game.snapshot().session(),1);assertEquals(Set.of(),pins(h.snapshot()));
        }
    }
    @Test void sharedMediaIsReleasedOnlyAfterTheLastRequestAndDuplicatePrepareIsIdempotent()throws Exception{
        try(var h=new Harness()){
            var first=h.prepare(1);first.get(5,TimeUnit.SECONDS);assertSame(first,h.prepare(1));h.prepare(2).get(5,TimeUnit.SECONDS);assertEquals(2,h.posts.get());
            h.client.cancel(h.game.snapshot().session(),1);assertEquals(Set.of(h.media.toString()),pins(h.snapshot()));
            h.client.cancel(UUID.randomUUID(),2);assertEquals(Set.of(h.media.toString()),pins(h.snapshot()));
            h.client.cancel(h.game.snapshot().session(),2);assertTrue(pins(h.snapshot()).isEmpty());
        }
    }
    @Test void cancelBeforeReadyOrInsideCompletionCannotRepin()throws Exception{
        try(var h=new Harness()){
            var held=new CompletableFuture<JsonObject>();h.replies.add(held);var before=h.prepare(1);assertNotNull(h.requests.poll(5,TimeUnit.SECONDS));
            h.client.cancel(h.game.snapshot().session(),1);held.complete(ready(h.media));assertTrue(before.isCancelled());
            var next=new CompletableFuture<JsonObject>();h.replies.add(next);var during=h.prepare(2);assertNotNull(h.requests.poll(5,TimeUnit.SECONDS));
            var canceled=during.thenRun(()->h.client.cancel(h.game.snapshot().session(),2));next.complete(ready(h.media));canceled.get(5,TimeUnit.SECONDS);
            assertTrue(pins(h.snapshot()).isEmpty());h.client.heartbeat();assertTrue(pins(h.snapshot()).isEmpty());
        }
    }
    @Test void readyAndCancelPublishPromptFullSetsAndHeartbeatDoesNotExposeQueueMetadata()throws Exception{
        try(var h=new Harness()){
            h.client.publish(h.game.snapshot());assertTrue(pins(h.nextPacket()).isEmpty());h.prepare(1).get(5,TimeUnit.SECONDS);
            var pinned=h.awaitPins(Set.of(h.media.toString()));assertEquals(h.instance.toString(),pinned.get("instanceId").getAsString());
            assertFalse(pinned.toString().contains("Private queued"));assertFalse(pinned.toString().contains("BaW_jenozKc"));assertFalse(pinned.get("rooms").toString().contains(h.media.toString()));
            h.client.heartbeat();assertEquals(Set.of(h.media.toString()),pins(h.nextPacket()));
            h.client.cancel(h.game.snapshot().session(),1);h.awaitPins(Set.of());
        }
    }
    @Test void scheduledReadyHeartbeatReadsLatestQuizStateRatherThanCapturedOldState()throws Exception{
        var main=new ConcurrentLinkedQueue<Runnable>();
        try(var h=new Harness(main::add)){
            h.client.publish(h.game.snapshot());h.nextPacket();var future=h.prepare(1);await(()->!pins(h.snapshot()).isEmpty());
            h.game.join(UUID.randomUUID());long newest=h.game.snapshot().revision();h.client.publish(h.game.snapshot());
            await(()->{Runnable task;while((task=main.poll())!=null)task.run();return future.isDone();});future.get(5,TimeUnit.SECONDS);
            var packet=h.awaitPins(Set.of(h.media.toString()));assertEquals(Long.toString(newest),packet.getAsJsonArray("rooms").get(0).getAsJsonObject().get("roomRevision").getAsString());
            h.client.heartbeat();assertEquals(Long.toString(newest),h.nextPacket().getAsJsonArray("rooms").get(0).getAsJsonObject().get("roomRevision").getAsString());
        }
    }
    @Test void queuedPacketCapturesItsPinSetAndLaterReleaseIsNotOverwritten()throws Exception{
        try(var h=new Harness()){
            var gate=new CompletableFuture<Void>();h.syncGates.add(gate);h.client.publish(h.game.snapshot());h.nextPacket();
            var future=h.prepare(1);await(()->!pins(h.snapshot()).isEmpty());h.client.heartbeat();h.game.stop();h.client.publish(h.game.snapshot());assertTrue(future.isCancelled());
            gate.complete(null);var pinned=h.nextPacket();assertEquals(Set.of(h.media.toString()),pins(pinned));
            var stopped=h.awaitPins(Set.of());assertEquals("ENDED",stopped.getAsJsonArray("rooms").get(0).getAsJsonObject().get("phase").getAsString());
            h.client.heartbeat();assertTrue(pins(h.nextPacket()).isEmpty());
        }
    }
    @Test void oneHundredRequestsBoundBothPendingAndReadyReferencesAndCancellationFreesCapacity()throws Exception{
        try(var h=new Harness()){
            for(int i=0;i<100;i++)h.prepare(i).get(5,TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,()->h.prepare(100).get(5,TimeUnit.SECONDS));assertEquals(100,h.posts.get());assertEquals(Set.of(h.media.toString()),pins(h.snapshot()));
            h.client.cancel(h.game.snapshot().session(),0);h.prepare(100).get(5,TimeUnit.SECONDS);assertEquals(101,h.posts.get());
        }
    }
    @Test void busyConversionQueueRetriesWithoutRefundingAndUsesFreshNonceWithSameRequestId()throws Exception {
        try(var h=new Harness()){
            h.rejectEnqueues.set(1);var future=h.prepare(1);future.get(6,TimeUnit.SECONDS);
            assertEquals(2,h.posts.get());assertNotEquals(h.enqueueNonces.remove(),h.enqueueNonces.remove());
            assertEquals(h.enqueueIds.remove(),h.enqueueIds.remove());assertEquals(Set.of(h.media.toString()),pins(h.snapshot()));
        }
    }
    @Test void cancellationStopsEnqueueRetriesWhileTheBackendIsBusy()throws Exception {
        try(var h=new Harness()){
            h.rejectEnqueues.set(100);var future=h.prepare(1);await(()->h.posts.get()==1);
            h.client.cancel(h.game.snapshot().session(),1);assertTrue(future.isCancelled());Thread.sleep(2300);assertEquals(1,h.posts.get());
        }
    }
    @Test void gracefulStopPublishesEmptyPinsBeforeCloseAndLateReadyCannotReviveThem()throws Exception{
        try(var h=new Harness()){
            h.client.publish(h.game.snapshot());h.nextPacket();h.prepare(1).get(5,TimeUnit.SECONDS);h.awaitPins(Set.of(h.media.toString()));
            var gate=new CompletableFuture<Void>();h.syncGates.add(gate);h.game.stop();h.client.publish(h.game.snapshot());assertTrue(pins(h.nextPacket()).isEmpty());
            h.client.closeAfterSync();gate.complete(null);var last=h.awaitPins(Set.of());assertEquals("ENDED",last.getAsJsonArray("rooms").get(0).getAsJsonObject().get("phase").getAsString());
            assertThrows(ExecutionException.class,()->h.prepare(2).get(5,TimeUnit.SECONDS));assertTrue(pins(h.snapshot()).isEmpty());
        }
    }
    @Test void newSessionAndNewBridgeInstanceStartWithExplicitEmptyPins()throws Exception{
        try(var h=new Harness()){
            h.prepare(1).get(5,TimeUnit.SECONDS);h.awaitPins(Set.of(h.media.toString()));h.game.stop();h.game.start(List.of(h.owner));h.client.publish(h.game.snapshot());assertTrue(pins(h.nextPacket()).isEmpty());
            try(var restarted=new BridgeClient(URI.create("http://127.0.0.1:8788"),new byte[32],"primary",UUID.randomUUID(),Runnable::run,List::of,(r,a,b)->{},s->{})){
                var packet=restarted.snapshot(h.game.snapshot(),List.of());assertTrue(pins(packet).isEmpty());assertNotEquals(h.instance.toString(),packet.get("instanceId").getAsString());
            }
        }
    }
    @Test void readyCannotCompleteUntilThePacketContainingItsPinIsAcknowledged()throws Exception{
        try(var h=new Harness()){
            var gate=new CompletableFuture<Void>();h.syncGates.add(gate);var future=h.prepare(1);
            assertEquals(Set.of(h.media.toString()),pins(h.nextPacket()));assertFalse(future.isDone());
            gate.complete(null);assertEquals(h.media.toString(),future.get(5,TimeUnit.SECONDS).id());
        }
    }
    @Test void cancelWhilePinAckIsPendingIgnoresItsLateSuccessAndPublishesRelease()throws Exception{
        try(var h=new Harness()){
            var gate=new CompletableFuture<Void>();h.syncGates.add(gate);var future=h.prepare(1);h.awaitPins(Set.of(h.media.toString()));
            h.client.cancel(h.game.snapshot().session(),1);assertTrue(future.isCancelled());gate.complete(null);h.awaitPins(Set.of());
            h.client.heartbeat();assertTrue(pins(h.nextPacket()).isEmpty());assertTrue(future.isCancelled());
        }
    }
    @Test void failedPinAckNeverExposesReadyToQuizAndRefundsTheReservation()throws Exception{
        try(var h=new Harness()){
            h.failPinSync.set(true);var states=new LinkedBlockingQueue<QuizGame.Snapshot>();
            var game=new QuizGame(h.client,Runnable::run,()->100000,new QuizGame.Output(){public void changed(QuizGame.Snapshot s){states.add(s);}public void error(UUID id,String m){}},115,false);
            game.start(List.of(h.owner));game.grant(h.owner,1);states.clear();game.submit(h.owner,game.snapshot().revision(),Song.input("https://youtu.be/BaW_jenozKc","Song","tag"));
            QuizGame.Snapshot state;do{state=states.poll(5,TimeUnit.SECONDS);assertNotNull(state);assertNull(state.media());assertNotEquals(QuizGame.Phase.PREPARING,state.phase());}while(state.phase()!=QuizGame.Phase.WAITING_FOR_SUBMISSION);
            assertEquals(1,game.credits(h.owner));assertFalse(game.pending(h.owner));h.awaitPins(Set.of());
        }
    }
    @Test void gracefulCloseSupersedesEvenAFullControlQueueWithTheFinalEmptySet()throws Exception{
        try(var h=new Harness()){
            h.prepare(1).get(5,TimeUnit.SECONDS);h.awaitPins(Set.of(h.media.toString()));
            var gate=new CompletableFuture<Void>();h.syncGates.add(gate);h.client.heartbeat();h.nextPacket();
            for(int i=0;i<65;i++){h.game.stop();h.client.publish(h.game.snapshot());h.game.start(List.of(h.owner));h.client.publish(h.game.snapshot());}
            h.game.stop();h.client.publish(h.game.snapshot());h.client.closeAfterSync();gate.complete(null);
            var last=h.nextPacket();assertTrue(pins(last).isEmpty());assertEquals("ENDED",last.getAsJsonArray("rooms").get(0).getAsJsonObject().get("phase").getAsString());
        }
    }
}
