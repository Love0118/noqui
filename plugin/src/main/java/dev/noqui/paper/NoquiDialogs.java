package dev.noqui.paper;

import dev.noqui.core.*;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

final class NoquiDialogs {
    private final NoquiPlugin plugin;
    NoquiDialogs(NoquiPlugin plugin){this.plugin=plugin;}
    private static Component text(String s){return Component.text(s);}
    private ActionButton button(Player owner,String label,BiConsumer<Player,DialogResponseView> action) {
        UUID id=owner.getUniqueId();
        return ActionButton.create(text(label),null,210,DialogAction.customClick((response,audience)->{
            if(!(audience instanceof Player player) || !player.getUniqueId().equals(id))return;
            plugin.main(()->{if(player.isOnline())try{action.accept(player,response);}catch(IllegalArgumentException error){player.sendMessage(text(error.getMessage()));}});
        },ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build()));
    }
    private void show(Player p,String title,List<ActionButton> actions) {
        p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text(title)).canCloseWithEscape(true).build())
                .type(DialogType.multiAction(actions,ActionButton.create(text("닫기"),null,120,null),1))));
    }
    void controls(Player p) {
        if(!plugin.participant(p.getUniqueId()))return;
        var buttons=new ArrayList<ActionButton>();
        if(Objects.equals(plugin.game().snapshot().presenter(),p.getUniqueId()))buttons.add(button(p,"노래 제출",(who,response)->submit(who)));
        buttons.add(button(p,"브라우저 연결",(who,response)->{if(plugin.participant(who.getUniqueId()))plugin.browserLink(who);}));
        buttons.add(button(p,"점수 · "+plugin.game().snapshot().scores().getOrDefault(p.getUniqueId(),0),(who,response)->{if(plugin.participant(who.getUniqueId()))scores(who);}));
        buttons.add(button(p,"BGM ON / OFF",(who,response)->{if(plugin.participant(who.getUniqueId()))plugin.recovery.toggleBgm(who);}));
        buttons.add(button(p,"MC Luck Defense",(who,response)->{if(plugin.participant(who.getUniqueId()))plugin.toDefense(who);}));
        show(p,"노래퀴즈",buttons);
    }
    void connection(Player p,BrowserConnections.Entry entry,String url) {
        var actions=new ArrayList<ActionButton>();
        if(url!=null)actions.add(ActionButton.create(text("연결"),null,210,DialogAction.staticAction(ClickEvent.openUrl(url))));
        actions.add(button(p,url==null?"연결":"새 링크",(who,response)->{
            if(entry.equals(plugin.connectionEntry(who.getUniqueId())))plugin.browserLink(who);
        }));
        show(p,"브라우저 연결",actions);
    }
    private void scores(Player p) {
        String lines=plugin.game().snapshot().scores().entrySet().stream().sorted(Map.Entry.<UUID,Integer>comparingByValue().reversed()).limit(10)
                .map(e->{var player=org.bukkit.Bukkit.getOfflinePlayer(e.getKey());return Objects.toString(player.getName(),"참가자")+" · "+e.getValue()+"점";})
                .collect(java.util.stream.Collectors.joining("\n"));
        p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text("점수")).body(List.of(DialogBody.plainMessage(text(lines)))).build()).type(DialogType.notice())));
    }
    void submit(Player p) {
        var state=plugin.game().snapshot();
        if(state.phase()!=QuizGame.Phase.WAITING_FOR_SUBMISSION || !Objects.equals(state.presenter(),p.getUniqueId()) || !plugin.participant(p.getUniqueId()))
            throw new IllegalArgumentException("현재 출제자만 곡을 제출할 수 있습니다.");
        var draft=plugin.draft(p.getUniqueId());long revision=state.revision();
        var inputs=List.of(DialogInput.text("youtubeLink",text("YouTube 링크")).initial(draft==null?"":draft.youtubeUrl()).maxLength(2048).build(),
                DialogInput.text("title",text("제목")).initial(draft==null?"":draft.title()).maxLength(160).build(),
                DialogInput.text("tags",text("태그 · ; 구분 · 최대 10개")).initial(draft==null?"":String.join(";",draft.tags())).maxLength(330).build());
        p.showDialog(Dialog.create(b->b.empty().base(DialogBase.builder(text("노래 제출")).canCloseWithEscape(true).inputs(inputs).build())
                .type(DialogType.confirmation(button(p,"제출",(who,response)->plugin.submit(who,revision,response.getText("youtubeLink"),response.getText("title"),response.getText("tags"))),
                        ActionButton.create(text("취소"),null,120,null)))));
    }
    void admin(Player p) {
        if(!p.isOp())return;
        show(p,"Noqui",List.of(button(p,"시작",(who,r)->{if(who.isOp())plugin.start(who);}),
                button(p,"출제자",(who,r)->{if(who.isOp())presenters(who);}),
                button(p,"넘기기",(who,r)->{if(who.isOp())plugin.game().skip();}),
                button(p,"힌트",(who,r)->{if(who.isOp())plugin.game().hint();}),
                button(p,"종료",(who,r)->{if(who.isOp())plugin.stopQuiz();})));
    }
    void presenters(Player p) {
        if(!p.isOp())return;long revision=plugin.game().snapshot().revision();var options=new ArrayList<ActionButton>();
        for(Player target:plugin.eligible())if(plugin.participant(target.getUniqueId())) {
            UUID id=target.getUniqueId();options.add(button(p,target.getName(),(who,r)->{
                if(who.isOp() && plugin.game().snapshot().revision()==revision)plugin.game().give(id);
            }));
        }
        show(p,"출제자",options);
    }
}
