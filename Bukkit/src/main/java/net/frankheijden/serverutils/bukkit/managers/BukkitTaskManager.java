package net.frankheijden.serverutils.bukkit.managers;

import net.frankheijden.serverutils.bukkit.ServerUtils;
import net.frankheijden.serverutils.common.managers.AbstractTaskManager;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

public class BukkitTaskManager extends AbstractTaskManager<BukkitTask> {

    private static final boolean FOLIA;

    static {
        boolean isFolia;

        try {
            Bukkit.class.getDeclaredMethod("getAsyncScheduler");
            isFolia = true;
        } catch (NoSuchMethodException e) {
            isFolia = false;
        }

        FOLIA = isFolia;
    }

    public BukkitTaskManager() {
        super(BukkitTask::cancel);
    }

    @Override
    protected BukkitTask runTaskImpl(Runnable runnable) {
        if (FOLIA) {
            return new ScheduledTaskWrapper(Bukkit.getGlobalRegionScheduler().run(ServerUtils.getInstance(), ignored -> runnable.run()));
        }
        return Bukkit.getScheduler().runTask(ServerUtils.getInstance(), runnable);
    }

    @Override
    public BukkitTask runTaskLater(Runnable runnable, long delay) {
        if (FOLIA) {
            return new ScheduledTaskWrapper(Bukkit.getGlobalRegionScheduler().runDelayed(ServerUtils.getInstance(), ignored -> runnable.run(), delay));
        }
        return Bukkit.getScheduler().runTaskLater(ServerUtils.getInstance(), runnable, delay);
    }

    @Override
    protected BukkitTask runTaskAsynchronouslyImpl(Runnable runnable) {
        if (FOLIA) {
            return new ScheduledTaskWrapper(Bukkit.getAsyncScheduler().runNow(ServerUtils.getInstance(), ignored -> runnable.run()));
        }
        return Bukkit.getScheduler().runTaskAsynchronously(ServerUtils.getInstance(), runnable);
    }

    @Override
    public void cancelTask(BukkitTask task) {
        task.cancel();
    }
}
