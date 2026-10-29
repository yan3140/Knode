package com.yan.knowledge.core.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只处理文档语义，不依赖 Spring AI Document；Adapter 后续负责把 ChunkDraft 写入具体索引。
 * 标题先形成章节边界，章节过长时再按字符上限和重叠窗口切分。
 */
public final class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");
    private static final int MAX_SECTION_PREFIX = 200;

    public List<ChunkDraft> chunk(ParsedDocument document, ChunkingPolicy policy) {
        List<Section> sections = splitSections(document.title(), document.content());
        List<ChunkDraft> result = new ArrayList<>();
        int chunkIndex = 0;
        for (Section section : sections) {
            for (String content : splitSection(section, policy)) {
                result.add(new ChunkDraft(
                        chunkIndex,
                        section.title(),
                        content,
                        Map.of(
                                "title", document.title(),
                                "source", document.source(),
                                "section", section.title(),
                                "chunkIndex", String.valueOf(chunkIndex))));
                chunkIndex++;
            }
        }
        return List.copyOf(result);
    }

    private List<Section> splitSections(String documentTitle, String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        List<Section> sections = new ArrayList<>();
        String currentTitle = normalizeTitle(documentTitle);
        StringBuilder body = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                addSectionIfPresent(sections, currentTitle, body);
                currentTitle = normalizeTitle(matcher.group(1));
                body = new StringBuilder();
            } else {
                if (!body.isEmpty()) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        addSectionIfPresent(sections, currentTitle, body);
        return sections;
    }

    private void addSectionIfPresent(List<Section> sections, String title, StringBuilder body) {
        String text = body.toString().strip();
        if (!text.isEmpty()) {
            sections.add(new Section(title, text));
        }
    }

    private List<String> splitSection(Section section, ChunkingPolicy policy) {
        String prefix = sectionPrefix(section.title(), policy.targetCharacters());
        int bodyCapacity = Math.max(1, policy.targetCharacters() - prefix.length());
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < section.body().length()) {
            int end = chooseBoundary(section.body(), start, bodyCapacity);
            chunks.add(prefix + section.body().substring(start, end));
            if (end == section.body().length()) {
                break;
            }
            int overlap = Math.min(policy.overlapCharacters(), end - start);
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    private int chooseBoundary(String body, int start, int capacity) {
        int hardEnd = Math.min(body.length(), start + capacity);
        if (hardEnd == body.length()) {
            return hardEnd;
        }
        int minimumUsefulBoundary = start + capacity / 2;
        for (int index = hardEnd; index > minimumUsefulBoundary; index--) {
            char previous = body.charAt(index - 1);
            if (previous == '\n' || Character.isWhitespace(previous)) {
                return index;
            }
        }
        return hardEnd;
    }

    private String sectionPrefix(String title, int targetCharacters) {
        int prefixLimit = Math.max(1, Math.min(MAX_SECTION_PREFIX, targetCharacters - 1));
        String safeTitle = title.length() <= prefixLimit ? title : title.substring(0, prefixLimit);
        return safeTitle + "\n";
    }

    private String normalizeTitle(String title) {
        return title == null || title.isBlank() ? "正文" : title.strip();
    }

    private record Section(String title, String body) {
    }
}
