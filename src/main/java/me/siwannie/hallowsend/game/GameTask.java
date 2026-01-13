package me.siwannie.hallowsend.game;

import org.bukkit.scheduler.BukkitRunnable;

public class GameTask extends BukkitRunnable {

    private final GameManager gameManager;

    public GameTask(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public void run() {
        if (!gameManager.isGameRunning()) {
            this.cancel();
            return;
        }

        gameManager.tick();
    }
}