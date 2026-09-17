package dev.gearreserve.domain;

public record Equipment(long id, String name, boolean requiresApproval) {
    public Equipment {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
