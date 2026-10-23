package dev.noqui.paper;

import dev.noqui.api.*;
import dev.noqui.bridge.BridgeClient;
import dev.noqui.core.*;
import dev.noqui.lobby.LobbyFeatures;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class NoquiPlugin extends JavaPlugin implements QuizService,TabExecutor {
    final PlayerRecovery recovery=new PlayerRecovery();
    private final CabinInventoryVault cabinInventory=new CabinInventoryVault();
    private final Map<UUID,BridgeClient.Member> members=new LinkedHashMap<>();
    private final Set<UUID> optedOut=new HashSet<>();
    private final Map<UUID,String> names=new HashMap<>();
    private final Set<UUID> cabinOwners=new HashSet<>(),handoffs=new HashSet<>();
    private final QuizHud hud=new QuizHud();
    private BrowserConnections connections;
    private BridgeClient bridge;
    private QuizGame game;
    private QuestionTickets tickets;
    private LobbyFeatures lobbyFeatures;
    Cabin cabin;
    NoquiDialogs dialogs;
    private String publicOrigin;
    private YamlConfiguration catalog;
    private volatile QuizGame.Snapshot answerState;
    private int lastCountdown,ticks;
    private boolean shuttingDown;
    private boolean ticketsActive;
    private boolean starting;
    @Override public void onEnable() {
        try {
            saveDefaultConfig();cabin=new Cabin(getConfig());
            publicOrigin=getConfig().getString("public-origin","https://noqui.danta.qzz.io").replaceAll("/+$","");
            URI publicUri=URI.create(publicOrigin);
            if(!"https".equals(publicUri.getScheme())||publicUri.getHost()==null||publicUri.getUserInfo()!=null||publicUri.getPort()!=-1
                    ||!publicUri.getPath().isEmpty()||publicUri.getQuery()!=null||publicUri.getFragment()!=null)throw new IllegalArgumentException("Invalid public HTTPS origin");
            byte[] key=Files.readAllBytes(getDataFolder().toPath().resolve(getConfig().getString("bridge.secret-file","bridge.key")));
            if(key.length!=32)key=HexFormat.of().parseHex(new String(key,java.nio.charset.StandardCharsets.US_ASCII).trim());
            String saved=getConfig().getString("instance-id");UUID instance=saved==null?UUID.randomUUID():UUID.fromString(saved);
            getConfig().set("instance-id",instance.toString());saveConfig();
            Executor main=task->{if(isEnabled())Bukkit.getScheduler().runTask(this,()->{if(isEnabled())task.run();});};
            bridge=new BridgeClient(URI.create(getConfig().getString("bridge.origin","http://127.0.0.1:8788")),key,getConfig().getString("bridge.key-id","primary"),instance,main,
                    ()->List.copyOf(members.values()),new BridgeClient.Readiness(){
                        public void accept(long revision,boolean any,boolean all){game.browsersReady(revision,any,all);}
                        public void controlAccepted(long revision){game.controlAccepted(revision);}
                        public void browsers(UUID session,List<BridgeClient.BrowserPresence> browsers){if(connections!=null)for(var browser:browsers)connections.observe(browser.player(),new BrowserConnections.Entry(session,browser.membership()),browser.connected());}
                        public void unavailable(UUID session){if(connections!=null)connections.unknown(session);}
                    },getLogger()::warning);
            Arrays.fill(key,(byte)0);
            catalog=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("songs.yml").toFile());
            var ledger=new CreditLedger(getDataFolder().toPath().resolve("credits.json"));
            game=new QuizGame(bridge,main,System::currentTimeMillis,new QuizGame.Output(){
                public void changed(QuizGame.Snapshot state){changedState(state);}
                public void error(UUID id,String message){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(text(message));}
                public void correctAnswer(UUID round,UUID player,int answerPoints){
                    var wallet=Bukkit.getServicesManager().load(LobbyService.class);
                    if(wallet==null)throw new IllegalStateException("Lobby wallet unavailable");
                    wallet.rewardAnswerPoints(player,"noqui-answer:"+round+":"+player,answerPoints);
                }
                public void answerAccepted(UUID player){for(UUID id:game.snapshot().participants()){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(text(name(player)+" 정답").color(net.kyori.adventure.text.format.NamedTextColor.GREEN));}}
                public void roundEnded(){for(UUID id:game.snapshot().participants()){Player p=Bukkit.getPlayer(id);if(p!=null)hud.roundEnd(p);}}
                public void ended(Map<UUID,Integer> scores){finishQuiz(scores);}
            },ledger,getConfig().getInt("round-seconds",115),0,getConfig().getBoolean("match-skip",false),
                    getConfig().getBoolean("title-bonus",true),getConfig().getBoolean("auto-hint",false),RandomGenerator.getDefault());
            game.modes(configuredModes(getConfig()));
            dialogs=new NoquiDialogs(this);
            tickets=new QuestionTickets(ledger,()->started()||starting,p->ownsCabin(p.getUniqueId()),this::openTicketForm);
            connections=new BrowserConnections(this::connectionEntry,(id,entry)->bridge.joinToken(members.get(id),entry.session())
                    .thenApply(token->publicOrigin+"/join#t="+token),main,new BrowserConnections.View(){
                public void pending(UUID id,BrowserConnections.Entry entry){}
                public void ready(UUID id,BrowserConnections.Entry entry,String url){Player p=Bukkit.getPlayer(id);if(p!=null)dialogs.connection(p,entry,url);}
                public void failed(UUID id,BrowserConnections.Entry entry){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(text("연결 실패 · /noqui connect로 다시 시도하세요."));}
                public boolean available(UUID id){Player p=Bukkit.getPlayer(id);return p!=null&&dialogs.canPrompt(p);}
                public void close(UUID id,BrowserConnections.Entry entry){Player p=Bukkit.getPlayer(id);if(p!=null)dialogs.closeConnection(p,entry);}
            });
            Bukkit.getPluginManager().registerEvents(new NoquiListener(this),this);
            Bukkit.getPluginManager().registerEvents(tickets,this);
            Bukkit.getServicesManager().register(QuizService.class,this,this,ServicePriority.Normal);
            lobbyFeatures=new LobbyFeatures(this,cabin.spawn.getWorld(),new LobbyFeatures.CreditPort(){
                public long balance(UUID player){return game.credits(player);}
                public void grant(UUID player,long amount,String transactionId){giveTickets(player,amount,transactionId);}
            });
            lobbyFeatures.start();
            Bukkit.getServicesManager().register(LobbyService.class,lobbyFeatures,this,ServicePriority.Normal);
            var command=Objects.requireNonNull(getCommand("noqui"));command.setExecutor(this);command.setTabCompleter(this);
            Objects.requireNonNull(getCommand("money")).setExecutor(this);
            for(Player p:Bukkit.getOnlinePlayers()){recoverPlayer(p);lobbyFeatures.join(p);tickets.refresh(p);}
            Bukkit.getScheduler().runTaskTimer(this,this::tick,1,1);
            Bukkit.getScheduler().runTaskTimer(this,bridge::heartbeat,1,40);
            if(getConfig().getBoolean("quiz-active",false))main(()->start(getConfig().getInt("quiz-limit",0)));
            getLogger().info("Noqui enabled; /noqui starts the quiz.");
        }catch(Exception error){getLogger().severe("Noqui startup failed ("+error.getClass().getSimpleName()+"). Check world, spawn, bridge and credit ledger.");Bukkit.getPluginManager().disablePlugin(this);}
    }
    DefenseService defense(){return Bukkit.getServicesManager().load(DefenseService.class);}
    boolean defenseActive(Player p){var service=defense();return service!=null&&service.active(p);}
    QuizGame game(){return game;}
    void main(Runnable task){if(isEnabled())Bukkit.getScheduler().runTask(this,()->{if(isEnabled())task.run();});}
    static Component text(String value){return Component.text(value);}
    private void tick() {
        game.tick();var state=game.snapshot();
        if(connections!=null)connections.tick();
        if(state.phase()!=QuizGame.Phase.COUNTDOWN)lastCountdown=0;
        else {
            int remaining=(int)Math.ceil((state.startsAt()-System.currentTimeMillis())/1000d);
            if(remaining>=1&&remaining<=3&&remaining!=lastCountdown){lastCountdown=remaining;
                for(UUID id:state.participants()){Player p=Bukkit.getPlayer(id);if(p!=null)hud.countdown(p,remaining,state.round(),state.totalRounds());}}
        }
        if(++ticks%5==0){
            if(lobbyFeatures!=null)lobbyFeatures.tick();
            for(Player p:Bukkit.getOnlinePlayers())tickets.refresh(p);
            for(UUID id:state.participants()){Player p=Bukkit.getPlayer(id);if(p!=null)render(p,state);}
        }
        if(ticks%20==0)tickets.refreshGround();
    }
    private void render(Player p,QuizGame.Snapshot state) {
        boolean reveal=state.phase()==QuizGame.Phase.REVEAL;
        hud.setImmersive(p,ownsCabin(p.getUniqueId()));
        hud.render(p,new QuizHud.Frame(state.round(),state.totalRounds(),state.phase().name(),state.remainingMillis(),state.clipEndMillis(),
                name(state.presenter()),state.hint(),reveal&&state.song()!=null?state.song().title():"",reveal&&state.song()!=null?state.song().tags():List.of(),
                reveal?state.winners().stream().map(this::name).toList():List.of(),state.skipVotes(),state.skipRequired()),state.scores().getOrDefault(p.getUniqueId(),0),game.credits(p.getUniqueId()));
    }
    private void changedState(QuizGame.Snapshot state) {
        answerState=state;
        if(tickets!=null&&ticketsActive!=(state.phase()!=QuizGame.Phase.STOPPED)){
            ticketsActive=state.phase()!=QuizGame.Phase.STOPPED;
            for(Player p:Bukkit.getOnlinePlayers())if(ticketsActive||!ownsCabin(p.getUniqueId()))tickets.refresh(p);tickets.refreshGround();
        }
        if(state.song()!=null&&state.roundId()!=null){String path="songs."+state.roundId();
            if(!catalog.contains(path)||state.media()!=null&&!catalog.contains(path+".media-id")){
                catalog.set(path+".youtube-url",state.song().youtubeUrl());catalog.set(path+".title",state.song().title());catalog.set(path+".tags",state.song().tags());
                if(state.media()!=null)catalog.set(path+".media-id",state.media().id());
                try{catalog.save(getDataFolder().toPath().resolve("songs.yml").toFile());}catch(java.io.IOException error){getLogger().warning("Could not save private song catalogue.");}
            }
        }
        for(UUID id:state.participants()){
            Player p=Bukkit.getPlayer(id);if(p==null)continue;render(p,state);
            if(state.phase()==QuizGame.Phase.REVEAL&&state.song()!=null)
                hud.reveal(p,state.song().title(),state.song().tags(),state.winners().stream().map(this::name).toList());
        }
    }
    private String name(UUID id){if(id==null)return "대기 중";Player p=Bukkit.getPlayer(id);return p==null?names.getOrDefault(id,"참가자"):p.getName();}
    @Override public boolean started(){return game!=null&&game.started();}
    @Override public boolean participant(UUID id){return game!=null&&game.participant(id);}
    @Override public boolean ownsCabin(UUID id){return cabinOwners.contains(id);}
    void start(int limit) {
        if(started())throw new IllegalArgumentException("이미 진행 중입니다.");
        if(limit<0)throw new IllegalArgumentException("라운드 제한은 0(무제한) 또는 양의 정수입니다.");
        var players=Bukkit.getOnlinePlayers().stream().map(p->(Player)p).toList();
        if(players.size()>64)throw new IllegalArgumentException("노래퀴즈는 최대 64명입니다.");
        optedOut.clear();
        starting=true;
        try{
            for(Player p:players)enter(p);
            game.start(players.stream().map(Player::getUniqueId).toList(),limit);
            for(Player p:players)hud.start(p,game.snapshot().totalRounds());
            getConfig().set("quiz-active",true);getConfig().set("quiz-limit",limit);saveConfig();
        }catch(RuntimeException error){if(started())game.stop();else for(UUID id:List.copyOf(members.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)restore(p,true);}throw error;}
        finally{starting=false;}
    }
    void enter(Player p){enter(p,true);}
    private void enter(Player p,boolean takeCabin) {
        UUID id=p.getUniqueId();if(members.containsKey(id))return;
        if(members.size()>=64)throw new IllegalArgumentException("노래퀴즈는 최대 64명입니다.");
        recoverPlayer(p);
        UUID generation=recovery.beginMembership(p);
        members.put(id,new BridgeClient.Member(id,generation,p.getName(),texture(p)));names.put(id,p.getName());
        try{hud.enter(p);hud.setImmersive(p,false);var service=defense();if(service!=null)service.silenceBgm(p);if(takeCabin&&!defenseActive(p))enterCabin(p);}
        catch(RuntimeException error){restore(p,true);throw error;}
    }
    private void enterCabin(Player p) {
        UUID id=p.getUniqueId();if(ownsCabin(id)||defenseActive(p))return;
        prepareItems(p);
        var service=defense();if(service!=null)service.releaseLobbyTools(p);
        if(tickets!=null)tickets.strip(p);p.closeInventory();
        recovery.captureCabin(p);cabinOwners.add(id);
        try{
            p.setItemOnCursor(null);p.getInventory().clear();p.setInvisible(false);p.setGameMode(GameMode.ADVENTURE);
            p.setFlying(false);p.setAllowFlight(false);p.setFallDistance(0);p.setFireTicks(0);p.setFoodLevel(20);
            if(!cabin.spawn.equals(p.getLocation())&&!p.teleport(cabin.spawn))throw new IllegalStateException("Noqui teleport rejected");
            if(!cabinInventory.restore(p)){p.getInventory().setItem(0,minigameCompass());p.getInventory().setHeldItemSlot(0);cabinInventory.markLoaded(p);}
            hud.setImmersive(p,true);
            itemsChanged(p);
        }catch(RuntimeException error){if(cabinInventory.loaded(p))cabinInventory.leave(p);cabinOwners.remove(id);try{recovery.releaseCabin(p,true);}finally{itemsChanged(p);}throw error;}
    }
    private String texture(Player p) {
        try{var url=p.getPlayerProfile().getTextures().getSkin();if(url==null)return null;
            String hash=url.getPath().substring(url.getPath().lastIndexOf('/')+1);return url.getHost().equals("textures.minecraft.net")&&hash.matches("[a-f0-9]{64}")?hash:null;
        }catch(RuntimeException ignored){return null;}
    }
    private ItemStack tool(Material type,String id,String title,String lore) {
        var item=new ItemStack(type);var meta=item.getItemMeta();meta.displayName(text(title).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));meta.lore(List.of(text(lore).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false)));
        meta.getPersistentDataContainer().set(new NamespacedKey(this,"tool"),PersistentDataType.STRING,id);item.setItemMeta(meta);return item;
    }
    ItemStack minigameCompass(){
        var compass=tool(Material.COMPASS,"defense","미니게임","우클릭: 미니게임 메뉴");var meta=compass.getItemMeta();
        meta.displayName(text("미니게임").color(net.kyori.adventure.text.format.NamedTextColor.AQUA).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));compass.setItemMeta(meta);return compass;
    }
    String tool(Player p){var item=p.getInventory().getItemInMainHand();return item.hasItemMeta()?item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(this,"tool"),PersistentDataType.STRING):null;}
    @Override public boolean routeJoin(Player p) {
        if(!started()||optedOut.contains(p.getUniqueId()))return false;
        boolean joined=participant(p.getUniqueId());
        try{
            if(!joined){enter(p);game.join(p.getUniqueId());hud.start(p,game.snapshot().totalRounds());}
            else if(!defenseActive(p))enterCabin(p);
            else hud.setImmersive(p,false);
            return true;
        }catch(RuntimeException error){
            if(!joined)try{game.leave(p.getUniqueId());restore(p,true);}catch(RuntimeException cleanup){getLogger().warning("Noqui entry rollback retained recovery markers.");}
            p.sendMessage(text("노래퀴즈에 입장하지 못했습니다. 관리자에게 문의하세요."));return false;
        }
    }
    @Override public void openControls(Player p){if(participant(p.getUniqueId()))dialogs.controls(p);}
    @Override public long ticketCount(UUID player){return game==null?0:game.credits(player);}
    @Override public void openProxy(Player p){if(started()&&p.isOnline())dialogs.proxy(p);}
    void visitCabin(Player p){var service=defense();if(service!=null&&service.active(p)){service.leaveGame(p);if(service.active(p))return;}optedOut.remove(p.getUniqueId());if(!routeJoin(p))throw new IllegalArgumentException("노래퀴즈에 입장할 수 없습니다.");}
    @Override public void prepareDefense(Player p) {
        prepareItems(p);
        UUID id=p.getUniqueId();if(!ownsCabin(id)){if(tickets!=null)tickets.strip(p);return;}
        cabinInventory.leave(p);
        handoffs.add(id);cabinOwners.remove(id);hud.setImmersive(p,false);
        try{recovery.releaseCabin(p,false);}catch(RuntimeException error){cabinOwners.add(id);handoffs.remove(id);hud.setImmersive(p,true);cabinInventory.restore(p);itemsChanged(p);throw error;}
        if(tickets!=null)tickets.strip(p);
    }
    @Override public void defenseEntered(Player p){handoffs.remove(p.getUniqueId());if(participant(p.getUniqueId()))hud.setImmersive(p,false);itemsChanged(p);}
    @Override public void defenseFailed(Player p){if(handoffs.remove(p.getUniqueId())&&participant(p.getUniqueId())&&!defenseActive(p))enterCabin(p);itemsChanged(p);}
    @Override public void leave(Player p){optedOut.add(p.getUniqueId());if(game!=null)game.leave(p.getUniqueId());boolean cabin=ownsCabin(p.getUniqueId());restore(p,true);if(cabin){var service=defense();if(service!=null)service.sendLobby(p);}}
    private void restore(Player p,boolean teleport) {
        UUID id=p.getUniqueId();members.remove(id);handoffs.remove(id);if(connections!=null)connections.forget(id);
        boolean owned=cabinOwners.remove(id);
        try{hud.leave(p);}finally{
            try{if(owned&&!defenseActive(p)){prepareItems(p);cabinInventory.leave(p);p.closeDialog();recovery.releaseCabin(p,teleport);}}
            finally{if(dialogs!=null)dialogs.closeOwned(p);recovery.restoreMusic(p);itemsChanged(p);}
        }
    }
    void quit(Player p){if(game!=null)game.leave(p.getUniqueId());restore(p,false);}
    void leftWorld(Player p){if(ownsCabin(p.getUniqueId())){prepareItems(p);cabinInventory.leave(p);cabinOwners.remove(p.getUniqueId());hud.setImmersive(p,false);try{recovery.releaseCabin(p,false);}finally{itemsChanged(p);}}}
    private void prepareItems(Player p){if(lobbyFeatures!=null)lobbyFeatures.prepareInventoryChange(p);}
    private void itemsChanged(Player p){try{if(tickets!=null)tickets.refresh(p);}finally{if(lobbyFeatures!=null&&cabinInventory.loaded(p)&&ownsCabin(p.getUniqueId()))lobbyFeatures.inventoryChanged(p);}}
    void recoverPlayer(Player p){prepareItems(p);if(!defenseActive(p)&&recovery.pending(p)&&cabinInventory.loaded(p))cabinInventory.leave(p);try{recovery.recover(p,defenseActive(p),true);}finally{itemsChanged(p);}}
    void dismissBrowser(Player p){if(connections!=null)connections.dismiss(p.getUniqueId());}
    void otherInteraction(Player p){if(dialogs!=null)dialogs.otherInteraction(p);else dismissBrowser(p);}
    boolean captureAnswer(UUID id,String input) {
        var s=answerState;if(s==null||s.phase()!=QuizGame.Phase.PLAYING||input==null||input.isBlank()||!s.participants().contains(id))return false;
        if(s.modes().has(QuizModes.Mode.NONCOMPETITIVE)&&s.winners().contains(id)){
            main(()->{var current=game.snapshot();if(current.session().equals(s.session())&&Objects.equals(current.roundId(),s.roundId())&&current.phase()==QuizGame.Phase.PLAYING)
                for(UUID recipient:current.winners()){Player p=Bukkit.getPlayer(recipient);if(p!=null)p.sendMessage(text("<"+name(id)+"> "+input).color(net.kyori.adventure.text.format.NamedTextColor.GRAY));}});return true;
        }
        if(!s.accepts(id,input))return false;
        main(()->{if(game.snapshot().session().equals(s.session())&&Objects.equals(game.snapshot().roundId(),s.roundId()))game.answer(id,input);});return true;
    }
    void openMinigames(Player p) {
        var service=defense();if(service==null){p.sendMessage(text("디펜스가 준비 중입니다. 잠시 후 다시 시도하세요."));return;}
        if(!ownsCabin(p.getUniqueId())||!p.isOnline())return;
        service.openMenu(p);
    }
    void stopQuiz(){if(!started())throw new IllegalArgumentException("진행 중인 노래퀴즈가 없습니다.");game.stop();}
    private void finishQuiz(Map<UUID,Integer> scores) {
        if(!shuttingDown){getConfig().set("quiz-active",false);saveConfig();}
        for(UUID id:List.copyOf(members.keySet())){
            Player p=Bukkit.getPlayer(id);
            if(p!=null){boolean owned=ownsCabin(id);
                try{try{hud.finish(p,scores);}finally{restore(p,true);}
                    if(owned){var service=defense();if(service!=null)service.sendLobby(p);}
                }catch(RuntimeException error){getLogger().warning("Noqui player cleanup retained recovery markers for reconnect.");}
            }else{members.remove(id);cabinOwners.remove(id);handoffs.remove(id);if(connections!=null)connections.forget(id);}
        }
        names.clear();
        if(dialogs!=null)for(Player p:Bukkit.getOnlinePlayers())dialogs.closeOwned(p);
        if(bridge!=null)bridge.publish(game.snapshot());
    }
    boolean hasTicket(Player p){return game!=null&&game.credits(p.getUniqueId())>0&&(!ownsCabin(p.getUniqueId())||tickets!=null&&tickets.has(p));}
    void giveTickets(UUID player,long amount,String transactionId){game.giveOnce(player,amount,transactionId);tickets.refresh(player);if(tickets.pending(player)>0)throw new QuestionTickets.DeliveryPending();}
    void openTicketForm(Player p){
        UUID session=game.snapshot().session();var membership=connectionEntry(p.getUniqueId());
        main(()->{
            if(!p.isOnline()||!session.equals(game.snapshot().session())||!Objects.equals(membership,connectionEntry(p.getUniqueId()))||!hasTicket(p))return;
            try{
                if(!started())throw new IllegalArgumentException("진행 중인 노래퀴즈가 없습니다.");
                if(!participant(p.getUniqueId())&&!routeJoin(p))throw new IllegalArgumentException("노래퀴즈에 참가할 수 없습니다.");
                p.closeInventory();dialogs.submit(p);
            }catch(IllegalArgumentException error){p.sendMessage(text(error.getMessage()));}
        });
    }
    void openProxySubmission(Player p) {
        if(!started()||game.credits(p.getUniqueId())<1)throw new IllegalArgumentException("출제권이 없습니다.");
        if(!participant(p.getUniqueId())) {
            try{enter(p,false);game.join(p.getUniqueId());optedOut.remove(p.getUniqueId());hud.start(p,game.snapshot().totalRounds());}
            catch(RuntimeException error){game.leave(p.getUniqueId());restore(p,true);throw error;}
        }
        p.closeInventory();dialogs.submit(p);
    }
    void refreshTickets(Player p){if(tickets!=null)tickets.refresh(p);}
    void submit(Player p,UUID session,String url,String title,String tags){
        if(!game.snapshot().session().equals(session)||!participant(p.getUniqueId()))throw new IllegalArgumentException("노래퀴즈 입력 창을 다시 열어 주세요.");
        Song song=Song.input(url,title,tags);
        if(tickets==null)throw new IllegalArgumentException("사용할 출제권이 없습니다.");
        try{if(ownsCabin(p.getUniqueId()))tickets.submit(p,()->game.submit(p.getUniqueId(),game.snapshot().revision(),song));else game.submit(p.getUniqueId(),game.snapshot().revision(),song);}
        catch(java.io.UncheckedIOException error){throw new IllegalArgumentException("출제권을 저장하지 못했습니다. 다시 시도하세요.",error);}
    }
    BrowserConnections.Entry connectionEntry(UUID id) {
        var member=members.get(id);Player p=Bukkit.getPlayer(id);
        return member==null||p==null||!p.isOnline()||!participant(id)?null:new BrowserConnections.Entry(game.snapshot().session(),member.membershipId());
    }
    void browserLink(Player p){if(connections!=null)connections.open(p.getUniqueId());}
    void joined(Player p){if(lobbyFeatures!=null)lobbyFeatures.join(p);routeJoin(p);}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        try{
            if(label.equalsIgnoreCase("money")||command!=null&&command.getName().equalsIgnoreCase("money")){
                if(!(sender instanceof Player p))throw new IllegalArgumentException("게임에서 /money를 사용하세요.");
                var wallet=Bukkit.getServicesManager().load(LobbyService.class);
                if(wallet==null)throw new IllegalArgumentException("지갑을 준비 중입니다.");wallet.showMoney(p);return true;
            }
            String action=args.length==0?"menu":args[0].toLowerCase(Locale.ROOT);
            if(action.equals("skip")&&(args.length<2||!args[1].equalsIgnoreCase("force"))){
                if(!(sender instanceof Player p)||!game.voteSkip(p.getUniqueId(),game.snapshot().roundId()))throw new IllegalArgumentException("현재 라운드에서 한 번 투표할 수 있습니다.");return true;
            }
            if(action.equals("leave")&&sender instanceof Player p){leave(p);return true;}
            if(action.equals("connect")&&sender instanceof Player p){if(!participant(p.getUniqueId()))throw new IllegalArgumentException("참가 중에 /noqui connect를 사용하세요.");otherInteraction(p);browserLink(p);return true;}
            if(action.equals("bgm")&&sender instanceof Player p){
                if(args.length!=1||!participant(p.getUniqueId()))throw new IllegalArgumentException("참가 중에 /noqui bgm을 사용하세요.");
                recovery.toggleBgm(p);p.sendMessage(text(recovery.bgmMuted(p)?"BGM OFF":"BGM ON"));return true;
            }
            if(action.equals("menu")&&sender instanceof Player p&&participant(p.getUniqueId())){openControls(p);return true;}
            if(!sender.isOp())throw new IllegalArgumentException("OP만 노래퀴즈를 관리할 수 있습니다.");
            switch(action){
                case "start"->{if(args.length>2)throw new IllegalArgumentException("/noqui start [라운드 수]");start(args.length==2?Integer.parseInt(args[1]):0);}
                case "limit"->{if(args.length!=2)throw new IllegalArgumentException("/noqui limit <0=무제한 또는 라운드 수>");int limit=Integer.parseInt(args[1]);game.limit(limit);getConfig().set("quiz-limit",limit);saveConfig();}
                case "stop"->stopQuiz();case "skip"->game.forceSkip(game.snapshot().roundId());case "hint"->game.hint();case "pause"->game.pause();case "resume"->game.resume();
                case "give"->{if(args.length<2||args.length>3)throw new IllegalArgumentException("/noqui give <player> [1..1000]");
                    OfflinePlayer target=Bukkit.getPlayerExact(args[1]);
                    if(target==null)target=Arrays.stream(Bukkit.getOfflinePlayers()).filter(p->args[1].equalsIgnoreCase(p.getName())).findFirst().orElse(null);
                    if(target==null)throw new IllegalArgumentException("서버에 접속한 적이 있는 플레이어를 선택하세요.");
                    int amount=args.length==3?Integer.parseInt(args[2]):1;game.grant(target.getUniqueId(),amount);if(tickets!=null)tickets.refresh(target.getUniqueId());sender.sendMessage(text(target.getName()+" · 출제권 "+amount+"장 "+(tickets!=null&&tickets.pending(target.getUniqueId())>0?"지급 대기":"지급")));}
                case "status"->{var s=game.snapshot();sender.sendMessage(text("Noqui · "+s.phase()+" · 참가 "+s.participants().size()+"명 · "+s.round()+" / "+(s.totalRounds()==0?"∞":s.totalRounds())+" 라운드"));}
                case "mod"->modeCommand(sender,args);
                case "ban"->banCommand(sender,args);
                case "admin"->{if(sender instanceof Player p)dialogs.admin(p);}
                default->sender.sendMessage(text("/noqui start [rounds] | limit <rounds> | stop | skip force | hint | pause | resume | give <player> [amount] | mod list/add/remove | ban list/add/remove | status | admin"));
            }
        }catch(NumberFormatException error){sender.sendMessage(text("유효한 정수 수량을 입력하세요."));}
        catch(IllegalArgumentException error){sender.sendMessage(text(error.getMessage()));}
        catch(java.io.UncheckedIOException error){sender.sendMessage(text("출제권 저장에 실패했습니다. 잔액을 변경하지 않았습니다."));getLogger().warning("Credit ledger write failed; check storage before retrying.");}
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        List<String> choices=args.length==1?(sender.isOp()?List.of("start","limit","stop","skip","hint","pause","resume","give","mod","ban","status","admin","bgm","connect","leave"):List.of("skip","bgm","connect","leave")):
                sender.isOp()&&args.length==2&&Set.of("mod","ban").contains(args[0])?List.of("list","add","remove"):
                sender.isOp()&&args.length==3&&args[0].equals("mod")?Arrays.stream(QuizModes.Mode.values()).map(QuizModes.Mode::label).toList():
                sender.isOp()&&args.length==2&&args[0].equals("give")?Bukkit.getOnlinePlayers().stream().map(Player::getName).toList():
                sender.isOp()&&args.length==2&&args[0].equals("skip")?List.of("force"):List.of();
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    static QuizModes configuredModes(org.bukkit.configuration.ConfigurationSection settings) {
        var modes=QuizModes.legacy(settings.getBoolean("match-skip",false),settings.getBoolean("title-bonus",true),settings.getBoolean("auto-hint",false));
        if(settings.contains("modes",true)&&settings.isList("modes"))modes=new QuizModes(settings.getStringList("modes").stream().map(QuizModes.Mode::parse).collect(java.util.stream.Collectors.toSet()),Set.of());
        return modes.withBanned(new HashSet<>(settings.getStringList("banned-answers")));
    }
    private void storeModes(QuizModes modes){getConfig().set("modes",Arrays.stream(QuizModes.Mode.values()).filter(modes::has).map(QuizModes.Mode::label).toList());getConfig().set("banned-answers",modes.bannedAnswers().stream().sorted().toList());saveConfig();game.modes(modes);}
    private void modeCommand(CommandSender sender,String[] args) {
        if(args.length==1||args.length==2&&args[1].equals("list")){sender.sendMessage(text("적용된 모드: "+String.join(", ",Arrays.stream(QuizModes.Mode.values()).filter(game.modes()::has).map(QuizModes.Mode::label).toList())));sender.sendMessage(text("모드: "+String.join(", ",Arrays.stream(QuizModes.Mode.values()).map(QuizModes.Mode::label).toList())));return;}
        if(args.length!=3||!Set.of("add","remove").contains(args[1]))throw new IllegalArgumentException("/noqui mod <list|add 이름|remove 이름>");
        var mode=QuizModes.Mode.parse(args[2]);boolean on=args[1].equals("add");storeModes(game.modes().with(mode,on));sender.sendMessage(text(mode.label()+" · "+(on?"ON":"OFF")+" · 다음 라운드부터 적용"));
    }
    private void banCommand(CommandSender sender,String[] args) {
        if(args.length==1||args.length==2&&args[1].equals("list")){sender.sendMessage(text("날먹금지 키워드: "+String.join(", ",game.modes().bannedAnswers().stream().sorted().toList())));return;}
        if(args.length<3||!Set.of("add","remove").contains(args[1]))throw new IllegalArgumentException("/noqui ban <list|add 키워드|remove 키워드>");
        String keyword=Song.normalize(String.join(" ",Arrays.copyOfRange(args,2,args.length)));if(keyword.isEmpty())throw new IllegalArgumentException("유효한 키워드를 입력하세요.");
        var banned=new HashSet<>(game.modes().bannedAnswers());if(args[1].equals("add"))banned.add(keyword);else banned.remove(keyword);storeModes(game.modes().withBanned(banned));sender.sendMessage(text("날먹금지 키워드를 저장했습니다. 다음 라운드부터 적용합니다."));
    }
    @Override public void onDisable() {
        shuttingDown=true;Bukkit.getServicesManager().unregisterAll(this);if(game!=null)game.stop();
        for(Player p:Bukkit.getOnlinePlayers())try{restore(p,true);recoverPlayer(p);}catch(RuntimeException error){getLogger().warning("Player recovery retained for reconnect.");}
        if(lobbyFeatures!=null)lobbyFeatures.close();hud.close();if(bridge!=null){if(game!=null)bridge.closeAfterSync();else bridge.close();}
    }
}
