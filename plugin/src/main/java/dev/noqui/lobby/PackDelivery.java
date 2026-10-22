package dev.noqui.lobby;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.bukkit.entity.Player;

final class PackDelivery {
    static final UUID ID=UUID.fromString("7d5243d5-c30e-4bdd-a039-71ec1ad10001");
    private final String url;
    private final byte[] sha1;
    private final Set<UUID> sent=new HashSet<>();
    PackDelivery(Path file)throws IOException {
        Properties p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
        try {
            URI uri=URI.create(p.getProperty("url",""));
            if(!"https".equals(uri.getScheme())||!"noqui.danta.qzz.io".equals(uri.getHost())||uri.getPort()!=-1||uri.getUserInfo()!=null
                    ||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().matches("/packs/[a-f0-9]{64}\\.zip"))throw new IllegalArgumentException();
            url=uri.toString();String hash=p.getProperty("sha1","");if(!hash.matches("[a-f0-9]{40}"))throw new IllegalArgumentException();sha1=HexFormat.of().parseHex(hash);
        }catch(IllegalArgumentException e){throw new IOException("Invalid lobby pack descriptor",e);}
    }
    void join(Player p){if(sent.add(p.getUniqueId()))try{p.addResourcePack(ID,url,sha1.clone(),"Noqui 로비",true);}catch(RuntimeException error){sent.remove(p.getUniqueId());throw error;}}
    void quit(UUID id){sent.remove(id);}
}
