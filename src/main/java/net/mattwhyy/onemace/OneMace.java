    /*
     * ============================================================
     * AI CODE CHATGPT CODEX
     * ============================================================
     */

package net.mattwhyy.onemace;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class OneMace extends JavaPlugin implements Listener {

    /*
     * ============================================================
     * MULTI-MACE SETTINGS
     * ============================================================
     */

    private int maceCount;
    private int maxMaces;

    private boolean configDirty;

    /*
     * Used to prevent the same destroyed item being counted twice
     * by multiple Paper/Bukkit destruction events.
     */
    private final Set<UUID> pendingDestroyedMaces = new HashSet<>();

    private final NamespacedKey maceKey =
            new NamespacedKey(this, "mace-tracker");

    /*
     * ============================================================
     * CONTAINER SETTINGS
     * ============================================================
     */

    private final Set<String> mandatoryContainers = Set.of(
            "PLAYER",
            "CRAFTING",
            "WORKBENCH",
            "CREATIVE"
    );

    private final Set<String> allowedContainers = new HashSet<>();

    /*
     * ============================================================
     * ENABLE / DISABLE
     * ============================================================
     */

    @Override
    public void onEnable() {

        saveDefaultConfig();

        updateConfig();
        loadAllowedContainers();

        maxMaces = Math.max(
                1,
                getConfig().getInt("settings.max-maces", 5)
        );

        /*
         * Migration from the old boolean system.
         */
        if (!getConfig().contains("settings.mace-count")) {

            boolean oldMaceCrafted =
                    getConfig().getBoolean(
                            "settings.mace-crafted",
                            false
                    );

            maceCount = oldMaceCrafted ? 1 : 0;

            getConfig().set(
                    "settings.mace-count",
                    maceCount
            );

            markConfigDirty();

        } else {

            maceCount = Math.max(
                    0,
                    getConfig().getInt(
                            "settings.mace-count",
                            0
                    )
            );
        }

        /*
         * Prevent an invalid config from having a value above max.
         */
        if (maceCount > maxMaces) {
            maceCount = maxMaces;
            saveMaceCount();
        }

        Bukkit.getPluginManager()
                .registerEvents(this, this);

        OneMaceCommand command =
                new OneMaceCommand(this);

        Objects.requireNonNull(
                getCommand("onemace"),
                "onemace command is missing from plugin.yml"
        ).setExecutor(command);

        Objects.requireNonNull(
                getCommand("onemace"),
                "onemace command is missing from plugin.yml"
        ).setTabCompleter(command);

        /*
         * Wait for worlds and players to finish loading.
         */
        Bukkit.getScheduler().runTaskLater(
                this,
                () -> {

                    if (maceCount >= maxMaces) {

                        removeAllMaceRecipes();

                        getLogger().info(
                                "[OneMace] Mace limit reached: "
                                        + maceCount
                                        + "/"
                                        + maxMaces
                                        + ". Recipes removed."
                        );

                    } else {

                        ensureMaceRecipeAvailable();

                        getLogger().info(
                                "[OneMace] Maces: "
                                        + maceCount
                                        + "/"
                                        + maxMaces
                                        + ". Crafting available."
                        );
                    }

                },
                40L
        );

        getLogger().info(
                "[OneMace] Plugin enabled! Mace limit: "
                        + maxMaces
        );
    }

    @Override
    public void onDisable() {

        getLogger().info(
                "[OneMace] Saving offline Mace tracking before shutdown..."
        );

        for (Player player : Bukkit.getOnlinePlayers()) {

            getConfig().set(
                    "offline_inventory."
                            + player.getUniqueId(),
                    playerHasMace(player)
            );
        }

        getConfig().set(
                "settings.mace-count",
                maceCount
        );

        saveConfig();

        getLogger().info(
                "[OneMace] Plugin disabled!"
        );
    }

    /*
     * ============================================================
     * CONFIGURATION
     * ============================================================
     */

    public void markConfigDirty() {

        if (configDirty) {
            return;
        }

        configDirty = true;

        Bukkit.getScheduler().runTaskLater(
                this,
                () -> {

                    saveConfig();
                    configDirty = false;

                },
                40L
        );
    }

    private void saveMaceCount() {

        getConfig().set(
                "settings.mace-count",
                maceCount
        );

        markConfigDirty();
    }

    public void updateConfig() {

        boolean changed = false;

        if (!getConfig().contains(
                "settings.max-maces"
        )) {

            getConfig().set(
                    "settings.max-maces",
                    5
            );

            changed = true;
        }

        if (!getConfig().contains(
                "messages.crafted"
        )) {

            getConfig().set(
                    "messages.crafted",
                    "&b[OneMace] &eThe Mace has been crafted!"
            );

            changed = true;
        }

        if (!getConfig().contains(
                "messages.lost"
        )) {

            getConfig().set(
                    "messages.lost",
                    "&b[OneMace] &eA Mace has been lost!"
            );

            changed = true;
        }

        if (!getConfig().contains(
                "settings.allow-locate-for-all"
        )) {

            getConfig().set(
                    "settings.allow-locate-for-all",
                    false
            );

            changed = true;
        }

        if (!getConfig().contains(
                "settings.colored-name"
        )) {

            getConfig().set(
                    "settings.colored-name",
                    false
            );

            changed = true;
        }

        if (!getConfig().contains(
                "settings.mace-name-color"
        )) {

            getConfig().set(
                    "settings.mace-name-color",
                    "RED"
            );

            changed = true;
        }

        if (!getConfig().contains(
                "settings.optional-allowed-containers"
        )) {

            getConfig().set(
                    "settings.optional-allowed-containers",
                    Arrays.asList(
                            "ENDER_CHEST",
                            "ANVIL",
                            "ENCHANTING"
                    )
            );

            changed = true;
        }

        if (changed) {
            markConfigDirty();
        }
    }

    /*
     * ============================================================
     * CONTAINERS
     * ============================================================
     */

    public void loadAllowedContainers() {

        allowedContainers.clear();

        allowedContainers.addAll(
                mandatoryContainers
        );

        List<String> containerNames =
                getConfig().getStringList(
                        "settings.optional-allowed-containers"
                );

        for (String name : containerNames) {

            String normalized =
                    name.toUpperCase(Locale.ROOT);

            if (
                    isKnownContainerName(normalized)
                            || normalized.equals("SHELF")
            ) {

                allowedContainers.add(
                        normalized
                );

            } else {

                getLogger().warning(
                        "[OneMace] Invalid optional inventory type in config: "
                                + name
                );
            }
        }
    }

    private boolean isKnownContainerName(
            String name
    ) {

        try {

            InventoryType.valueOf(name);

            return true;

        } catch (IllegalArgumentException e) {

            return false;
        }
    }

    private boolean isAllowedContainer(
            InventoryType type
    ) {

        return type != null
                && allowedContainers.contains(
                type.name()
        );
    }

    private boolean isAllowedContainerName(
            String name
    ) {

        return allowedContainers.contains(
                name
        );
    }

    /*
     * ============================================================
     * MACE IDENTIFICATION
     * ============================================================
     */

    public boolean isMace(ItemStack item) {

        return MaceStorageUtil.isMace(item);
    }

    public boolean containsMace(
            ItemStack item
    ) {

        return MaceStorageUtil.containsMace(
                item
        );
    }

    public boolean containsMace(
            Inventory inventory
    ) {

        return MaceStorageUtil.containsMace(
                inventory
        );
    }

    /*
     * Kept for compatibility with OneMaceCommand.
     *
     * In the multi-mace version this means:
     * "Has the configured limit been reached?"
     */
    public boolean isMaceCrafted() {

        return maceCount >= maxMaces;
    }

    public int getMaceCount() {

        return maceCount;
    }

    public int getMaxMaces() {

        return maxMaces;
    }

    public int getRemainingMaces() {

        return Math.max(
                0,
                maxMaces - maceCount
        );
    }

    public boolean canCraftAnotherMace() {

        return maceCount < maxMaces;
    }

    /*
     * ============================================================
     * OFFLINE STORAGE
     * ============================================================
     */

    public boolean hasOfflineMaceRecord() {

        if (
                !getConfig().isConfigurationSection(
                        "offline_inventory"
                )
        ) {

            return false;
        }

        for (
                String uuid :
                Objects.requireNonNull(
                        getConfig()
                                .getConfigurationSection(
                                        "offline_inventory"
                                )
                ).getKeys(false)
        ) {

            if (
                    getConfig().getBoolean(
                            "offline_inventory."
                                    + uuid,
                            false
                    )
            ) {

                return true;
            }
        }

        return false;
    }

    /*
     * ============================================================
     * MACE TRACKING TAG
       ITS AI CODE CHATGPT CODEX
     * ============================================================
     */

    public void markMace(
            ItemStack mace
    ) {

        if (!isMace(mace)) {
            return;
        }

        ItemMeta meta =
                mace.getItemMeta();

        if (meta == null) {
            return;
        }

        PersistentDataContainer data =
                meta.getPersistentDataContainer();

        data.set(
                maceKey,
                PersistentDataType.STRING,
                "true"
        );

        mace.setItemMeta(meta);
    }

    /*
     * ============================================================
     * 
