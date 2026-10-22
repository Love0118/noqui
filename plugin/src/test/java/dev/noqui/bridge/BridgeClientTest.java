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
            var game=game(fake);game.start(player,List.of(player,membership));
            var song=Song.input("https://youtu.be/BaW_jenozKc","Private answer","PrivateTag");game.submit(player,game.snapshot().revision(),song);
            String pending=client.snapshot(game.snapshot(),List.of()).toString();assertFalse(pending.contains("Private"));assertFalse(pending.contains("BaW_jenozKc"));
            future.complete(new MediaBridge.Media(UUID.randomUUID().toString(),60000));
            String prepared=client.snapshot(game.snapshot(),List.of()).toString();assertFalse(prepared.contains("Private"));assertFalse(prepared.contains("youtube"));
            game.browsersReady(game.snapshot().revision(),true,true);game.skip();String reveal=client.snapshot(game.snapshot(),List.of()).toString();
            assertTrue(reveal.contains("Private answer"));assertTrue(reveal.contains("PrivateTag"));assertTrue(reveal.contains("BaW_jenozKc"));
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
            var game=game(fake);game.start(player,List.of(player));client.publish(game.snapshot());
            String raw=client.joinToken(member,game.snapshot().session()).toCompletableFuture().get(8,TimeUnit.SECONDS);
            assertTrue(raw.matches("[A-Za-z0-9_-]{43}"));assertEquals(BridgeClient.hash(raw.getBytes(StandardCharsets.UTF_8)),tokenBody.get().get("tokenSha256").getAsString());
            assertFalse(tokenBody.get().toString().contains(raw));assertNull(failure.get());
        }finally{server.stop(0);}
    }
}
