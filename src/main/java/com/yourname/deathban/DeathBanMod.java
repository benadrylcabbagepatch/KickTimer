// DeathBanMod.java
package com.yourname.deathban;

import com.mojang.datafixers.util.Pair;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class DeathBanMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("kicktimer");
    private static final String CONFIG_FILE = "config/kicktimer.properties";
    private static final String BAN_FILE = "bans/kicktimer_bans.dat";
    
    private static Map<UUID, Long> banList = new HashMap<>();
    private static int tickCounter = 0;
    private static Config config;

    @Override
    public void onInitialize() {
        loadConfig();
        
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			loadBans();
			cleanUpExpiredBans();
			LOGGER.info("Loaded {} active bans", banList.size());
		});

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveBans());

		// Add this death event listener
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayerEntity player) {
				if (!shouldBypass(player)) {
					banPlayer(player);
					player.networkHandler.disconnect(Text.of(
						"You died! Come back in " + 
						formatTime(config.getTotalBanMillis())
					));
				}
			}
		});

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			if (player == null) return; // Add null check
			if (!shouldBypass(player)) {
				checkBanStatus(player);
			}
		});

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (tickCounter++ >= 1200) {
                cleanUpExpiredBans();
                tickCounter = 0;
            }
        });
    }

    private void loadConfig() {
        try {
            Path configPath = Path.of(CONFIG_FILE);
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    Properties defaults = new Properties();
                    defaults.setProperty("banDurationHours", "24");
                    defaults.setProperty("banDurationMinutes", "0");
                    defaults.setProperty("excludeOps", "true");
                    defaults.store(out, "Kick Timer Configuration");
                }
            }
            
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            }
            
            config = new Config(
                Integer.parseInt(props.getProperty("banDurationHours", "24")),
                Integer.parseInt(props.getProperty("banDurationMinutes", "0")),
                Boolean.parseBoolean(props.getProperty("excludeOps", "true"))
            );
            
        } catch (Exception e) {
            LOGGER.error("Error loading config, using defaults", e);
            config = new Config(24, 0, true);
        }
    }

    private boolean shouldBypass(ServerPlayerEntity player) {
        return config.excludeOps() && player.hasPermissionLevel(4);
    }

    private void banPlayer(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        banList.put(uuid, System.currentTimeMillis());
        saveBans();
        LOGGER.info("Banned player {} for {}h {}m", 
            player.getName().getString(), 
            config.banDurationHours(), 
            config.banDurationMinutes()
        );
    }

    private void checkBanStatus(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        if (banList.containsKey(uuid)) {
            long banDuration = config.getTotalBanMillis();
            long remaining = (banList.get(uuid) + banDuration) - System.currentTimeMillis();
            
            if (remaining > 0) {
                player.networkHandler.disconnect(Text.of(
                    "You died! Come back in " + formatTime(remaining)
                ));
            } else {
                banList.remove(uuid);
                saveBans();
            }
        }
    }
	private String formatTime(long millis) {
		long seconds = millis / 1000;
		long minutes = seconds / 60;
		long hours = minutes / 60;
		minutes %= 60;
    
		if (hours > 0) {
			return String.format("%d hours %d minutes", hours, minutes);
		} else if (minutes > 0) {
			return String.format("%d minutes", minutes);
		} else {
			return String.format("%d seconds", seconds % 60);
		}
	}

	private void cleanUpExpiredBans() {
		long now = System.currentTimeMillis();
		banList.entrySet().removeIf(entry -> 
			(entry.getValue() + config.getTotalBanMillis()) <= now
		);
	}

	private void loadBans() {
		Path banPath = Path.of(BAN_FILE);
		try {
			if (Files.exists(banPath)) {
				Files.createDirectories(banPath.getParent());
				try (DataInputStream in = new DataInputStream(Files.newInputStream(banPath))) {
					int size = in.readInt();
					banList = new HashMap<>();
					for (int i = 0; i < size; i++) {
						UUID uuid = new UUID(in.readLong(), in.readLong());
						long banTime = in.readLong();
						banList.put(uuid, banTime);
					}
				}
			}
		} catch (IOException e) {
			LOGGER.error("Error loading bans", e);
			banList = new HashMap<>();
		}
	}

	private void saveBans() {
		Path banPath = Path.of(BAN_FILE);
		try {
			Files.createDirectories(banPath.getParent());
			try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(banPath))) {
				out.writeInt(banList.size());
				for (Map.Entry<UUID, Long> entry : banList.entrySet()) {
					UUID uuid = entry.getKey();
					out.writeLong(uuid.getMostSignificantBits());
					out.writeLong(uuid.getLeastSignificantBits());
					out.writeLong(entry.getValue());
				}
			}
		} catch (IOException e) {
			LOGGER.error("Error saving bans", e);
		}
	}
    // Other methods (formatTime, cleanUpExpiredBans, loadBans, saveBans) remain similar
    // Add getTotalBanMillis() to Config record
}

record Config(int banDurationHours, int banDurationMinutes, boolean excludeOps) {
    long getTotalBanMillis() {
        return (banDurationHours * 3600L + banDurationMinutes * 60L) * 1000L;
    }
}