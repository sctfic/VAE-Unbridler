#include "ble_service.hpp"

#include <cstring>

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_hs_mbuf.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

namespace ebike {
namespace {
constexpr char kTag[] = "ble";
constexpr uint16_t kNoConnection = 0xffff;

// NimBLE stores UUID128 bytes least-significant byte first.
ble_uuid128_t kServiceUuid = BLE_UUID128_INIT(0x01, 0x11, 0x3d, 0x87, 0x9d, 0x9e, 0xda, 0xa0,
                                               0x9f, 0x4d, 0x8a, 0x9b, 0x00, 0x00, 0xf5, 0xa6);
ble_uuid128_t kTelemetryUuid = BLE_UUID128_INIT(0x01, 0x11, 0x3d, 0x87, 0x9d, 0x9e, 0xda, 0xa0,
                                                 0x9f, 0x4d, 0x8a, 0x9b, 0x01, 0x00, 0xf5, 0xa6);
ble_uuid128_t kConfigUuid = BLE_UUID128_INIT(0x01, 0x11, 0x3d, 0x87, 0x9d, 0x9e, 0xda, 0xa0,
                                              0x9f, 0x4d, 0x8a, 0x9b, 0x02, 0x00, 0xf5, 0xa6);
}  // namespace

BleService* BleService::instance_ = nullptr;

BleService::BleService(ConfigStore& config, TelemetryStore& telemetry)
    : config_(config), telemetry_(telemetry) {}

void BleService::begin() {
    instance_ = this;
    nimble_port_init();
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set("E-Bike RT");
    register_gatt();

    ble_hs_cfg.sync_cb = &BleService::on_sync;
    BaseType_t host_started = xTaskCreatePinnedToCore(&BleService::host_task, "nimble_host", 6144,
                                                       this, 5, nullptr, 0);
    configASSERT(host_started == pdPASS);
    BaseType_t notifier_started = xTaskCreatePinnedToCore(&BleService::notify_task, "ble_notify", 4096,
                                                           this, 4, nullptr, 0);
    configASSERT(notifier_started == pdPASS);
}

void BleService::host_task(void*) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void BleService::notify_task(void* context) {
    auto* self = static_cast<BleService*>(context);
    while (true) {
        self->send_telemetry();
        vTaskDelay(pdMS_TO_TICKS(200));
    }
}

void BleService::on_sync() {
    if (instance_ == nullptr) {
        return;
    }
    const int ensure_result = ble_hs_util_ensure_addr(0);
    if (ensure_result != 0 || ble_hs_id_infer_auto(0, &instance_->address_type_) != 0) {
        ESP_LOGE(kTag, "Unable to infer BLE address");
        return;
    }
    instance_->start_advertising();
}

void BleService::register_gatt() {
    static ble_gatt_chr_def characteristics[3]{};
    characteristics[0].uuid = &kTelemetryUuid.u;
    characteristics[0].access_cb = &BleService::gatt_access;
    characteristics[0].flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY;
    characteristics[0].val_handle = &telemetry_handle_;

    characteristics[1].uuid = &kConfigUuid.u;
    characteristics[1].access_cb = &BleService::gatt_access;
    characteristics[1].flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE;
    characteristics[1].val_handle = &config_handle_;

    static ble_gatt_svc_def services[2]{};
    services[0].type = BLE_GATT_SVC_TYPE_PRIMARY;
    services[0].uuid = &kServiceUuid.u;
    services[0].characteristics = characteristics;

    ESP_ERROR_CHECK(ble_gatts_count_cfg(services));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
}

void BleService::start_advertising() {
    ble_hs_adv_fields fields{};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    const char* name = ble_svc_gap_device_name();
    fields.name = reinterpret_cast<const uint8_t*>(name);
    fields.name_len = std::strlen(name);
    fields.name_is_complete = 1;
    fields.uuids128 = &kServiceUuid;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;
    ESP_ERROR_CHECK(ble_gap_adv_set_fields(&fields));

    ble_gap_adv_params parameters{};
    parameters.conn_mode = BLE_GAP_CONN_MODE_UND;
    parameters.disc_mode = BLE_GAP_DISC_MODE_GEN;
    const int result = ble_gap_adv_start(address_type_, nullptr, BLE_HS_FOREVER, &parameters,
                                         &BleService::gap_event, this);
    if (result != 0) {
        ESP_LOGE(kTag, "Unable to advertise: %d", result);
    }
}

int BleService::gap_event(ble_gap_event* event, void* context) {
    auto* self = static_cast<BleService*>(context);
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                self->connection_handle_ = event->connect.conn_handle;
                ESP_LOGI(kTag, "Android connected");
            } else {
                self->start_advertising();
            }
            return 0;
        case BLE_GAP_EVENT_DISCONNECT:
            self->connection_handle_ = kNoConnection;
            ESP_LOGI(kTag, "Android disconnected");
            self->start_advertising();
            return 0;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            self->start_advertising();
            return 0;
        default:
            return 0;
    }
}

int BleService::gatt_access(uint16_t, uint16_t attribute_handle, ble_gatt_access_ctxt* context, void*) {
    if (instance_ == nullptr) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (attribute_handle == instance_->telemetry_handle_) {
        return context->op == BLE_GATT_ACCESS_OP_READ_CHR ? instance_->read_telemetry(context)
                                                           : BLE_ATT_ERR_UNLIKELY;
    }
    if (attribute_handle == instance_->config_handle_) {
        if (context->op == BLE_GATT_ACCESS_OP_READ_CHR) {
            return instance_->read_config(context);
        }
        if (context->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
            return instance_->write_config(context);
        }
    }
    return BLE_ATT_ERR_UNLIKELY;
}

int BleService::read_telemetry(ble_gatt_access_ctxt* context) {
    const TelemetrySnapshot state = telemetry_.snapshot();
    BleTelemetryPacket packet{};
    packet.protocol_version = kProtocolVersion;
    packet.flags = static_cast<uint8_t>((state.wheel_moving ? 0x01 : 0) |
                                        (state.simulated_output ? 0x02 : 0));
    packet.sequence = sequence_++;
    packet.uptime_ms = static_cast<uint32_t>(esp_timer_get_time() / 1000);
    packet.wheel_interval_us = state.wheel_interval_us;
    packet.motor_interval_us = state.motor_interval_us;
    packet.wheel_speed_centi_kmh = state.wheel_speed_centi_kmh;
    packet.motor_speed_centi_kmh = state.motor_speed_centi_kmh;
    packet.wheel_revolutions = state.wheel_revolutions;
    packet.emitted_pulses = state.emitted_pulses;
    packet.circumference_mm = state.circumference_mm;
    return os_mbuf_append(context->om, &packet, sizeof(packet)) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

int BleService::read_config(ble_gatt_access_ctxt* context) {
    const RuntimeConfig config = config_.snapshot();
    BleConfigPacket packet{};
    packet.protocol_version = kProtocolVersion;
    packet.circumference_mm = config.circumference_mm;
    packet.threshold_centi_kmh = config.threshold_centi_kmh;
    return os_mbuf_append(context->om, &packet, sizeof(packet)) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

int BleService::write_config(ble_gatt_access_ctxt* context) {
    if (OS_MBUF_PKTLEN(context->om) != sizeof(BleConfigPacket)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    BleConfigPacket packet{};
    if (os_mbuf_copydata(context->om, 0, sizeof(packet), &packet) != 0 ||
        packet.protocol_version != kProtocolVersion || packet.flags != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    RuntimeConfig updated{};
    updated.circumference_mm = packet.circumference_mm;
    updated.threshold_centi_kmh = packet.threshold_centi_kmh;
    return config_.update(updated) ? 0 : BLE_ATT_ERR_VALUE_NOT_ALLOWED;
}

void BleService::send_telemetry() {
    const uint16_t connection = connection_handle_;
    if (connection == kNoConnection || telemetry_handle_ == 0) {
        return;
    }
    const TelemetrySnapshot state = telemetry_.snapshot();
    BleTelemetryPacket packet{};
    packet.protocol_version = kProtocolVersion;
    packet.flags = static_cast<uint8_t>((state.wheel_moving ? 0x01 : 0) |
                                        (state.simulated_output ? 0x02 : 0));
    packet.sequence = sequence_++;
    packet.uptime_ms = static_cast<uint32_t>(esp_timer_get_time() / 1000);
    packet.wheel_interval_us = state.wheel_interval_us;
    packet.motor_interval_us = state.motor_interval_us;
    packet.wheel_speed_centi_kmh = state.wheel_speed_centi_kmh;
    packet.motor_speed_centi_kmh = state.motor_speed_centi_kmh;
    packet.wheel_revolutions = state.wheel_revolutions;
    packet.emitted_pulses = state.emitted_pulses;
    packet.circumference_mm = state.circumference_mm;

    os_mbuf* buffer = ble_hs_mbuf_from_flat(&packet, sizeof(packet));
    if (buffer != nullptr) {
        ble_gatts_notify_custom(connection, telemetry_handle_, buffer);
    }
}

}  // namespace ebike
