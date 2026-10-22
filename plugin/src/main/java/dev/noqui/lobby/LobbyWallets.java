package dev.noqui.lobby;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static dev.noqui.lobby.LobbyCatalog.*;
import dev.noqui.lobby.LobbyCatalog.Currency;

/** Atomic, checksummed snapshots. A debit and its credit-delivery outbox commit together. */
final class LobbyWallets {
    record Grant(UUID player, long amount, String transaction) {}
    record Wallet(long stars, long points, Map<String,Long> owned, Map<String,String> equipped) {
        Wallet { owned=Map.copyOf(owned);equipped=Map.copyOf(equipped); }
    }
    private record State(Map<UUID,Wallet> wallets, Map<String,String> receipts, Map<String,Grant> outbox) {}
    @FunctionalInterface interface Commit { void write(Path path, byte[] bytes) throws IOException; }
    private static final int MAGIC=0x4e514c31, LIMIT=100000;
    private static final Wallet EMPTY=new Wallet(0,0,Map.of(),Map.of());
    private final Path file;
    private final Commit commit;
    private State state;
    LobbyWallets(Path file) throws IOException { this(file,LobbyWallets::atomicWrite); }
    LobbyWallets(Path file, Commit commit) throws IOException {
        this.file=file;this.commit=commit;state=Files.exists(file)?decode(Files.readAllBytes(file)):new State(Map.of(),Map.of(),Map.of());
    }
    Wallet wallet(UUID id) { return state.wallets.getOrDefault(id,EMPTY); }
    List<Grant> pending() { return List.copyOf(state.outbox.values()); }
    void reward(UUID id,String transaction,long amount,Currency currency) throws IOException {
        if(amount<1)throw new IllegalArgumentException("Reward must be positive");
        String fingerprint="reward|"+id+"|"+amount+"|"+currency;
        if(replayed(transaction,fingerprint))return;
        Wallet old=wallet(id);
        Wallet next=new Wallet(currency==Currency.STARLIGHT?Math.addExact(old.stars,amount):old.stars,
                currency==Currency.POINTS?Math.addExact(old.points,amount):old.points,old.owned,old.equipped);
        save(id,next,transaction,fingerprint,null);
    }
    void purchase(UUID id,Product product,Currency currency,String transaction) throws IOException {
        long price=product.price(currency);
        String fingerprint="buy|"+id+"|"+product.id()+"|"+currency+"|"+price;
        if(replayed(transaction,fingerprint))return;
        Wallet old=wallet(id);long balance=currency==Currency.STARLIGHT?old.stars:old.points;
        if(balance<price)throw new IllegalArgumentException(currency==Currency.STARLIGHT?"별빛 부족":"포인트 부족");
        var owned=new TreeMap<>(old.owned);
        if(product.kind()!=Kind.CREDIT) {
            boolean durable=product.kind()==Kind.COSMETIC||Set.of("boots","suit").contains(product.slot());
            if(durable&&owned.getOrDefault(product.id(),0L)>0)throw new IllegalArgumentException("보유 중");
            owned.merge(product.id(),1L,Math::addExact);
        }
        Wallet next=new Wallet(old.stars-(currency==Currency.STARLIGHT?price:0),old.points-(currency==Currency.POINTS?price:0),owned,old.equipped);
        Grant grant=product.kind()==Kind.CREDIT?new Grant(id,1,"lobby:"+hex(transaction.getBytes(java.nio.charset.StandardCharsets.UTF_8))):null;
        save(id,next,transaction,fingerprint,grant);
    }
    void equip(UUID id,Product product) throws IOException {
        Wallet old=wallet(id);
        if(product.kind()==Kind.CREDIT||old.owned.getOrDefault(product.id(),0L)<1)throw new IllegalArgumentException("보유 아이템 없음");
        var equipped=new TreeMap<>(old.equipped);
        if(product.id().equals(equipped.get(product.slot())))equipped.remove(product.slot());else equipped.put(product.slot(),product.id());
        save(id,new Wallet(old.stars,old.points,old.owned,equipped),null,null,null);
    }
    void consume(UUID id,String product) throws IOException {
        Wallet old=wallet(id);long count=old.owned.getOrDefault(product,0L);
        if(count<1)throw new IllegalArgumentException("보유 아이템 없음");
        var owned=new TreeMap<>(old.owned);var equipped=new TreeMap<>(old.equipped);
        if(count==1){owned.remove(product);equipped.values().removeIf(product::equals);}else owned.put(product,count-1);
        save(id,new Wallet(old.stars,old.points,owned,equipped),null,null,null);
    }
    void delivered(String transaction) throws IOException {
        var outbox=new TreeMap<>(state.outbox);if(outbox.remove(transaction)==null)return;
        replace(new State(state.wallets,state.receipts,outbox));
    }
    private boolean replayed(String id,String fingerprint) {
        if(id==null||id.isBlank()||id.length()>200)throw new IllegalArgumentException("Invalid transaction ID");
        String prior=state.receipts.get(id);
        if(prior==null)return false;
        if(!prior.equals(fingerprint))throw new IllegalArgumentException("Transaction conflict");
        return true;
    }
    private void save(UUID id,Wallet wallet,String transaction,String fingerprint,Grant grant) throws IOException {
        var wallets=new TreeMap<>(state.wallets);wallets.put(id,wallet);
        var receipts=new TreeMap<>(state.receipts);var outbox=new TreeMap<>(state.outbox);
        if(transaction!=null)receipts.put(transaction,fingerprint);
        if(grant!=null)outbox.put(grant.transaction,grant);
        if(wallets.size()>10000||receipts.size()>LIMIT||outbox.size()>1000)throw new IOException("Lobby ledger capacity reached");
        replace(new State(wallets,receipts,outbox));
    }
    private void replace(State next) throws IOException { commit.write(file,encode(next));state=next; }
    private static byte[] encode(State state) throws IOException {
        var buffer=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);out.writeInt(state.wallets.size());
            for(var row:new TreeMap<>(state.wallets).entrySet()) {
                out.writeUTF(row.getKey().toString());Wallet w=row.getValue();out.writeLong(w.stars);out.writeLong(w.points);
                out.writeInt(w.owned.size());for(var item:new TreeMap<>(w.owned).entrySet()){out.writeUTF(item.getKey());out.writeLong(item.getValue());}
                out.writeInt(w.equipped.size());for(var item:new TreeMap<>(w.equipped).entrySet()){out.writeUTF(item.getKey());out.writeUTF(item.getValue());}
            }
            out.writeInt(state.receipts.size());for(var row:new TreeMap<>(state.receipts).entrySet()){out.writeUTF(row.getKey());out.writeUTF(row.getValue());}
            out.writeInt(state.outbox.size());for(Grant g:new TreeMap<>(state.outbox).values()){out.writeUTF(g.player.toString());out.writeLong(g.amount);out.writeUTF(g.transaction);}
        }
        byte[] body=buffer.toByteArray();buffer.writeBytes(digest(body));return buffer.toByteArray();
    }
    private static State decode(byte[] bytes) throws IOException {
        if(bytes.length<40||bytes.length>32*1024*1024)throw new IOException("Invalid lobby ledger size");
        byte[] body=Arrays.copyOf(bytes,bytes.length-32);
        if(!MessageDigest.isEqual(digest(body),Arrays.copyOfRange(bytes,body.length,bytes.length)))throw new IOException("Lobby ledger checksum mismatch");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))) {
            if(in.readInt()!=MAGIC)throw new IOException("Unknown lobby ledger version");
            var wallets=new TreeMap<UUID,Wallet>();int count=count(in,10000);
            for(int i=0;i<count;i++) {
                UUID id=UUID.fromString(in.readUTF());long stars=in.readLong(),points=in.readLong();if(stars<0||points<0)throw new IOException("Negative wallet");
                var owned=new TreeMap<String,Long>();int items=count(in,100);
                for(int j=0;j<items;j++){String key=in.readUTF();long value=in.readLong();if(value<1)throw new IOException("Invalid stock");owned.put(key,value);}
                var equipped=new TreeMap<String,String>();int slots=count(in,20);for(int j=0;j<slots;j++)equipped.put(in.readUTF(),in.readUTF());
                if(wallets.put(id,new Wallet(stars,points,owned,equipped))!=null)throw new IOException("Duplicate wallet");
            }
            var receipts=new TreeMap<String,String>();count=count(in,LIMIT);for(int i=0;i<count;i++)if(receipts.put(in.readUTF(),in.readUTF())!=null)throw new IOException("Duplicate receipt");
            var outbox=new TreeMap<String,Grant>();count=count(in,1000);for(int i=0;i<count;i++){Grant g=new Grant(UUID.fromString(in.readUTF()),in.readLong(),in.readUTF());if(g.amount<1||outbox.put(g.transaction,g)!=null)throw new IOException("Invalid outbox");}
            if(in.available()!=0)throw new IOException("Trailing ledger bytes");return new State(wallets,receipts,outbox);
        } catch(IllegalArgumentException error){throw new IOException("Invalid lobby ledger",error);}
    }
    private static int count(DataInputStream in,int maximum)throws IOException {int n=in.readInt();if(n<0||n>maximum)throw new IOException("Invalid ledger count");return n;}
    private static byte[] digest(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String hex(byte[] bytes){return HexFormat.of().formatHex(digest(bytes));}
    private static void atomicWrite(Path path,byte[] bytes)throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());Path temp=Files.createTempFile(path.toAbsolutePath().getParent(),"wallet-",".tmp");
        try {
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);}
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temp);}
    }
}
