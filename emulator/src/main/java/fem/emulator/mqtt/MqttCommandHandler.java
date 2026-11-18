package fem.emulator.mqtt;

import fem.common.mqtt.MqttPayloads;
import fem.common.mqtt.MqttTopics;
import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.emulator.device.SimulatedDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * {@code factory/A/device/{id}/command} 로 온 명령을 설비에 적용하고 보낼 응답을 만든다.
 * Paho와 분리해 두어 브로커 없이 테스트할 수 있다.
 */
public final class MqttCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(MqttCommandHandler.class);

    private final String factoryId;
    private final Function<String, SimulatedDevice> devices;
    private final LongSupplier clock;

    public MqttCommandHandler(String factoryId, Function<String, SimulatedDevice> devices, LongSupplier clock) {
        this.factoryId = factoryId;
        this.devices = devices;
        this.clock = clock;
    }

    /** 보낼 응답 한 건 */
    public record Outgoing(String topic, byte[] payload) {
    }

    /**
     * @return 보낼 응답. 형식이 잘못된 메시지면 응답할 commandId도 알 수 없으므로 비어 있다
     */
    public Optional<Outgoing> handle(String topic, byte[] payload) {
        String deviceId;
        MqttPayloads.Command cmd;
        CommandAction action;
        try {
            deviceId = MqttTopics.deviceIdOf(topic);
            cmd = MqttCodec.readCommand(payload);
            action = CommandAction.valueOf(cmd.action());
        } catch (RuntimeException e) {
            log.warn("ignore malformed command on {}: {}", topic, e.getMessage());
            return Optional.empty();
        }

        SimulatedDevice device = devices.apply(deviceId);
        CommandOutcome outcome;
        String state;
        if (device == null) {
            outcome = CommandOutcome.UNKNOWN_DEVICE;
            state = "UNKNOWN";
        } else {
            outcome = device.handle(cmd.commandId(), action);
            state = device.state().name();
        }
        byte[] ack = MqttCodec.commandAck(new MqttPayloads.CommandAck(cmd.commandId(), outcome.name(), state, clock.getAsLong()));
        return Optional.of(new Outgoing(MqttTopics.commandAck(factoryId, deviceId), ack));
    }
}
