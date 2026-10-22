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
        assertThrows(IllegalArgumentException.class,()->w.purchase(id,product("glasses"),Currency.POINTS,"cosmetic"));
        assertEquals(100,w.wallet(id).points());assertTrue(w.pending().isEmpty());
    }
    @Test void rewardAndPurchaseReplayAreDurableAndConflictsRejected()throws Exception {
        Path file=dir.resolve("wallet");LobbyWallets w=new LobbyWallets(file);
        w.reward(id,"stars",20,Currency.STARLIGHT);w.reward(id,"stars",20,Currency.STARLIGHT);
        assertThrows(IllegalArgumentException.class,()->w.reward(id,"stars",21,Currency.STARLIGHT));
        w.purchase(id,product("hammer"),Currency.STARLIGHT,"purchase");w.purchase(id,product("hammer"),Currency.STARLIGHT,"purchase");
        LobbyWallets again=new LobbyWallets(file);again.purchase(id,product("hammer"),Currency.STARLIGHT,"purchase");
        assertEquals(17,again.wallet(id).stars());assertEquals(1,again.wallet(id).owned().get("hammer"));
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
    @Test void equipmentAndConsumablesPersistWithoutCreatingInventoryItems()throws Exception {
        Path file=dir.resolve("wallet");LobbyWallets w=new LobbyWallets(file);w.reward(id,"points",100,Currency.POINTS);w.reward(id,"stars",100,Currency.STARLIGHT);
        w.purchase(id,product("quad_boots"),Currency.POINTS,"boots");w.equip(id,product("quad_boots"));
        w.purchase(id,product("hammer"),Currency.POINTS,"hammer");w.equip(id,product("hammer"));
        w.purchase(id,product("glasses"),Currency.STARLIGHT,"glasses");w.equip(id,product("glasses"));
        LobbyWallets again=new LobbyWallets(file);assertEquals("quad_boots",again.wallet(id).equipped().get("boots"));assertEquals("glasses",again.wallet(id).equipped().get("face"));
        again.consume(id,"hammer");assertFalse(again.wallet(id).equipped().containsKey("hand"));
        assertThrows(IllegalArgumentException.class,()->again.consume(id,"hammer"));
        again.equip(id,product("glasses"));assertFalse(again.wallet(id).equipped().containsKey("face"));
    }
    @Test void duplicateDurablesAndOverflowFailClosed()throws Exception {
        LobbyWallets w=new LobbyWallets(dir.resolve("wallet"));w.reward(id,"seed",100,Currency.STARLIGHT);w.purchase(id,product("glasses"),Currency.STARLIGHT,"one");
        assertThrows(IllegalArgumentException.class,()->w.purchase(id,product("glasses"),Currency.STARLIGHT,"two"));assertEquals(88,w.wallet(id).stars());
        assertThrows(ArithmeticException.class,()->w.reward(id,"overflow",Long.MAX_VALUE,Currency.STARLIGHT));assertEquals(88,w.wallet(id).stars());
        assertThrows(IllegalArgumentException.class,()->w.reward(id,"negative",-1,Currency.STARLIGHT));
    }
}
