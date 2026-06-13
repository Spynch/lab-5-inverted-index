package searchengine.document;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WikipediaMarkupCleaner {
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern INLINE_TAG_BLOCK = Pattern.compile(
            "(?is)<(ref|math)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?is)<(references|gallery|source|syntaxhighlight|timeline)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern HEADING = Pattern.compile("^\\s*=+\\s*(.*?)\\s*=+\\s*$");
    private static final Pattern LIST_PREFIX = Pattern.compile("^\\s*([*#]+)\\s*");
    private static final Pattern DEFINITION_PREFIX = Pattern.compile("^\\s*[;:]\\s*");
    private static final Pattern MAGIC_WORD = Pattern.compile("__[A-ZА-Я0-9_]+__");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[\\t\\x0B\\f ]+");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([,.;:!?])");
    private static final Pattern EXCESSIVE_BLANK_LINES = Pattern.compile("\\n{3,}");

    private WikipediaMarkupCleaner() {
    }

    public static String toPlainText(String wikiText) {
        if (wikiText == null || wikiText.isEmpty()) {
            return "";
        }

        String text = wikiText.replace("\r\n", "\n").replace('\r', '\n');
        text = COMMENT.matcher(text).replaceAll("");
        text = INLINE_TAG_BLOCK.matcher(text).replaceAll(" ");
        text = BLOCK_TAG.matcher(text).replaceAll("\n");
        text = removeBalanced(text, "{{", "}}");
        text = removeBalanced(text, "{|", "|}");
        text = replaceInternalLinks(text);
        text = replaceExternalLinks(text);
        text = HTML_TAG.matcher(text).replaceAll("");
        text = MAGIC_WORD.matcher(text).replaceAll("");
        text = text.replace("'''", "").replace("''", "");
        text = decodeEntities(text);
        return normalizeLines(text);
    }

    private static String removeBalanced(String text, String opening, String closing) {
        StringBuilder result = new StringBuilder(text.length());
        int offset = 0;
        while (offset < text.length()) {
            int start = text.indexOf(opening, offset);
            if (start < 0) {
                result.append(text, offset, text.length());
                break;
            }
            result.append(text, offset, start);
            int end = balancedEnd(text, start, opening, closing);
            if (end < 0) {
                result.append(text, start, text.length());
                break;
            }
            result.append('\n');
            offset = end;
        }
        return result.toString();
    }

    private static int balancedEnd(String text, int start, String opening, String closing) {
        int depth = 0;
        int offset = start;
        while (offset < text.length()) {
            if (text.startsWith(opening, offset)) {
                depth++;
                offset += opening.length();
            } else if (text.startsWith(closing, offset)) {
                depth--;
                offset += closing.length();
                if (depth == 0) {
                    return offset;
                }
            } else {
                offset++;
            }
        }
        return -1;
    }

    private static String replaceInternalLinks(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int offset = 0;
        while (offset < text.length()) {
            int start = text.indexOf("[[", offset);
            if (start < 0) {
                result.append(text, offset, text.length());
                break;
            }
            result.append(text, offset, start);
            int end = balancedEnd(text, start, "[[", "]]");
            if (end < 0) {
                result.append(text, start, text.length());
                break;
            }
            result.append(internalLinkLabel(text.substring(start + 2, end - 2)));
            offset = end;
        }
        return result.toString();
    }

    private static String internalLinkLabel(String link) {
        String target = firstPart(link).trim();
        String normalizedTarget = target.toLowerCase(Locale.ROOT);
        if (normalizedTarget.startsWith("file:")
                || normalizedTarget.startsWith("image:")
                || normalizedTarget.startsWith("category:")
                || normalizedTarget.startsWith("файл:")
                || normalizedTarget.startsWith("изображение:")
                || normalizedTarget.startsWith("категория:")) {
            return "";
        }

        int separator = link.lastIndexOf('|');
        String label = separator >= 0 ? link.substring(separator + 1) : target;
        int section = label.indexOf('#');
        if (section >= 0) {
            label = label.substring(0, section);
        }
        return replaceInternalLinks(label).replace('_', ' ').trim();
    }

    private static String firstPart(String value) {
        int separator = value.indexOf('|');
        return separator >= 0 ? value.substring(0, separator) : value;
    }

    private static String replaceExternalLinks(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int offset = 0;
        while (offset < text.length()) {
            int start = text.indexOf('[', offset);
            if (start < 0) {
                result.append(text, offset, text.length());
                break;
            }
            int end = text.indexOf(']', start + 1);
            if (end < 0) {
                result.append(text, offset, text.length());
                break;
            }
            String content = text.substring(start + 1, end).trim();
            if (!isExternalLink(content)) {
                result.append(text, offset, start + 1);
                offset = start + 1;
                continue;
            }
            result.append(text, offset, start);
            int space = content.indexOf(' ');
            if (space >= 0) {
                result.append(content.substring(space + 1).trim());
            }
            offset = end + 1;
        }
        return result.toString();
    }

    private static boolean isExternalLink(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("ftp://")
                || lower.startsWith("//");
    }

    private static String decodeEntities(String text) {
        Matcher matcher = NUMERIC_ENTITY.matcher(text);
        StringBuffer decoded = new StringBuffer(text.length());
        while (matcher.find()) {
            String value = matcher.group(1);
            try {
                int codePoint = value.charAt(0) == 'x' || value.charAt(0) == 'X'
                        ? Integer.parseInt(value.substring(1), 16)
                        : Integer.parseInt(value);
                matcher.appendReplacement(decoded, Matcher.quoteReplacement(
                        Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : ""));
            } catch (IllegalArgumentException e) {
                matcher.appendReplacement(decoded, "");
            }
        }
        matcher.appendTail(decoded);
        return decoded.toString()
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&ndash;", "-")
                .replace("&mdash;", "-")
                .replace("&hellip;", "...")
                .replace("&laquo;", "\u00ab")
                .replace("&raquo;", "\u00bb");
    }

    private static String normalizeLines(String text) {
        StringBuilder result = new StringBuilder(text.length());
        String[] lines = text.split("\\n", -1);
        boolean previousBlank = true;
        for (String sourceLine : lines) {
            String line = sourceLine;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                line = heading.group(1);
            } else {
                Matcher list = LIST_PREFIX.matcher(line);
                if (list.find()) {
                    line = "- " + line.substring(list.end());
                } else {
                    line = DEFINITION_PREFIX.matcher(line).replaceFirst("");
                }
            }
            line = HORIZONTAL_SPACE.matcher(line).replaceAll(" ").trim();
            line = SPACE_BEFORE_PUNCTUATION.matcher(line).replaceAll("$1");
            if (line.isEmpty()) {
                if (!previousBlank) {
                    result.append('\n');
                }
                previousBlank = true;
            } else {
                result.append(line).append('\n');
                previousBlank = false;
            }
        }
        String normalized = EXCESSIVE_BLANK_LINES.matcher(result.toString()).replaceAll("\n\n").trim();
        return normalized;
    }
}
