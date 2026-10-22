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
}
