package com.supermobtracker.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityLiving;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.IClientCommand;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.drops.DropSimulator;
import com.supermobtracker.drops.DropSimulator.ProfileResult;
import com.supermobtracker.drops.LootDump;
import com.supermobtracker.drops.LootDump.DumpWriteResult;
import com.supermobtracker.integration.jei.JEIIntegration;


/**
 * Creates the local loot data set consumed by the Super Mob Tracker JEI category.
 */
public class CommandLootDump extends CommandBase implements IClientCommand {
    @Override
    @Nonnull
    public String getName() {
        return "smtlootdump";
    }

    @Override
    @Nonnull
    public String getUsage(@Nonnull ICommandSender sender) {
        return I18n.format("command.supermobtracker.lootdump.usage");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean allowUsageWithoutPrefix(ICommandSender sender, String message) {
        return false;
    }

    @Override
    @Nonnull
    public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
            String[] args, BlockPos targetPos) {
        return Collections.emptyList();
    }

    @Override
    public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length > 1) throw new CommandException(getUsage(sender));

        int simulationCount = args.length == 1
            ? parseInt(args[0], 100, 100000)
            : ModConfig.clientDropSimulationCount;

        if (DropSimulator.isMultiplayer()) {
            sendMessage(sender, TextFormatting.RED, "command.supermobtracker.lootdump.remote_server");
            return;
        }

        sendMessage(sender, TextFormatting.YELLOW, "command.supermobtracker.lootdump.start",
            simulationCount);
        new Thread(() -> runDump(sender, simulationCount), "SMT-LootDump").start();
    }

    private void runDump(ICommandSender sender, int simulationCount) {
        List<ResourceLocation> entityIds = new ArrayList<>();
        int excludedCount = 0;

        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            ResourceLocation entityId = entry.getRegistryName();
            if (entityId == null) continue;
            if (!EntityLiving.class.isAssignableFrom(entry.getEntityClass())) continue;
            if (ModConfig.isGuiAndLootExcludedEntity(entityId.toString())) {
                excludedCount++;
                continue;
            }

            entityIds.add(entityId);
        }
        entityIds.sort(Comparator.comparing(ResourceLocation::toString));

        Map<ResourceLocation, DropSimulator.DropSimulationResult> results = new LinkedHashMap<>();
        int failedCount = 0;

        try {
            for (int index = 0; index < entityIds.size(); index++) {
                ResourceLocation entityId = entityIds.get(index);
                if ((index + 1) % 50 == 0) {
                    sendMessage(sender, TextFormatting.YELLOW, "command.supermobtracker.lootdump.progress",
                        index + 1, entityIds.size());
                }

                try {
                    ProfileResult result = DropSimulator.profileEntity(entityId, simulationCount);
                    if (result.status == ProfileResult.Status.SUCCESS && result.result != null && result.hasDrops()) {
                        results.put(entityId, result.result);
                    } else if (result.status != ProfileResult.Status.NO_DROPS) {
                        failedCount++;
                    }
                } catch (Throwable error) {
                    failedCount++;
                    SuperMobTracker.LOGGER.warn("Could not dump loot for {}", entityId, error);
                }
            }

            DumpWriteResult writeResult = LootDump.write(results, simulationCount);
            if (Loader.isModLoaded("jei")) {
                Minecraft.getMinecraft().addScheduledTask(JEIIntegration::refreshMobLootRecipes);
            }

            sendMessage(sender, TextFormatting.GREEN, "command.supermobtracker.lootdump.complete",
                writeResult.mobCount, writeResult.uniqueItemCount, writeResult.dropTypeCount);

            if (failedCount > 0) {
                sendMessage(sender, TextFormatting.YELLOW, "command.supermobtracker.lootdump.skipped",
                    failedCount);
            }

            if (excludedCount > 0) {
                sendMessage(sender, TextFormatting.YELLOW, "command.supermobtracker.lootdump.excluded",
                    excludedCount);
            }

            sendMessage(sender, TextFormatting.AQUA, "command.supermobtracker.lootdump.saved",
                writeResult.file.getAbsolutePath());
        } catch (Exception error) {
            SuperMobTracker.LOGGER.error("Failed to write mob loot dump", error);
            sendMessage(sender, TextFormatting.RED, "command.supermobtracker.lootdump.failed",
                error.getMessage());
        } finally {
            DropSimulator.clearProfileCache();
        }
    }

    private static void sendMessage(ICommandSender sender, TextFormatting color, String translationKey,
            Object... parameters) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            TextComponentTranslation text = new TextComponentTranslation(translationKey, parameters);
            text.getStyle().setColor(color);

            TextComponentString prefix = new TextComponentString("[SMT] ");
            prefix.getStyle().setColor(color);
            sender.sendMessage(prefix.appendSibling(text));
        });
    }
}
