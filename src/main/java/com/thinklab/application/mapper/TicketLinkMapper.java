package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.TicketLinkResponse;
import com.thinklab.domain.model.TicketLink;
import com.thinklab.domain.model.TicketLink.LinkAuditEntry;

/** Maps between the TicketLink aggregate and its DTOs. Static, stateless. */
public final class TicketLinkMapper {

    private TicketLinkMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static TicketLinkResponse toResponse(TicketLink link) {
        return new TicketLinkResponse(link.getId(), link.getOrganisationId(), link.getConnectionId(), link.getSubjectType().name(), link.getSubjectId(),
                link.getExternalId(), link.getExternalUrl(), link.getStatus().name(), link.getLastPushedStatus(), link.getSyncedCommentIds().size(),
                link.getLastSyncedAt(), link.getLastDirection() != null ? link.getLastDirection().name() : null, link.getLastError(), link.getCreatedAt(),
                link.getUpdatedAt());
    }

    public static AuditEntryResponse toResponse(LinkAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(),
                entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
    }
}
