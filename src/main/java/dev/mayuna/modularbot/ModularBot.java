package dev.mayuna.modularbot;

import dev.mayuna.consoleparallax.ConsoleParallax;
import dev.mayuna.mayusjdautils.MayusJDAUtilities;
import dev.mayuna.mayuslibrary.exceptionreporting.UncaughtExceptionReporter;
import dev.mayuna.modularbot.base.Module;
import dev.mayuna.modularbot.base.ModuleManager;
import dev.mayuna.modularbot.config.ModularBotConfig;
import dev.mayuna.modularbot.console.ModularBotOutputHandler;
import dev.mayuna.modularbot.console.ModularConsoleCommand;
import dev.mayuna.modularbot.console.StopConsoleCommand;
import dev.mayuna.modularbot.managers.DefaultModuleManager;
import dev.mayuna.modularbot.managers.ModularBotDataManager;
import dev.mayuna.modularbot.util.logging.ModularBotLogger;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.concurrent.Executors;

public final class ModularBot {

    private static final ModularBotLogger LOGGER = ModularBotLogger.create(ModularBot.class);

    private static final List<Module> internalModules = new ArrayList<>();

    private static @Getter ModularBotConfig config;
    private static @Getter ConsoleParallax consoleParallax;
    private static @Getter MayusJDAUtilities baseMayusJDAUtilities;
    private static @Getter ModuleManager moduleManager;
    private static @Getter ModularBotDataManager modularBotDataManager;
    private static @Getter ModularBotShardManager modularBotShardManager;

    private static @Getter @Setter boolean shouldHaltJVM = true;
    private static @Getter @Setter boolean running;
    private static @Getter @Setter boolean stopping;

    private ModularBot() {
    }

    public static void start(String[] args) {
        LOGGER.info("Starting ModularDiscordBot @ {}", ModularBotConstants.getVersion());
        LOGGER.info("Made by Mayuna");

        LOGGER.mdebug("Java Runtime Information:");
        LOGGER.mdebug("Java Version: {}", System.getProperty("java.version"));
        LOGGER.mdebug("Java Vendor: {}", System.getProperty("java.vendor"));
        LOGGER.mdebug("Java VM Name: {}", System.getProperty("java.vm.name"));
        LOGGER.mdebug("Java VM Version: {}", System.getProperty("java.vm.version"));
        LOGGER.mdebug("Java VM Vendor: {}", System.getProperty("java.vm.vendor"));

        LOGGER.info("""
                            \s
                            \033[0;35m  __  __         _      _            ___  _                   _   ___      _  \s
                            \033[0;35m |  \\/  |___  __| |_  _| |__ _ _ _  |   \\(_)___ __ ___ _ _ __| | | _ ) ___| |_\s
                            \033[0;35m | |\\/| / _ \\/ _` | || | / _` | '_| | |) | (_-</ _/ _ \\ '_/ _` | | _ \\/ _ \\  _|
                            \033[0;35m |_|  |_\\___/\\__,_|\\_,_|_\\__,_|_|   |___/|_/__/\\__\\___/_| \\__,_| |___/\\___/\\__|\033[0m
                            """);

        LOGGER.info("Loading...");
        final long startMillis = System.currentTimeMillis();

        LOGGER.info("Phase 1/6 - Loading core...");

        LOGGER.mdebug("Loading configuration");
        loadConfiguration();

        LOGGER.mdebug("Loading ConsoleParallax");
        loadConsoleParallax();

        LOGGER.mdebug("Registering Shutdown hook");
        registerShutdownHook();

        LOGGER.mdebug("Registering UncaughtExceptionReporter");
        registerUncaughtExceptionReporter();

        LOGGER.mdebug("Loading Mayu's JDA Utilities");
        loadMayusJDAUtilities();

        LOGGER.mdebug("Preparing ModuleManager");
        prepareModuleManager();

        LOGGER.info("Phase 2/6 - Loading modules...");
        loadModules();

        LOGGER.info("Phase 3/6 - Loading DataManager...");
        loadDataManager();

        LOGGER.info("Phase 4/6 - Enabling modules...");
        enableModules();

        LOGGER.info("Phase 5/6 - Preparing JDA...");

        LOGGER.mdebug("Creating ModularBotShardManager...");
        createModularBotShardManager();

        LOGGER.mdebug("Initializing modules...");
        initializeModules();

        LOGGER.mdebug("Finishing ModularBotShardManager...");
        finishModularBotShardManager();

        LOGGER.info("Phase 6/6 - Connecting to Discord...");
        connectToDiscord();

        LOGGER.success("Successfully started ModularDiscordBot (took {}ms)", (System.currentTimeMillis() - startMillis));
        running = true;

        LOGGER.info("Initializing Presence Activity Cycle...");
        initializePresenceActivityCycle();
    }

    /**
     * Loads configuration
     */
    private static void loadConfiguration() {
        config = ModularBotConfig.load();

        // Failed to load
        if (config == null) {
            shutdown();
        }
    }

    /**
     * Loads ConsoleParallax
     */
    private static void loadConsoleParallax() {
        consoleParallax = ConsoleParallax.builder()
                                         .setOutputHandler(new ModularBotOutputHandler())
                                         .setCommandExecutor(Executors.newCachedThreadPool())
                                         .build();

        consoleParallax.registerDefaultHelpCommand();
        consoleParallax.registerCommand(new ModularConsoleCommand());
        consoleParallax.registerCommand(new StopConsoleCommand());
        consoleParallax.start();
    }

    /**
     * Registers JVM Shutdown hook
     */
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!stopping) {
                LOGGER.warn("Modular Discord Bot has not stopped gracefully! Please, use command 'stop' to stop the application. There is a chance that the modules won't be unloaded fully before JVM termination.");

                LOGGER.info("Shutting down Modular Discord Bot...");
                shutdown();
            }
        }));
    }

    /**
     * Registers exception reporter
     */
    private static void registerUncaughtExceptionReporter() {
        UncaughtExceptionReporter.register();
        UncaughtExceptionReporter.addExceptionReportConsumer(exceptionReport -> {
            LOGGER.warn("Uncaught exception occurred! Sending to modules...", exceptionReport.getThrowable());
            moduleManager.processException(exceptionReport.getThrowable());
        });
    }

    /**
     * Loads {@link MayusJDAUtilities}
     */
    private static void loadMayusJDAUtilities() {
        baseMayusJDAUtilities = new MayusJDAUtilities();
        baseMayusJDAUtilities.setMessageInfoStyles(new ModularBotStyles(baseMayusJDAUtilities));
    }

    /**
     * Prepares module manager
     */
    private static void prepareModuleManager() {
        moduleManager = new DefaultModuleManager();

        if (!internalModules.isEmpty()) {
            LOGGER.info("Adding {} internal modules...", internalModules.size());
            moduleManager.addInternalModules(internalModules.toArray(new Module[0]));
        }
    }

    /**
     * Loads modules
     */
    private static void loadModules() {
        if (!moduleManager.loadModules()) {
            shutdown();
        }
    }

    /**
     * Loads DataManager
     */
    private static void loadDataManager() {
        modularBotDataManager = new ModularBotDataManager(config.getStorageSettings());

        LOGGER.mdebug("Preparing DataManager...");
        modularBotDataManager.prepareStorage();

        LOGGER.mdebug("Preparing GlobalDataHolder...");
        modularBotDataManager.getGlobalDataHolder();
    }

    /**
     * Enables modules
     */
    private static void enableModules() {
        moduleManager.enableModules();
    }

    /**
     * Initializes Discord stuff such as CommandClientBuilder, etc.
     */
    private static void initializeModules() {
        LOGGER.mdebug("Processing ConsoleParallax...");
        moduleManager.processConsoleParallax(consoleParallax);

        LOGGER.mdebug("Processing CommandClientBuilder...");
        moduleManager.processCommandClientBuilder(modularBotShardManager.getCommandClientBuilder());

        LOGGER.mdebug("Processing ShardManagerBuilder...");
        moduleManager.processShardBuilder(modularBotShardManager.getShardManagerBuilder());
    }

    /**
     * Creates ShardManager
     */
    private static void createModularBotShardManager() {
        modularBotShardManager = new ModularBotShardManager(config.getDiscord());

        LOGGER.mdebug("Initializing ModularBotShardManager...");
        if (!modularBotShardManager.init()) {
            shutdown();
        }
    }

    /**
     * Builds shard manager
     */
    private static void finishModularBotShardManager() {
        if (!modularBotShardManager.finish()) {
            shutdown();
        }
    }

    /**
     * Connects to Discord
     */
    private static void connectToDiscord() {
        if (!modularBotShardManager.connect()) {
            shutdown();
        }
    }

    /**
     * Initializes Presence Activity Cycle
     */
    private static void initializePresenceActivityCycle() {
        modularBotShardManager.initPresenceActivityCycle();
    }

    /**
     * Shutdowns ModularDiscordBot
     */
    public static void shutdown() {
        stopping = true;

        LOGGER.info("Shutting down ModularDiscordBot @ {}", ModularBotConstants.getVersion());

        internalModules.clear();

        LOGGER.info("Shutting down ConsoleParallax...");
        consoleParallax.interrupt();

        LOGGER.info("Unloading modules...");
        moduleManager.unloadModules();

        LOGGER.info("Disconnecting from Discord...");
        if (modularBotShardManager != null) {
            modularBotShardManager.shutdown();
        }

        LOGGER.success("Shutdown completed");

        if (shouldHaltJVM) {
            LOGGER.info("Halting JVM...");
            Runtime.getRuntime().halt(0);
        }
    }

    /**
     * Adds internal module. Added modules will be loaded upon starting the ModularBot. If it's already started, it will be loaded immediately.
     * @param modules Modules to add
     */
    public static void addInternalModules(@NonNull Module... modules) {
        if (running) {
            moduleManager.addInternalModules(modules);
            return;
        }

        var listModules = List.of(modules);
        LOGGER.info("Adding {} internal modules", listModules.size());
        internalModules.addAll(listModules);
    }
}
