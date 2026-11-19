package fem.server.adapter.in.ui;

import fem.server.domain.DeviceSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UiLiveUpdatesTest {

    private final List<String> broadcasts = new ArrayList<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private final UiSessions sessions = new UiSessions() {
        @Override
        public void broadcast(String text) {
            broadcasts.add(text);
        }

        @Override
        public int count() {
            return 1;
        }
    };
    private final UiLiveUpdates live = new UiLiveUpdates(sessions, json, () -> 42L);

    private static DeviceSnapshot d(String id, long seq) {
        return new DeviceSnapshot(id, "A", seq, seq, "RUNNING", 0, 0, 0, 0);
    }

    @Test
    void device_changes_are_coalesced_to_latest_per_device_until_flush() {
        live.devicesChanged(List.of(d("A-0001", 1), d("A-0002", 1)));
        live.devicesChanged(List.of(d("A-0001", 3)));
        live.devicesChanged(List.of(d("A-0001", 2))); // 늦게 온 옛 값은 최신을 덮지 않는다
        assertTrue(broadcasts.isEmpty(), "nothing sent before flush");

        live.flush();

        assertEquals(1, broadcasts.size());
        JsonNode frame = json.readTree(broadcasts.getFirst());
        assertEquals(2, frame.get("items").size());
        assertEquals(42L, frame.get("serverTime").longValue());
        for (JsonNode item : frame.get("items")) {
            if (item.get("deviceId").stringValue().equals("A-0001")) assertEquals(3, item.get("seq").longValue());
        }
    }

    @Test
    void flush_without_changes_sends_nothing() {
        live.flush();
        assertTrue(broadcasts.isEmpty());
    }
}
