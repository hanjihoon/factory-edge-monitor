package fem.emulator;

import fem.emulator.modbus.ModbusFleet;
import fem.emulator.mqtt.MqttFleet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/** 공장 A(MQTT)·공장 B(Modbus) 설비를 띄우고 종료 신호까지 기다린다. */
public final class EmulatorMain {

    private static final Logger log = LoggerFactory.getLogger(EmulatorMain.class);

    public static void main(String[] args) throws Exception {
        EmulatorConfig config = EmulatorConfig.from(System.getenv());
        log.info("emulator config: {}", config);

        // seq 초기값 = 현재 epoch 초. 설비 카운터는 재부팅해도 이어진다(비휘발)고 가정한다.
        // 0부터 다시 세면 서버가 (deviceId, seq) 기준으로 새 데이터를 전부 중복으로 버린다.
        // 1초에 1씩 오르므로 epoch 초에서 시작하면 재시작 후에도 이전 값보다 커진다. uint32라 2106년까지 안전.
        long initialSeq = System.currentTimeMillis() / 1000;

        MqttFleet mqtt = config.mqttEnabled() ? new MqttFleet(config, initialSeq) : null;
        ModbusFleet modbus = config.modbusEnabled() ? new ModbusFleet(config, initialSeq) : null;
        if (modbus != null) modbus.start(); // Modbus 먼저: 브로커 대기와 무관하게 바로 열 수 있다
        if (mqtt != null) mqtt.start();

        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            try {
                if (mqtt != null) mqtt.close();
            } catch (Exception e) {
                log.warn("mqtt close failed", e);
            }
            if (modbus != null) modbus.close();
            done.countDown();
        }));
        done.await();
    }
}
