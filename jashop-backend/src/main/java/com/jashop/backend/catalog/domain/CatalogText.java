package com.jashop.backend.catalog.domain;

import java.util.Locale;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;

public final class CatalogText {
    private CatalogText() {}
    public static String strip(String value) { return value == null ? null : value.strip(); }
    public static String optional(String value) {
        String text = strip(value);
        return text == null || text.isBlank() ? null : text;
    }
    public static String normalizeSku(String value) {
        String text = strip(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }
    public static String required(String value, int maximum) {
        String text = optional(value);
        if (text == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return bounded(text, maximum);
    }
    public static String bounded(String value, int maximum) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }
}
