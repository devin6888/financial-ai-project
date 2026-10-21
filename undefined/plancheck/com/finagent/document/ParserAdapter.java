package com.finagent.document;
public interface ParserAdapter {
    ParsedDocument parse(byte[] pdf, String sourceName, Integer pageStart, Integer pageEnd);
    String name();
}
