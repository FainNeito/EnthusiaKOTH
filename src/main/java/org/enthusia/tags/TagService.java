package org.enthusia.tags;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
/** Compile-only public method mirror, excluded from the plugin artifact. */
public abstract class TagService {
    public abstract CompletableFuture<Boolean> grantTagPersisted(UUID playerId, String tagId);
}
