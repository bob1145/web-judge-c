package com.example.demo.dto;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public record SandboxComparison(Mode mode, String precision) {
    public static final int MAX_TOKEN_BYTES = 4096;
    public static final int MAX_EXPONENT_MAGNITUDE = 1000;
    private static final Pattern DECIMAL_TOKEN = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    public SandboxComparison {
        if (mode == null || precision == null) {
            throw new IllegalArgumentException("comparison mode and decimal precision are required");
        }
        BigDecimal value = parseDecimal(precision);
        if (value.signum() < 0 || (mode != Mode.DECIMAL_ABSOLUTE && value.signum() != 0)) {
            throw new IllegalArgumentException("precision must be nonnegative and zero for non-decimal comparison");
        }
        if (value.signum() == 0) {
            precision = "0";
        }
    }

    public enum Mode {
        DECIMAL_ABSOLUTE,
        LEGACY_TEXT,
        SPECIAL_JUDGE
    }

    public static SandboxComparison decimal(String precision) {
        return new SandboxComparison(Mode.DECIMAL_ABSOLUTE, precision);
    }

    public static boolean isDecimalToken(String token) {
        return token != null && DECIMAL_TOKEN.matcher(token).matches();
    }

    public static BigDecimal parseDecimal(String token) {
        requireTokenBudget(token);
        if (!isDecimalToken(token)) {
            throw new IllegalArgumentException("precision must be a finite decimal");
        }
        return new BigDecimal(token);
    }

    public static void requireTokenBudget(String token) {
        if (token == null || token.length() > MAX_TOKEN_BYTES
                || token.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES) {
            throw new IllegalArgumentException("comparison token exceeds 4096 UTF-8 bytes");
        }
        if (!isDecimalToken(token)) {
            return;
        }
        int exponentOffset = Math.max(token.indexOf('e'), token.indexOf('E')) + 1;
        if (exponentOffset == 0) {
            return;
        }
        if (token.charAt(exponentOffset) == '+' || token.charAt(exponentOffset) == '-') {
            exponentOffset++;
        }
        int magnitude = 0;
        for (int index = exponentOffset; index < token.length(); index++) {
            magnitude = magnitude * 10 + token.charAt(index) - '0';
            if (magnitude > MAX_EXPONENT_MAGNITUDE) {
                throw new IllegalArgumentException("comparison exponent exceeds 1000");
            }
        }
    }
}
