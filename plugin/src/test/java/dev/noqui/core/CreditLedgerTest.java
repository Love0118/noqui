package dev.noqui.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CreditLedgerTest {
    @TempDir Path directory;
    @Test void reservationsRecoverOnceAcrossRestartWhileSpentCreditsStaySpent()throws Exception {
        Path file=directory.resolve("credits.json");UUID player=UUID.randomUUID(),pending=UUID.randomUUID(),spent=UUID.randomUUID();
        var ledger=new CreditLedger(file);ledger.grant(player,3);ledger.reserve(pending,player);ledger.reserve(spent,player);ledger.spend(spent);assertEquals(1,ledger.balance(player));
        var recovered=new CreditLedger(file);assertEquals(2,recovered.balance(player));assertFalse(recovered.refund(pending));assertEquals(2,new CreditLedger(file).balance(player));
    }
    @Test void refundIsIdempotentAndBoundedGrantNeverOverflows() {
        var ledger=new CreditLedger();UUID player=UUID.randomUUID(),id=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->ledger.reserve(id,player));
        ledger.grant(player,1);ledger.reserve(id,player);assertTrue(ledger.refund(id));assertFalse(ledger.refund(id));assertEquals(1,ledger.balance(player));
        for(int amount:new int[]{0,-1,1001,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->ledger.grant(player,amount));
        for(int i=0;i<999;i++)ledger.grant(player,1000);ledger.grant(player,999);assertEquals(CreditLedger.MAX_BALANCE,ledger.balance(player));ledger.reserve(id,player);assertThrows(IllegalArgumentException.class,()->ledger.grant(player,1));assertTrue(ledger.refund(id));
    }
    @Test void corruptLedgerFailsClosedAndFailedPersistenceDoesNotAcceptCredits()throws Exception {
        Path file=directory.resolve("credits.json");Files.writeString(file,"{invalid");assertThrows(java.io.IOException.class,()->new CreditLedger(file));
        Files.delete(file);var ledger=new CreditLedger(file);Files.createDirectory(file);UUID player=UUID.randomUUID();assertThrows(java.io.UncheckedIOException.class,()->ledger.grant(player,1));assertEquals(0,ledger.balance(player));
    }
    @Test void paidGrantTransactionSurvivesRestartAndCanBeRetriedAfterSpend()throws Exception {
        Path file=directory.resolve("credits.json");UUID player=UUID.randomUUID(),reservation=UUID.randomUUID();
        var ledger=new CreditLedger(file);ledger.giveOnce(player,2,"purchase:one");ledger.giveOnce(player,2,"purchase:one");assertEquals(2,ledger.balance(player));
        ledger.reserve(reservation,player);ledger.spend(reservation);var recovered=new CreditLedger(file);recovered.giveOnce(player,2,"purchase:one");assertEquals(1,recovered.balance(player));
        assertThrows(IllegalArgumentException.class,()->recovered.giveOnce(player,3,"purchase:one"));assertThrows(IllegalArgumentException.class,()->recovered.giveOnce(UUID.randomUUID(),2,"purchase:one"));
    }
    @Test void groundClaimsTransferPartiallyAcrossRestartAndNeverRefundOrRedeemTwice()throws Exception {
        Path file=directory.resolve("credits.json");UUID owner=UUID.randomUUID(),recipient=UUID.randomUUID(),claim=UUID.randomUUID();
        var ledger=new CreditLedger(file);ledger.grant(owner,5);ledger.drop(claim,owner,3,new CreditLedger.Ground(UUID.randomUUID(),1,65,2,0));assertEquals(2,ledger.balance(owner));
        ledger=new CreditLedger(file);assertEquals(2,ledger.balance(owner));assertEquals(3,ledger.dropped(claim).amount());
        assertEquals(1,ledger.pickup(claim,recipient,1));assertEquals(2,ledger.dropped(claim).amount());
        ledger=new CreditLedger(file);assertEquals(1,ledger.balance(recipient));assertEquals(2,ledger.pickup(claim,recipient,64));assertEquals(0,ledger.pickup(claim,recipient,64));assertEquals(3,ledger.balance(recipient));
        assertNull(new CreditLedger(file).dropped(claim));assertEquals(2,new CreditLedger(file).balance(owner));
        UUID destroyed=UUID.randomUUID();ledger.drop(destroyed,owner,1,new CreditLedger.Ground(UUID.randomUUID(),1,65,2,0));ledger.retireDrop(destroyed);
        ledger=new CreditLedger(file);assertEquals(1,ledger.balance(owner));assertNull(ledger.dropped(destroyed));
    }
    @Test void failedDropAndPickupWritesLeaveBothSidesUnchanged()throws Exception {
        Path file=directory.resolve("credits.json"),blocked=directory.resolve("credits.json.tmp");var ledger=new CreditLedger(file);UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),claim=UUID.randomUUID();
        ledger.grant(owner,2);var ground=new CreditLedger.Ground(UUID.randomUUID(),1,65,2,0);Files.createDirectory(blocked);
        assertThrows(java.io.UncheckedIOException.class,()->ledger.drop(claim,owner,1,ground));assertEquals(2,ledger.balance(owner));assertNull(ledger.dropped(claim));
        Files.delete(blocked);ledger.drop(claim,owner,1,ground);Files.createDirectory(blocked);
        assertThrows(java.io.UncheckedIOException.class,()->ledger.pickup(claim,other,1));assertEquals(0,ledger.balance(other));assertEquals(1,ledger.dropped(claim).amount());assertEquals(1,ledger.balance(owner));
    }
}
