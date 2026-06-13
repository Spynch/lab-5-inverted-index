package searchengine;

import org.junit.jupiter.api.Test;
import searchengine.document.WikipediaMarkupCleaner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikipediaMarkupCleanerTest {
    @Test
    void convertsCommonWikipediaMarkupToReadableText() {
        String wikiText = "{{Карточка|имя={{lang|ru|Россия}}}}\n"
                + "== История ==\n"
                + "'''Россия''' — государство в [[Восточная Европа|Восточной Европе]].<ref>Источник</ref>\n"
                + "* Столица — [[Москва]].\n"
                + "* [https://example.org Официальный сайт]\n"
                + "[[Файл:Map.svg|thumb|Карта с [[Москва|вложенной ссылкой]]]]\n"
                + "{| class=\"wikitable\"\n|-\n| Служебная таблица\n|}\n"
                + "<!-- комментарий -->Население&nbsp;страны.";

        String plainText = WikipediaMarkupCleaner.toPlainText(wikiText);

        assertEquals("История\n"
                + "Россия — государство в Восточной Европе.\n"
                + "- Столица — Москва.\n"
                + "- Официальный сайт\n"
                + "\n"
                + "Население страны.", plainText);
        assertFalse(plainText.contains("{{"));
        assertFalse(plainText.contains("[["));
        assertFalse(plainText.contains("]]"));
        assertFalse(plainText.contains("вложенной ссылкой"));
        assertFalse(plainText.contains("<ref"));
    }

    @Test
    void preservesMalformedMarkupInsteadOfDroppingFollowingText() {
        String plainText = WikipediaMarkupCleaner.toPlainText("Текст {{незакрытый шаблон и продолжение");

        assertTrue(plainText.contains("продолжение"));
    }
}
