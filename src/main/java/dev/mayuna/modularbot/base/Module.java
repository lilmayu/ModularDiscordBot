package dev.mayuna.modularbot.base;

import com.jagrosh.jdautilities.command.CommandClientBuilder;
import dev.mayuna.consoleparallax.ConsoleParallax;
import dev.mayuna.mayusjdautils.MayusJDAUtilities;
import dev.mayuna.modularbot.concurrent.ModuleScheduler;
import dev.mayuna.modularbot.util.logging.ModularBotLogger;
import dev.mayuna.modularbot.objects.ModuleConfig;
import dev.mayuna.modularbot.objects.ModuleInfo;
import dev.mayuna.modularbot.objects.ModuleStatus;
import dev.mayuna.modularbot.objects.activity.ModuleActivities;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;

@Getter
@Setter
public abstract class Module {

    private final ModuleActivities moduleActivities = new ModuleActivities(this);

    private ModuleInfo moduleInfo;
    private ModuleStatus moduleStatus;
    private ModuleConfig moduleConfig;
    private ModularBotLogger logger;
    private MayusJDAUtilities mayusJDAUtilities;
    private ModuleScheduler moduleScheduler;

    /**
     * This method is called when the module is loaded
     */
    public void onLoad() {
        // Empty
    }

    /**
     * This method is called when the module is enabling
     */
    public abstract void onEnable();

    /**
     * This method is called when the module is disabling
     */
    public abstract void onDisable();

    /**
     * This method is called when the module is unloaded
     */
    public void onUnload() {
        // Empty
    }

    /**
     * This method is called when the JDA Utilities' {@link CommandClientBuilder} is initializing. You cna register commands here and more.
     *
     * @param commandClientBuilder Non-null {@link CommandClientBuilder}
     */
    public void onCommandClientBuilderInitialization(@NonNull CommandClientBuilder commandClientBuilder) {
        // Empty
    }

    /**
     * This method is called when the JDA is initializing. You can register events here and more.
     *
     * @param shardManagerBuilder Non-null {@link DefaultShardManagerBuilder}
     */
    public void onShardManagerBuilderInitialization(@NonNull DefaultShardManagerBuilder shardManagerBuilder) {
        // Empty
    }

    /**
     * This method is called when Modular Bot is registering console commands
     *
     * @param consoleParallax Non-null {@link ConsoleParallax}
     */
    public void onConsoleCommandRegistration(@NonNull ConsoleParallax consoleParallax) {
    }

    /**
     * This method is called when some exception is uncaught
     *
     * @param throwable Non-null {@link Throwable}
     */
    public void onUncaughtException(@NonNull Throwable throwable) {
        // Empty
    }
}
