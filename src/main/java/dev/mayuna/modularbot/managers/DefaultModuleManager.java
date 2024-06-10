package dev.mayuna.modularbot.managers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mayuna.mayusjdautils.MayusJDAUtilities;
import dev.mayuna.modularbot.ModularBot;
import dev.mayuna.modularbot.ModularBotConstants;
import dev.mayuna.modularbot.base.Module;
import dev.mayuna.modularbot.base.ModuleManager;
import dev.mayuna.modularbot.classloaders.ModuleClassLoader;
import dev.mayuna.modularbot.concurrent.ModuleScheduler;
import dev.mayuna.modularbot.objects.ModuleConfig;
import dev.mayuna.modularbot.objects.ModuleInfo;
import dev.mayuna.modularbot.objects.ModuleStatus;
import dev.mayuna.modularbot.util.InputStreamUtils;
import dev.mayuna.modularbot.util.logging.ModularBotLogger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public final class DefaultModuleManager implements ModuleManager {

    private final static ModularBotLogger LOGGER = ModularBotLogger.create("ModuleManager");
    private final static PathMatcher JAR_FILE_PATH_MATCHER = FileSystems.getDefault().getPathMatcher("glob:*.jar");

    private final List<ModuleClassLoader> moduleClassLoaders = Collections.synchronizedList(new LinkedList<>());
    private List<Module> modules = createModuleList();

    /**
     * Creates empty list for modules
     *
     * @return List of {@link Module}
     */
    private List<Module> createModuleList() {
        return Collections.synchronizedList(new LinkedList<>());
    }

    @Override
    public ModularBotLogger getLogger() {
        return LOGGER;
    }

    @Override
    public List<Module> getModules() {
        return modules;
    }

    @Override
    public boolean loadModules() {
        LOGGER.mdebug("Loading modules...");

        if (!modules.isEmpty()) {
            LOGGER.mdebug("Some modules are loaded - unloading them...");
            unloadModules();
        }

        Path modulesDirectory = ModularBotConstants.PATH_FOLDER_MODULES;

        if (!Files.exists(modulesDirectory)) {
            try {
                Files.createDirectories(modulesDirectory);
            } catch (IOException exception) {
                LOGGER.error("Failed to create modules directory!");
                return false;
            }

            LOGGER.mdebug("The modules directory was just created, there won't be any modules.");
            return true;
        }

        List<Path> moduleFiles;

        try (Stream<Path> paths = Files.walk(modulesDirectory)) {
            moduleFiles = paths
                    .filter(Files::isRegularFile) // Only files
                    .filter(path -> path.getFileName().toString().endsWith(".jar")) // Only jar files
                    .toList();
        } catch (IOException exception) {
            LOGGER.error("Failed to list files in modules directory!", exception);
            return false;
        }

        for (Path moduleFile : moduleFiles) {
            LOGGER.mdebug("Loading module: {}", moduleFile.getFileName());
            Optional<Module> optionalModule = loadModuleFile(moduleFile);

            // Could not load module, error logged
            if (optionalModule.isEmpty()) {
                continue;
            }

            Module module = optionalModule.get();

            // Load the module
            loadModule(module);
        }

        return true;
    }

    /**
     * Loads {@link Module} from specified {@link Path}
     *
     * @param moduleFile {@link Path} to the module file
     *
     * @return Optional of {@link Module}
     */
    private Optional<Module> loadModuleFile(Path moduleFile) {
        ModuleClassLoader moduleClassLoader;

        try {
            moduleClassLoader = new ModuleClassLoader(moduleFile, DefaultModuleManager.class.getClassLoader(), moduleClassLoaders);
        } catch (MalformedURLException exception) {
            LOGGER.error("Failed to create class loader for module: " + moduleFile.getFileName(), exception);
            return Optional.empty();
        }

        try (ZipFile zipFile = new ZipFile(moduleFile.toFile())) {
            InputStream moduleInfoInputStream = InputStreamUtils.openFileAsInputStream(zipFile, ModularBotConstants.FILE_NAME_MODULE_INFO);

            if (moduleInfoInputStream == null) {
                LOGGER.warn("Module {} does not contain module_info.json! However, it will be loaded in classpath.", moduleFile.getFileName());
                return Optional.empty();
            }

            String moduleInfoFileContent = InputStreamUtils.readStreamAsString(moduleInfoInputStream);
            ModuleInfo moduleInfo = ModuleInfo.loadFromJsonObject(JsonParser.parseString(moduleInfoFileContent).getAsJsonObject());

            JsonObject defaultConfig;
            InputStream defaultConfigStream = InputStreamUtils.openFileAsInputStream(zipFile, ModularBotConstants.FILE_NAME_MODULE_CONFIG);

            if (defaultConfigStream != null) {
                String defaultConfigFileContent = InputStreamUtils.readStreamAsString(defaultConfigStream);

                defaultConfig = JsonParser.parseString(defaultConfigFileContent).getAsJsonObject();
            } else {
                LOGGER.warn("Module {} does not have default config.", moduleInfo.getName());
                defaultConfig = new JsonObject();
            }

            Module module = (Module) moduleClassLoader.loadClass(moduleInfo.getMainClass()).getConstructor().newInstance();
            module.setModuleInfo(moduleInfo);
            module.setModuleStatus(ModuleStatus.NOT_LOADED);
            module.setModuleConfig(new ModuleConfig(module, defaultConfig));
            module.setModuleScheduler(new ModuleScheduler(module));
            module.setLogger(ModularBotLogger.create(module.getModuleInfo().getName()));

            var mayusJdaUtilities = new MayusJDAUtilities();
            mayusJdaUtilities.copyFrom(ModularBot.getBaseMayusJDAUtilities());
            module.setMayusJDAUtilities(mayusJdaUtilities);

            // Add the module's class loader to the list of class loaders
            synchronized (moduleClassLoaders) {
                moduleClassLoaders.add(moduleClassLoader);
            }

            // Copy default config, if empty
            module.getModuleConfig().copyDefaultsIfEmpty();

            return Optional.of(module);
        } catch (IOException exception) {
            LOGGER.error("Failed to read module: " + moduleFile.getFileName(), exception);
        } catch (ClassNotFoundException exception) {
            LOGGER.error("Could not find main class for module: " + moduleFile.getFileName(), exception);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException exception) {
            LOGGER.error("Could not create module instance for module: " + moduleFile.getFileName() + " (does the main class have public no-args constructor?)", exception);
        }

        return Optional.empty();
    }

    @Override
    public void loadModule(Module module) {
        String moduleName = module.getModuleInfo().getName();

        if (module.getModuleStatus() != ModuleStatus.NOT_LOADED) {
            LOGGER.warn("Tried loading module {}, which does not have status of NOT_LOADED!", moduleName);
            return;
        }

        LOGGER.mdebug("Loading module {}...", moduleName);
        module.setModuleStatus(ModuleStatus.LOADING);

        try {
            module.onLoad();
        } catch (Exception exception) {
            LOGGER.error("Exception occurred while loading module " + moduleName + "!", exception);
            module.setModuleStatus(ModuleStatus.FAILED);

            // Remove the module's class loader from the list of class loaders
            synchronized (moduleClassLoaders) {
                moduleClassLoaders.remove((ModuleClassLoader) module.getClass().getClassLoader());
            }
            return;
        }

        LOGGER.mdebug("Module {} loaded successfully.", moduleName);
        module.setModuleStatus(ModuleStatus.LOADED);
        modules.add(module);
    }

    @Override
    public void enableModules() {
        LOGGER.mdebug("Enabling {} modules...", modules.size());

        modules.forEach(module -> {
            try {
                enableModule(module);
            } catch (StackOverflowError stackOverflowError) {
                String moduleName = module.getModuleInfo().getName();
                String depend = Arrays.toString(module.getModuleInfo().getDepend());
                String softDepend = Arrays.toString(module.getModuleInfo().getSoftDepend());

                LOGGER.error("StackOverflowError occurred while loading module {}! It depends on {}, soft-depends on {}", moduleName, depend, softDepend);
                unloadModule(module);
            }
        });

        LOGGER.mdebug("Unloading modules that failed to enable, if any...");
        modules.forEach(module -> {
            if (module.getModuleStatus() != ModuleStatus.ENABLED) {
                unloadModule(module);
            }
        });

        LOGGER.info("Enabled");
    }

    @Override
    public void enableModule(Module module) {
        if (module.getModuleStatus() == ModuleStatus.ENABLED) {
            return;
        }

        ModuleInfo moduleInfo = module.getModuleInfo();

        // Depend
        for (String dependentName : moduleInfo.getDepend()) {
            Optional<Module> optionalDependentModule = getModuleByName(dependentName);

            if (optionalDependentModule.isEmpty()) {
                LOGGER.error("Module {} specified {} as dependent but the module is not loaded!", moduleInfo.getName(), dependentName);
                return;
            }

            enableModule(optionalDependentModule.get());
        }

        // Soft-depend
        for (String dependentModule : moduleInfo.getSoftDepend()) {
            Optional<Module> optionalDependentModule = getModuleByName(dependentModule);

            if (optionalDependentModule.isEmpty()) {
                LOGGER.warn("Module {} specified {} as soft-dependent but the module is not loaded.", moduleInfo.getName(), dependentModule);
                continue;
            }

            enableModule(optionalDependentModule.get());
        }

        LOGGER.mdebug("Enabling module {}...", moduleInfo.getName());
        module.setModuleStatus(ModuleStatus.ENABLING);

        try {
            module.onEnable();
        } catch (Exception exception) {
            LOGGER.error("Failed to enable module " + moduleInfo.getName() + "!", exception);
            unloadModule(module);
            return;
        }

        LOGGER.mdebug("Module {} enabled successfully.", moduleInfo.getName());
        module.setModuleStatus(ModuleStatus.ENABLED);
    }

    @Override
    public void unloadModules() {
        if (modules.isEmpty()) {
            return;
        }

        // Copy modules to temporary field
        List<Module> oldModules = modules;
        modules = createModuleList();

        oldModules.forEach(this::unloadModule);

        LOGGER.success("Unloaded {} modules successfully.", oldModules.size());
    }

    @Override
    public void unloadModule(Module module) {
        String moduleName = module.getModuleInfo().getName();

        switch (module.getModuleStatus()) {
            case NOT_LOADED -> {
                LOGGER.warn("Tried unloading module ({}) which is not loaded!", moduleName);
            }
            case LOADED, ENABLING, DISABLED -> {
                LOGGER.mdebug("Unloading module {}...", moduleName);
                module.setModuleStatus(ModuleStatus.UNLOADING);

                try {
                    module.onUnload();
                } catch (Exception unloadException) {
                    LOGGER.error("Exception occurred while unloading module " + moduleName + "!", unloadException);
                }

                modules.remove(module);

                synchronized (moduleClassLoaders) {
                    moduleClassLoaders.remove((ModuleClassLoader) module.getClass().getClassLoader());
                }

                module.setModuleStatus(ModuleStatus.NOT_LOADED);

                LOGGER.mdebug("Module {} unloaded successfully.", moduleName);
            }
            case ENABLED -> {
                LOGGER.mdebug("Disabling module {}...", moduleName);
                module.setModuleStatus(ModuleStatus.DISABLING);

                try {
                    module.onDisable();
                    module.getModuleScheduler().cancelTasks();
                } catch (Exception disableException) {
                    LOGGER.error("Exception occurred while disabling module " + moduleName + "!", disableException);
                }

                module.setModuleStatus(ModuleStatus.DISABLED);
                LOGGER.mdebug("Module {} disabled successfully.", moduleName);

                unloadModule(module);
            }
        }
    }
}
