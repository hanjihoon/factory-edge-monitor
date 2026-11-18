package fem.emulator.mqtt;

import fem.common.DeviceIds;
import fem.common.mqtt.MqttTopics;
import fem.emulator.EmulatorConfig;
import fem.emulator.device.Reading;
import fem.emulator.device.SimulatedDevice;
import fem.emulator.device.TickLoop;
import fem.emulator.fault.FaultInjector;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 공장 A: MQTT 설비 N대.
 *
 * <p>단순화: 설비마다 MQTT 연결을 따로 열지 않고 연결 하나를 공유한다.
 * 브로커 입장에서 보이는 토픽·QoS·페이로드는 설비가 각자 연결한 경우와 같고,
 * 게이트웨이 쪽 동작(구독, 중복·순서 처리)을 검증하는 데에는 차이가 없다.
 * (설계 문서 2장 가정에 적는다)
 */
public final class MqttFleet implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MqttFleet.class);
    private static final int QOS = 1;

    private final EmulatorConfig config;
    private final Map<String, SimulatedDevice> devices = new ConcurrentHashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private MqttAsyncClient client;

    public MqttFleet(EmulatorConfig config, long initialSeq) {
        this.config = config;
        SplittableRandom seeds = new SplittableRandom();
        for (int i = 1; i <= config.mqttDevices(); i++) {
            String id = DeviceIds.of(EmulatorConfig.MQTT_FACTORY, i);
            devices.put(id, new SimulatedDevice(id, initialSeq, config.deviceFaultRate(), seeds.split()));
        }
    }

    public void start() throws MqttException, InterruptedException {
        client = new MqttAsyncClient(config.mqttUrl(), "fem-emulator-" + EmulatorConfig.MQTT_FACTORY, new MemoryPersistence());
        MqttCommandHandler handler = new MqttCommandHandler(EmulatorConfig.MQTT_FACTORY, devices::get, System::currentTimeMillis);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                // cleanSession=true라 재연결하면 구독이 사라진다. 연결될 때마다 다시 구독한다.
                log.info("mqtt connected (reconnect={}) {}", reconnect, serverURI);
                try {
                    client.subscribe(MqttTopics.allCommands(EmulatorConfig.MQTT_FACTORY), QOS,
                            (topic, msg) -> handler.handle(topic, msg.getPayload())
                                    .ifPresent(out -> publish(out.topic(), out.payload())));
                } catch (MqttException e) {
                    log.error("subscribe failed", e);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("mqtt connection lost: {}", cause.toString());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // 구독별 리스너에서 처리한다
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });

        connectWithRetry();
        startDevices();
    }

    private void connectWithRetry() throws InterruptedException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setKeepAliveInterval(30);
        // QoS 1은 브로커 확인(PUBACK)을 받을 때까지 "전송 중"으로 잡힌다. 기본 상한 10이면 초당 600건을 감당 못 한다.
        options.setMaxInflight(10_000);

        // 끊긴 동안 발행한 메시지는 메모리에 잠시 쌓았다가 재연결 후 보낸다 (설비 자체 버퍼라고 가정)
        DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
        buffer.setBufferEnabled(true);
        buffer.setBufferSize(100_000);
        buffer.setDeleteOldestMessages(true);
        client.setBufferOpts(buffer);

        while (true) {
            try {
                client.connect(options).waitForCompletion(10_000);
                return;
            } catch (MqttException e) {
                // 브로커가 아직 안 떴을 수 있다 (compose 기동 순서)
                log.warn("mqtt connect failed ({}), retry in 2s", e.getMessage());
                Thread.sleep(2000);
            }
        }
    }

    private void startDevices() {
        SplittableRandom random = new SplittableRandom();
        for (SimulatedDevice device : devices.values()) {
            FaultInjector<Reading> faults = new FaultInjector<>(
                    config.duplicateRate(), config.reorderRate(), config.delayMaxMs(), random.split());
            TickLoop loop = new TickLoop(device, random.nextInt(1000), reading -> emit(faults.next(reading)));
            threads.add(Thread.ofVirtual().name("mqtt-" + device.deviceId()).start(loop));
        }
        log.info("started {} MQTT devices (dup={}, reorder={}, delay<={}ms)",
                devices.size(), config.duplicateRate(), config.reorderRate(), config.delayMaxMs());
    }

    private void emit(FaultInjector.Plan<Reading> plan) {
        if (plan.delayMs() > 0) {
            try {
                Thread.sleep(plan.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        for (Reading r : plan.messages()) {
            publish(MqttTopics.telemetry(EmulatorConfig.MQTT_FACTORY, r.deviceId()), MqttCodec.telemetry(r));
        }
    }

    private void publish(String topic, byte[] payload) {
        try {
            client.publish(topic, payload, QOS, false);
            published.incrementAndGet();
        } catch (MqttException e) {
            // 실패가 쏟아질 때 로그가 폭주하지 않도록 1,000건에 한 번만 남긴다
            if (failed.incrementAndGet() % 1000 == 1) {
                log.warn("publish failed (total {}): {}", failed.get(), e.getMessage());
            }
        }
    }

    public long publishedCount() {
        return published.get();
    }

    @Override
    public void close() throws MqttException {
        threads.forEach(Thread::interrupt);
        if (client != null && client.isConnected()) {
            client.disconnect().waitForCompletion(2000);
        }
    }
}
