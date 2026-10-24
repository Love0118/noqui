package dev.noqui.lobby;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.noqui.lobby.LobbyCatalog.Currency;

class LobbyWalletsTest {
    @TempDir Path dir;
    UUID id=UUID.randomUUID();
    LobbyCatalog.Product product(String id){return LobbyCatalog.defaults().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();}
    @Test void zeroInitialFundsInsufficientMoneyAndCurrencyRestrictions()throws Exception {
        LobbyWallets w=new LobbyWallets(dir.resolve("wallet"));
        assertEquals(0,w.wallet(id).stars());assertEquals(0,w.wallet(id).points());
        assertThrows(IllegalArgumentException.class,()->w.purchase(id,product("hammer"),Currency.STARLIGHT,"buy"));
        w.reward(id,"points",100,Currency.POINTS);
        assertThrows(IllegalArgumentException.class,()->w.purchase(id,product("question_credit"),Currency.POINTS,"credit"));
        w.purchase(id,product("glasses"),Currency.POINTS,"cosmetic");
        assertEquals(80,w.wallet(id).points());assertEquals(1,w.wallet(id).owned().get("glasses"));assertTrue(w.pending().isEmpty());
    }
    @Test void rewardAndPurchaseReplayAreDurableAndConflictsRejected()throws Exception {
        Path file=dir.resolve("wallet");LobbyWallets w=new LobbyWallets(file);
        w.reward(id,"stars",200,Currency.STARLIGHT);w.reward(id,"stars",200,Currency.STARLIGHT);
        assertThrows(IllegalArgumentException.class,()->w.reward(id,"stars",201,Currency.STARLIGHT));
        w.purchase(id,product("hammer"),Currency.STARLIGHT,"purchase");w.purchase(id,product("hammer"),Currency.STARLIGHT,"purchase");
        LobbyWallets again=new LobbyWallets(file);again.purchase(id,product("hammer"),Currency.STARLIGHT,"purchase");
        assertEquals(100,again.wallet(id).stars());assertFalse(again.wallet(id).owned().containsKey("hammer"));
        assertEquals(1,again.pendingGadgets(id).size());assertEquals(w.pendingGadgets(id).getFirst().serial(),again.pendingGadgets(id).getFirst().serial());
        assertThrows(IllegalArgumentException.class,()->again.purchase(id,product("hammer"),Currency.POINTS,"purchase"));
    }
    @Test void paidCreditOutboxSurvivesRestartAndAckPersists()throws Exception {
        Path file=dir.resolve("wallet");LobbyWallets w=new LobbyWallets(file);w.reward(id,"seed",20,Currency.STARLIGHT);
        w.purchase(id,product("question_credit"),Currency.STARLIGHT,"credit");
        LobbyWallets restart=new LobbyWallets(file);var grant=restart.pending().getFirst();
        assertEquals(id,grant.player());assertEquals(1,grant.amount());assertEquals(10,restart.wallet(id).stars());
        restart.purchase(id,product("question_credit"),Currency.STARLIGHT,"credit");assertEquals(1,restart.pending().size());
        restart.delivered(grant.transaction());assertTrue(new LobbyWallets(file).pending().isEmpty());
    }
    @Test void saveFailureCannotDebitOrAddOutboxAndCorruptionCannotResetBalances()throws Exception {
        Path file=dir.resolve("wallet");LobbyWallets original=new LobbyWallets(file);original.reward(id,"seed",20,Currency.STARLIGHT);
        LobbyWallets broken=new LobbyWallets(file,(p,b)->{throw new IOException("disk failure");});
        assertThrows(IOException.class,()->broken.purchase(id,product("question_credit"),Currency.STARLIGHT,"credit"));
        assertEquals(20,broken.wallet(id).stars());assertTrue(broken.pending().isEmpty());assertEquals(20,new LobbyWallets(file).wallet(id).stars());
        byte[] bytes=Files.readAllBytes(file);bytes[15]^=1;Files.write(file,bytes);assertThrows(IOException.class,()->new LobbyWallets(file));
    }
    @Test void cosmeticsRemainVirtualAndPhysicalGadgetSerialsRetireDurably()throws Exception {
        Path file=dir.resolve("wallet");LobbyWallets w=new LobbyWallets(file);w.reward(id,"points",100,Currency.POINTS);w.reward(id,"stars",1000,Currency.STARLIGHT);
        w.purchase(id,product("quad_boots"),Currency.POINTS,"boots");
        w.purchase(id,product("hammer"),Currency.POINTS,"hammer");
        w.purchase(id,product("glasses"),Currency.STARLIGHT,"glasses");w.equip(id,product("glasses"));
        LobbyWallets again=new LobbyWallets(file);assertFalse(again.wallet(id).equipped().containsKey("boots"));assertEquals("glasses",again.wallet(id).equipped().get("face"));
        var hammer=again.pendingGadgets(id).stream().filter(g->g.product().equals("hammer")).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->again.consumeGadget(hammer.serial(),"hammer"));
        again.gadgetDelivered(hammer.serial());again.consumeGadget(hammer.serial(),"hammer");
        assertNull(new LobbyWallets(file).gadget(hammer.serial()));
        assertThrows(IllegalArgumentException.class,()->again.consumeGadget(hammer.serial(),"hammer"));
        again.equip(id,product("glasses"));assertFalse(again.wallet(id).equipped().containsKey("face"));
    }
    private Path legacyWallet()throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.DataOutputStream(bytes)) {
            out.writeInt(0x4e514c31);out.writeInt(1);out.writeUTF(id.toString());out.writeLong(88);out.writeLong(9);
            out.writeInt(3);out.writeUTF("quad_boots");out.writeLong(1);out.writeUTF("snow_bomb");out.writeLong(2);out.writeUTF("glasses");out.writeLong(1);
            out.writeInt(2);out.writeUTF("boots");out.writeUTF("quad_boots");out.writeUTF("face");out.writeUTF("glasses");
            out.writeInt(0);out.writeInt(0);
        }
        byte[] body=bytes.toByteArray();bytes.writeBytes(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        Path file=dir.resolve("legacy");Files.write(file,bytes.toByteArray());return file;
    }
    @Test void legacyPaidGadgetsMigrateOnceWithoutResettingMoneyCosmeticsOrSpentItems()throws Exception {
        Path file=legacyWallet();LobbyWallets wallet=new LobbyWallets(file);wallet.migrateGadgets(id);
        assertEquals(3,wallet.pendingGadgets(id).size());assertEquals(Map.of("glasses",1L),wallet.wallet(id).owned());
        assertEquals(Map.of("face","glasses"),wallet.wallet(id).equipped());assertEquals(88,wallet.wallet(id).stars());assertEquals(9,wallet.wallet(id).points());
        var spent=wallet.pendingGadgets(id).stream().filter(g->g.product().equals("snow_bomb")).findFirst().orElseThrow();
        wallet.gadgetDelivered(spent.serial());wallet.consumeGadget(spent.serial(),"snow_bomb");
        var restarted=new LobbyWallets(file);restarted.migrateGadgets(id);assertEquals(2,restarted.pendingGadgets(id).size());assertNull(restarted.gadget(spent.serial()));
    }
    @Test void failedMigrationOrRetirementCannotLoseLegacyOwnershipOrSpendARealItem()throws Exception {
        Path file=legacyWallet();byte[] original=Files.readAllBytes(file);
        var broken=new LobbyWallets(file,(p,b)->{throw new IOException("disk full");});
        assertThrows(IOException.class,()->broken.migrateGadgets(id));assertEquals(2,broken.wallet(id).owned().get("snow_bomb"));assertTrue(broken.pendingGadgets(id).isEmpty());assertArrayEquals(original,Files.readAllBytes(file));
        var working=new LobbyWallets(file);working.migrateGadgets(id);var gadget=working.pendingGadgets(id).getFirst();working.gadgetDelivered(gadget.serial());
        var failing=new LobbyWallets(file,(p,b)->{throw new IOException("disk full");});
        assertThrows(IOException.class,()->failing.consumeGadget(gadget.serial(),gadget.product()));assertNotNull(failing.gadget(gadget.serial()));
    }
    @Test void duplicateDurablesAndOverflowFailClosed()throws Exception {
        LobbyWallets w=new LobbyWallets(dir.resolve("wallet"));w.reward(id,"seed",1000,Currency.STARLIGHT);w.purchase(id,product("glasses"),Currency.STARLIGHT,"one");
        assertThrows(IllegalArgumentException.class,()->w.purchase(id,product("glasses"),Currency.STARLIGHT,"two"));assertEquals(800,w.wallet(id).stars());
        assertThrows(ArithmeticException.class,()->w.reward(id,"overflow",Long.MAX_VALUE,Currency.STARLIGHT));assertEquals(800,w.wallet(id).stars());
        assertThrows(IllegalArgumentException.class,()->w.reward(id,"negative",-1,Currency.STARLIGHT));
    }
}
