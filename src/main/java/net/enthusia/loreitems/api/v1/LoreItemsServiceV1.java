package net.enthusia.loreitems.api.v1;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface LoreItemsServiceV1 {
    int API_VERSION = 1;

    CompletionStage<LoreDeliveryResult> queueDelivery(
            String definitionKey,
            UUID playerId,
            String externalOperationId);
}
