package dev.noqui.paper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Main-thread coordinator for private links and entry-priority native prompts. */
final class BrowserConnections {
    record Entry(UUID session,UUID membership) {}
    interface View {
        void pending(UUID player,Entry entry);
        void ready(UUID player,Entry entry,String url);
        void failed(UUID player,Entry entry);
    }
    private record Request(Entry entry) {}
    private final Function<UUID,Entry> current;
    private final BiFunction<UUID,Entry,CompletionStage<String>> issue;
    private final Executor main;
    private final View view;
    private final Map<UUID,Request> pending=new HashMap<>();
    BrowserConnections(Function<UUID,Entry> current,BiFunction<UUID,Entry,CompletionStage<String>> issue,Executor main,View view) {
        this.current=current;this.issue=issue;this.main=main;this.view=view;
    }
    void open(UUID player) {
        Entry entry=current.apply(player);if(entry==null)return;
        var existing=pending.get(player);if(existing!=null && existing.entry().equals(entry))return;
        var request=new Request(entry);pending.put(player,request);view.pending(player,entry);
        try {issue.apply(player,entry).whenComplete((url,error)->main.execute(()->complete(player,request,url,error)));}
        catch(RuntimeException error){complete(player,request,null,error);}
    }
    private void complete(UUID player,Request request,String url,Throwable error) {
        if(pending.get(player)!=request)return;
        pending.remove(player);
        if(!request.entry().equals(current.apply(player)))return;
        if(error==null && url!=null)view.ready(player,request.entry(),url);else view.failed(player,request.entry());
    }
    void forget(UUID player){pending.remove(player);}
}
