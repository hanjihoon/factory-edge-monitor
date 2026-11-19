package fem.gateway.collect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 설비별 마지막 수신 시각. 상태 보고의 "최근 N초 안에 데이터가 들어온 설비 수"에 쓴다.
 * 관제 화면에서 "게이트웨이는 살아 있는데 설비 몇 대가 조용하다"를 구분하기 위한 값이다.
 */
public final class DeviceActivity {

    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public void seen(String deviceId, long nowMs) {
        lastSeen.put(deviceId, nowMs);
    }

    public int activeWithin(long windowMs, long nowMs) {
        long since = nowMs - windowMs;
        int n = 0;
        for (long t : lastSeen.values()) {
            if (t >= since) n++;
        }
        return n;
    }
}
