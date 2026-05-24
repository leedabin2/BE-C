package com.notification.adapter.in.web.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * JSON 오브젝트/배열을 String으로 역직렬화한다.
 * 클라이언트가 contentData를 문자열 또는 오브젝트 어느 형태로 전달해도 DB 저장용 JSON 문자열로 수신한다.
 */
public class JsonStringDeserializer extends StdDeserializer<String> {

    public JsonStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return p.getText();
        }
        return p.readValueAsTree().toString();
    }
}
