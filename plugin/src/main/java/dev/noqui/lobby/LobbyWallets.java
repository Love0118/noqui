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
    record Gadget(UUID serial,UUID buyer,String product,boolean delivered) {}
    record Wallet(long stars, long points, Map<String,Long> owned, Map<String,String> equipped) {
        Wallet { owned=Map.copyOf(owned);equipped=Map.copyOf(equipped); }
    }
    private record State(Map<UUID,Wallet> wallets, Map<String,String> receipts, Map<String,Grant> outbox,Map<UUID,Gadget> gadgets) {}
    @FunctionalInterface interface Commit { void write(Path path, byte[] bytes) throws IOException; }
    private static final int LEGACY_MAGIC=0x4e514c31, MAGIC=0x4e514c32, LIMIT=100000;
    private static final Set<String> GADGETS=Set.of("triple_boots","quad_boots","hammer","snow_bomb","gravity_suit");
    private static final Wallet EMPTY=new Wallet(0,0,Map.of(),Map.of());
    private final Path file;
    private final Commit commit;
    private State state;
    LobbyWallets(Path file) throws IOException { this(file,LobbyWallets::atomicWrite); }
    LobbyWallets(Path file, Commit commit) throws IOException {
        this.file=file;this.commit=commit;state=Files.exists(file)?decode(Files.readAllBytes(file)):new State(Map.of(),Map.of(),Map.of(),Map.of());
    }
    Wallet wallet(UUID id) { return state.wallets.getOrDefault(id,EMPTY); }
    List<Grant> pending() { return List.copyOf(state.outbox.values()); }
    Gadget gadget(UUID serial){return state.gadgets.get(serial);}
    List<Gadget> pendingGadgets(UUID buyer){return state.gadgets.values().stream().filter(g->g.buyer.equals(buyer)&&!g.delivered).sorted(Comparator.comparing(Gadget::serial)).toList();}
    void reward(UUID id,String transaction,long amount,Currency currency) throws IOException {
        if(amount<1)throw new IllegalArgumentException("Reward must be positive");
        String fingerprint="reward|"+id+"|"+amount+"|"+currency;
        if(replayed(transaction,fingerprint))return;
        Wallet old=wallet(id);
        Wallet next=new Wallet(currency==Currency.STARLIGHT?Math.addExact(old.stars,amount):old.stars,
                currency==Currency.POINTS?Math.addExact(old.points,amount):old.points,old.owned,old.equipped);
        save(id,next,transaction,fingerprint,null,null);
    }
    boolean spend(UUID id,String transaction,long amount) throws IOException {
        if(amount<1)throw new IllegalArgumentException("Debit must be positive");
        String fingerprint="spend|"+id+"|"+amount;
        if(replayed(transaction,fingerprint))return true;
        Wallet old=wallet(id);
        if(old.stars<amount)return false;
        save(id,new Wallet(old.stars-amount,old.points,old.owned,old.equipped),transaction,fingerprint,null,null);
        return true;
    }
    void purchase(UUID id,Product product,Currency currency,String transaction) throws IOException {
        if(product.kind()==Kind.GADGET&&!GADGETS.contains(product.id()))throw new IllegalArgumentException("Unknown gadget");
        long price=product.price(currency);
        String fingerprint="buy|"+id+"|"+product.id()+"|"+currency+"|"+price;
        if(replayed(transaction,fingerprint))return;
        Wallet old=wallet(id);long balance=currency==Currency.STARLIGHT?old.stars:old.points;
        if(balance<price)throw new IllegalArgumentException(currency==Currency.STARLIGHT?"별빛 부족":"포인트 부족");
        var owned=new TreeMap<>(old.owned);
        if(product.kind()==Kind.COSMETIC) {
            if(owned.getOrDefault(product.id(),0L)>0)throw new IllegalArgumentException("보유 중");
            owned.merge(product.id(),1L,Math::addExact);
        }
        Wallet next=new Wallet(old.stars-(currency==Currency.STARLIGHT?price:0),old.points-(currency==Currency.POINTS?price:0),owned,old.equipped);
        Grant grant=product.kind()==Kind.CREDIT?new Grant(id,1,"lobby:"+hex(transaction.getBytes(java.nio.charset.StandardCharsets.UTF_8))):null;
        Gadget gadget=product.kind()==Kind.GADGET?new Gadget(UUID.randomUUID(),id,product.id(),false):null;
        save(id,next,transaction,fingerprint,grant,gadget);
    }
    void equip(UUID id,Product product) throws IOException {
        Wallet old=wallet(id);
        if(product.kind()!=Kind.COSMETIC||old.owned.getOrDefault(product.id(),0L)<1)throw new IllegalArgumentException("보유 치장 없음");
        var equipped=new TreeMap<>(old.equipped);
        if(product.id().equals(equipped.get(product.slot())))equipped.remove(product.slot());else equipped.put(product.slot(),product.id());
        save(id,new Wallet(old.stars,old.points,old.owned,equipped),null,null,null,null);
    }
    /** Move legacy paid stock into unique pending items atomically; removed stock can never remint. */
    void migrateGadgets(UUID player)throws IOException {
        Wallet old=wallet(player);var owned=new TreeMap<>(old.owned);var equipped=new TreeMap<>(old.equipped);
        var gadgets=new TreeMap<>(state.gadgets);boolean changed=false;
        for(String product:GADGETS) {
            long count=owned.getOrDefault(product,0L);
            if(count>LIMIT-gadgets.size())throw new IOException("Gadget ledger capacity reached");
            for(long i=0;i<count;i++){Gadget item=new Gadget(UUID.randomUUID(),player,product,false);gadgets.put(item.serial,item);}
            changed|=owned.remove(product)!=null;
        }
        changed|=equipped.values().removeIf(GADGETS::contains);
        if(!changed)return;
        var wallets=new TreeMap<>(state.wallets);wallets.put(player,new Wallet(old.stars,old.points,owned,equipped));
        replace(new State(wallets,state.receipts,state.outbox,gadgets));
    }
    void gadgetDelivered(UUID serial)throws IOException {
        Gadget item=state.gadgets.get(serial);if(item==null||item.delivered)return;
        var gadgets=new TreeMap<>(state.gadgets);gadgets.put(serial,new Gadget(serial,item.buyer,item.product,true));
        replace(new State(state.wallets,state.receipts,state.outbox,gadgets));
    }
    void consumeGadget(UUID serial,String product)throws IOException {
        Gadget item=state.gadgets.get(serial);
        if(item==null||!item.delivered||!item.product.equals(product))throw new IllegalArgumentException("사용할 장난감 없음");
        var gadgets=new TreeMap<>(state.gadgets);gadgets.remove(serial);
        replace(new State(state.wallets,state.receipts,state.outbox,gadgets));
    }
    void delivered(String transaction) throws IOException {
        var outbox=new TreeMap<>(state.outbox);if(outbox.remove(transaction)==null)return;
        replace(new State(state.wallets,state.receipts,outbox,state.gadgets));
    }
    private boolean replayed(String id,String fingerprint) {
        if(id==null||id.isBlank()||id.length()>200)throw new IllegalArgumentException("Invalid transaction ID");
        String prior=state.receipts.get(id);
        if(prior==null)return false;
        if(!prior.equals(fingerprint))throw new IllegalArgumentException("Transaction conflict");
        return true;
    }
    private void save(UUID id,Wallet wallet,String transaction,String fingerprint,Grant grant,Gadget gadget) throws IOException {
        var wallets=new TreeMap<>(state.wallets);wallets.put(id,wallet);
        var receipts=new TreeMap<>(state.receipts);var outbox=new TreeMap<>(state.outbox);
        if(transaction!=null)receipts.put(transaction,fingerprint);
        if(grant!=null)outbox.put(grant.transaction,grant);
        var gadgets=new TreeMap<>(state.gadgets);if(gadget!=null)gadgets.put(gadget.serial,gadget);
        if(wallets.size()>10000||receipts.size()>LIMIT||outbox.size()>1000||gadgets.size()>LIMIT)throw new IOException("Lobby ledger capacity reached");
        replace(new State(wallets,receipts,outbox,gadgets));
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
            out.writeInt(state.gadgets.size());for(Gadget g:new TreeMap<>(state.gadgets).values()){out.writeUTF(g.serial.toString());out.writeUTF(g.buyer.toString());out.writeUTF(g.product);out.writeBoolean(g.delivered);}
        }
        byte[] body=buffer.toByteArray();buffer.writeBytes(digest(body));return buffer.toByteArray();
    }
    private static State decode(byte[] bytes) throws IOException {
        if(bytes.length<40||bytes.length>32*1024*1024)throw new IOException("Invalid lobby ledger size");
        byte[] body=Arrays.copyOf(bytes,bytes.length-32);
        if(!MessageDigest.isEqual(digest(body),Arrays.copyOfRange(bytes,body.length,bytes.length)))throw new IOException("Lobby ledger checksum mismatch");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))) {
            int version=in.readInt();if(version!=MAGIC&&version!=LEGACY_MAGIC)throw new IOException("Unknown lobby ledger version");
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
            var gadgets=new TreeMap<UUID,Gadget>();
            if(version==MAGIC){count=count(in,LIMIT);for(int i=0;i<count;i++){Gadget g=new Gadget(UUID.fromString(in.readUTF()),UUID.fromString(in.readUTF()),in.readUTF(),in.readBoolean());if(!GADGETS.contains(g.product)||gadgets.put(g.serial,g)!=null)throw new IOException("Invalid gadget record");}}
            if(in.available()!=0)throw new IOException("Trailing ledger bytes");return new State(wallets,receipts,outbox,gadgets);
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
