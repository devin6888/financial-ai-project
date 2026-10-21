package com.finagent.document;
public record ParsedDocument(
        String contentHash, String parserVersion, int pageCount, int blockCount,
        int tableCount, int headingCount, int anomalyCount, String rawJson) {}
