package fem.gateway;

import fem.gateway.collect.DeviceActivity;
import fem.gateway.collect.ModbusCollector;
import fem.gateway.collect.MqttCollector;
import fem.gateway.command.CommandExecutor;
import fem.gateway.command.CommandService;
import fem.gateway.command.ModbusCommandExecutor;
import fem.gateway.command.MqttCommandExecutor;
import fem.gateway.ingest.IngestWriter;
import fem.gateway.normalize.ModbusNormalizer;
import fem.gateway.normalize.MqttNormalizer;
import fem.gateway.outbox.SqliteOutbox;
import fem.gateway.uplink.Backoff;
import fem.gateway.uplink.Uplink;
import fem.gateway.uplink.WebSocketConnector;
import fem.gateway.wire.WireCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 게이트웨이 조립과 기동.
 *
 * <pre>
 *  수집기(MQTT/Modbus) → IngestWriter → SqliteOutbox → Uplink ⇄ WebSocketConnector ⇄ 서버
 *                                                       │
 *                         CommandService ◀── COMMAND ───┘ ──▶ 설비 (MQTT 발행 / Modbus 쓰기)
 * </pre>
 */
public final class GatewayMain {

    private static final Logger log = LoggerFactory.getLogger(GatewayMain.class);

    public static void main(String[] args) throws Exception {
        GatewayConfig cfg = GatewayConfig.from(System.getenv());
        log.info("starting {}", cfg);

        WireCodec codec = new WireCodec();
        DeviceActivity activity = new DeviceActivity();
        SqliteOutbox outbox = new SqliteOutbox(cfg.bufferPath(), cfg.bufferMaxRows(), codec, System::currentTimeMillis);

        // Uplink ↔ CommandService는 서로를 참조한다 (명령 수신 → 실행 → 결과 전송). 참조 하나를 나중에 채운다.
        AtomicReference<CommandService> commands = new AtomicReference<>();
        Uplink uplink = new Uplink(cfg.gatewayId(), outbox, codec, cmd -> commands.get().handle(cmd),
                System::currentTimeMillis, cfg.batchSize(), cfg.window(), cfg.ackTimeoutMs());
        IngestWriter ingest = new IngestWriter(outbox::append, 50_000, 1000, uplink::wake);

        List<AutoCloseable> closeables = new ArrayList<>();
        CommandExecutor executor;
        CollectorStart startCollector; // 수집 시작은 맨 마지막에 한다 (아래 설명)
        if (cfg.source().equals("mqtt")) {
            MqttCollector collector = new MqttCollector(cfg.mqttUrl(), cfg.gatewayId(), cfg.factoryId(),
                    new MqttNormalizer(cfg.factoryId(), codec), ingest, activity, System::currentTimeMillis);
            MqttCommandExecutor mqttExec = new MqttCommandExecutor(cfg.factoryId(), collector::publish, codec, cfg.commandTimeoutMs());
            collector.onCommandAck(mqttExec::onAck);
            executor = mqttExec;
            closeables.add(collector);
            startCollector = collector::start;
        } else {
            var lines = ModbusCollector.layout(cfg.modbusHost(), cfg.modbusBasePort(), cfg.modbusDevices());
            ModbusCollector collector = new ModbusCollector(lines, new ModbusNormalizer(cfg.factoryId(), cfg.modbusBasePort()),
                    ingest, activity, System::currentTimeMillis, cfg.modbusPollMs());
            executor = new ModbusCommandExecutor(cfg.factoryId(), lines);
            closeables.add(collector);
            startCollector = collector::start;
        }
        CommandService commandService = new CommandService(executor, uplink::sendIfConnected, System::currentTimeMillis);
        commands.set(commandService);
        closeables.add(commandService);

        // 시작 순서: 디스크 기록기 → 서버 전송·상태 보고 → 수집.
        // MQTT 수집 시작은 브로커에 붙을 때까지 기다린다(무한 재시도). 수집을 먼저 시작하면
        // 브로커가 죽은 채로 게이트웨이가 재시작됐을 때 전송 스레드가 뜨지 않아서
        //  - 디스크에 남아 있던 미전송분이 브로커가 살아날 때까지 서버로 안 가고
        //  - 서버에는 "게이트웨이 끊김"으로만 보여 회선 장애와 구분이 안 된다.
        // 전송을 먼저 띄우면 서버는 "연결됨, 활성 설비 0"을 받아 공장 내부 문제임을 알 수 있다.
        startThread("ingest", ingest);
        startThread("uplink", uplink);
        startThread("connector", new WebSocketConnector(cfg.serverUri(), cfg.gatewayId(), cfg.token(), uplink,
                new Backoff(500, 30_000, new SplittableRandom())));
        startThread("status", new StatusReporter(cfg.gatewayId(), outbox, activity, uplink, System::currentTimeMillis));

        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            for (AutoCloseable c : closeables) {
                try {
                    c.close();
                } catch (Exception e) {
                    log.warn("close failed: {}", e.toString());
                }
            }
            // 디스크 버퍼는 닫기만 한다. 남은 데이터는 다음 기동 때 이어서 보낸다
            try {
                outbox.close();
            } catch (Exception e) {
                log.warn("outbox close failed: {}", e.toString());
            }
            done.countDown();
        }));
        // 종료 훅을 먼저 걸어 두고 수집을 시작한다. 브로커를 기다리는 중에 종료 신호가 와도 버퍼를 닫고 끝난다.
        startCollector.start();
        done.await();
    }

    /** MQTT·Modbus 수집기의 start()를 같은 모양으로 다루기 위한 함수형 인터페이스 (MQTT 쪽은 예외를 던진다). */
    @FunctionalInterface
    private interface CollectorStart {
        void start() throws Exception;
    }

    private static void startThread(String name, Runnable r) {
        Thread.ofVirtual().name(name).start(r);
    }
}
