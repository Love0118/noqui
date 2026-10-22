package dev.noqui.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SongTest {
    @Test void acceptedUrlsCanonicalizeAndStripPlaybackParameters() {
        for(String url:List.of("https://youtu.be/BaW_jenozKc?t=7","https://www.youtube.com/watch?v=BaW_jenozKc&list=foo", "https://music.youtube.com/watch?v=BaW_jenozKc", "https://m.youtube.com/shorts/BaW_jenozKc", "https://youtube.com/live/BaW_jenozKc"))
            assertEquals("https://www.youtube.com/watch?v=BaW_jenozKc",Song.youtube(url));
    }
    @Test void ambiguousAndNonYoutubeSourcesAreRejected() {
        for(String url:List.of("http://youtu.be/BaW_jenozKc","https://youtube.com.evil/watch?v=BaW_jenozKc","https://user@youtube.com/watch?v=BaW_jenozKc",
                "https://youtube.com:443/watch?v=BaW_jenozKc","https://youtube.com/watch?v=BaW_jenozKc&v=BaW_jenozKc", "https://youtube.com/watch?v=BaW_jenozKc&%76=BaW_jenozKc",
                "https://youtube.com/watch?v=%QQ", "https://youtu.be/short", "file:///etc/passwd"))assertThrows(IllegalArgumentException.class,()->Song.youtube(url),url);
    }
    @Test void answersUsePackSubstringNormalizationAndTagsAreBounded() {
        var song=Song.input("https://youtu.be/BaW_jenozKc","Bad Apple!!","동방;동방;YoAsObI");
        assertEquals(2,song.tags().size());assertTrue(song.matches("BAD - APPLE !!!"));assertTrue(song.matches("아마 동방 노래"));assertTrue(song.matches("yoasobi"));
        assertFalse(song.matches("!!!"));assertThrows(IllegalArgumentException.class,()->Song.input(song.youtubeUrl(),"!!!",""));
        assertThrows(IllegalArgumentException.class,()->Song.input(song.youtubeUrl(),"노래","태그;"));
        assertThrows(IllegalArgumentException.class,()->new Song(song.youtubeUrl(),"노래",Collections.nCopies(11,"태그")));
        assertThrows(IllegalArgumentException.class,()->Song.input(song.youtubeUrl(),"노래","가".repeat(33)));
    }
}
