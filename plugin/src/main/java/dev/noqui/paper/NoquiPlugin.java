package dev.noqui.paper;

import dev.noqui.api.*;
import dev.noqui.bridge.BridgeClient;
import dev.noqui.core.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class NoquiPlugin extends JavaPlugin implements QuizService, TabExecutor {
    final PlayerRecovery recovery=new PlayerRecovery();
    private final Map<UUID,BridgeClient.Member> members=new LinkedHashMap<>();
    private final Map<UUID,Song> drafts=new HashMap<>();
    private BrowserConnections connections;
    private BridgeClient bridge;
    private QuizGame game;
    Cabin cabin;
    NoquiDialogs dialogs;
    private String publicOrigin;
    private YamlConfiguration catalog;
    private UUID lastPresenter;
    private volatile QuizGame.Snapshot answerState;
    private QuizGame.Phase lastPhase;
    private int lastCountdown;
    @Override public void onEnable() {
        try {
            saveDefaultConfig();cabin=new Cabin(getConfig());
            publicOrigin=getConfig().getString("public-origin","https://noqui.danta.qzz.io").replaceAll("/+$","");
            URI publicUri=URI.create(publicOrigin);
            if(!"https".equals(publicUri.getScheme()) || publicUri.getHost()==null || publicUri.getUserInfo()!=null || publicUri.getPort()!=-1
                    || !publicUri.getPath().isEmpty() || publicUri.getQuery()!=null || publicUri.getFragment()!=null)throw new IllegalArgumentException("Invalid public HTTPS origin");
            var keyPath=getDataFolder().toPath().resolve(getConfig().getString("bridge.secret-file","bridge.key"));
            byte[] key=Files.readAllBytes(keyPath);
            if(key.length!=32)key=HexFormat.of().parseHex(new String(key,java.nio.charset.StandardCharsets.US_ASCII).trim());
            String saved=getConfig().getString("instance-id");UUID instance=saved==null?UUID.randomUUID():UUID.fromString(saved);
            getConfig().set("instance-id",instance.toString());saveConfig();
            Executor main=task->{if(isEnabled())Bukkit.getScheduler().runTask(this,()->{if(isEnabled())task.run();});};
            bridge=new BridgeClient(URI.create(getConfig().getString("bridge.origin","http://127.0.0.1:8788")),key,getConfig().getString("bridge.key-id","primary"),instance,main,
                    ()->List.copyOf(members.values()),new BridgeClient.Readiness(){
                        public void accept(long revision,boolean any,boolean all){game.browsersReady(revision,any,all);}
                        public void controlAccepted(long revision){game.controlAccepted(revision);}
                    },getLogger()::warning);
            Arrays.fill(key,(byte)0);
            catalog=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("songs.yml").toFile());
            game=new QuizGame(bridge,main,System::currentTimeMillis,new QuizGame.Output() {
                @Override public void changed(QuizGame.Snapshot state){changedState(state);}
                @Override public void error(UUID player,String message){Player p=Bukkit.getPlayer(player);if(p!=null)p.sendMessage(Component.text(message));}
            },getConfig().getInt("round-seconds",115),getConfig().getBoolean("match-skip",false));
            dialogs=new NoquiDialogs(this);
            connections=new BrowserConnections(this::connectionEntry,(id,entry)->bridge.joinToken(members.get(id),entry.session())
                    .thenApply(token->publicOrigin+"/join#t="+token),main,new BrowserConnections.View(){
                public void pending(UUID id,BrowserConnections.Entry entry){Player p=Bukkit.getPlayer(id);if(p!=null)dialogs.connection(p,entry,null);}
                public void ready(UUID id,BrowserConnections.Entry entry,String url){Player p=Bukkit.getPlayer(id);if(p!=null){
                    dialogs.connection(p,entry,url);p.sendMessage(text("브라우저 연결").clickEvent(ClickEvent.openUrl(url)));
                }}
                public void failed(UUID id,BrowserConnections.Entry entry){Player p=Bukkit.getPlayer(id);if(p!=null){
                    dialogs.connection(p,entry,null);p.sendMessage(text("연결 실패 · 다시 시도하세요."));
                }}
            });
            Bukkit.getPluginManager().registerEvents(new NoquiListener(this),this);
            Bukkit.getServicesManager().register(QuizService.class,this,this,ServicePriority.Normal);
            var command=Objects.requireNonNull(getCommand("noqui"));command.setExecutor(this);command.setTabCompleter(this);
            for(Player p:Bukkit.getOnlinePlayers())recovery.restore(p,true);
            Bukkit.getScheduler().runTaskTimer(this,this::tick,1,1);
            Bukkit.getScheduler().runTaskTimer(this,bridge::heartbeat,1,40);
            if(getConfig().getBoolean("quiz-active",false))main(()->start(eligible().stream().findFirst().orElse(null)));
            getLogger().info("Noqui enabled; /noqui starts the quiz.");
        } catch(Exception error) {
            getLogger().severe("Noqui startup failed ("+error.getClass().getSimpleName()+"). Check the imported world, safe spawn and bridge key/configuration.");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    DefenseService defense(){return Bukkit.getServicesManager().load(DefenseService.class);}
    QuizGame game(){return game;}
    void main(Runnable task){if(isEnabled())Bukkit.getScheduler().runTask(this,()->{if(isEnabled())task.run();});}
    static Component text(String value){return Component.text(value);}
    private void tick() {
        game.tick();var state=game.snapshot();
        if(state.phase()!=QuizGame.Phase.COUNTDOWN){lastCountdown=0;return;}
        int remaining=(int)Math.ceil((state.startsAt()-System.currentTimeMillis())/1000d);
        if(remaining>=1 && remaining<=3 && remaining!=lastCountdown) {
            lastCountdown=remaining;
            var title=net.kyori.adventure.title.Title.title(text(Integer.toString(remaining)),Component.empty(),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO,java.time.Duration.ofSeconds(1),java.time.Duration.ZERO));
            for(UUID id:state.participants()){Player p=Bukkit.getPlayer(id);if(p!=null)p.showTitle(title);}
        }
    }
    private void changedState(QuizGame.Snapshot state) {
        answerState=state;
        if(state.song()!=null && state.roundId()!=null) {
            String path="songs."+state.roundId();
            if(!catalog.contains(path) || state.media()!=null && !catalog.contains(path+".media-id")) {
                catalog.set(path+".youtube-url",state.song().youtubeUrl());catalog.set(path+".title",state.song().title());catalog.set(path+".tags",state.song().tags());
                if(state.media()!=null)catalog.set(path+".media-id",state.media().id());
                try{catalog.save(getDataFolder().toPath().resolve("songs.yml").toFile());}catch(java.io.IOException error){getLogger().warning("Could not save private song catalogue.");}
            }
        }
        boolean phaseChange=state.phase()!=lastPhase || !Objects.equals(state.presenter(),lastPresenter);
        if(phaseChange) {
            String message=switch(state.phase()) {
                case WAITING_FOR_PRESENTER->"출제할 참가자를 기다립니다.";
                case WAITING_FOR_SUBMISSION->"출제자: "+name(state.presenter())+" · 곡을 제출해 주세요.";
                case WAITING_FOR_MEDIA->"오디오를 준비하고 있습니다.";
                case PREPARING->"브라우저에서 오디오 켜기를 눌러 주세요. 최대 20초 대기합니다.";
                case COUNTDOWN->"곧 시작합니다! 노래를 듣고 채팅으로 맞혀 주세요.";
                case PLAYING->"노래퀴즈 "+state.round()+"라운드";
                case REVEAL->(state.winner()==null?"정답자 없음":"정답: "+name(state.winner()))+" · "+(state.song()==null?"":state.song().title());
                case STOPPED->"노래퀴즈가 종료되었습니다.";
            };
            for(UUID id:state.participants()){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(text(message));}
            lastPhase=state.phase();lastPresenter=state.presenter();
        }
        if(!state.hint().isEmpty())for(UUID id:state.participants()){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendActionBar(text("힌트: "+state.hint()));}
    }
    private String name(UUID id){Player p=id==null?null:Bukkit.getPlayer(id);return p==null?"대기 중":p.getName();}
    @Override public boolean started(){return game!=null && game.started();}
    @Override public boolean participant(UUID id){return game!=null && game.participant(id);}
    List<Player> eligible() {
        var service=defense();return Bukkit.getOnlinePlayers().stream().filter(p->service==null || !service.active(p)).limit(64).map(p->(Player)p).toList();
    }
    void start(Player first) {
        if(started())throw new IllegalArgumentException("이미 진행 중입니다.");
        List<Player> players=eligible();if(first==null && !players.isEmpty() || first!=null && !players.contains(first))throw new IllegalArgumentException("디펜스에 참가 중이지 않은 접속자를 선택하세요.");
        try {
            for(Player p:players)enter(p);
            game.start(first==null?null:first.getUniqueId(),players.stream().map(Player::getUniqueId).toList());
            for(Player p:players)promptEntry(p);
            getConfig().set("quiz-active",true);saveConfig();
        } catch(RuntimeException error) {
            for(UUID id:List.copyOf(members.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)restore(p,true);}
            throw error;
        }
    }
    void enter(Player player) {
        if(members.containsKey(player.getUniqueId()))return;
        if(members.size()>=64)throw new IllegalArgumentException("노래퀴즈는 최대 64명입니다.");
        var defense=defense();if(defense!=null){if(defense.active(player))throw new IllegalArgumentException("먼저 디펜스에서 나와 주세요.");defense.releaseLobbyTools(player);defense.silenceBgm(player);}
        recovery.restore(player,true);
        try {
            UUID generation=recovery.enter(player);
            player.closeInventory();player.getInventory().clear();player.setInvisible(false);player.setGameMode(GameMode.ADVENTURE);
            player.setFlying(false);player.setAllowFlight(false);player.setFallDistance(0);player.setFireTicks(0);player.setFoodLevel(20);
            if(!player.teleport(cabin.spawn))throw new IllegalStateException("Noqui teleport rejected");
            player.getInventory().setItem(0,tool(Material.COMPASS,"defense","MC Luck Defense","우클릭: 디펜스 참가·관전 메뉴"));
            player.getInventory().setItem(1,tool(Material.PAPER,"controls","노래퀴즈","우클릭 또는 F: 출제·브라우저·BGM"));player.getInventory().setHeldItemSlot(1);
            members.put(player.getUniqueId(),new BridgeClient.Member(player.getUniqueId(),generation,player.getName(),texture(player)));
        } catch(RuntimeException error){recovery.restore(player,true);throw error;}
    }
    private String texture(Player p) {
        try {var url=p.getPlayerProfile().getTextures().getSkin();if(url==null)return null;
            String hash=url.getPath().substring(url.getPath().lastIndexOf('/')+1);return url.getHost().equals("textures.minecraft.net")&&hash.matches("[a-f0-9]{64}")?hash:null;
        }catch(RuntimeException ignored){return null;}
    }
    private ItemStack tool(Material type,String id,String title,String lore) {
        var item=new ItemStack(type);var meta=item.getItemMeta();meta.displayName(text(title));meta.lore(List.of(text(lore)));
        meta.getPersistentDataContainer().set(new NamespacedKey(this,"tool"),PersistentDataType.STRING,id);item.setItemMeta(meta);return item;
    }
    String tool(Player p){var item=p.getInventory().getItemInMainHand();return item.hasItemMeta()?item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(this,"tool"),PersistentDataType.STRING):null;}
    @Override public boolean routeJoin(Player p) {
        if(!started())return false;
        if(participant(p.getUniqueId()))return true;
        var service=defense();if(service!=null && service.active(p))return false;
        try {enter(p);game.join(p.getUniqueId());promptEntry(p);return true;}
        catch(RuntimeException error){p.sendMessage(text("노래퀴즈에 입장하지 못했습니다. 관리자에게 문의하세요."));return false;}
    }
    @Override public void leave(Player p) {members.remove(p.getUniqueId());if(participant(p.getUniqueId()))game.leave(p.getUniqueId());restore(p,true);}
    private void restore(Player p,boolean teleport) {
        members.remove(p.getUniqueId());if(connections!=null)connections.forget(p.getUniqueId());drafts.remove(p.getUniqueId());
        if(recovery.pending(p)){p.closeDialog();recovery.restore(p,teleport);}
    }
    void quit(Player p) {
        members.remove(p.getUniqueId());if(connections!=null)connections.forget(p.getUniqueId());drafts.remove(p.getUniqueId());
        if(game!=null)game.leave(p.getUniqueId());
        // Restore while the Player is still available; the persisted marker also covers abrupt loss.
        recovery.restore(p,false);
    }
    void leftWorld(Player p){if(participant(p.getUniqueId())){members.remove(p.getUniqueId());game.leave(p.getUniqueId());restore(p,false);}}
    boolean captureAnswer(UUID player,String input) {
        var s=answerState;
        if(s==null || s.phase()!=QuizGame.Phase.PLAYING || s.winner()!=null || s.song()==null || !s.participants().contains(player)
                || Objects.equals(s.presenter(),player) || !s.song().matches(input))return false;
        main(()->game.answer(player,input));return true;
    }
    void toDefense(Player p) {
        var service=defense();if(service==null){p.sendMessage(text("디펜스가 준비 중입니다. 잠시 후 다시 시도하세요."));return;}
        leave(p);service.sendLobby(p);
        main(()->{var current=defense();if(current!=null && !participant(p.getUniqueId()) && p.isOnline())current.openMenu(p);});
    }
    void stopQuiz() {
        if(!started())throw new IllegalArgumentException("진행 중인 노래퀴즈가 없습니다.");
        var finalState=game.snapshot();String result=finalState.scores().entrySet().stream().sorted(Map.Entry.<UUID,Integer>comparingByValue().reversed()).limit(10)
                .map(entry->name(entry.getKey())+" "+entry.getValue()+"점").collect(java.util.stream.Collectors.joining(" · "));
        for(UUID id:finalState.participants()){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(text("노래퀴즈 결과 · "+result));}
        getConfig().set("quiz-active",false);saveConfig();
        var ids=List.copyOf(members.keySet());members.clear();game.stop();
        for(UUID id:ids){Player p=Bukkit.getPlayer(id);if(p!=null){restore(p,true);var service=defense();if(service!=null)service.sendLobby(p);p.sendMessage(text("노래퀴즈가 종료되었습니다."));}}
    }
    Song draft(UUID id){return drafts.get(id);}
    void submit(Player p,long revision,String url,String title,String tags) {
        Song song=Song.input(url,title,tags);game.submit(p.getUniqueId(),revision,song);drafts.put(p.getUniqueId(),song);
    }
    BrowserConnections.Entry connectionEntry(UUID id) {
        var member=members.get(id);Player p=Bukkit.getPlayer(id);
        return member==null || p==null || !p.isOnline() || !participant(id)?null:new BrowserConnections.Entry(game.snapshot().session(),member.membershipId());
    }
    private void promptEntry(Player p) {
        var entry=connectionEntry(p.getUniqueId());
        main(()->{if(entry!=null && entry.equals(connectionEntry(p.getUniqueId())))browserLink(p);});
    }
    void browserLink(Player p){if(connections!=null)connections.open(p.getUniqueId());}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.isOp()){sender.sendMessage(text("OP만 노래퀴즈를 관리할 수 있습니다. 참가 메뉴는 F키로 여세요."));return true;}
        try {
            String action=args.length==0?"menu":args[0].toLowerCase(Locale.ROOT);
            switch(action) {
                case "start"->{Player first=args.length>1?Bukkit.getPlayerExact(args[1]):sender instanceof Player p?p:eligible().stream().findFirst().orElse(null);
                    if(args.length>1 && first==null)throw new IllegalArgumentException("접속 중인 플레이어를 선택하세요.");start(first);}
                case "stop"->stopQuiz();case "skip"->game.skip();case "hint"->game.hint();
                case "give"->{if(args.length<2 && sender instanceof Player p)dialogs.presenters(p);else {
                    Player p=args.length>1?Bukkit.getPlayerExact(args[1]):null;
                    if(p==null)throw new IllegalArgumentException("/noqui give <접속 중인 참가자>");game.give(p.getUniqueId());}}
                case "status"->{var s=game.snapshot();sender.sendMessage(text("Noqui · "+s.phase()+" · 출제자 "+name(s.presenter())+" · 참가 "+s.participants().size()+"명 · "+s.round()+"라운드"));}
                default->{if(sender instanceof Player p)dialogs.admin(p);else sender.sendMessage(text("/noqui start [player] | stop | skip | hint | give <player> | status"));}
            }
        }catch(IllegalArgumentException error){sender.sendMessage(text(error.getMessage()));}
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(!sender.isOp())return List.of();
        List<String> choices=args.length==1?List.of("start","stop","skip","hint","give","status"):
                args.length==2 && Set.of("start","give").contains(args[0])?eligible().stream().map(Player::getName).toList():List.of();
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    @Override public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        members.clear();if(game!=null)game.stop();
        for(Player p:Bukkit.getOnlinePlayers())try{restore(p,true);}catch(RuntimeException error){getLogger().warning("Player recovery retained; it will retry on reconnect.");}
        if(bridge!=null){if(game!=null)bridge.closeAfterSync();else bridge.close();}
    }
}
