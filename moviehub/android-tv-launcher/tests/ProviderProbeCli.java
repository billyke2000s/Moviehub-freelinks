package local.tv.home;

import java.nio.file.*;

/**
 * Runs one provider operation step with the app's own interpreter limits (ProviderScript) and
 * prints how long the step took on stderr, for the live provider probe. Not part of the app.
 */
public final class ProviderProbeCli {

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        String out;
        try {
            out = ProviderScript.evaluate(
                Files.readString(Path.of(args[0])),
                args[1],
                Files.readString(Path.of(args[2]))
            );
        } catch (Throwable e) {
            StringBuilder why = new StringBuilder(e.getClass().getSimpleName() + ": " + e.getMessage());
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) why.append(" <- ")
                .append(c.getClass().getSimpleName())
                .append(": ")
                .append(String.valueOf(c.getMessage()).replaceAll("https?://\\S+", "<url>"));
            System.err.println("ERROR " + why.toString().replace('\n', ' '));
            System.err.println("ELAPSED " + (System.nanoTime() - start) / 1_000_000);
            System.exit(2);
            return;
        }
        System.out.print(out);
        System.err.println("ELAPSED " + (System.nanoTime() - start) / 1_000_000);
    }
}
