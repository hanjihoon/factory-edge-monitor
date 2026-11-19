package fem.gateway.command;

import fem.common.DeviceIds;
import fem.common.mqtt.MqttPayloads;
import fem.common.mqtt.MqttTopics;
import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.gateway.wire.WireCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MQTT 설비 명령: command 토픽에 발행하고 command/ack 토픽에서 같은 commandId 응답을 기다린다.
 */
public final class MqttCommandExecutor implements CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(MqttCommandExecutor.class);

    @FunctionalInterface
    public interface Publisher {
        void publish(String topic, byte[] payload) throws Exception;
    }

    private final String factoryId;
    private final Publisher publisher;
    private final WireCodec codec;
    private final long timeoutMs;
    /** commandId → 응답을 기다리는 자리 */
    private final Map<String, CompletableFuture<MqttPayloads.CommandAck>> waiting = new ConcurrentHashMap<>();

    public MqttCommandExecutor(String factoryId, Publisher publisher, WireCodec codec, long timeoutMs) {
        this.factoryId = factoryId;
        this.publisher = publisher;
        this.codec = codec;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Result execute(String commandId, String deviceId, CommandAction action) throws InterruptedException {
        if (!factoryId.equals(DeviceIds.factoryOf(deviceId))) {
            return new Result(CommandOutcome.UNKNOWN_DEVICE, "device is not in factory " + factoryId);
        }
        CompletableFuture<MqttPayloads.CommandAck> reply = new CompletableFuture<>();
        waiting.put(commandId, reply); // 발행 전에 자리를 만든다. 응답이 아주 빨리 와도 놓치지 않게
        try {
            byte[] payload = codec.toJson(new MqttPayloads.Command(commandId, action.name())).getBytes(StandardCharsets.UTF_8);
            publisher.publish(MqttTopics.command(factoryId, deviceId), payload);
            MqttPayloads.CommandAck ack = reply.get(timeoutMs, TimeUnit.MILLISECONDS);
            return new Result(CommandOutcome.valueOf(ack.outcome()), "device state " + ack.state());
        } catch (TimeoutException e) {
            return new Result(CommandOutcome.DEVICE_UNREACHABLE, "no ack within " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new Result(CommandOutcome.DEVICE_UNREACHABLE, "publish failed: " + e.getMessage());
        } finally {
            waiting.remove(commandId);
        }
    }

    /** command/ack 토픽 메시지 (MqttCollector가 넘겨 준다) */
    public void onAck(String topic, byte[] payload) {
        MqttPayloads.CommandAck ack;
        try {
            ack = codec.fromJson(new String(payload, StandardCharsets.UTF_8), MqttPayloads.CommandAck.class);
        } catch (RuntimeException e) {
            log.warn("malformed command ack on {}: {}", topic, e.getMessage());
            return;
        }
        CompletableFuture<MqttPayloads.CommandAck> reply = waiting.get(ack.commandId());
        if (reply != null) {
            reply.complete(ack);
        }
        // 기다리는 쪽이 없으면 시간 초과 뒤 늦게 온 응답이다. 서버가 재시도하면 설비가 같은 결과를 다시 보낸다
    }
}
