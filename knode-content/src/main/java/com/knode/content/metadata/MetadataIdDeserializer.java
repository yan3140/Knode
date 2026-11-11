package com.knode.content.metadata;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Invalid body IDs are treated as invalid parameters instead of being truncated or coerced. */
public final class MetadataIdDeserializer extends JsonDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        JsonToken token=parser.currentToken();
        if (token!=JsonToken.VALUE_STRING && token!=JsonToken.VALUE_NUMBER_INT) {
            parser.skipChildren();
            return null;
        }
        try {
            return MetadataQuery.id(parser.getText());
        } catch (MetadataFailure invalid) {
            return null;
        }
    }
}
