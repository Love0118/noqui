package dev.noqui.paper;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserConnectionsTest {
    private final UUID player=UUID.randomUUID();
    private BrowserConnections.Entry entry(){return new BrowserConnections.Entry(UUID.randomUUID(),UUID.randomUUID());}
    @Test void autoPromptRequiresFreshDisconnectedObservationAndDefersOtherUiAndLateResponses() {
        long[] now={1000};var current=entry();var view=mock(BrowserConnections.View.class);var futures=new ArrayList<CompletableFuture<String>>();
        var main=new ArrayDeque<Runnable>();var connections=new BrowserConnections(id->current,(id,e)->{var f=new CompletableFuture<String>();futures.add(f);return f;},main::add,view,()->now[0]);
        connections.tick();assertTrue(futures.isEmpty());connections.observe(player,current,false);connections.unknown(current.session());
        when(view.available(player)).thenReturn(true);now[0]=3000;connections.tick();assertTrue(futures.isEmpty());connections.observe(player,current,false);when(view.available(player)).thenReturn(false);
        now[0]=3000;connections.tick();assertTrue(futures.isEmpty()); // A form/shop is still open.
        when(view.available(player)).thenReturn(true);connections.tick();assertEquals(1,futures.size());
        when(view.available(player)).thenReturn(false);futures.getFirst().complete("late-over-form");main.remove().run();verify(view,never()).ready(any(),any(),any());
        when(view.available(player)).thenReturn(true);now[0]=63000;connections.tick();assertEquals(1,futures.size()); // Backend feedback is now stale.
        connections.observe(player,current,false);connections.tick();assertEquals(2,futures.size());futures.getLast().complete("fresh");main.remove().run();verify(view).ready(player,current,"fresh");
        connections.observe(player,current,true);verify(view,atLeastOnce()).close(player,current);now[0]+=1000;connections.observe(player,current,false);connections.tick();assertEquals(2,futures.size());
        connections.observe(player,current,true);now[0]+=3000;connections.tick();assertEquals(2,futures.size());
    }
    @Test void connectedOrUnknownNeverPromptsAndLeavingInvalidatesPendingAutomaticLink() {
        long[] now={1000};var current=new BrowserConnections.Entry[]{entry()};var view=mock(BrowserConnections.View.class);when(view.available(player)).thenReturn(true);
        var future=new CompletableFuture<String>();var requests=new java.util.concurrent.atomic.AtomicInteger();
        var connections=new BrowserConnections(id->current[0],(id,e)->{requests.incrementAndGet();return future;},Runnable::run,view,()->now[0]);
        connections.observe(player,current[0],true);now[0]+=60000;connections.tick();assertEquals(0,requests.get());
        connections.observe(player,current[0],false);now[0]+=2000;connections.tick();assertEquals(1,requests.get());
        current[0]=null;connections.tick();future.complete("after-opt-out");verify(view,never()).ready(any(),any(),any());
        now[0]+=120000;connections.tick();assertEquals(1,requests.get());
    }
    @Test void dismissingAutoPromptRetriesAfterTwoSecondsAndConnectedResponseCancelsInflightLink() {
        long[] now={1000};var entry=entry();var view=mock(BrowserConnections.View.class);when(view.available(player)).thenReturn(true);var futures=new ArrayList<CompletableFuture<String>>();
        var connections=new BrowserConnections(id->entry,(id,e)->{var f=new CompletableFuture<String>();futures.add(f);return f;},Runnable::run,view,()->now[0]);
        connections.observe(player,entry,false);now[0]=3000;connections.tick();futures.getFirst().complete("first");connections.dismiss(player);
        now[0]+=1999;connections.observe(player,entry,false);connections.tick();assertEquals(1,futures.size());
        now[0]++;connections.observe(player,entry,false);connections.tick();assertEquals(2,futures.size());
        connections.observe(player,entry,true);futures.getLast().complete("must-not-open");verify(view,times(1)).ready(any(),any(),any());
    }
    @Test void closedPromptDefersToOtherMenusAndStoppingQuizCancelsTheRetry() {
        long[] now={1000};var current=new BrowserConnections.Entry[]{entry()};var view=mock(BrowserConnections.View.class);when(view.available(player)).thenReturn(true);
        var requests=new ArrayList<CompletableFuture<String>>();
        var connections=new BrowserConnections(id->current[0],(id,e)->{var f=new CompletableFuture<String>();requests.add(f);return f;},Runnable::run,view,()->now[0]);
        connections.observe(player,current[0],false);now[0]=3000;connections.tick();requests.getFirst().complete("first");connections.dismiss(player);
        when(view.available(player)).thenReturn(false);now[0]=5000;connections.observe(player,current[0],false);connections.tick();assertEquals(1,requests.size());
        now[0]=10000;connections.observe(player,current[0],false);connections.tick();assertEquals(1,requests.size());
        when(view.available(player)).thenReturn(true);connections.tick();assertEquals(2,requests.size());
        current[0]=null;connections.tick();requests.getLast().complete("after-stop");now[0]+=60000;connections.tick();
        assertEquals(2,requests.size());verify(view,times(1)).ready(any(),any(),any());
    }
    @Test void aFormOpenedDuringLinkIssuanceDefersTheResultAndRetriesSoonAfterItCloses() {
        long[] now={1000};var entry=entry();var view=mock(BrowserConnections.View.class);when(view.available(player)).thenReturn(true);
        var requests=new ArrayList<CompletableFuture<String>>();
        var connections=new BrowserConnections(id->entry,(id,e)->{var f=new CompletableFuture<String>();requests.add(f);return f;},Runnable::run,view,()->now[0]);
        connections.observe(player,entry,false);now[0]=3000;connections.tick();
        when(view.available(player)).thenReturn(false);requests.getFirst().complete("over-form");verify(view,never()).ready(any(),any(),any());
        now[0]=5000;connections.observe(player,entry,false);connections.tick();assertEquals(1,requests.size());
        when(view.available(player)).thenReturn(true);connections.tick();assertEquals(2,requests.size());requests.getLast().complete("after-form");verify(view).ready(player,entry,"after-form");
    }
    @Test void nativePromptAppearsBeforeAsyncLinkAndRenewalIssuesAnotherLink() {
        var entry=entry();var view=mock(BrowserConnections.View.class);var futures=new ArrayList<CompletableFuture<String>>();var main=new ArrayDeque<Runnable>();
        var connections=new BrowserConnections(id->entry,(id,e)->{var f=new CompletableFuture<String>();futures.add(f);return f;},main::add,view);
        connections.open(player);connections.open(player);assertEquals(1,futures.size());verify(view).pending(player,entry);verify(view,never()).ready(any(),any(),any());
        futures.getFirst().complete("https://noqui.danta.qzz.io/join#t=first");verify(view,never()).ready(any(),any(),any());main.remove().run();
        verify(view).ready(player,entry,"https://noqui.danta.qzz.io/join#t=first");
        connections.open(player);assertEquals(2,futures.size());verify(view,times(2)).pending(player,entry);
        futures.getLast().complete("https://noqui.danta.qzz.io/join#t=renewed");main.remove().run();verify(view).ready(player,entry,"https://noqui.danta.qzz.io/join#t=renewed");
    }
    @Test void lateOldEntryCannotDisplayLinkOrClearNewEntryRequest() {
        var current=new BrowserConnections.Entry[]{entry()};var first=current[0];var view=mock(BrowserConnections.View.class);var futures=new ArrayList<CompletableFuture<String>>();
        var connections=new BrowserConnections(id->current[0],(id,e)->{var f=new CompletableFuture<String>();futures.add(f);return f;},Runnable::run,view);
        connections.open(player);connections.forget(player);current[0]=new BrowserConnections.Entry(first.session(),UUID.randomUUID());connections.open(player);
        futures.getFirst().complete("old");connections.open(player);assertEquals(2,futures.size());verify(view,never()).ready(any(),any(),any());
        futures.getLast().complete("new");verify(view).ready(player,current[0],"new");
    }
    @Test void sessionChangeOrExitSuppressesOldResponse() {
        var current=new BrowserConnections.Entry[]{entry()};var view=mock(BrowserConnections.View.class);var future=new CompletableFuture<String>();
        var connections=new BrowserConnections(id->current[0],(id,e)->future,Runnable::run,view);
        connections.open(player);current[0]=new BrowserConnections.Entry(UUID.randomUUID(),current[0].membership());future.complete("old-session");
        verify(view,never()).ready(any(),any(),any());
        current[0]=null;connections.open(player);verify(view,times(1)).pending(any(),any());
    }
    @Test void failureOffersRetryWithoutLosingEntry() {
        var entry=entry();var view=mock(BrowserConnections.View.class);var futures=new ArrayList<CompletableFuture<String>>();
        var connections=new BrowserConnections(id->entry,(id,e)->{var f=new CompletableFuture<String>();futures.add(f);return f;},Runnable::run,view);
        connections.open(player);futures.getFirst().completeExceptionally(new IllegalStateException("private error"));verify(view).failed(player,entry);
        connections.open(player);assertEquals(2,futures.size());futures.getLast().complete("retry");verify(view).ready(player,entry,"retry");
    }
}
