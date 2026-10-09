package net.enthusia.loreitems.api.v1;

import java.util.Objects;

public record LoreDeliveryResult(
        LoreDeliveryStatus status,
        String externalOperationId,
        String detail) {
    public LoreDeliveryResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(externalOperationId, "externalOperationId");
        Objects.requireNonNull(detail, "detail");
    }
}
