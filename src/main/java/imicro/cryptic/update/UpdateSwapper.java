package imicro.cryptic.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Puts a downloaded Cryptic in place of the running one, once the game has
 * closed.
 *
 * Run as a separate little Java process by the auto updater as the game shuts
 * down, from a copy of this class outside the mod jar: Windows will not let a
 * jar be deleted while a program has it open, and the game has the old one
 * open until it exits. Plain Java and nothing else, because none of Minecraft
 * is loaded here.
 *
 * The old jar is moved aside before the new one goes in, and put back if the
 * new one cannot be copied: two Cryptic jars in the mods folder stop Fabric
 * starting, and none means no Cryptic, so neither is ever left behind.
 *
 * Arguments: the game's process id, the running jar, the downloaded jar, and
 * a log file.
 */
public final class UpdateSwapper {
	private static final int ATTEMPTS = 120;
	private static final long RETRY_MILLIS = 500;

	private static Path log;

	public static void main(String[] args) throws Exception {
		long pid = Long.parseLong(args[0]);
		Path oldJar = Path.of(args[1]);
		Path newJar = Path.of(args[2]);
		log = Path.of(args[3]);

		say("Waiting for the game (" + pid + ") to close");
		ProcessHandle.of(pid).ifPresent(game -> {
			try {
				game.onExit().get(10, TimeUnit.MINUTES);
			} catch (Exception ignored) {
				// Gone or taking too long: try anyway; a locked jar just fails below.
			}
		});

		Path backup = newJar.resolveSibling(oldJar.getFileName() + ".old");
		Path target = oldJar.resolveSibling(newJar.getFileName());

		// The jar can stay locked for a moment after the game has gone.
		boolean movedAside = false;
		for (int attempt = 0; attempt < ATTEMPTS && !movedAside; attempt++) {
			try {
				Files.move(oldJar, backup, StandardCopyOption.REPLACE_EXISTING);
				movedAside = true;
			} catch (IOException error) {
				if (attempt == ATTEMPTS - 1) {
					say("Could not move the old jar aside, leaving it as it is: " + error);
					return;
				}
				Thread.sleep(RETRY_MILLIS);
			}
		}

		try {
			Files.copy(newJar, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException error) {
			say("Could not copy the new jar in, putting the old one back: " + error);
			Files.move(backup, oldJar, StandardCopyOption.REPLACE_EXISTING);
			return;
		}

		Files.deleteIfExists(backup);
		Files.deleteIfExists(newJar);
		say("Installed " + target.getFileName() + " in place of " + oldJar.getFileName());
	}

	private static void say(String message) {
		try {
			Files.writeString(log, Instant.now() + " " + message + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Nowhere left to say it.
		}
	}

	private UpdateSwapper() {}
}
