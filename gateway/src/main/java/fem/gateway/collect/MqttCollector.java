package fem.gateway.collect;

import fem.common.TelemetryRecord;
import fem.common.mqtt.MqttTopics;
import fem.gateway.ingest.RecordSink;
import fem.gateway.normalize.MqttNormalizer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * 공장 A 브로커 구독. 텔레메트리 → 정규화 → 디스크, 명령 응답 → 명령 실행기.
 *
 * <h2>브로커 → 디스크 구간 유실 방지</h2>
 * <ul>
 *   <li><b>수동 ack</b>: 디스크 커밋 뒤에 PUBACK을 보낸다. 그전에 죽으면 브로커가 다시 보낸다.</li>
 *   <li><b>영속 세션</b>(cleanSession=false): 게이트웨이가 꺼져 있는 동안 온 QoS 1 메시지를 브로커가 쌓아 둔다.</li>
 * </ul>
 */
public final class MqttCollector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MqttCollector.class);
    private static final int QOS = 1;

    private final String url;
    private final String clientId;
    private final String factoryId;
    private final MqttNormalizer normalizer;
    private final RecordSink sink;
    private final DeviceActivity activity;
    private final LongSupplier clock;
    /** command/ack 토픽 메시지를 받을 곳 (명령 실행기). 인자: 토픽, 페이로드 */
    private volatile BiConsumer<String, byte[]> commandAckHandler = (t, p) -> {};
    private MqttAsyncClient client;

    public MqttCollector(String url, String gatewayId, String factoryId, MqttNormalizer normalizer,
                         RecordSink sink, DeviceActivity activity, LongSupplier clock) {
        this.url = url;
        this.clientId = "fem-gateway-" + gatewayId; // 영속 세션은 clientId로 찾으므로 고정값이어야 한다
        this.factoryId = factoryId;
        this.normalizer = normalizer;
        this.sink = sink;
        this.activity = activity;
        this.clock = clock;
    }

    public void onCommandAck(BiConsumer<String, byte[]> handler) {
        this.commandAckHandler = handler;
    }

    public void start() throws MqttException, InterruptedException {
        client = new MqttAsyncClient(url, clientId, new MemoryPersistence());
        client.setManualAcks(true);
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("mqtt connected (reconnect={}) {}", reconnect, serverURI);
                try {
                    client.subscribe(new String[]{MqttTopics.allTelemetry(factoryId), MqttTopics.allCommandAcks(factoryId)},
                            new int[]{QOS, QOS});
                } catch (MqttException e) {
                    log.error("subscribe failed", e);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("mqtt connection lost: {}", cause.toString());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws InterruptedException {
                int id = message.getId();
                int qos = message.getQos();
                onMessage(topic, message.getPayload(), () -> ack(id, qos));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        options.setAutomaticReconnect(true);
        options.setKeepAliveInterval(30);
        options.setMaxInflight(1000);
        while (true) {
            try {
                client.connect(options).waitForCompletion(10_000);
                return;
            } catch (MqttException e) {
                log.warn("mqtt connect failed ({}), retry in 2s", e.getMessage());
                Thread.sleep(2000);
            }
        }
    }

    /**
     * 메시지 한 건 처리. Paho와 분리해 테스트할 수 있게 패키지 공개로 둔다.
     *
     * @param ack 브로커에 PUBACK을 보내는 동작
     */
    void onMessage(String topic, byte[] payload, Runnable ack) throws InterruptedException {
        if (topic.endsWith("/command/ack")) {
            try {
                commandAckHandler.accept(topic, payload);
            } finally {
                ack.run();
            }
            return;
        }
        long now = clock.getAsLong();
        TelemetryRecord record;
        try {
            record = normalizer.normalize(topic, payload, now);
        } catch (IllegalArgumentException e) {
            // 형식이 틀린 메시지는 다시 받아도 틀리다. ack 해서 브로커가 계속 재전송하지 않게 한다.
            log.warn("drop malformed telemetry: {}", e.getMessage());
            ack.run();
            return;
        }
        activity.seen(record.deviceId(), now);
        sink.submit(record, ack); // 디스크 커밋 후 ack
    }

    /** 명령 발행 (명령 실행기가 사용) */
    public void publish(String topic, byte[] payload) throws MqttException {
        client.publish(topic, payload, QOS, false);
    }

    private void ack(int messageId, int qos) {
        try {
            client.messageArrivedComplete(messageId, qos);
        } catch (MqttException e) {
            // 연결이 끊긴 뒤라면 실패한다. 브로커는 재연결 후 다시 보내고, 서버가 중복을 거른다.
            log.debug("ack {} failed: {}", messageId, e.getMessage());
        }
    }

    @Override
    public void close() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect().waitForCompletion(2000);
        }
    }
}
