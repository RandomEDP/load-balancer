package loadbalancer;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.PropertiesDefaultProvider;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI entry point. picocli parses the flags (and an optional --config properties
 * file) into option fields, which are assembled into an {@link LbConfig} and
 * handed to the {@link LoadBalancerServer}.
 */
@Command(name = "load-balancer", mixinStandardHelpOptions = true, version = "load-balancer 1.0.0",
        description = "Layer-4 (TCP) load balancer.")
public final class Main implements Callable<Integer> {

    @Option(names = "--host", description = "Listen address (default: ${DEFAULT-VALUE})")
    String host = "0.0.0.0";

    @Option(names = "--port", description = "Listen port, 0 = ephemeral (default: ${DEFAULT-VALUE})")
    int port = 8080;

    @Option(names = "--backends", split = ",", paramLabel = "host:port",
            converter = HostPortConverter.class,
            description = "Comma-separated backends, e.g. 127.0.0.1:9001,127.0.0.1:9002 (required)")
    List<HostPort> backends;

    @Option(names = "--strategy", description = "round-robin | least-connections (default: ${DEFAULT-VALUE})")
    String strategy = "round-robin";

    @Option(names = "--health-interval-ms", description = "Probe interval (default: ${DEFAULT-VALUE})")
    long healthIntervalMs = 2000;

    @Option(names = "--health-timeout-ms", description = "Probe connect timeout (default: ${DEFAULT-VALUE})")
    int healthTimeoutMs = 1000;

    @Option(names = "--health-rise", description = "Successes to mark a backend UP (default: ${DEFAULT-VALUE})")
    int healthRise = 2;

    @Option(names = "--health-fall", description = "Failures to mark a backend DOWN (default: ${DEFAULT-VALUE})")
    int healthFall = 3;

    @Option(names = "--connect-timeout-ms", description = "Backend connect timeout (default: ${DEFAULT-VALUE})")
    int connectTimeoutMs = 2000;

    @Option(names = "--max-connections", description = "Concurrent connection cap (default: ${DEFAULT-VALUE})")
    int maxConnections = 1000;

    @Option(names = "--idle-timeout-ms", description = "Drop idle connections, 0 = off (default: ${DEFAULT-VALUE})")
    int idleTimeoutMs = 300_000;

    @Option(names = "--stats-interval-ms", description = "Stats log cadence, 0 = off (default: ${DEFAULT-VALUE})")
    long statsIntervalMs = 5000;

    @Option(names = "--config", paramLabel = "FILE",
            description = "Properties file of defaults; any CLI flag overrides it.")
    File config;

    @Override
    public Integer call() throws Exception {
        LbConfig cfg = new LbConfig(host, port, backends, strategy,
                healthIntervalMs, healthTimeoutMs, healthRise, healthFall,
                connectTimeoutMs, maxConnections, idleTimeoutMs, statsIntervalMs);

        LoadBalancerServer server = new LoadBalancerServer(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "lb-shutdown-hook"));
        server.start();
        server.awaitShutdown();
        return 0;
    }

    public static void main(String[] args) {
        Main app = new Main();
        CommandLine cmd = new CommandLine(app);
        // If --config is given, load it as the source of defaults. CLI flags still
        // win because an explicitly-supplied option beats a default value.
        String configPath = valueOf(args, "--config");
        if (configPath != null) {
            cmd.setDefaultValueProvider(new PropertiesDefaultProvider(new File(configPath)));
        }
        System.exit(cmd.execute(args));
    }

    private static String valueOf(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return null;
    }

    /** Lets picocli turn each "host:port" token into a {@link HostPort}. */
    static final class HostPortConverter implements ITypeConverter<HostPort> {
        @Override
        public HostPort convert(String value) {
            return HostPort.parse(value);
        }
    }
}
