package io.github.badgersmc.advancements.pilot;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Display-only API. Providers remain authoritative for progress and reward claims. */
public interface ProjectionService {
    /**
     * Immutable provider DTO. Keep the canonical descriptor and compatibility overloads:
     * already-built provider plugins link directly to these constructors.
     */
    record Node(
        String key, String parentKey, String title, List<String> description,
        Material icon, Integer customModelData, String itemModel, String frame,
        float x, float y
    ) {
        public Node(String key, String parentKey, String title, List<String> description,
                    Material icon, String frame, float x, float y) {
            this(key, parentKey, title, description, icon, null, null, frame, x, y);
        }

        public Node(String key, String parentKey, String title, List<String> description,
                    Material icon, Integer customModelData, String frame, float x, float y) {
            this(key, parentKey, title, description, icon, customModelData, null, frame, x, y);
        }

        public Node(String key, String parentKey, String title, List<String> description,
                    Material icon, String itemModel, String frame, float x, float y) {
            this(key, parentKey, title, description, icon, null, itemModel, frame, x, y);
        }

        public Node {
            NodeChecks.validateIdentity(key, title);
            description = NodeChecks.checkedDescription(description);
            if (icon == null) icon = Material.CLOCK;
            NodeChecks.validateIcon(customModelData, itemModel);
            NodeChecks.validateFrame(frame);
        }
    }

    void registerTree(
        Plugin owner,
        String namespace,
        ItemStack icon,
        List<Node> nodes
    );
    void removeTree(Plugin owner, String namespace);
    boolean ready(Player player);
    void project(
        Plugin owner,
        String namespace,
        Player player,
        Map<String, Integer> progress
    );
    void celebrate(Plugin owner, String namespace, Player player, String key);

    /** Legacy entry points reject ownerless writes rather than bypassing namespace ownership. */
    @Deprecated
    default void project(
        String namespace,
        Player player,
        Map<String, Integer> progress
    ) {
        throw new UnsupportedOperationException(
            "Projection requires the registered owner; update the provider plugin."
        );
    }

    @Deprecated
    default void celebrate(String namespace, Player player, String key) {
        throw new UnsupportedOperationException(
            "Celebration requires the registered owner; update the provider plugin."
        );
    }
}
