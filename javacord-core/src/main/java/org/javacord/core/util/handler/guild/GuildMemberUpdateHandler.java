package org.javacord.core.util.handler.guild;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.server.role.UserRoleAddEvent;
import org.javacord.api.event.server.role.UserRoleRemoveEvent;
import org.javacord.api.event.user.UserChangeAvatarEvent;
import org.javacord.api.event.user.UserChangeDiscriminatorEvent;
import org.javacord.api.event.user.UserChangeGlobalNameEvent;
import org.javacord.api.event.user.UserChangeNameEvent;
import org.javacord.api.event.user.UserChangeNicknameEvent;
import org.javacord.api.event.user.UserChangePendingEvent;
import org.javacord.api.event.user.UserChangeServerAvatarEvent;
import org.javacord.api.event.user.UserChangeTimeoutEvent;
import org.javacord.core.entity.server.ServerImpl;
import org.javacord.core.entity.user.Member;
import org.javacord.core.entity.user.MemberImpl;
import org.javacord.core.entity.user.UserImpl;
import org.javacord.core.event.server.role.UserRoleAddEventImpl;
import org.javacord.core.event.server.role.UserRoleRemoveEventImpl;
import org.javacord.core.event.user.UserChangeAvatarEventImpl;
import org.javacord.core.event.user.UserChangeDiscriminatorEventImpl;
import org.javacord.core.event.user.UserChangeGlobalNameEventImpl;
import org.javacord.core.event.user.UserChangeNameEventImpl;
import org.javacord.core.event.user.UserChangeNicknameEventImpl;
import org.javacord.core.event.user.UserChangePendingEventImpl;
import org.javacord.core.event.user.UserChangeServerAvatarEventImpl;
import org.javacord.core.event.user.UserChangeTimeoutEventImpl;
import org.javacord.core.util.cache.MessageCacheImpl;
import org.javacord.core.util.event.DispatchQueueSelector;
import org.javacord.core.util.gateway.PacketHandler;
import org.javacord.core.util.logging.LoggerUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Handles the guild member update packet.
 */
public class GuildMemberUpdateHandler extends PacketHandler {

    private static final Logger logger = LoggerUtil.getLogger(GuildMemberUpdateHandler.class);

    /**
     * Creates a new instance of this class.
     *
     * @param api The api.
     */
    public GuildMemberUpdateHandler(DiscordApi api) {
        super(api, true, "GUILD_MEMBER_UPDATE");
    }

    @Override
    public void handle(JsonNode packet) {
        api.getPossiblyUnreadyServerById(packet.get("guild_id").asLong()).map(server -> (ServerImpl) server)
                .ifPresent(server -> {
                    long userId = packet.get("user").get("id").asLong();
                    boolean selfMuted = server.isSelfMuted(userId);
                    boolean selfDeafened = server.isSelfDeafened(userId);
                    // GUILD_MEMBER_UPDATE doesn't contain self voice states, so we either pass the old or set to false.
                    // https://discord.com/developers/docs/topics/gateway-events#guild-member-update
                    MemberImpl newMember = new MemberImpl(api, server, packet, null)
                            .setSelfMuted(selfMuted)
                            .setSelfDeafened(selfDeafened);
                    Member oldMember = server.getRealMemberById(userId).orElse(null);

                    api.addMemberToCacheOrReplaceExisting(newMember);

                    if (oldMember == null) {
                        // Should only happen shortly after startup and is unproblematic
                        return;
                    }

                    if (!newMember.getNickname().equals(oldMember.getNickname())) {
                        UserChangeNicknameEvent event =
                                new UserChangeNicknameEventImpl(newMember, oldMember);

                        api.getEventDispatcher().dispatchUserChangeNicknameEvent(
                                server, server, newMember.getUser(), event);
                    }

                    if (!newMember.getTimeout().equals(oldMember.getTimeout())) {
                        UserChangeTimeoutEvent event =
                                new UserChangeTimeoutEventImpl(newMember, oldMember);

                        api.getEventDispatcher().dispatchUserChangeTimeoutEvent(
                                server, server, newMember.getUser(), event);
                    }

                    if (!newMember.getServerAvatarHash().equals(oldMember.getServerAvatarHash())) {
                        UserChangeServerAvatarEvent event =
                                new UserChangeServerAvatarEventImpl(newMember, oldMember);

                        api.getEventDispatcher().dispatchUserChangeServerAvatarEvent(
                                server, server, newMember.getUser(), event);
                    }

                    if (newMember.isPending() != oldMember.isPending()) {
                        UserChangePendingEvent event =
                                new UserChangePendingEventImpl(oldMember, newMember);

                        api.getEventDispatcher().dispatchUserChangePendingEvent(
                                server, server, newMember.getUser(), event);
                    }

                    if (packet.has("roles")) {
                        JsonNode jsonRoles = packet.get("roles");
                        Collection<Role> newRoles = new HashSet<>();
                        Collection<Role> oldRoles = oldMember.getRoles();
                        Collection<Role> intersection = new HashSet<>();
                        for (JsonNode roleIdJson : jsonRoles) {
                            api.getRoleById(roleIdJson.asText())
                                    .map(role -> {
                                        newRoles.add(role);
                                        return role;
                                    })
                                    .filter(oldRoles::contains)
                                    .ifPresent(intersection::add);
                        }

                        // Added roles
                        Collection<Role> addedRoles = new ArrayList<>(newRoles);
                        addedRoles.removeAll(intersection);
                        for (Role role : addedRoles) {
                            if (role.isEveryoneRole()) {
                                continue;
                            }
                            UserRoleAddEvent event = new UserRoleAddEventImpl(role, newMember);

                            api.getEventDispatcher().dispatchUserRoleAddEvent((DispatchQueueSelector) role.getServer(),
                                    role, role.getServer(), newMember.getId(), event);
                        }

                        // Removed roles
                        Collection<Role> removedRoles = new ArrayList<>(oldRoles);
                        removedRoles.removeAll(intersection);
                        for (Role role : removedRoles) {
                            if (role.isEveryoneRole()) {
                                continue;
                            }
                            UserRoleRemoveEvent event = new UserRoleRemoveEventImpl(role, newMember);

                            api.getEventDispatcher().dispatchUserRoleRemoveEvent(
                                    (DispatchQueueSelector) role.getServer(), role, role.getServer(), newMember.getId(),
                                    event);
                        }
                    }

                    if (newMember.getUser().isYourself()) {
                        Set<Long> unreadableChannels = server.getTextChannels().stream()
                                .filter(((Predicate<ServerTextChannel>) ServerTextChannel::canYouSee).negate())
                                .map(ServerTextChannel::getId)
                                .collect(Collectors.toSet());
                        api.forEachCachedMessageWhere(
                                msg -> unreadableChannels.contains(msg.getChannel().getId()),
                                msg -> {
                                    api.removeMessageFromCache(msg.getId());
                                    ((MessageCacheImpl) msg.getChannel().getMessageCache()).removeMessage(msg);
                                }
                        );
                    }

                    // Update base user as well; GUILD_MEMBER_UPDATE is fired for user changes
                    // to allow disabling presences, see
                    // https://github.com/discord/discord-api-docs/pull/1307#issuecomment-581561519
                    if (oldMember.getUser() != null) {
                        UserImpl oldUser = (UserImpl) oldMember.getUser();

                        boolean userChanged = false;
                        UserImpl updatedUser = oldUser.replacePartialUserData(packet.get("user"));

                        if (packet.get("user").has("username")) {
                            String newName = packet.get("user").get("username").asText();
                            String oldName = oldUser.getName();
                            if (!oldName.equals(newName)) {
                                dispatchUserChangeNameEvent(updatedUser, newName, oldName);
                                userChanged = true;
                            }
                        }
                        if (packet.get("user").has("discriminator")) {
                            String newDiscriminator = packet.get("user").get("discriminator").asText();
                            String oldDiscriminator = oldUser.getDiscriminator();
                            if (!oldDiscriminator.equals(newDiscriminator)) {
                                dispatchUserChangeDiscriminatorEvent(updatedUser, newDiscriminator, oldDiscriminator);
                                userChanged = true;
                            }
                        }
                        if (packet.get("user").has("avatar")) {
                            String newAvatarHash = packet.get("user").get("avatar").asText(null);
                            String oldAvatarHash = oldUser.getAvatarHash().orElse(null);
                            if (!Objects.deepEquals(newAvatarHash, oldAvatarHash)) {
                                dispatchUserChangeAvatarEvent(updatedUser, newAvatarHash, oldAvatarHash);
                                userChanged = true;
                            }
                        }

                        if (packet.get("user").has("global_name")) {
                            String newGlobalName = packet.get("user").get("global_name").asText(null);
                            String oldGlobalName = oldUser.getGlobalName().orElse(null);
                            if (!Objects.deepEquals(newGlobalName, oldGlobalName)) {
                                dispatchUserChangeGlobalNameEvent(updatedUser, newGlobalName, oldGlobalName);
                                userChanged = true;
                            }
                        }

                        if (userChanged) {
                            api.updateUserOfAllMembers(updatedUser);
                        }
                    }
                });
    }

    private void dispatchUserChangeNameEvent(User user, String newName, String oldName) {
        UserChangeNameEvent event = new UserChangeNameEventImpl(user, newName, oldName);

        api.getEventDispatcher().dispatchUserChangeNameEvent(
                api,
                user.getMutualServers(),
                Collections.singleton(user),
                event
        );
    }

    private void dispatchUserChangeDiscriminatorEvent(User user, String newDiscriminator, String oldDiscriminator) {
        UserChangeDiscriminatorEvent event =
                new UserChangeDiscriminatorEventImpl(user, newDiscriminator, oldDiscriminator);

        api.getEventDispatcher().dispatchUserChangeDiscriminatorEvent(
                api,
                user.getMutualServers(),
                Collections.singleton(user),
                event
        );
    }

    private void dispatchUserChangeAvatarEvent(User user, String newAvatarHash, String oldAvatarHash) {
        UserChangeAvatarEvent event = new UserChangeAvatarEventImpl(user, newAvatarHash, oldAvatarHash);

        api.getEventDispatcher().dispatchUserChangeAvatarEvent(
                api,
                user.getMutualServers(),
                Collections.singleton(user),
                event
        );
    }

    private void dispatchUserChangeGlobalNameEvent(User user, String newGlobalName, String oldGlobalName) {
        UserChangeGlobalNameEvent event = new UserChangeGlobalNameEventImpl(user, newGlobalName, oldGlobalName);

        api.getEventDispatcher().dispatchUserChangeGlobalNameEvent(
                api,
                user.getMutualServers(),
                Collections.singleton(user),
                event
        );
    }
}
