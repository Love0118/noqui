package dev.noqui.paper;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LegacyCatalogueImportTest {
    @TempDir Path directory;
    private Path catalogue(int count,boolean large)throws Exception {
        var yaml=new YamlConfiguration();for(int i=0;i<count;i++){
            String key="songs."+new UUID(0,i+1);yaml.set(key+".youtube-url","https://youtu.be/BaW_jenozKc");yaml.set(key+".title",large?"곡"+"😀".repeat(159):"곡 "+i);
            var tags=new ArrayList<String>();for(int j=0;j<(large?10:1);j++)tags.add(large?j+"😀".repeat(31):"태그 "+i);yaml.set(key+".tags",tags);
        }
        Path file=directory.resolve("songs.yml");yaml.save(file.toFile());return file;
    }
    @Test void importsInBoundedBatchesPreservesStringsAndBacksUpOnlyAfterEveryAck()throws Exception {
        Path file=catalogue(51,false);byte[] original=Files.readAllBytes(file);var batches=new LinkedBlockingQueue<JsonArray>();var first=new CompletableFuture<Integer>();
        var call=new AtomicInteger();var result=LegacyCatalogueImport.start(file,batch->{batches.add(batch.deepCopy());return call.getAndIncrement()==0?first:CompletableFuture.completedFuture(batch.size());}).toCompletableFuture();
        var batch=batches.poll(5,TimeUnit.SECONDS);assertNotNull(batch);assertEquals(50,batch.size());assertFalse(result.isDone());assertArrayEquals(original,Files.readAllBytes(file));
        assertEquals(new UUID(0,1).toString(),batch.get(0).getAsJsonObject().get("id").getAsString());assertEquals("https://youtu.be/BaW_jenozKc",batch.get(0).getAsJsonObject().get("youtubeUrl").getAsString());
        first.complete(50);var imported=result.get(5,TimeUnit.SECONDS);assertEquals(51,imported.saved());assertEquals(1,Objects.requireNonNull(batches.poll(5,TimeUnit.SECONDS)).size());
        assertArrayEquals(original,Files.readAllBytes(imported.backup()));assertArrayEquals(original,Files.readAllBytes(file));
        assertTrue(LegacyCatalogueImport.start(file,b->{fail("Already imported file must not resend");return null;}).toCompletableFuture().get(5,TimeUnit.SECONDS).alreadyImported());
    }
    @Test void utf8LimitCanSplitBeforeFiftyRows()throws Exception {
        Path file=catalogue(50,true);var lengths=new ArrayList<Integer>();
        var result=LegacyCatalogueImport.start(file,batch->{var body=new JsonObject();body.addProperty("schemaVersion",1);body.add("songs",batch);int length=new Gson().toJson(body).getBytes(StandardCharsets.UTF_8).length;assertTrue(length<65536);assertTrue(batch.size()<=50);lengths.add(batch.size());return CompletableFuture.completedFuture(batch.size());}).toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertEquals(50,result.saved());assertTrue(lengths.size()>1);
    }
    @Test void partialFailureLeavesOriginalForIdempotentRetryWithoutBackupOrMarker()throws Exception {
        Path file=catalogue(51,false);byte[] original=Files.readAllBytes(file);var ids=new ArrayList<String>();var calls=new AtomicInteger();
        var failed=LegacyCatalogueImport.start(file,batch->{for(var row:batch)ids.add(row.getAsJsonObject().get("id").getAsString());return calls.getAndIncrement()==0?CompletableFuture.completedFuture(batch.size()):CompletableFuture.failedFuture(new IllegalStateException("offline"));}).toCompletableFuture();
        assertThrows(ExecutionException.class,()->failed.get(5,TimeUnit.SECONDS));assertArrayEquals(original,Files.readAllBytes(file));try(var files=Files.list(directory)){assertEquals(1,files.count());}
        var retryIds=new ArrayList<String>();var retry=LegacyCatalogueImport.start(file,batch->{for(var row:batch)retryIds.add(row.getAsJsonObject().get("id").getAsString());return CompletableFuture.completedFuture(batch.size());}).toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertEquals(ids,retryIds);assertEquals(51,retry.saved());
    }
    @Test void wrongAcknowledgementOrConcurrentFileChangeCannotMarkMigrationComplete()throws Exception {
        Path file=catalogue(1,false);var failed=LegacyCatalogueImport.start(file,batch->CompletableFuture.completedFuture(0)).toCompletableFuture();assertThrows(ExecutionException.class,()->failed.get(5,TimeUnit.SECONDS));
        var changed=LegacyCatalogueImport.start(file,batch->{try{Files.writeString(file,"# changed\n",StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}return CompletableFuture.completedFuture(batch.size());}).toCompletableFuture();
        assertThrows(ExecutionException.class,()->changed.get(5,TimeUnit.SECONDS));assertFalse(Files.exists(directory.resolve("songs.yml.imported.sha256")));assertTrue(Files.readString(file).contains("# changed"));
    }
    @Test void invalidRowsStayInTheOriginalAndBackupWhileValidRowsAreAcknowledged()throws Exception {
        Path file=catalogue(1,false);var yaml=YamlConfiguration.loadConfiguration(file.toFile());yaml.set("songs.not-a-uuid.title","bad");yaml.save(file.toFile());byte[] original=Files.readAllBytes(file);
        var result=LegacyCatalogueImport.start(file,batch->CompletableFuture.completedFuture(batch.size())).toCompletableFuture().get(5,TimeUnit.SECONDS);
        assertEquals(1,result.saved());assertEquals(1,result.skipped());assertArrayEquals(original,Files.readAllBytes(result.backup()));
    }
}
