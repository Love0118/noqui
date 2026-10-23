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
    private final Map<UUID,BridgeClient.Member> members=new LinkedHashMap<>();
    private final Map<UUID,Song> drafts=new HashMap<>();
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
                public void ended(Map<UUID,Integer> scores){finishQuiz(scores);}
            },ledger,getConfig().getInt("round-seconds",115),0,getConfig().getBoolean("match-skip",false),
                    getConfig().getBoolean("title-bonus",true),getConfig().getBoolean("auto-hint",false),RandomGenerator.getDefault());
            dialogs=new NoquiDialogs(this);
            tickets=new QuestionTickets(game::credits,this::openTicketForm);
            connections=new BrowserConnections(this::connectionEntry,(id,entry)->bridge.joinToken(members.get(id),entry.session())
                    .thenApply(token->publicOrigin+"/join#t="+token),main,new BrowserConnections.View(){
                public void pending(UUID id,BrowserConnections.Entry entry){Player p=Bukkit.getPlayer(id);if(p!=null)dialogs.connection(p,entry,null);}
                public void ready(UUID id,BrowserConnections.Entry entry,String url){Player p=Bukkit.getPlayer(id);if(p!=null)dialogs.connection(p,entry,url);}
                public void failed(UUID id,BrowserConnections.Entry entry){Player p=Bukkit.getPlayer(id);if(p!=null){p.closeDialog();p.sendMessage(text("연결 실패 · 메뉴에서 다시 시도하세요."));}}
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
            for(Player p:Bukkit.getOnlinePlayers()){recovery.recover(p,defenseActive(p),true);lobbyFeatures.join(p);tickets.refresh(p);}
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
    }
    private void render(Player p,QuizGame.Snapshot state) {
        boolean reveal=state.phase()==QuizGame.Phase.REVEAL;
        hud.setImmersive(p,ownsCabin(p.getUniqueId()));
        hud.render(p,new QuizHud.Frame(state.round(),state.totalRounds(),state.phase().name(),state.remainingMillis(),state.clipEndMillis(),
                name(state.presenter()),state.hint(),reveal&&state.song()!=null?state.song().title():"",reveal&&state.song()!=null?state.song().tags():List.of(),
                reveal&&state.winner()!=null?List.of(name(state.winner())):List.of(),state.skipVotes(),state.skipRequired()),state.scores().getOrDefault(p.getUniqueId(),0),game.credits(p.getUniqueId()));
    }
    private void changedState(QuizGame.Snapshot state) {
        answerState=state;
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
                hud.reveal(p,state.song().title(),state.song().tags(),state.winner()==null?List.of():List.of(name(state.winner())),state.skipped());
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
        try{
            for(Player p:players)enter(p);
            game.start(players.stream().map(Player::getUniqueId).toList(),limit);
            for(Player p:players){hud.start(p,game.snapshot().totalRounds());promptEntry(p);}
            getConfig().set("quiz-active",true);getConfig().set("quiz-limit",limit);saveConfig();
        }catch(RuntimeException error){if(started())game.stop();else for(UUID id:List.copyOf(members.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)restore(p,true);}throw error;}
    }
    void enter(Player p) {
        UUID id=p.getUniqueId();if(members.containsKey(id))return;
        if(members.size()>=64)throw new IllegalArgumentException("노래퀴즈는 최대 64명입니다.");
        recovery.recover(p,defenseActive(p),true);
        UUID generation=recovery.beginMembership(p);
        members.put(id,new BridgeClient.Member(id,generation,p.getName(),texture(p)));names.put(id,p.getName());
        try{hud.enter(p);hud.setImmersive(p,false);var service=defense();if(service!=null)service.silenceBgm(p);if(!defenseActive(p))enterCabin(p);}
        catch(RuntimeException error){restore(p,true);throw error;}
    }
    private void enterCabin(Player p) {
        UUID id=p.getUniqueId();if(ownsCabin(id)||defenseActive(p))return;
        var service=defense();if(service!=null)service.releaseLobbyTools(p);
        if(tickets!=null)tickets.strip(p);
        recovery.captureCabin(p);cabinOwners.add(id);
        try{
            p.closeInventory();p.getInventory().clear();p.setInvisible(false);p.setGameMode(GameMode.ADVENTURE);
            p.setFlying(false);p.setAllowFlight(false);p.setFallDistance(0);p.setFireTicks(0);p.setFoodLevel(20);
            if(!cabin.spawn.equals(p.getLocation())&&!p.teleport(cabin.spawn))throw new IllegalStateException("Noqui teleport rejected");
            p.getInventory().setItem(0,minigameCompass());
            p.getInventory().setItem(1,tool(Material.PAPER,"controls","노래퀴즈","우클릭 또는 F: 노래퀴즈"));p.getInventory().setHeldItemSlot(1);
            hud.setImmersive(p,true);
            if(tickets!=null)tickets.refresh(p);
        }catch(RuntimeException error){cabinOwners.remove(id);recovery.releaseCabin(p,true);throw error;}
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
    boolean protectedTool(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(this,"tool"),PersistentDataType.STRING);}
    @Override public boolean routeJoin(Player p){return routeJoin(p,true);}
    private boolean routeJoin(Player p,boolean promptBrowser) {
        if(!started())return false;
        boolean joined=participant(p.getUniqueId());
        try{
            if(!joined){enter(p);game.join(p.getUniqueId());hud.start(p,game.snapshot().totalRounds());if(promptBrowser)promptEntry(p);}
            else if(!defenseActive(p))enterCabin(p);
            else hud.setImmersive(p,false);
            return true;
        }catch(RuntimeException error){
            if(!joined)try{game.leave(p.getUniqueId());restore(p,true);}catch(RuntimeException cleanup){getLogger().warning("Noqui entry rollback retained recovery markers.");}
            p.sendMessage(text("노래퀴즈에 입장하지 못했습니다. 관리자에게 문의하세요."));return false;
        }
    }
    @Override public void openControls(Player p){if(participant(p.getUniqueId()))dialogs.controls(p);}
    @Override public void prepareDefense(Player p) {
        if(tickets!=null)tickets.strip(p);
        UUID id=p.getUniqueId();if(!ownsCabin(id))return;
        handoffs.add(id);cabinOwners.remove(id);hud.setImmersive(p,false);
        try{recovery.releaseCabin(p,false);if(tickets!=null)tickets.strip(p);}catch(RuntimeException error){cabinOwners.add(id);handoffs.remove(id);hud.setImmersive(p,true);if(tickets!=null)tickets.refresh(p);throw error;}
    }
    @Override public void defenseEntered(Player p){handoffs.remove(p.getUniqueId());if(participant(p.getUniqueId()))hud.setImmersive(p,false);if(tickets!=null)tickets.refresh(p);}
    @Override public void defenseFailed(Player p){if(handoffs.remove(p.getUniqueId())&&participant(p.getUniqueId())&&!defenseActive(p))enterCabin(p);if(tickets!=null)tickets.refresh(p);}
    @Override public void leave(Player p){if(game!=null)game.leave(p.getUniqueId());boolean cabin=ownsCabin(p.getUniqueId());restore(p,true);if(cabin){var service=defense();if(service!=null)service.sendLobby(p);}}
    private void restore(Player p,boolean teleport) {
        UUID id=p.getUniqueId();members.remove(id);handoffs.remove(id);drafts.remove(id);if(connections!=null)connections.forget(id);
        boolean owned=cabinOwners.remove(id);
        try{hud.leave(p);}finally{
            try{if(owned&&!defenseActive(p)){if(tickets!=null)tickets.strip(p);p.closeDialog();recovery.releaseCabin(p,teleport);}}
            finally{recovery.restoreMusic(p);if(tickets!=null)tickets.refresh(p);}
        }
    }
    void quit(Player p){if(game!=null)game.leave(p.getUniqueId());restore(p,false);}
    void leftWorld(Player p){if(ownsCabin(p.getUniqueId())){cabinOwners.remove(p.getUniqueId());hud.setImmersive(p,false);recovery.releaseCabin(p,false);}}
    boolean captureAnswer(UUID id,String input) {
        var s=answerState;if(s==null||s.phase()!=QuizGame.Phase.PLAYING||s.winner()!=null||s.song()==null||!s.participants().contains(id)||Objects.equals(s.presenter(),id)||!s.song().matches(input))return false;
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
            }else{members.remove(id);cabinOwners.remove(id);handoffs.remove(id);drafts.remove(id);if(connections!=null)connections.forget(id);}
        }
        names.clear();
        if(bridge!=null)bridge.publish(game.snapshot());
    }
    Song draft(UUID id){return drafts.get(id);}
    boolean hasTicket(Player p){return tickets!=null&&tickets.has(p);}
    void giveTickets(UUID player,long amount,String transactionId){game.giveOnce(player,amount,transactionId);tickets.refresh(player);if(tickets.pending(player)>0)throw new QuestionTickets.DeliveryPending();}
    void openTicketForm(Player p){
        UUID session=game.snapshot().session();var membership=connectionEntry(p.getUniqueId());
        main(()->{
            if(!p.isOnline()||!session.equals(game.snapshot().session())||!Objects.equals(membership,connectionEntry(p.getUniqueId()))||!hasTicket(p))return;
            try{
                if(!started())throw new IllegalArgumentException("진행 중인 노래퀴즈가 없습니다.");
                if(!participant(p.getUniqueId())&&!routeJoin(p,false))throw new IllegalArgumentException("노래퀴즈에 참가할 수 없습니다.");
                p.closeInventory();dialogs.submit(p);
            }catch(IllegalArgumentException error){p.sendMessage(text(error.getMessage()));}
        });
    }
    void refreshTickets(Player p){if(tickets!=null)tickets.refresh(p);}
    void submit(Player p,UUID session,String url,String title,String tags){
        if(!game.snapshot().session().equals(session)||!participant(p.getUniqueId()))throw new IllegalArgumentException("노래퀴즈 입력 창을 다시 열어 주세요.");
        Song song=Song.input(url,title,tags);
        drafts.put(p.getUniqueId(),song);
        if(tickets==null)throw new IllegalArgumentException("사용할 출제권이 없습니다.");
        try{tickets.submit(p,()->game.submit(p.getUniqueId(),game.snapshot().revision(),song));}
        catch(java.io.UncheckedIOException error){throw new IllegalArgumentException("출제권을 저장하지 못했습니다. 다시 시도하세요.",error);}
    }
    BrowserConnections.Entry connectionEntry(UUID id) {
        var member=members.get(id);Player p=Bukkit.getPlayer(id);
        return member==null||p==null||!p.isOnline()||!participant(id)?null:new BrowserConnections.Entry(game.snapshot().session(),member.membershipId());
    }
    private void promptEntry(Player p){var entry=connectionEntry(p.getUniqueId());main(()->{if(entry!=null&&entry.equals(connectionEntry(p.getUniqueId())))browserLink(p);});}
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
                case "admin"->{if(sender instanceof Player p)dialogs.admin(p);}
                default->sender.sendMessage(text("/noqui start [rounds] | limit <rounds> | stop | skip force | hint | pause | resume | give <player> [amount] | status | admin"));
            }
        }catch(NumberFormatException error){sender.sendMessage(text("유효한 정수 수량을 입력하세요."));}
        catch(IllegalArgumentException error){sender.sendMessage(text(error.getMessage()));}
        catch(java.io.UncheckedIOException error){sender.sendMessage(text("출제권 저장에 실패했습니다. 잔액을 변경하지 않았습니다."));getLogger().warning("Credit ledger write failed; check storage before retrying.");}
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        List<String> choices=args.length==1?(sender.isOp()?List.of("start","limit","stop","skip","hint","pause","resume","give","status","admin","bgm","leave"):List.of("skip","bgm","leave")):
                sender.isOp()&&args.length==2&&args[0].equals("give")?Bukkit.getOnlinePlayers().stream().map(Player::getName).toList():
                sender.isOp()&&args.length==2&&args[0].equals("skip")?List.of("force"):List.of();
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    @Override public void onDisable() {
        shuttingDown=true;Bukkit.getServicesManager().unregisterAll(this);if(game!=null)game.stop();
        for(Player p:Bukkit.getOnlinePlayers())try{restore(p,true);recovery.recover(p,defenseActive(p),true);}catch(RuntimeException error){getLogger().warning("Player recovery retained for reconnect.");}
        if(lobbyFeatures!=null)lobbyFeatures.close();hud.close();if(bridge!=null){if(game!=null)bridge.closeAfterSync();else bridge.close();}
    }
}
