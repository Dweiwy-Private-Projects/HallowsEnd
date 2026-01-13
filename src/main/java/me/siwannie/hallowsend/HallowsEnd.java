package me.siwannie.hallowsend;

import me.siwannie.hallowsend.commands.EventAdminCommand;
import me.siwannie.hallowsend.config.ConfigManager;
import me.siwannie.hallowsend.config.MessageManager;
import me.siwannie.hallowsend.expansion.EventPlaceholders;
import me.siwannie.hallowsend.game.GameManager;
import me.siwannie.hallowsend.listeners.PlayerConnectionListener;
import me.siwannie.hallowsend.listeners.PlayerMoveListener;
import me.siwannie.hallowsend.listeners.PuzzleListener;
import me.siwannie.hallowsend.listeners.WorldInteractionListener;
import me.siwannie.hallowsend.modules.loot.*;
import me.siwannie.hallowsend.modules.mobs.MobManager;
import me.siwannie.hallowsend.modules.ritual.RitualManager;
import me.siwannie.hallowsend.modules.sanity.SanityManager;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class HallowsEnd extends JavaPlugin implements Listener {

    private static HallowsEnd instance;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private GameManager gameManager;
    private SanityManager sanityManager;
    private RitualManager ritualManager;
    private MobManager mobManager;
    private LootManager lootManager;
    private LootChestManager lootChestManager;
    private DeathChestManager deathChestManager;
    private CustomItemManager customItemManager;
    private PuzzleListener puzzleListener;
    private PlayerMoveListener playerMoveListener;

    @Override
    public void onEnable() {
        instance = this;

        setupManagers();

        getCommand("hallowsend").setExecutor(new EventAdminCommand(this));

        getServer().getPluginManager().registerEvents(this, this);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldInteractionListener(this), this);

        this.playerMoveListener = new PlayerMoveListener(this);
        getServer().getPluginManager().registerEvents(this.playerMoveListener, this);

        getServer().getPluginManager().registerEvents(this.lootChestManager, this);
        getServer().getPluginManager().registerEvents(this.deathChestManager, this);
        this.puzzleListener = new PuzzleListener(this);
        getServer().getPluginManager().registerEvents(this.puzzleListener, this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new EventPlaceholders(this).register();
            getLogger().info("Successfully hooked into PlaceholderAPI.");
        } else {
            getLogger().warning("PlaceholderAPI not found. Placeholders will not work.");
        }

        getLogger().info("Hallow's End has been enabled! Waiting for server to finish loading...");
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        getLogger().info("Server has finished loading. Initializing Hallow's End data...");
        this.lootChestManager.loadLootChests();
    }

    public void setupManagers() {
        configManager = new ConfigManager(this);
        messageManager = new MessageManager(configManager);
        customItemManager = new CustomItemManager(this);
        sanityManager = new SanityManager(this);
        ritualManager = new RitualManager(this);
        mobManager = new MobManager(this);
        lootManager = new LootManager(this);
        lootChestManager = new LootChestManager(this);
        gameManager = new GameManager(this);
        deathChestManager = new DeathChestManager(this);

    }

    @Override
    public void onDisable() {
        if (gameManager != null && gameManager.isGameRunning()) {
            gameManager.stopGame(true);
        }
        getLogger().info("Hallow's End has been disabled.");
    }

    public static HallowsEnd getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public GameManager getGameManager() { return gameManager; }
    public SanityManager getSanityManager() { return sanityManager; }
    public RitualManager getRitualManager() { return ritualManager; }
    public MobManager getMobManager() { return mobManager; }
    public LootManager getLootManager() { return lootManager; }
    public LootChestManager getLootChestManager() { return lootChestManager; }
    public DeathChestManager getDeathChestManager() { return deathChestManager; }
    public PuzzleListener getPuzzleListener() { return puzzleListener; }
    public PlayerMoveListener getPlayerMoveListener() { return playerMoveListener;}
    public CustomItemManager getCustomItemManager() { return customItemManager; }
}

