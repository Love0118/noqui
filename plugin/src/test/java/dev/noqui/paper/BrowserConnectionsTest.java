package dev.noqui.paper;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserConnectionsTest {
    private final UUID player=UUID.randomUUID();
    private BrowserConnections.Entry entry(){return new BrowserConnections.Entry(UUID.randomUUID(),UUID.randomUUID());}
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
