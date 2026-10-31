package org.creatordesk.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import org.creatordesk.config.OwnerConfig;
import org.creatordesk.config.OwnerRegistry;
import org.springframework.stereotype.Component;

@Component
public final class PeerDirectory {
    private final OwnerRegistry owners;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, PeerClients> clients = new ConcurrentHashMap<>();

    public PeerDirectory(OwnerRegistry owners, ObjectMapper mapper) {
        this.owners = owners;
        this.mapper = mapper;
    }

    public PeerClients forOwner(String owner) {
        OwnerConfig config = owners.forOwner(owner);
        if (config == null) throw new org.creatordesk.error.DeskException(503, "owner_peers_unconfigured");
        return clients.computeIfAbsent(owner, ignored -> new PeerClients(config, mapper));
    }
}
