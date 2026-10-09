package io.github.badgersmc.advancements.pilot;

import java.util.List;

/** Validates independent node fields without changing the public constructor contract. */
final class NodeChecks {

    private NodeChecks() {}

    static void validateIdentity(String key, String title) {
        validateKey(key);
        if (
            title == null || title.isBlank()
        ) throw new IllegalArgumentException("Missing title");
    }

    private static void validateKey(String key) {
        if (
            key == null || !key.matches("[a-z0-9/._-]+") || "root".equals(key)
        ) {
            throw new IllegalArgumentException(
                "Invalid or reserved advancement key: " + key
            );
        }
    }

    static List<String> checkedDescription(List<String> description) {
        if (description == null) throw new IllegalArgumentException(
            "Missing description"
        );
        for (String line : description) {
            if (line == null) throw new IllegalArgumentException(
                "Null description entry"
            );
        }
        return List.copyOf(description);
    }

    static void validateIcon(Integer customModelData, String itemModel) {
        if (customModelData != null && customModelData <= 0) {
            throw new IllegalArgumentException("Invalid custom model data");
        }
        if (
            itemModel != null &&
            !itemModel.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
        ) {
            throw new IllegalArgumentException(
                "Invalid item model: " + itemModel
            );
        }
    }

    static void validateFrame(String frame) {
        if (
            frame == null ||
            !List.of("TASK", "GOAL", "CHALLENGE").contains(frame)
        ) {
            throw new IllegalArgumentException("Invalid frame");
        }
    }
}
