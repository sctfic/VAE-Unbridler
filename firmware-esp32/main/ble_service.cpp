#include "ble_service.hpp"
#include "status_led.hpp"

#include <cstring>

#include "esp_random.h"
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
constexpr uint16_t kNoConnection = 0xffff;

// NimBLE stores UUID128 bytes least-significant byte first.
ble_uuid128_t kServiceUuid = BLE_UUID128_INIT(0x01, 0x11, 0x3d, 0x87, 0x9d, 0x9e, 0xda, 0xa0,
                                               0x9f, 0x4d, 0x8a, 0x9b, 0x00, 0x00, 0xf5, 0xa6);
ble_uuid128_t kEventsUuid = BLE_UUID128_INIT(0x01, 0x11, 0x3d, 0x87, 0x9d, 0x9e, 0xda, 0xa0,
                                              0x9f, 0x4d, 0x8a, 0x9b, 0x07, 0x00, 0xf5, 0xa6);
ble_uuid128_t kConfigUuid = BLE_UUID128_INIT(0x01, 0x11, 0x3d, 0x87, 0x9d, 0x9e, 0xda, 0xa0,
                                              0x9f, 0x4d, 0x8a, 0x9b, 0x02, 0x00, 0xf5, 0xa6);
ble_uuid128_t kOtaControlUuid = BLE_UUID128_INIT(0x01, 0x11, 0x3d, 0x87, 0x9d, 0x9e, 0xda, 0xa0,
                                               0x9f, 0x4d, 0x8a, 0x9b, 0x05, 0x00, 0xf5, 0xa6);
ble_uuid128_t kOtaDataUuid = BLE_UUID128_INIT(0x01, 0x11, 0x3d, 0x87, 0x9d, 0x9e, 0xda, 0xa0,
                                            0x9f, 0x4d, 0x8a, 0x9b, 0x06, 0x00, 0xf5, 0xa6);
}  // namespace

BleService* BleService::instance_ = nullptr;

BleService::BleService(ConfigStore& config, TelemetryStore& telemetry, OtaWakeOutput& wake)
    : config_(config), telemetry_(telemetry), ota_(config, telemetry, wake) {}

void BleService::begin() {
    instance_ = this;
    session_ = (uint64_t(esp_random()) << 32) | esp_random();
    ota_.begin();
    const esp_err_t init_result = nimble_port_init();
    if (init_result != ESP_OK) {
        status_led::fault();
        return;
    }
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
    const auto flash_started = xTaskCreatePinnedToCore(&BleService::flash_task, "ota_flash", 8192,
                                                       this, 3, &flash_task_handle_, 0);
    configASSERT(flash_started == pdPASS);
}

void BleService::host_task(void*) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void BleService::notify_task(void* context) {
    auto* self = static_cast<BleService*>(context);
    while (true) {
        self->ota_.tick(self->ble_ready_);
        if (self->flash_task_handle_) xTaskNotifyGive(self->flash_task_handle_);
        self->update_link_priority();
        self->send_telemetry();
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

void BleService::flash_task(void* context) {
    auto* self = static_cast<BleService*>(context);
    while (true) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
        while (self->ota_.process_pending()) self->send_progress();
        self->send_progress();
    }
}

void BleService::send_progress() {
    if (!progress_subscribed_ || connection_handle_ == kNoConnection) return;
    auto p = ota_.progress();
    p.interval_units = interval_units_; p.tx_phy = tx_phy_; p.rx_phy = rx_phy_;
    const auto epoch = progress_epoch_.load();
    if (epoch == ack_epoch_ && std::memcmp(&p, &last_progress_, sizeof(p)) == 0) return;
    auto* buffer = ble_hs_mbuf_from_flat(&p, sizeof(p));
    if (buffer && ble_gatts_notify_custom(connection_handle_, ota_control_handle_, buffer) == 0) {
        last_progress_ = p; ack_epoch_ = epoch;
    }
}

void BleService::update_link_priority() {
    const auto connection = connection_handle_;
    if (connection == kNoConnection) { priority_active_ = false; priority_attempts_ = 0; return; }
    const bool fast = ota_.busy();
    if (fast != priority_active_) {
        priority_active_ = fast; priority_attempts_ = 0; last_priority_request_us_ = 0;
    }
    const auto now = esp_timer_get_time();
    if (priority_attempts_ >= 3 || (priority_attempts_ && now - last_priority_request_us_ < 1'000'000)) return;
    if (priority_attempts_ && (!fast || (interval_units_ == 6 && tx_phy_ == 2 && rx_phy_ == 2))) return;
    ++priority_attempts_; last_priority_request_us_ = now;
    ble_gap_upd_params params{};
    params.itvl_min = fast ? 6 : 24;
    params.itvl_max = fast ? 6 : 40;
    params.latency = 0; params.supervision_timeout = 400;
    ble_gap_update_params(connection, &params);
    if (fast) {
        ble_gap_set_data_len(connection, 251, 2120);
        ble_gap_set_prefered_le_phy(connection, 2, 2, 0);
    }
}

void BleService::on_sync() {
    if (instance_ == nullptr) {
        return;
    }
    const int ensure_result = ble_hs_util_ensure_addr(0);
    if (ensure_result != 0 || ble_hs_id_infer_auto(0, &instance_->address_type_) != 0) {
        status_led::fault();
        return;
    }
    instance_->start_advertising();
}

void BleService::register_gatt() {
    static ble_gatt_chr_def characteristics[5]{};
    characteristics[0].uuid = &kEventsUuid.u;
    characteristics[0].access_cb = &BleService::gatt_access;
    characteristics[0].flags = BLE_GATT_CHR_F_NOTIFY;
    characteristics[0].val_handle = &events_handle_;
    characteristics[1].uuid = &kConfigUuid.u;
    characteristics[1].access_cb = &BleService::gatt_access;
    characteristics[1].flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE;
    characteristics[1].val_handle = &config_handle_;
    characteristics[2].uuid = &kOtaControlUuid.u;
    characteristics[2].access_cb = &BleService::gatt_access;
    characteristics[2].flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_NOTIFY;
    characteristics[2].val_handle = &ota_control_handle_;
    characteristics[3].uuid = &kOtaDataUuid.u;
    characteristics[3].access_cb = &BleService::gatt_access;
    characteristics[3].flags = BLE_GATT_CHR_F_WRITE_NO_RSP;
    characteristics[3].val_handle = &ota_data_handle_;

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
    fields.uuids128 = &kServiceUuid;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;
    // Flags + UUID + name exceed the 31-byte advertising limit.
    // Put the name in the scan response, preserving the UUID for Android filters.
    ble_hs_adv_fields response{};
    const char* name = ble_svc_gap_device_name();
    response.name = reinterpret_cast<const uint8_t*>(name);
    response.name_len = std::strlen(name);
    response.name_is_complete = 1;
    int fields_result = ble_gap_adv_set_fields(&fields);
    if (fields_result == 0) fields_result = ble_gap_adv_rsp_set_fields(&response);
    if (fields_result != 0) {
        status_led::fault();
        return;
    }

    ble_gap_adv_params parameters{};
    parameters.conn_mode = BLE_GAP_CONN_MODE_UND;
    parameters.disc_mode = BLE_GAP_DISC_MODE_GEN;
    const int result = ble_gap_adv_start(address_type_, nullptr, BLE_HS_FOREVER, &parameters,
                                         &BleService::gap_event, this);
    if (result != 0) {
        status_led::fault();
    } else {
        ble_ready_ = true;
        status_led::ready();
    }
}

int BleService::gap_event(ble_gap_event* event, void* context) {
    auto* self = static_cast<BleService*>(context);
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                self->connection_handle_ = event->connect.conn_handle;
                status_led::connected(true);

            } else {
                self->start_advertising();
            }
            return 0;
        case BLE_GAP_EVENT_DISCONNECT:
            self->events_subscribed_ = false;
            self->progress_subscribed_ = false;
            self->interval_units_ = 0; self->tx_phy_ = 1; self->rx_phy_ = 1;
            self->connection_handle_ = kNoConnection;
            self->ota_.disconnected();
            status_led::connected(false);

            self->start_advertising();
            return 0;
        case BLE_GAP_EVENT_CONN_UPDATE:
            if (event->type == BLE_GAP_EVENT_CONN_UPDATE && event->conn_update.status == 0) {
                ble_gap_conn_desc desc{};
                if (ble_gap_conn_find(event->conn_update.conn_handle, &desc) == 0) self->interval_units_ = desc.conn_itvl;
            }
            return 0;
        case BLE_GAP_EVENT_PHY_UPDATE_COMPLETE:
            if (event->phy_updated.status == 0) {
                self->tx_phy_ = event->phy_updated.tx_phy; self->rx_phy_ = event->phy_updated.rx_phy;
            }
            return 0;
        case BLE_GAP_EVENT_SUBSCRIBE:
            if (event->subscribe.attr_handle == self->events_handle_) {
                if (event->subscribe.cur_notify) ++self->stream_epoch_;
                self->events_subscribed_ = event->subscribe.cur_notify != 0;
            }
            if (event->subscribe.attr_handle == self->ota_control_handle_) {
                self->progress_subscribed_ = event->subscribe.cur_notify != 0;
                ++self->progress_epoch_;
                if (self->flash_task_handle_) xTaskNotifyGive(self->flash_task_handle_);
            }
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
    if (attribute_handle == instance_->config_handle_) {
        if (context->op == BLE_GATT_ACCESS_OP_READ_CHR) {
            return instance_->read_config(context);
        }
        if (context->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
            return instance_->write_config(context);
        }
    }
    if (attribute_handle == instance_->ota_control_handle_ && context->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        const auto status = instance_->ota_.status();
        return os_mbuf_append(context->om, &status, sizeof(status)) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if ((attribute_handle == instance_->ota_control_handle_ || attribute_handle == instance_->ota_data_handle_) &&
        context->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t buffer[512];
        const size_t size = OS_MBUF_PKTLEN(context->om);
        if (!size || size > sizeof(buffer) || os_mbuf_copydata(context->om, 0, size, buffer) != 0)
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        const bool ok = attribute_handle == instance_->ota_control_handle_
            ? instance_->ota_.control(buffer, size) : instance_->ota_.write(buffer, size);
        if (instance_->flash_task_handle_) xTaskNotifyGive(instance_->flash_task_handle_);
        return ok ? 0 : BLE_ATT_ERR_VALUE_NOT_ALLOWED;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

int BleService::read_config(ble_gatt_access_ctxt* context) {
    const RuntimeConfig config = config_.snapshot();
    BleConfigPacket packet{};
    packet.protocol_version = kProtocolVersion;
    packet.circumference_mm = config.circumference_mm;
    packet.threshold_centi_kmh = config.threshold_centi_kmh;
    packet.flags = 0x02 | (config_.speed_enabled() ? 0x01 : 0);
    return os_mbuf_append(context->om, &packet, sizeof(packet)) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

int BleService::write_config(ble_gatt_access_ctxt* context) {
    if (ota_.busy()) return BLE_ATT_ERR_VALUE_NOT_ALLOWED;
    if (OS_MBUF_PKTLEN(context->om) != sizeof(BleConfigPacket)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    BleConfigPacket packet{};
    if (os_mbuf_copydata(context->om, 0, sizeof(packet), &packet) != 0 ||
        packet.protocol_version != kProtocolVersion || (packet.flags & ~0x03) != 0 ||
        packet.reserved != 0 || packet.flags == 0x01) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    RuntimeConfig updated{};
    updated.circumference_mm = packet.circumference_mm;
    updated.threshold_centi_kmh = packet.threshold_centi_kmh;
    const RuntimeConfig current = config_.snapshot();
    if ((current.circumference_mm != updated.circumference_mm ||
         current.threshold_centi_kmh != updated.threshold_centi_kmh) && !config_.update(updated)) {
        return BLE_ATT_ERR_VALUE_NOT_ALLOWED;
    }
    if (packet.flags & 0x02) config_.set_speed_enabled((packet.flags & 0x01) != 0);
    return 0;
}

void BleService::send_event(BleEventPayload payload, uint8_t type) {
    const uint16_t connection = connection_handle_;
    payload.session = session_;
    BleEventPacket packet{};
    packet.type = type;
    packet.sequence = sequence_++;
    packet.data = payload;
    os_mbuf* buffer = ble_hs_mbuf_from_flat(&packet, sizeof(packet));
    if (buffer) ble_gatts_notify_custom(connection, events_handle_, buffer);
}

void BleService::send_telemetry() {
    BleEventPayload payload{};
    uint8_t type = 0;
    const bool subscribed = connection_handle_ != kNoConnection && events_subscribed_;
    const uint32_t epoch = stream_epoch_;
    if (!subscribed || !initial_state_sent_ || epoch != sent_epoch_) {
        // Never replay pre-connection measurements as current measurements.
        while (telemetry_.pop_event(payload, type)) {}
        initial_state_sent_ = subscribed;
        sent_epoch_ = epoch;
        if (subscribed) {
            const auto now = esp_timer_get_time();
            send_event(make_event(telemetry_.snapshot(), now, config_.speed_enabled()), 2);
            last_presence_us_ = now;
        }
        return;
    }
    for (unsigned i = 0; i < 16 && telemetry_.pop_event(payload, type); ++i) send_event(payload, type);
    const auto now = esp_timer_get_time();
    if (now - last_presence_us_ >= 5'000'000) {
        send_event(make_event(telemetry_.snapshot(), now, config_.speed_enabled()), 3);
        last_presence_us_ = now;
    }
}
}  // namespace ebike
