package dev.noqui.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

public record Song(String youtubeUrl, String title, List<String> tags) {
    public Song {
        youtubeUrl = youtube(youtubeUrl);
        title = checked(title, 160, "제목");
        if (normalize(title).isEmpty()) throw new IllegalArgumentException("제목에 한글, 영문 또는 숫자를 입력하세요.");
        if (tags == null || tags.size() > 10) throw new IllegalArgumentException("태그는 최대 10개입니다.");
        var unique = new LinkedHashMap<String,String>();
        for (String tag : tags) {
            tag = checked(tag, 32, "태그");
            String answer = normalize(tag);
            if (answer.isEmpty()) throw new IllegalArgumentException("태그에 한글, 영문 또는 숫자를 입력하세요.");
            unique.putIfAbsent(answer, tag);
        }
        tags = List.copyOf(unique.values());
    }
    public static Song input(String url, String title, String tags) {
        String value = tags == null ? "" : tags.trim();
        return new Song(url, title, value.isEmpty() ? List.of() : Arrays.asList(value.split("[;,\\n]", -1)));
    }
    private static String checked(String text, int max, String label) {
        if (text != null) text=Normalizer.normalize(text.trim(),Normalizer.Form.NFC);
        if (text == null || text.isBlank() || text.codePointCount(0,text.length()) > max || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + "을(를) 1~" + max + "자로 입력하세요.");
        return text.trim();
    }
    public static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[^0-9A-Za-z가-힣]", "").toLowerCase(Locale.ROOT);
    }
    public boolean matches(String input) {
        String value = normalize(input);
        return !value.isEmpty() && (value.contains(normalize(title)) || tags.stream().map(Song::normalize).anyMatch(value::contains));
    }
    public boolean matchesTitle(String input){return normalize(input).contains(normalize(title));}
    public static String youtube(String input) {
        try {
            if(input==null || input.length()>2048) throw new IllegalArgumentException();
            URI uri = URI.create(input.trim()); String host = uri.getHost(), id = null;
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getPort() != -1) throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("youtu.be")) id = uri.getPath().substring(1);
            else if (Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com").contains(host)) {
                if (uri.getPath().equals("/watch") && uri.getRawQuery() != null) {
                    int count=0;
                    for (String part : uri.getRawQuery().split("&")) {
                        String[] pair=part.split("=",2);
                        if(URLDecoder.decode(pair[0],StandardCharsets.UTF_8).equals("v")) {
                            if(++count!=1 || pair.length!=2) throw new IllegalArgumentException();
                            id=URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        }
                    }
                } else if (uri.getPath().matches("/(shorts|live)/[^/]+")) id = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            }
            if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException();
            return "https://www.youtube.com/watch?v=" + id;
        } catch (RuntimeException error) { throw new IllegalArgumentException("유효한 YouTube 영상 링크를 입력하세요."); }
    }
}
