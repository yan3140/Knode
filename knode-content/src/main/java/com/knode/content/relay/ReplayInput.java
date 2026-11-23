package com.knode.content.relay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

@JsonDeserialize(using=ReplayInput.Parser.class)
public record ReplayInput(String replayRequestId) {
    public static final class Parser extends JsonDeserializer<ReplayInput> {
        @Override public ReplayInput deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(!parser.isExpectedStartObjectToken()) throw JsonMappingException.from(parser,"Invalid request");
            String id=null;boolean seen=false;
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                if(parser.currentToken()!=JsonToken.FIELD_NAME) throw JsonMappingException.from(parser,"Invalid request");
                String name=parser.currentName();
                if(parser.nextToken()!=JsonToken.VALUE_STRING || !"replayRequestId".equals(name) || seen)
                    throw JsonMappingException.from(parser,"Invalid request");
                seen=true;id=parser.getText();
                if(!id.matches("[A-Za-z0-9._-]{1,64}")) throw JsonMappingException.from(parser,"Invalid request");
            }
            if(!seen) throw JsonMappingException.from(parser,"Invalid request");
            return new ReplayInput(id);
        }
    }
}
