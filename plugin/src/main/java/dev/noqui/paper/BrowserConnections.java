package dev.noqui.paper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Main-thread private links and prompts driven only by fresh backend connection observations. */
final class BrowserConnections {
    private static final long PROMPT_DELAY_MILLIS=2000;
    record Entry(UUID session,UUID membership) {}
    interface View {
        void pending(UUID player,Entry entry);
        void ready(UUID player,Entry entry,String url);
        void failed(UUID player,Entry entry);
        default boolean available(UUID player){return true;}
        default void close(UUID player,Entry entry){}
    }
    private record Request(Entry entry,boolean automatic) {}
    private static final class Status {
        final Entry entry;
        boolean connected,known;
        long observedAt,nextPromptAt,shownUntil;
        Status(Entry entry,long now){this.entry=entry;nextPromptAt=now+PROMPT_DELAY_MILLIS;}
    }
    private final Function<UUID,Entry> current;
    private final BiFunction<UUID,Entry,CompletionStage<String>> issue;
    private final Executor main;
    private final View view;
    private final LongSupplier clock;
    private final Map<UUID,Request> pending=new HashMap<>();
    private final Map<UUID,Status> statuses=new HashMap<>();
    BrowserConnections(Function<UUID,Entry> current,BiFunction<UUID,Entry,CompletionStage<String>> issue,Executor main,View view){this(current,issue,main,view,System::currentTimeMillis);}
    BrowserConnections(Function<UUID,Entry> current,BiFunction<UUID,Entry,CompletionStage<String>> issue,Executor main,View view,LongSupplier clock) {
        this.current=current;this.issue=issue;this.main=main;this.view=view;this.clock=clock;
    }
    void observe(UUID player,Entry entry,boolean connected) {
        if(!entry.equals(current.apply(player)))return;
        long now=clock.getAsLong();var state=statuses.get(player);
        if(state==null||!state.entry.equals(entry)){forget(player);state=new Status(entry,now);statuses.put(player,state);}
        if(state.connected&&!connected)state.nextPromptAt=now+PROMPT_DELAY_MILLIS;
        state.connected=connected;state.known=true;state.observedAt=now;
        if(connected){pending.remove(player);state.shownUntil=0;view.close(player,entry);}
    }
    void unknown(UUID session){for(var state:statuses.values())if(state.entry.session().equals(session))state.known=false;}
    void tick() {
        long now=clock.getAsLong();
        for(UUID player:List.copyOf(statuses.keySet())) {
            var state=statuses.get(player);
            if(!state.entry.equals(current.apply(player))){forget(player);continue;}
            if(state.shownUntil>0&&now>=state.shownUntil){view.close(player,state.entry);state.shownUntil=0;}
            if(!state.known||state.connected||now-state.observedAt>15000||state.shownUntil>0||now<state.nextPromptAt||pending.containsKey(player)||!view.available(player))continue;
            open(player,true);
        }
    }
    void open(UUID player){open(player,false);}
    private void open(UUID player,boolean automatic) {
        Entry entry=current.apply(player);if(entry==null)return;
        var existing=pending.get(player);if(existing!=null && existing.entry().equals(entry))return;
        var state=statuses.get(player);if(state!=null&&state.entry.equals(entry))state.nextPromptAt=clock.getAsLong()+60000;
        var request=new Request(entry,automatic);pending.put(player,request);view.pending(player,entry);
        try {issue.apply(player,entry).whenComplete((url,error)->main.execute(()->complete(player,request,url,error)));}
        catch(RuntimeException error){complete(player,request,null,error);}
    }
    private void complete(UUID player,Request request,String url,Throwable error) {
        if(pending.get(player)!=request)return;
        pending.remove(player);
        if(!request.entry().equals(current.apply(player)))return;
        var state=statuses.get(player);long now=clock.getAsLong();
        if(state!=null&&state.connected)return;
        if(request.automatic()&&(state==null||!state.known||now-state.observedAt>15000||!view.available(player))){
            if(state!=null)state.nextPromptAt=now+PROMPT_DELAY_MILLIS;
            return;
        }
        if(error==null&&url!=null){
            if(state!=null)state.shownUntil=now+55000;
            view.ready(player,request.entry(),url);
        }else if(!request.automatic())view.failed(player,request.entry());
    }
    /** Closing this prompt or using another UI defers the next fresh disconnected prompt briefly. */
    void dismiss(UUID player) {
        pending.remove(player);var state=statuses.get(player);
        if(state!=null){state.shownUntil=0;state.nextPromptAt=clock.getAsLong()+PROMPT_DELAY_MILLIS;view.close(player,state.entry);}
    }
    void forget(UUID player){var request=pending.remove(player);var state=statuses.remove(player);if(state!=null)view.close(player,state.entry);else if(request!=null)view.close(player,request.entry());}
}
