package fem.server.domain;

import java.util.List;

/**
 * 가동률 KPI 보고 (선택 구현). 가동률 = 가동(RUNNING) 샘플 ÷ 전체 샘플. 설비가 1초마다 보고하므로 "가동 초 ÷ 보고 초"다.
 *
 * <p>OEE(가동률 × 성능 × 품질) 중 성능·품질은 생산 수량과 불량 수가 있어야 계산할 수 있는데 이 데이터에는 없다.
 * 그래서 가동률만 낸다 (설계 문서 6장).
 *
 * @param hours        최근 몇 시간 기준인가
 * @param factories    공장별 합계
 * @param hourly       공장별 시간대 추이 (막대그래프)
 * @param worstDevices 가동률이 가장 낮은 설비 (먼저 볼 곳)
 */
public record KpiReport(int hours, List<FactoryKpi> factories, List<HourPoint> hourly, List<DeviceKpi> worstDevices) {

    /** @param availabilityPct 가동률 % (소수 1자리). 샘플이 없으면 0 */
    public record FactoryKpi(String factoryId, long samples, long running, long stopped, long fault, double availabilityPct) {
    }

    /** @param hourStartMs 그 시간대의 시작 (epoch ms) */
    public record HourPoint(String factoryId, long hourStartMs, long samples, double availabilityPct) {
    }

    /** @param faultSamples 고장 상태로 보고된 초 */
    public record DeviceKpi(String deviceId, String factoryId, long samples, long faultSamples, double availabilityPct) {
    }
}
