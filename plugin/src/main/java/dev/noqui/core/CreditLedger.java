package dev.noqui.core;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Writes reservations before accepting submissions; an interrupted preparation is refunded on load. */
public final class CreditLedger {
    public static final int MAX_GRANT=1000;
    public static final long MAX_BALANCE=1_000_000;
    private final Path file;
    private Map<UUID,Long> balances=new HashMap<>();
    private Map<UUID,UUID> pending=new HashMap<>();
    private record Grant(UUID player,long amount) {}
    private Map<String,Grant> grants=new HashMap<>();
    public record Ground(UUID world,double x,double y,double z,int age) {
        public Ground {Objects.requireNonNull(world);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||age<0)throw new IllegalArgumentException("Invalid ground ticket location");}
    }
    public record DroppedCredit(UUID owner,int amount,Ground ground) {}
    private Map<UUID,DroppedCredit> dropped=new HashMap<>();
    public CreditLedger(){file=null;}
    public CreditLedger(Path file) throws IOException {
        this.file=file;
        if(Files.exists(file))try {
            JsonObject data=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            int version=data.get("version").getAsInt();if(version!=1&&version!=2)throw new IllegalArgumentException("Unsupported ledger version");
            data.getAsJsonObject("balances").entrySet().forEach(e->{long value=e.getValue().getAsLong();
                if(value<0 || value>MAX_BALANCE)throw new IllegalArgumentException("Invalid balance");balances.put(UUID.fromString(e.getKey()),value);});
            data.getAsJsonObject("pending").entrySet().forEach(e->pending.put(UUID.fromString(e.getKey()),UUID.fromString(e.getValue().getAsString())));
            if(data.has("grants"))data.getAsJsonObject("grants").entrySet().forEach(e->{var grant=e.getValue().getAsJsonObject();
                grants.put(e.getKey(),new Grant(UUID.fromString(grant.get("player").getAsString()),grant.get("amount").getAsLong()));});
            if(data.has("dropped"))data.getAsJsonObject("dropped").entrySet().forEach(e->{var d=e.getValue().getAsJsonObject();int amount=d.get("amount").getAsInt();
                if(amount<1||amount>64)throw new IllegalArgumentException("Invalid dropped credit amount");
                dropped.put(UUID.fromString(e.getKey()),new DroppedCredit(UUID.fromString(d.get("owner").getAsString()),amount,
                    new Ground(UUID.fromString(d.get("world").getAsString()),d.get("x").getAsDouble(),d.get("y").getAsDouble(),d.get("z").getAsDouble(),d.get("age").getAsInt())));});
            var recovered=new HashMap<>(balances);
            pending.values().forEach(id->recovered.merge(id,1L,Long::sum));
            if(recovered.values().stream().anyMatch(n->n>MAX_BALANCE))throw new IllegalArgumentException("Invalid reserved balance");
            commit(recovered,Map.of(),grants);
        } catch(RuntimeException error){throw new IOException("Invalid credit ledger",error);}
    }
    public long balance(UUID player){return balances.getOrDefault(player,0L);}
    public Map<UUID,DroppedCredit> dropped(){return Map.copyOf(dropped);}
    public DroppedCredit dropped(UUID claim){return dropped.get(claim);}
    /** The debit and transferable ground claim are one durable transaction. */
    public void drop(UUID claim,UUID owner,int amount,Ground ground) {
        if(dropped.containsKey(claim))throw new IllegalArgumentException("Duplicate ticket drop");
        if(amount<1||amount>64||balance(owner)<amount)throw new IllegalArgumentException("출제권 수량을 확인하세요.");
        var next=new HashMap<>(balances);next.put(owner,balance(owner)-amount);
        var claims=new HashMap<>(dropped);claims.put(Objects.requireNonNull(claim),new DroppedCredit(owner,amount,Objects.requireNonNull(ground)));commit(next,pending,grants,claims);
    }
    /** Partial/full pickup debits the claim and credits its recipient atomically; stale copies cannot pay twice. */
    public int pickup(UUID claim,UUID player,int requested) {
        var value=dropped.get(claim);if(value==null||requested<=0)return 0;
        long reserved=pending.values().stream().filter(player::equals).count();
        int amount=(int)Math.min(Math.min(requested,value.amount()),MAX_BALANCE-balance(player)-reserved);if(amount<=0)return 0;
        var next=new HashMap<>(balances);next.put(player,balance(player)+amount);var claims=new HashMap<>(dropped);
        if(amount==value.amount())claims.remove(claim);else claims.put(claim,new DroppedCredit(value.owner(),value.amount()-amount,value.ground()));
        commit(next,pending,grants,claims);return amount;
    }
    public void checkpointDrops(Map<UUID,Ground> positions) {
        var claims=new HashMap<>(dropped);boolean changed=false;
        for(var entry:positions.entrySet()) {var value=claims.get(entry.getKey());if(value==null||value.ground().equals(entry.getValue()))continue;
            claims.put(entry.getKey(),new DroppedCredit(value.owner(),value.amount(),entry.getValue()));changed=true;}
        if(changed)commit(balances,pending,grants,claims);
    }
    /** Natural despawn/destruction spends the ground item; it never returns to the original balance. */
    public void retireDrop(UUID claim) {
        if(!dropped.containsKey(claim))return;var claims=new HashMap<>(dropped);claims.remove(claim);commit(balances,pending,grants,claims);
    }
    public void grant(UUID player,int amount) {
        long reserved=pending.values().stream().filter(player::equals).count();
        if(amount<1 || amount>MAX_GRANT || balance(player)+reserved+amount>MAX_BALANCE)
            throw new IllegalArgumentException("출제권은 1..1000개, 보유 한도는 1,000,000개입니다.");
        var next=new HashMap<>(balances);next.put(player,balance(player)+amount);commit(next,pending,grants);
    }
    public void giveOnce(UUID player,long amount,String transactionId) {
        if(transactionId==null||transactionId.isBlank()||transactionId.length()>256)throw new IllegalArgumentException("Invalid transaction ID");
        var grant=new Grant(Objects.requireNonNull(player),amount);var previous=grants.get(transactionId);
        if(previous!=null){if(!previous.equals(grant))throw new IllegalArgumentException("Conflicting credit transaction");return;}
        long reserved=pending.values().stream().filter(player::equals).count();
        if(amount<1||amount>MAX_BALANCE||balance(player)+reserved+amount>MAX_BALANCE)throw new IllegalArgumentException("출제권 보유 한도를 넘습니다.");
        var next=new HashMap<>(balances);next.put(player,balance(player)+amount);
        var transactions=new HashMap<>(grants);transactions.put(transactionId,grant);commit(next,pending,transactions);
    }
    public void reserve(UUID reservation,UUID player) {
        if(pending.containsKey(reservation) || balance(player)<1)throw new IllegalArgumentException("출제권이 부족합니다.");
        var next=new HashMap<>(balances);next.put(player,balance(player)-1);
        var reservations=new HashMap<>(pending);reservations.put(reservation,player);commit(next,reservations,grants);
    }
    public boolean refund(UUID reservation) {
        UUID owner=pending.get(reservation);if(owner==null)return false;
        var next=new HashMap<>(balances);next.put(owner,balance(owner)+1);
        var reservations=new HashMap<>(pending);reservations.remove(reservation);commit(next,reservations,grants);return true;
    }
    public void spend(UUID reservation) {
        if(!pending.containsKey(reservation))throw new IllegalStateException("Missing credit reservation");
        var reservations=new HashMap<>(pending);reservations.remove(reservation);commit(balances,reservations,grants);
    }
    private void commit(Map<UUID,Long> next,Map<UUID,UUID> reservations,Map<String,Grant> transactions) {
        commit(next,reservations,transactions,dropped);
    }
    private void commit(Map<UUID,Long> next,Map<UUID,UUID> reservations,Map<String,Grant> transactions,Map<UUID,DroppedCredit> claims) {
        if(file!=null)try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            var data=new JsonObject();data.addProperty("version",2);var b=new JsonObject();var p=new JsonObject();
            next.forEach((id,n)->b.addProperty(id.toString(),n));reservations.forEach((id,owner)->p.addProperty(id.toString(),owner.toString()));
            data.add("balances",b);data.add("pending",p);
            var g=new JsonObject();transactions.forEach((id,grant)->{var value=new JsonObject();value.addProperty("player",grant.player().toString());value.addProperty("amount",grant.amount());g.add(id,value);});data.add("grants",g);
            var d=new JsonObject();claims.forEach((id,claim)->{var value=new JsonObject();var ground=claim.ground();
                value.addProperty("owner",claim.owner().toString());value.addProperty("amount",claim.amount());value.addProperty("world",ground.world().toString());
                value.addProperty("x",ground.x());value.addProperty("y",ground.y());value.addProperty("z",ground.z());value.addProperty("age",ground.age());d.add(id.toString(),value);});data.add("dropped",d);
            Path temp=file.resolveSibling(file.getFileName()+".tmp");
            try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                ByteBuffer bytes=StandardCharsets.UTF_8.encode(data.toString());while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
            }
            try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException error){throw new UncheckedIOException("Could not save credit ledger",error);}
        balances=new HashMap<>(next);pending=new HashMap<>(reservations);grants=new HashMap<>(transactions);dropped=new HashMap<>(claims);
    }
}
