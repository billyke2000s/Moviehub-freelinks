package local.tv.home;

import java.nio.file.*;

public final class ProviderRuntimeCli {

    public static void main(String[] args) throws Exception {
        System.out.print(
            ProviderScript.evaluate(
                Files.readString(Path.of(args[0])),
                args[1],
                Files.readString(Path.of(args[2]))
            )
        );
    }
}
