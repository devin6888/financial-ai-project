package com.finagent.citation;

/**
 * 表格内的行范围。
 *
 * <p>库里没有 tableId，所以本阶段恒为 {@code null}（ST5 偏差登记 #5）。
 * 保留类型是为了前端与阶段 7 的接口稳定，不是为了现在填值。
 */
public record TableRef(String tableId, Integer rowStart, Integer rowEnd) { }
