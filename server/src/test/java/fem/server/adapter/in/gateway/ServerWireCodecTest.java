package fem.server.adapter.in.gateway;

import fem.common.DeviceState;
import fem.common.wire.WireMessages.Batch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class ServerWireCodecTest {

    private final ServerWireCodec codec = new ServerWireCodec(JsonMapper.builder().build());

    private static String batchWithState(String state) {
        return """
                {"type":"BATCH","body":{"gatewayId":"gw-a","fromOutboxId":1,"toOutboxId":2,"records":[
                 {"deviceId":"A-0001","factoryId":"A","protocol":"MQTT","seq":1,"deviceTs":1,"gatewayTs":1,
                  "state":"RUNNING","temperature":1,"vibration":1,"rpm":1,"alarmCode":0},
                 {"deviceId":"A-0002","factoryId":"A","protocol":"MQTT","seq":1,"deviceTs":1,"gatewayTs":1,
                  "state":"%s","temperature":1,"vibration":1,"rpm":1,"alarmCode":0}]}}
                """.formatted(state);
    }

    @Test
    void unknown_enum_value_from_a_newer_gateway_does_not_break_the_whole_batch() {
        Batch batch = (Batch) codec.decode(batchWithState("MAINTENANCE"));

        assertEquals(2, batch.records().size(), "the batch is still readable, so it can be acked");
        assertEquals(DeviceState.RUNNING, batch.records().get(0).state());
        assertNull(batch.records().get(1).state(), "only this record is marked, then quarantined by RecordCheck");
    }

    @Test
    void broken_json_is_still_rejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{not json"));
    }
}
