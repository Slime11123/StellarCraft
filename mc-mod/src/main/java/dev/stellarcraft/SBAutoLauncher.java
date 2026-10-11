package dev.stellarcraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Auto-launches Stellar Blade when Minecraft starts, so the bridge "just works"
 * without the player having to start both games by hand.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>On client init we fire a background thread that checks whether Stellar Blade is
 *       already running (by scanning for its window/exe name). If it is, we do nothing.</li>
 *   <li>If it isn't, we spawn {@code SB-Win64-Shipping.exe} via {@link ProcessBuilder}.
 *       UE4SS (the {@code dwmapi.dll} sitting next to the exe) loads automatically, which
 *       in turn loads {@code stellarcraft_sb.dll} and creates the shared-memory mapping.</li>
 *   <li>The existing {@code ensureConnected()} tick loop then notices the mapping come up
 *       and wires the bridge as usual — no race conditions, no duplicate launches.</li>
 * </ul>
 *
 * <p>The SB exe path is read from {@code config/stellarcraft.properties} (key
 * {@code sbExePath}) and falls back to a list of common Steam install locations. You can
 * also disable auto-launch with {@code autoLaunchSb=false}.
 */
public final class SBAutoLauncher {
    private SBAutoLauncher() {}

    private static final String PROP_FILE = "config/stellarcraft.properties";

    // Common install roots, tried in order if no explicit path is configured.
    private static final String[] DEFAULT_CANDIDATES = {
        "D:\\SteamLibrary\\steamapps\\common\\StellarBlade\\SB\\Binaries\\Win64\\SB-Win64-Shipping.exe",
        "C:\\Program Files (x86)\\Steam\\steamapps\\common\\StellarBlade\\SB\\Binaries\\Win64\\SB-Win64-Shipping.exe",
        "C:\\Program Files\\Steam\\steamapps\\common\\StellarBlade\\SB\\Binaries\\Win64\\SB-Win64-Shipping.exe",
        "E:\\SteamLibrary\\steamapps\\common\\StellarBlade\\SB\\Binaries\\Win64\\SB-Win64-Shipping.exe",
    };

    private static volatile boolean launchAttempted = false;

    /** Call once from client init. Non-blocking; returns immediately. */
    public static void maybeLaunch() {
        if (launchAttempted) return;
        launchAttempted = true;

        Thread t = new Thread(SBAutoLauncher::run, "StellarCraft-SB-Launcher");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            // Small delay so we don't race the game's own boot.
            Thread.sleep(2000);

            if (isSbRunning()) {
                log("Stellar Blade already running; skipping launch.");
                return;
            }

            String exe = resolveExePath();
            if (exe == null) {
                log("Stellar Blade exe not found and no sbExePath configured; "
                        + "set sbExePath in config/stellarcraft.properties to enable auto-launch.");
                return;
            }

            log("Launching Stellar Blade: " + exe);
            ProcessBuilder pb = new ProcessBuilder(exe);
            pb.directory(Paths.get(exe).getParent().toFile());
            // Recognized Steam installs need their launcher to establish the game session.
            Path steam=Paths.get("C:/Program Files (x86)/Steam/steam.exe");
            Path ancestor=Paths.get(exe).getParent();
            for(int depth=0;depth<6&&ancestor!=null;depth++,ancestor=ancestor.getParent()) {
                if(Files.isRegularFile(ancestor.resolve("appmanifest_3489700.acf"))&&Files.isRegularFile(steam)) {
                    pb=new ProcessBuilder(steam.toString(),"-applaunch","3489700");
                    pb.directory(steam.getParent().toFile());log("Starting recognized Stellar Blade install through Steam.");break;
                }
            }
            pb.redirectErrorStream(true);
            pb.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log("Failed to launch Stellar Blade: " + e.getMessage());
        } catch (Throwable t) {
            log("Unexpected error in SB auto-launcher: " + t);
        }
    }

    /** Resolve the exe path: explicit config first, then common install roots. */
    private static String resolveExePath() {
        // 1. Config file.
        String fromCfg = readConfigKey("sbExePath");
        if (fromCfg != null && !fromCfg.isEmpty() && Files.isRegularFile(Paths.get(fromCfg))) {
            return fromCfg;
        }
        // 2. Default candidates.
        for (String c : DEFAULT_CANDIDATES) {
            if (Files.isRegularFile(Paths.get(c))) return c;
        }
        return null;
    }

    /** Reads a simple key=value property file; returns null if missing/unreadable. */
    private static String readConfigKey(String key) {
        try {
            Path p = Paths.get(PROP_FILE);
            if (!Files.isRegularFile(p)) return null;
            for (String line : Files.readAllLines(p)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                if (line.substring(0, eq).trim().equalsIgnoreCase(key)) {
                    return line.substring(eq + 1).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** True if Stellar Blade appears to be running. */
    private static boolean isSbRunning() {
        // Tasklist lookup for the Shipping exe; cheap and cross-JDK.
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq SB-Win64-Shipping.exe")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.contains("SB-Win64-Shipping.exe");
        } catch (Exception e) {
            return false;
        }
    }

    private static void log(String msg) {
        System.out.println("[StellarCraft] " + msg);
    }
}
