# Layer-4 TCP Load Balancer

A software load balancer that works at layer 4 (TCP). It listens on one port,
forwards the raw byte stream of each connection to one of several backends, drops
backends that go offline, and adds them back when they recover.

Built on **Netty** for the networking core, with **picocli** for the CLI and
**SLF4J/Logback** for logging. Java 17, built with Maven.

## Requirements

| Requirement | Where it's met |
|---|---|
| Operate at layer 4 (TCP) | `FrontendHandler` / `BackendHandler` relay raw bytes without parsing the protocol |
| Accept traffic from many clients | `LoadBalancerServer` — Netty NIO event loop multiplexing connections, accept backlog, `--max-connections` cap |
| Balance traffic across multiple backends | `BackendPool.select()` with `RoundRobinStrategy` / `LeastConnectionsStrategy`, over healthy backends only |
| Remove a service when it goes offline | `HealthChecker` (active probes, rise/fall) plus passive failover in `FrontendHandler.connectWithFailover()` |
| No cloud services | pure local JVM + libraries; nothing external |

`LoadBalancerIntegrationTest` exercises the three core behaviours end to end
against a real Netty server: even distribution, removal of an offline backend,
and re-admission on recovery. Unit tests cover the supporting logic in isolation
— the `Backend` rise/fall health state machine, both balancing strategies
(including round-robin's counter wraparound), `BackendPool` health filtering,
`HostPort` parsing, and `LbConfig` validation.

## What it does

- **Handles many clients at once.** Netty's NIO event loop multiplexes all
  connections over a small pool of threads; a `Semaphore` caps how many
  connections are served concurrently (`--max-connections`).
- **Spreads traffic across backends.** `round-robin` (default) or
  `least-connections`, always choosing from the healthy backends only.
- **Removes backends that go offline.** Active TCP health checks with rise/fall
  thresholds drop a backend after N failed probes and restore it after M
  successes. A connect failure on live traffic also counts against health and
  triggers immediate failover, so a backend that dies between probes doesn't
  black-hole anything.

## Why layer 4

A layer-4 balancer works at the transport layer: it moves TCP bytes between
client and backend without understanding the application protocol. That keeps it
protocol-agnostic (HTTP, gRPC, Redis, raw TCP) and cheap. The cost is that it
can't make per-request routing decisions.

One consequence worth calling out: an L4 balancer balances *connections*, not
*requests*. Once a connection is assigned to a backend, every byte on it goes to
that backend for the connection's lifetime. With HTTP keep-alive many requests
share one connection and hit one backend. That's expected. (The demo opens a
fresh connection per request so the balancing is actually visible.)

## Architecture

```
                  ┌──────────────────────── LoadBalancerServer (Netty) ─────────────────────┐
   clients        │                                                                          │
  ───────────►  NioServerSocketChannel (accept)  ──►  per-connection pipeline:               │
   (TCP)          │                                     Semaphore cap                         │
                  │                                     FrontendHandler ──┐                   │
                  │                                       ├─ BackendPool.select()             │
                  │                                       │    └─ BalancingStrategy            │
                  │                                       │       (round-robin /              │
                  │                                       │        least-connections)         │
                  │                                       │   (healthy backend, fails over)   │
                  │                                       └─ relay client->backend ───────────┼──► backend
                  │                                          BackendHandler relays back        │
                  │                                          (NIO, half-close aware)           │
                  │                                                                            │
                  │   HealthChecker ── periodic TCP probes ──►  marks backends UP/DOWN ────────┘
                  └──────────────────────────────────────────────────────────────────────────┘
```

### `src/main/java/loadbalancer/`

- `Main` is the picocli entry point: parses flags (and an optional `--config`
  properties file) into an `LbConfig`.
- `LoadBalancerServer` configures and runs the Netty server (boss + worker event
  loop groups), runs the initial probe, schedules health checks and stats, and
  handles graceful shutdown.
- `FrontendHandler` is the client-facing half of each relay: selects a backend,
  connects with failover, then relays client->backend bytes. It handles TCP
  half-close so a client that finishes sending doesn't truncate its response.
- `BackendHandler` is the backend->client half of the relay.
- `BackendPool` plus `BalancingStrategy` (`RoundRobinStrategy`,
  `LeastConnectionsStrategy`) handle selection over the healthy backends.
- `HealthChecker` runs the active TCP-connect probes on an interval, with
  rise/fall thresholds. Probes run off the event loops so a slow probe can't
  stall traffic.
- `Backend` is a host/port plus live state (healthy flag, active/total counts).
- `LbConfig` is the immutable settings record; `HostPort` parses `host:port`.

### `src/main/java/demo/`

- `TestBackend` is a small HTTP server that reports its own id, handy for poking
  with curl or a browser.

## Build and run

Needs a JDK 17+. Maven is provided via the wrapper (`./mvnw`), so no system
Maven install is required.

```bash
# Build a runnable fat jar at target/load-balancer.jar (runs the tests too)
./mvnw clean package        # Windows: .\mvnw.cmd clean package

# Run the balancer
java -jar target/load-balancer.jar --backends 127.0.0.1:9001,127.0.0.1:9002,127.0.0.1:9003
java -jar target/load-balancer.jar --config lb.conf
java -jar target/load-balancer.jar --port 8080 --backends 127.0.0.1:9001,127.0.0.1:9002 --strategy least-connections
java -jar target/load-balancer.jar --help

# Run the integration test on its own
./mvnw test
```

Quick manual demo (three backends + the balancer):

```bash
java -cp target/load-balancer.jar demo.TestBackend 9001 B1
java -cp target/load-balancer.jar demo.TestBackend 9002 B2
java -cp target/load-balancer.jar demo.TestBackend 9003 B3
java -jar target/load-balancer.jar --config lb.conf
# then, in another shell:
curl http://127.0.0.1:8080/      # repeat; watch it cycle B1 -> B2 -> B3
```

`Ctrl+C` shuts the balancer down gracefully (a shutdown hook stops the health
checks and shuts down the Netty event loops).

## Configuration

Settings come from CLI flags, optionally defaulted from a `--config` properties
file (any flag overrides the file). Run `java -jar target/load-balancer.jar --help`
for the full list. See [`lb.conf`](lb.conf).

| Flag | Config key | Default | Meaning |
|---|---|---|---|
| `--host` | `host` | `0.0.0.0` | listen address |
| `--port` | `port` | `8080` | listen port (`0` = ephemeral) |
| `--backends` | `backends` | *(required)* | `host:port,host:port,…` |
| `--strategy` | `strategy` | `round-robin` | `round-robin` / `least-connections` |
| `--health-interval-ms` | `health-interval-ms` | `2000` | probe interval |
| `--health-timeout-ms` | `health-timeout-ms` | `1000` | probe connect timeout |
| `--health-rise` | `health-rise` | `2` | successes to mark a backend UP |
| `--health-fall` | `health-fall` | `3` | failures to mark a backend DOWN |
| `--connect-timeout-ms` | `connect-timeout-ms` | `2000` | backend connect timeout for live traffic |
| `--max-connections` | `max-connections` | `1000` | concurrent connection cap |
| `--idle-timeout-ms` | `idle-timeout-ms` | `300000` | drop idle connections; `0` = off |
| `--stats-interval-ms` | `stats-interval-ms` | `5000` | stats log cadence; `0` = off |

## Design notes

- **NIO event loop (Netty), not thread-per-connection.** A small set of worker
  threads multiplexes every connection, so the balancer handles a large number of
  concurrent connections without a thread per socket. Each direction of the relay
  reads the next chunk only once the previous write has flushed, which gives
  natural backpressure; the `Semaphore` adds a hard cap and rejects over it rather
  than blocking an event-loop thread (you must never block a Netty event loop).
- **TCP half-close handling.** `ALLOW_HALF_CLOSURE` is on, so a client that
  finishes sending (sends FIN) doesn't tear the connection down; the balancer
  half-closes the backend's input instead and keeps relaying the response back.
  Without this a half-closing client would truncate its own response.
- **Active and passive health detection.** Periodic probes catch backends that die
  quietly; a connect failure on real traffic fails over immediately and counts
  against health. Rise/fall thresholds keep a transient blip from flapping a
  backend in and out.
- **Backends start DOWN** and are admitted by a synchronous probe at startup, so
  traffic only goes to a backend we've confirmed reachable.

## Known limitations

- No TLS termination (deliberate, it's pure L4 pass-through).
- No connection draining on removal: existing connections to a now-unhealthy
  backend run to completion rather than being force-closed.
- Health checks are TCP-connect only. An app-level check (e.g. an HTTP `/health`
  probe) would catch a process that accepts connections but is broken.
- Backend list is static (config/CLI). Dynamic add/remove via an admin endpoint
  or service discovery would be the next step.
- `least-connections` counts connections, not real load. Weighted or
  latency-aware strategies would do better.
