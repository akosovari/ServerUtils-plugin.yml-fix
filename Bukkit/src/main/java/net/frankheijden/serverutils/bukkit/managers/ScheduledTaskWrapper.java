package net.frankheijden.serverutils.bukkit.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

public record ScheduledTaskWrapper(@NotNull ScheduledTask task) implements BukkitTask {

    @Override
    public int getTaskId() { // can't implement this
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Plugin getOwner() {
        return task.getOwningPlugin();
    }

    @Override
    public boolean isSync() { // can't implement this
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCancelled() {
        return task.isCancelled();
    }

    @Override
    public void cancel() {
        task.cancel();
    }
}
