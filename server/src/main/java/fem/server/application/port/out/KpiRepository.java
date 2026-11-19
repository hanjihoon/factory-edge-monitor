package fem.server.application.port.out;

import fem.server.domain.KpiReport.DeviceKpi;
import fem.server.domain.KpiReport.FactoryKpi;
import fem.server.domain.KpiReport.HourPoint;

import java.time.Instant;
import java.util.List;

/** 가동률 집계 조회 (출력 포트). 구현: JdbcKpiRepository (TimescaleDB 연속 집계 device_state_1m) */
public interface KpiRepository {

    List<FactoryKpi> byFactory(Instant from, Instant to);

    List<HourPoint> hourly(Instant from, Instant to);

    /** 가동률이 낮은 순 (같으면 고장 시간이 긴 순) */
    List<DeviceKpi> worstDevices(Instant from, Instant to, int limit);
}
