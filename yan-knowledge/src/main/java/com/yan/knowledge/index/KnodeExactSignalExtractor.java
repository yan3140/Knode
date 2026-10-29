package com.yan.knowledge.index;

import com.yan.knowledge.core.port.ExactSignalExtractor;
import com.yan.knowledge.core.retrieval.ExactMatchType;
import com.yan.knowledge.core.retrieval.ExactSignal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class KnodeExactSignalExtractor implements ExactSignalExtractor {
    private static final Pattern ERROR_CODE = Pattern.compile("\\b[A-Z][A-Z0-9_]*(?:-[A-Z0-9_]+)+\\b");
    private static final Pattern EXCEPTION = Pattern.compile("\\b[A-Za-z_$][\\w$]*(?:Exception|Error)\\b");
    private static final Pattern CONFIG_KEY = Pattern.compile("\\b[a-z][\\w-]*(?:\\.[a-z][\\w-]*)+\\b");
    private static final Pattern PATH = Pattern.compile("/[\\w./-]+");
    private static final Pattern PORT = Pattern.compile("\\b\\d{2,5}\\b");

    @Override
    public List<ExactSignal> extract(String query) {
        List<LocatedSignal> matches = new ArrayList<>();
        collect(matches, ERROR_CODE, ExactMatchType.ERROR_CODE, query);
        collect(matches, EXCEPTION, ExactMatchType.EXCEPTION, query);
        collect(matches, CONFIG_KEY, ExactMatchType.CONFIG_KEY, query);
        collect(matches, PATH, ExactMatchType.LOG_TERM, query);
        collect(matches, PORT, ExactMatchType.HOST_OR_PORT, query);
        return matches.stream()
                .sorted(Comparator.comparingInt(LocatedSignal::offset))
                .filter(signal -> matches.stream().noneMatch(other -> other != signal
                        && other.offset() <= signal.offset() && other.end() >= signal.end()))
                .map(signal -> new ExactSignal(signal.type(), signal.value()))
                .distinct()
                .toList();
    }

    private void collect(List<LocatedSignal> matches, Pattern pattern, ExactMatchType type, String query) {
        Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            matches.add(new LocatedSignal(matcher.start(), matcher.end(), matcher.group(), type));
        }
    }

    private record LocatedSignal(int offset, int end, String value, ExactMatchType type) {
    }
}
