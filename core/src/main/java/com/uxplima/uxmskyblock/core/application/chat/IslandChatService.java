package com.uxplima.uxmskyblock.core.application.chat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise domain application service orchestrating island team private chat,
 * dual dispatch modes (toggle / single-dispatch), cross-server pub/sub transport,
 * staff spy oversight, and rate limiting.
 */
public final class IslandChatService {

    private final IslandStoragePort islandStoragePort;
    private final IslandChatTransportPort transportPort;
    private final IslandChatDeliveryPort deliveryPort;
    private final IslandOnlineMemberProvider onlineMemberProvider;
    private final int rateLimitMessagesPerSecond;

    /**
     * The islands allied with this one, or null when alliances are switched off.
     *
     * <p>A function rather than the alliance service itself, the way the warp service takes its ally
     * check: the chat has no other business with alliances and a null one is a server that does not
     * have them.
     */
    private final @Nullable Function<IslandId, List<IslandId>> allyLookup;

    private final Map<ProfileId, IslandChatChannel> activeChannels = new ConcurrentHashMap<>();
    private final Set<ProfileId> activeSpies = ConcurrentHashMap.newKeySet();
    private final Map<ProfileId, Deque<Long>> rateLimitWindows = new ConcurrentHashMap<>();

    public IslandChatService(
            IslandStoragePort islandStoragePort,
            IslandChatTransportPort transportPort,
            IslandChatDeliveryPort deliveryPort,
            IslandOnlineMemberProvider onlineMemberProvider,
            int rateLimitMessagesPerSecond,
            @Nullable Function<IslandId, List<IslandId>> allyLookup) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.transportPort = Objects.requireNonNull(transportPort, "transportPort must not be null");
        this.deliveryPort = Objects.requireNonNull(deliveryPort, "deliveryPort must not be null");
        this.onlineMemberProvider =
                Objects.requireNonNull(onlineMemberProvider, "onlineMemberProvider must not be null");
        this.rateLimitMessagesPerSecond = rateLimitMessagesPerSecond;
        this.allyLookup = allyLookup;

        this.transportPort.subscribe(this::handleInboundFrame);
    }

    public IslandChatService(
            IslandStoragePort islandStoragePort,
            IslandChatTransportPort transportPort,
            IslandChatDeliveryPort deliveryPort,
            IslandOnlineMemberProvider onlineMemberProvider,
            int rateLimitMessagesPerSecond) {
        this(islandStoragePort, transportPort, deliveryPort, onlineMemberProvider, rateLimitMessagesPerSecond, null);
    }

    /** Whether this server has alliances, and so whether the alliance channel is worth offering. */
    public boolean hasAlliances() {
        return allyLookup != null;
    }

    public IslandChatChannel getChannel(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return activeChannels.getOrDefault(profileId, IslandChatChannel.GLOBAL);
    }

    public IslandChatChannel setChannel(ProfileId profileId, IslandChatChannel channel) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(channel, "channel must not be null");

        if (channel == IslandChatChannel.GLOBAL) {
            activeChannels.remove(profileId);
            return IslandChatChannel.GLOBAL;
        }
        if (islandStoragePort.findIslandIdByProfileId(profileId).isEmpty()) {
            throw new NoIslandForChatException(profileId);
        }
        // A server without alliances has no alliance channel to stand in, so asking for it puts the
        // player on their island's own channel rather than on one that would deliver to nobody.
        IslandChatChannel target =
                channel == IslandChatChannel.ALLIANCE && !hasAlliances() ? IslandChatChannel.ISLAND : channel;
        activeChannels.put(profileId, target);
        return target;
    }

    public IslandChatChannel toggleChannel(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        IslandChatChannel current = getChannel(profileId);
        if (current == IslandChatChannel.GLOBAL) {
            return setChannel(profileId, IslandChatChannel.ISLAND);
        } else {
            return setChannel(profileId, IslandChatChannel.GLOBAL);
        }
    }

    public boolean isSpy(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return activeSpies.contains(profileId);
    }

    public void setSpy(ProfileId profileId, boolean enabled) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        if (enabled) {
            activeSpies.add(profileId);
        } else {
            activeSpies.remove(profileId);
        }
    }

    public boolean toggleSpy(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        boolean newState = !isSpy(profileId);
        setSpy(profileId, newState);
        return newState;
    }

    public void handlePlayerQuit(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        activeChannels.remove(profileId);
        activeSpies.remove(profileId);
        rateLimitWindows.remove(profileId);
    }

    public void sendChat(ProfileId senderProfileId, String senderName, String message) {
        Objects.requireNonNull(senderProfileId, "senderProfileId must not be null");
        Objects.requireNonNull(senderName, "senderName must not be null");
        Objects.requireNonNull(message, "message must not be null");

        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        checkRateLimit(senderProfileId);

        IslandId islandId = islandStoragePort
                .findIslandIdByProfileId(senderProfileId)
                .orElseThrow(() -> new NoIslandForChatException(senderProfileId));

        Island island = islandStoragePort
                .findIslandById(islandId)
                .orElseThrow(() -> new NoIslandForChatException(senderProfileId));

        IslandRole senderRole = island.roleOf(senderProfileId);
        if (!senderRole.hasPermission(IslandPermission.CHAT_SEND)) {
            throw new IslandChatPermissionDeniedException(senderProfileId, islandId, IslandPermission.CHAT_SEND);
        }

        IslandChatFrame frame = new IslandChatFrame(
                islandId, senderProfileId, senderName, senderRole, trimmed, Instant.now(), channelFor(senderProfileId));

        transportPort.publish(frame);
    }

    /** Sends one line on {@code channel} whatever the sender is standing on, for a one shot command. */
    public void sendChatOn(ProfileId senderProfileId, String senderName, String message, IslandChatChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        IslandChatChannel previous = getChannel(senderProfileId);
        activeChannels.put(senderProfileId, channel == IslandChatChannel.GLOBAL ? IslandChatChannel.ISLAND : channel);
        try {
            sendChat(senderProfileId, senderName, message);
        } finally {
            if (previous == IslandChatChannel.GLOBAL) {
                activeChannels.remove(senderProfileId);
            } else {
                activeChannels.put(senderProfileId, previous);
            }
        }
    }

    /** The channel a frame from this sender carries: never GLOBAL, because a frame is never global. */
    private IslandChatChannel channelFor(ProfileId senderProfileId) {
        IslandChatChannel channel = getChannel(senderProfileId);
        return channel == IslandChatChannel.GLOBAL ? IslandChatChannel.ISLAND : channel;
    }

    /** Every island a frame on this channel reaches: the sender's own, and its allies when allied. */
    private List<IslandId> islandsReachedBy(IslandChatFrame frame) {
        List<IslandId> reached = new ArrayList<>();
        reached.add(frame.islandId());
        if (frame.channel() == IslandChatChannel.ALLIANCE && allyLookup != null) {
            for (IslandId ally : allyLookup.apply(frame.islandId())) {
                if (!reached.contains(ally)) {
                    reached.add(ally);
                }
            }
        }
        return reached;
    }

    private void handleInboundFrame(IslandChatFrame frame) {
        Optional<Island> optIsland = islandStoragePort.findIslandById(frame.islandId());
        if (optIsland.isEmpty()) {
            return;
        }
        Island island = optIsland.get();
        String islandName = frame.islandId().value().toString().substring(0, 8);

        // 1. Deliver to local online members of every island this frame reaches. On the island's own
        // channel that is one island; on the alliance channel it is that island and its allies, and
        // each of them decides for itself who among its members may read chat.
        Set<ProfileId> recipientMembers = new HashSet<>();
        for (IslandId reachedId : islandsReachedBy(frame)) {
            Optional<Island> optReached =
                    reachedId.equals(island.id()) ? Optional.of(island) : islandStoragePort.findIslandById(reachedId);
            if (optReached.isEmpty()) {
                continue;
            }
            Island reached = optReached.get();
            // The island is already in hand, so the provider is asked about its members rather than
            // reading the island again and walking every player on the server for each line of chat.
            for (ProfileId memberId :
                    onlineMemberProvider.onlineAmong(reached.members().keySet())) {
                if (reached.roleOf(memberId).hasPermission(IslandPermission.CHAT_VIEW)) {
                    recipientMembers.add(memberId);
                }
            }
        }

        if (!recipientMembers.isEmpty()) {
            deliveryPort.deliverToMembers(Collections.unmodifiableSet(recipientMembers), frame);
        }

        // 2. Deliver to active staff spies
        Set<ProfileId> recipientSpies = new HashSet<>();
        for (ProfileId spyId : activeSpies) {
            if (!recipientMembers.contains(spyId)) {
                recipientSpies.add(spyId);
            }
        }

        if (!recipientSpies.isEmpty()) {
            deliveryPort.deliverToSpies(Collections.unmodifiableSet(recipientSpies), frame, islandName);
        }
    }

    private void checkRateLimit(ProfileId senderProfileId) {
        if (rateLimitMessagesPerSecond <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        Deque<Long> window = rateLimitWindows.computeIfAbsent(senderProfileId, k -> new ArrayDeque<>());

        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 1000L) {
                window.pollFirst();
            }
            if (window.size() >= rateLimitMessagesPerSecond) {
                throw new ChatRateLimitExceededException(senderProfileId);
            }
            window.addLast(now);
        }
    }
}
