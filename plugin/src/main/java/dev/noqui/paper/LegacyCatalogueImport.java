package dev.noqui.paper;

import com.google.gson.*;
import dev.noqui.core.Song;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;

/** Imports the old private catalogue without changing its bytes or blocking the Paper thread. */
final class LegacyCatalogueImport {
    record Result(int saved,int skipped,Path backup,boolean alreadyImported) {}
    private record Plan(byte[] source,String hash,List<JsonArray> batches,int skipped,boolean imported) {}
    private static final Gson JSON=new Gson();
    static CompletionStage<Result> start(Path source,Function<JsonArray,CompletionStage<Integer>> send) {
        return CompletableFuture.supplyAsync(()->read(source)).thenCompose(plan->{
            if(plan.imported())return CompletableFuture.completedFuture(new Result(0,0,null,true));
            CompletionStage<Integer> chain=CompletableFuture.completedFuture(0);
            for(var batch:plan.batches())chain=chain.thenCompose(total->send.apply(batch).thenApply(saved->{
                if(saved==null||saved!=batch.size())throw new CompletionException(new IOException("Incomplete catalogue import acknowledgement"));
                return total+saved;
            }));
            return chain.thenApplyAsync(saved->{
                try{
                    if(!Arrays.equals(plan.source(),Files.readAllBytes(source)))throw new IOException("Legacy catalogue changed during import");
                    Path backup=source.resolveSibling(source.getFileName()+".migrated-"+plan.hash()+".bak");
                    if(Files.exists(backup)){if(!Arrays.equals(plan.source(),Files.readAllBytes(backup)))throw new IOException("Legacy backup mismatch");}
                    else Files.write(backup,plan.source(),StandardOpenOption.CREATE_NEW);
                    Files.writeString(marker(source),plan.hash(),StandardCharsets.UTF_8);
                    return new Result(saved,plan.skipped(),backup,false);
                }catch(IOException error){throw new CompletionException(error);}
            });
        });
    }
    private static Path marker(Path source){return source.resolveSibling(source.getFileName()+".imported.sha256");}
    private static Plan read(Path source) {
        try{
            if(!Files.exists(source))return new Plan(null,"",List.of(),0,true);
            byte[] bytes=Files.readAllBytes(source);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if(Files.exists(marker(source))&&Files.readString(marker(source),StandardCharsets.UTF_8).trim().equals(hash))return new Plan(bytes,hash,List.of(),0,true);
            var yaml=new YamlConfiguration();yaml.loadFromString(new String(bytes,StandardCharsets.UTF_8));var songs=yaml.getConfigurationSection("songs");
            if(songs==null)throw new IOException("Legacy catalogue has no songs section");
            var batches=new ArrayList<JsonArray>();var batch=new JsonArray();int skipped=0;
            for(String id:songs.getKeys(false)){
                try{
                    if(!UUID.fromString(id).toString().equalsIgnoreCase(id))throw new IllegalArgumentException();
                    var row=songs.getConfigurationSection(id);if(row==null)throw new IllegalArgumentException();
                    if(!(row.get("youtube-url") instanceof String url)||!(row.get("title") instanceof String title)||!(row.get("tags") instanceof List<?> values))throw new IllegalArgumentException();
                    var tags=new ArrayList<String>();for(Object tag:values){if(!(tag instanceof String text))throw new IllegalArgumentException();tags.add(text);}
                    new Song(url,title,tags); // Validate without replacing the original catalogue strings.
                    var item=new JsonObject();item.addProperty("id",id);item.addProperty("youtubeUrl",url);item.addProperty("title",title);item.add("tags",JSON.toJsonTree(tags));
                    var candidate=batch.deepCopy();candidate.add(item);
                    if(candidate.size()>50||size(candidate)>=65536){if(batch.isEmpty())throw new IllegalArgumentException();batches.add(batch);batch=new JsonArray();batch.add(item);}
                    else batch=candidate;
                }catch(IllegalArgumentException error){skipped++;}
            }
            if(!batch.isEmpty())batches.add(batch);
            if(batches.isEmpty()&&skipped>0)throw new IOException("Legacy catalogue has no valid songs");
            return new Plan(bytes,hash,List.copyOf(batches),skipped,false);
        }catch(Exception error){throw new CompletionException(error);}
    }
    private static int size(JsonArray songs){var body=new JsonObject();body.addProperty("schemaVersion",1);body.add("songs",songs);return JSON.toJson(body).getBytes(StandardCharsets.UTF_8).length;}
}
