package com.yan.knowledge.index;

import com.yan.knowledge.core.port.TokenCounter;
import org.springframework.stereotype.Component;

@Component
public class SimpleTokenCounter implements TokenCounter {
    @Override public long countTokens(String text) { return text == null || text.isEmpty() ? 0 : (text.length() + 3L) / 4L; }
}
