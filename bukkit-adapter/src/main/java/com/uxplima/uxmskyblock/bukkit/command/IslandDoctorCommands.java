package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.health.HealthCheck;
import com.uxplima.uxmlib.health.HealthReport;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * {@code /is doctor}: how the plugin is, one line per thing that can be wrong.
 *
 * <p>The checks read the database, so the report is put together off the thread the command arrived
 * on, and each line finds its way back to whoever asked.
 */
public final class IslandDoctorCommands {

    private final Supplier<List<HealthCheck>> checks;
    private final SchedulerPort schedulerPort;
    private final Messages messages;

    public IslandDoctorCommands(Supplier<List<HealthCheck>> checks, SchedulerPort schedulerPort, Messages messages) {
        this.checks = Objects.requireNonNull(checks, "checks must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildDoctor() {
        return Cmd.literal("doctor")
                .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_DOCTOR.node())
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().isOp())
                .executes(this::executeDoctor);
    }

    private int executeDoctor(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        send(sender, "doctor.header");
        schedulerPort.async(() -> {
            HealthReport report = HealthReport.run(checks.get());
            for (HealthReport.Entry entry : report.entries()) {
                String key =
                        switch (entry.result().status()) {
                            case OK -> "doctor.ok";
                            case WARN -> "doctor.warn";
                            case FAIL -> "doctor.fail";
                        };
                send(
                        sender,
                        key,
                        Placeholder.unparsed("check", entry.name()),
                        Placeholder.unparsed("said", entry.result().message()));
            }
            send(sender, report.hasFailure() ? "doctor.unwell" : "doctor.well");
        });
        return Cmd.OK;
    }

    private void send(
            Audience audience, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        var line = messages.render(audience, key, resolvers);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        } else {
            audience.sendMessage(line);
        }
    }
}
