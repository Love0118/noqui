package dev.noqui.bridge;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistBridgeTest {
    @Test void signedPrivatePagesLoadAllTracksWithoutIngestingOrExposingQueue()throws Exception {
        byte[] key=new byte[32];var offsets=new CopyOnWriteArrayList<Integer>();var failures=new ConcurrentLinkedQueue<Throwable>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",8788),0);
        server.createContext("/internal/v1/playlists",exchange->{try{
            String path=exchange.getRequestURI().toString();byte[] body=exchange.getRequestBody().readAllBytes();
            try{
                assertEquals("GET",exchange.getRequestMethod());assertEquals(0,body.length);
                var h=exchange.getRequestHeaders();assertEquals(BridgeClient.signature(key,"GET",path,h.getFirst("X-Noqui-Timestamp"),h.getFirst("X-Noqui-Nonce"),body),h.getFirst("X-Noqui-Signature"));
            }catch(Throwable error){failures.add(error);}
            var response=new JsonObject();
            if(path.equals("/internal/v1/playlists")){
                var entries=new JsonArray();var list=new JsonObject();list.addProperty("id","test");list.addProperty("name","씹노퀴");list.addProperty("trackCount",121);list.addProperty("playableCount",120);entries.add(list);response.add("playlists",entries);
            }else{
                int offset=Integer.parseInt(path.substring(path.lastIndexOf('/')+1));offsets.add(offset);var tracks=new JsonArray();
                for(int i=offset;i<Math.min(offset+50,120);i++){
                    var entry=new JsonObject();var song=new JsonObject();song.addProperty("id",UUID.randomUUID().toString());song.addProperty("youtubeUrl","https://www.youtube.com/watch?v=BaW_jenozKc");song.addProperty("title","Private"+i);song.add("tags",new JsonArray());entry.add("song",song);
                    entry.addProperty("mediaId",UUID.randomUUID().toString());entry.addProperty("durationMs",240000);tracks.add(entry);
                }
                response.addProperty("id","test");response.add("tracks",tracks);response.add("nextOffset",offset+50<120?new JsonPrimitive(offset+50):JsonNull.INSTANCE);
            }
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        try(var client=new BridgeClient(URI.create("http://127.0.0.1:8788"),key,"primary",UUID.randomUUID(),Runnable::run,List::of,(r,a,b)->{},s->{})){
            var lists=client.playlists().toCompletableFuture().get(5,TimeUnit.SECONDS);assertEquals("씹노퀴",lists.getFirst().name());assertEquals(120,lists.getFirst().playableCount());
            var tracks=client.playlistTracks("test").toCompletableFuture().get(5,TimeUnit.SECONDS);assertEquals(120,tracks.size());assertEquals("Private119",tracks.getLast().song().title());assertEquals(240000,tracks.getFirst().media().durationMillis());assertEquals(List.of(0,50,100),offsets);
            assertTrue(failures.isEmpty(),failures.toString());assertThrows(CompletionException.class,()->client.playlistTracks("../escape").toCompletableFuture().join());
        }finally{server.stop(0);}
    }
}
