package dev.mayuna.modularbot.console.commands.basic;

import dev.mayuna.modularbot.ModularBot;
import dev.mayuna.modularbot.console.commands.generic.AbstractConsoleCommand;
import dev.mayuna.modularbot.console.commands.generic.CommandResult;

public class StopConsoleCommand extends AbstractConsoleCommand {

    public StopConsoleCommand() {
        this.name = "stop";
        this.syntax = "";
    }

    @Override
    public CommandResult execute(String arguments) {
        ModularBot.shutdownGracefully();
        return CommandResult.SUCCESS;
    }
}
