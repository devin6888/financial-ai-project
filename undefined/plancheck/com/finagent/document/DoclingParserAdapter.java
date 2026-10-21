package com.finagent.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class DoclingParserAdapter implements ParserAdapter {

    private static final Logger log = LoggerFactory.getLogger(DoclingParserAdapter.class);

    private final RestClient client;
    private final ObjectMapper mapper;

    public DoclingParserAdapter(ParserProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory -> {
                    var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
                    f.setConnectTimeout(Duration.ofSeconds(10));
                    f.setReadTimeout(Duration.ofMinutes(props.readTimeoutMinutes()));
                    return f;
                })
                .build();
    }

    @Override
    public ParsedDocument parse(byte[] pdf, String sourceName, Integer pageStart, Integer pageEnd) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() { return sourceName; }
        });
        if (pageStart != null && pageEnd != null) {
            form.add("page_start", String.valueOf(pageStart));
            form.add("page_end", String.valueOf(pageEnd));
        }
        String body = client.post().uri("/parse")
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().body(String.class);
        return new ParsedDocument("", "", 0, 0, 0, 0, 0, body);
    }

    @Override
    public String name() { return "docling"; }
}
