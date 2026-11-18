package fem.common;

/**
 * 설비 ID 규칙: {@code <공장>-<4자리 번호>} (예: A-0001, B-0400).
 * 에뮬레이터·게이트웨이·서버가 같은 규칙을 쓰도록 한 곳에 둔다.
 */
public final class DeviceIds {

    private DeviceIds() {
    }

    public static String of(String factoryId, int number) {
        if (number < 1 || number > 9999) {
            throw new IllegalArgumentException("device number out of range: " + number);
        }
        return "%s-%04d".formatted(factoryId, number);
    }

    /** "B-0123" → 123 */
    public static int numberOf(String deviceId) {
        int dash = deviceId.indexOf('-');
        if (dash < 1 || dash == deviceId.length() - 1) {
            throw new IllegalArgumentException("invalid device id: " + deviceId);
        }
        return Integer.parseInt(deviceId.substring(dash + 1));
    }

    /** "B-0123" → "B" */
    public static String factoryOf(String deviceId) {
        int dash = deviceId.indexOf('-');
        if (dash < 1) {
            throw new IllegalArgumentException("invalid device id: " + deviceId);
        }
        return deviceId.substring(0, dash);
    }
}
