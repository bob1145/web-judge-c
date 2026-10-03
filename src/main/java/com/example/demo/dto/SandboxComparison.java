package com.example.demo.dto;

import java.math.BigDecimal;

public record SandboxComparison(Mode mode, String precision) {
    public SandboxComparison {
        if (mode == null || precision == null
                || !precision.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException("comparison mode and decimal precision are required");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(precision);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("precision must be a finite decimal", exception);
        }
        if (value.signum() < 0 || (mode != Mode.DECIMAL_ABSOLUTE && value.signum() != 0)) {
            throw new IllegalArgumentException("precision must be nonnegative and zero for non-decimal comparison");
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
}
