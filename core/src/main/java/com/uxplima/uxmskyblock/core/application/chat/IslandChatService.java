package com.uxplima.uxmskyblock.core.application.chat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<ProfileId, IslandChatChannel> activeChannels = new ConcurrentHashMap<>();
    private final Set<ProfileId> activeSpies = ConcurrentHashMap.newKeySet();
    private final Map<ProfileId, Deque<Long>> rateLimitWindows = new ConcurrentHashMap<>();

    public IslandChatService(
            IslandStoragePort islandStoragePort,
            IslandChatTransportPort transportPort,
            IslandChatDeliveryPort deliveryPort,
            IslandOnlineMemberProvider onlineMemberProvider,
            int rateLimitMessagesPerSecond) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.transportPort = Objects.requireNonNull(transportPort, "transportPort must not be null");
        this.deliveryPort = Objects.requireNonNull(deliveryPort, "deliveryPort must not be null");
        this.onlineMemberProvider =
                Objects.requireNonNull(onlineMemberProvider, "onlineMemberProvider must not be null");
        this.rateLimitMessagesPerSecond = rateLimitMessagesPerSecond;

        this.transportPort.subscribe(this::handleInboundFrame);
    }

    public IslandChatChannel getChannel(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return activeChannels.getOrDefault(profileId, IslandChatChannel.GLOBAL);
    }

    public IslandChatChannel setChannel(ProfileId profileId, IslandChatChannel channel) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(channel, "channel must not be null");

        if (channel == IslandChatChannel.ISLAND) {
            if (islandStoragePort.findIslandIdByProfileId(profileId).isEmpty()) {
                throw new NoIslandForChatException(profileId);
            }
            activeChannels.put(profileId, IslandChatChannel.ISLAND);
            return IslandChatChannel.ISLAND;
        } else {
            activeChannels.remove(profileId);
            return IslandChatChannel.GLOBAL;
        }
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

        IslandChatFrame frame =
                new IslandChatFrame(islandId, senderProfileId, senderName, senderRole, trimmed, Instant.now());

        transportPort.publish(frame);
    }

    private void handleInboundFrame(IslandChatFrame frame) {
        Optional<Island> optIsland = islandStoragePort.findIslandById(frame.islandId());
        if (optIsland.isEmpty()) {
            return;
        }
        Island island = optIsland.get();
        String islandName = frame.islandId().value().toString().substring(0, 8);

        // 1. Deliver to local online members
        Set<ProfileId> onlineMembers = onlineMemberProvider.getOnlineMembers(frame.islandId());
        Set<ProfileId> recipientMembers = new HashSet<>();
        for (ProfileId memberId : onlineMembers) {
            IslandRole role = island.roleOf(memberId);
            if (role.hasPermission(IslandPermission.CHAT_VIEW)) {
                recipientMembers.add(memberId);
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
