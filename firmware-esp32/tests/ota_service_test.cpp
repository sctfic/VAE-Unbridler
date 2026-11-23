#include <array>
#include <cassert>
#include <cstring>
#include <vector>
#include <functional>
#include "ota_service.hpp"
#include "firmware_version.hpp"
#include "status_led.hpp"
#include "status_led_policy.hpp"

namespace {
int64_t clock_us;
esp_partition_t partitions[] = {{16, 0x1e0000}, {17, 0x1e0000}};
const esp_partition_t* running = &partitions[0];
const esp_partition_t* selected;
esp_ota_img_states_t image_states[2];
bool metadata_exists, restarted, aborted, flash_fails, other_project, update_led, write_active;
std::function<void()> during_write;
std::vector<uint8_t> written;
size_t erased_length;
esp_app_desc_t description = {"1.0.2", "ebike_firmware_hw_v1"};
const uint8_t sha_abc[32] = {0xba,0x78,0x16,0xbf,0x8f,0x01,0xcf,0xea,0x41,0x41,0x40,0xde,0x5d,0xae,0x22,0x23,
    0xb0,0x03,0x61,0xa3,0x96,0x17,0x7a,0x9c,0xb4,0x10,0xff,0x61,0xf2,0x00,0x15,0xad};
void reset() {
    erased_length = 0; clock_us = 20'000'000; running = &partitions[0]; selected = running;
    image_states[0] = ESP_OTA_IMG_VALID; image_states[1] = ESP_OTA_IMG_UNDEFINED;
    update_led = false; write_active = false; during_write = {};
    metadata_exists = true; restarted = aborted = flash_fails = other_project = false; written.clear();
}
std::array<uint8_t, 41> begin_packet(bool valid_hash = true, uint32_t size = 3, uint32_t code = ebike::kFirmwareVersionCode + 1) {
    std::array<uint8_t, 41> packet{}; packet[0] = 6;
    std::memcpy(packet.data() + 1, &size, 4);
    if (valid_hash) std::memcpy(packet.data() + 5, sha_abc, 32);
    std::memcpy(packet.data() + 37, &code, 4);
    return packet;
}
const uint8_t chunk[] = {0, 0, 0, 0, 'a', 'b', 'c'};
const uint8_t finish[] = {2}, activate[] = {4};
struct FakeWake : ebike::OtaWakeOutput {
    unsigned requests = 0;
    uint16_t circumference = 0;
    bool complete = false, cancelled = false, available = true;
    bool request_ota_wake(uint16_t value) override {
        if (!available) return false;
        ++requests; circumference = value; complete = false; cancelled = false; return true;
    }
    void cancel_ota_wake() override { cancelled = true; }
    bool ota_wake_complete() const override { return complete && !cancelled; }
};
struct Fixture {
    FakeWake wake;
    ebike::ConfigStore config;
    ebike::TelemetryStore telemetry;
    ebike::OtaService ota{config, telemetry, wake};
    Fixture() { reset(); ota.begin(); }
    void begin(bool good_hash = true) {
        auto packet = begin_packet(good_hash); assert(ota.control(packet.data(), packet.size()));
    }
    void verify() { begin(); assert(ota.write(chunk, sizeof(chunk))); ota.process_pending(); assert(ota.control(finish, 1)); wake.complete = true; }
    void tick(int64_t advance = 0) {
        clock_us += advance; telemetry.wheel_task_heartbeat(clock_us); ota.tick(true);
    }
};
}

int64_t esp_timer_get_time() { return clock_us; }
void esp_restart() { restarted = true; }
const esp_app_desc_t* esp_app_get_description() { return &description; }
const esp_partition_t* esp_ota_get_running_partition() { return running; }
const esp_partition_t* esp_ota_get_next_update_partition(const esp_partition_t*) { return &partitions[running == &partitions[0] ? 1 : 0]; }
esp_err_t esp_ota_get_state_partition(const esp_partition_t* p, esp_ota_img_states_t* state) {
    if (!metadata_exists) return ESP_ERR_NOT_FOUND;
    *state = image_states[p - partitions]; return ESP_OK;
}
esp_err_t esp_ota_set_boot_partition(const esp_partition_t* p) {
    selected = p; metadata_exists = true; image_states[p - partitions] = ESP_OTA_IMG_NEW; return ESP_OK;
}
esp_err_t esp_ota_mark_app_valid_cancel_rollback() {
    if (!metadata_exists) return ESP_FAIL;
    image_states[running - partitions] = ESP_OTA_IMG_VALID; return ESP_OK;
}
esp_err_t esp_ota_begin(const esp_partition_t* p, size_t length, esp_ota_handle_t* handle) {
    erased_length = length;
    assert(p != running); if (flash_fails) return ESP_FAIL; *handle = 1; return ESP_OK;
}
esp_err_t esp_ota_write(esp_ota_handle_t handle, const void* data, size_t size) {
    assert(handle == 1); if (flash_fails) return ESP_FAIL;
    write_active = true;
    const auto* bytes = static_cast<const uint8_t*>(data); written.insert(written.end(), bytes, bytes + size);
    if (during_write) during_write();
    write_active = false; return ESP_OK;
}
esp_err_t esp_ota_abort(esp_ota_handle_t) { assert(!write_active); aborted = true; return ESP_OK; }
esp_err_t esp_ota_end(esp_ota_handle_t) { return flash_fails ? ESP_FAIL : ESP_OK; }
esp_err_t esp_ota_get_partition_description(const esp_partition_t*, esp_app_desc_t* output) {
    *output = description;
    if (other_project) std::strcpy(output->project_name, "ebike_firmware");
    return ESP_OK;
}
void ebike::status_led::updating(bool value) { update_led = value; }
ebike::RuntimeConfig ebike::ConfigStore::snapshot() const { return config_; }
void ebike::ConfigStore::set_speed_enabled(bool enabled) { speed_enabled_ = enabled; }

int main() {
    { Fixture f; f.verify(); assert(selected == running); assert(f.ota.status().state == 2);
      assert(f.ota.control(activate, 1)); assert(selected == &partitions[1]);
      f.ota.disconnected(); f.tick(2'000'000); assert(restarted); assert(written.size() == 3); }
    { Fixture f; f.begin(); f.telemetry.record_wheel_edge_from_isr(clock_us);
      assert(!f.ota.write(chunk, sizeof(chunk))); assert(f.ota.status().error == 1); assert(aborted); assert(selected == running); }
    { Fixture f; f.begin(); f.telemetry.record_wheel_edge_from_isr(clock_us); clock_us += 12'000'000;
      assert(f.ota.status().flags & 2); // It is quiet again, but this session must stay cancelled.
      assert(!f.ota.write(chunk, sizeof(chunk))); assert(f.ota.status().error == 1); }
    { Fixture f; f.verify(); f.telemetry.record_wheel_edge_from_isr(clock_us);
      assert(!f.ota.control(activate, 1)); assert(selected == running); }
    { Fixture f; f.verify(); assert(f.ota.control(activate, 1));
      f.telemetry.record_wheel_edge_from_isr(clock_us); f.tick(2'000'000);
      assert(!restarted); assert(selected == running); assert(f.ota.status().flags & 4); }
    { Fixture f; f.begin(); f.ota.disconnected(); assert(aborted); assert(selected == running); assert(f.ota.status().error == 6); }
    { Fixture f; f.begin(false); assert(f.ota.write(chunk, sizeof(chunk))); f.ota.process_pending();
      assert(!f.ota.control(finish, 1)); assert(f.ota.status().error == 4); assert(selected == running); }
    { Fixture f; f.begin(); assert(!f.ota.control(finish, 1)); assert(f.ota.status().error == 2); }
    { Fixture f; f.begin(); uint8_t bad[] = {3, 0, 0, 0, 'a'};
      assert(!f.ota.write(bad, sizeof(bad))); assert(aborted); }
    { Fixture f; f.begin(); f.tick(30'000'001); assert(aborted); assert(f.ota.status().error == 5); }
    { Fixture f; auto old = begin_packet(true, 3, ebike::kFirmwareVersionCode); assert(!f.ota.control(old.data(), old.size()));
      auto large = begin_packet(true, 0x1e0001); assert(!f.ota.control(large.data(), large.size())); }
    { Fixture f; f.begin(); flash_fails = true; assert(f.ota.write(chunk, sizeof(chunk))); f.ota.process_pending(); assert(f.ota.status().error == 3); }
    { Fixture f; f.begin(); assert(f.ota.write(chunk, sizeof(chunk))); f.ota.process_pending(); other_project = true;
      assert(!f.ota.control(finish, 1)); assert(f.ota.status().error == 3); assert(selected == running); }
    { Fixture f; metadata_exists = false; f.tick(); f.tick(10'000'001);
      assert(f.ota.status().flags & 4); assert(selected == running); }
    { Fixture f; image_states[0] = ESP_OTA_IMG_PENDING_VERIFY; f.tick();
      assert(!(f.ota.status().flags & 4)); f.tick(10'000'001); assert(f.ota.status().flags & 4); }
    // A subsequent update writes the other slot (B -> A).
    { Fixture f; running = &partitions[1]; selected = running; image_states[1] = ESP_OTA_IMG_VALID;
      ebike::OtaService next(f.config, f.telemetry, f.wake); next.begin(); auto packet = begin_packet();
      assert(next.control(packet.data(), packet.size())); assert(next.status().target_slot == 0); }
    { Fixture f; auto packet = begin_packet(); packet[0] = 1;
      assert(!f.ota.control(packet.data(), packet.size())); assert(f.ota.status().error == 2); }
    const uint8_t prepare[] = {5}, cancel[] = {3};
    { Fixture f; assert(f.ota.status().flags & 8);
      assert(f.ota.control(prepare, 1)); assert(f.ota.status().state == 5 && f.ota.busy());
      assert(update_led && f.wake.requests == 1 && f.wake.circumference == 2360);
      assert(!(f.ota.status().flags & 16));
      assert(f.ota.control(prepare, 1) && f.wake.requests == 1);
      // Emitted output pulses must not count as physical wheel movement.
      f.telemetry.record_emitted_pulse_from_isr(clock_us);
      f.wake.complete = true; assert(f.ota.status().flags & 16);
      f.begin(); assert(f.ota.status().state == 1 && f.wake.requests == 1 && update_led);
      f.ota.control(cancel, 1); assert(f.wake.cancelled && !update_led && !f.ota.busy()); }
    { Fixture f; f.ota.control(prepare, 1); auto packet = begin_packet();
      assert(f.ota.control(packet.data(), packet.size())); assert(f.ota.status().state == 1 && !f.wake.complete); }
    { Fixture f; f.ota.control(prepare, 1); f.telemetry.record_wheel_edge_from_isr(clock_us);
      f.tick(); assert(f.ota.status().error == 1 && f.wake.cancelled && !update_led); }
    { Fixture f; f.ota.control(prepare, 1); f.ota.disconnected();
      assert(f.ota.status().error == 6 && f.wake.cancelled && !update_led); }
    { Fixture f; f.ota.control(prepare, 1); f.tick(30'000'001);
      assert(f.ota.status().error == 5 && f.wake.cancelled && !update_led); }
    { Fixture f; f.ota.control(prepare, 1); f.tick(20'000'000);
      assert(f.ota.control(prepare, 1)); f.tick(20'000'000);
      assert(f.ota.status().state == 5 && f.wake.requests == 1); }
    { Fixture f; image_states[0] = ESP_OTA_IMG_PENDING_VERIFY;
      assert(!f.ota.control(prepare, 1) && f.wake.requests == 0 && !update_led); }
    { Fixture f; f.telemetry.record_wheel_edge_from_isr(clock_us);
      assert(!f.ota.control(prepare, 1) && f.wake.requests == 0); }
    { Fixture f; f.wake.available = false;
      assert(!f.ota.control(prepare, 1) && !update_led); }
    static_assert(ebike::status_led::update_color(true) == 0x180600, "OTA orange");
    static_assert(ebike::status_led::update_color(false) == 0x100010, "OTA purple");
    // Fast stream: receipt is queued, ACK advances only after a dedicated flash write.
    { Fixture f; auto packet = begin_packet();
      assert(f.ota.status().flags & 32); assert(f.ota.control(packet.data(), packet.size()));
      assert(f.wake.requests == 1 && !f.wake.complete);
      assert(f.ota.write(chunk, sizeof(chunk))); assert(f.ota.write(chunk, sizeof(chunk)));
      assert(f.ota.status().received == 0 && written.empty());
      f.ota.process_pending(); assert(f.ota.status().received == 3 && written.size() == 3);
      assert(f.ota.control(finish, 1)); assert(!f.ota.control(activate, 1));
      f.wake.complete = true; assert(f.ota.control(activate, 1)); }
    { Fixture f; auto packet = begin_packet(); f.ota.control(packet.data(), packet.size());
      uint8_t gap[] = {1,0,0,0,'b','c'};
      assert(!f.ota.write(gap, sizeof(gap))); assert(f.ota.status().state == 1);
      assert(f.ota.write(chunk, sizeof(chunk))); f.ota.process_pending();
      assert(f.ota.status().received == 3); assert(f.ota.control(finish, 1)); }
    { Fixture f; auto packet = begin_packet(); f.ota.control(packet.data(), packet.size());
      f.ota.write(chunk, sizeof(chunk));
      during_write = [&] {
          assert(f.ota.control(cancel, 1)); assert(!aborted);
          assert(!f.ota.control(packet.data(), packet.size()));
      };
      f.ota.process_pending(); assert(aborted && f.ota.status().received == 0 && f.ota.status().error == 6); }
    { Fixture f; auto packet = begin_packet(); f.ota.control(packet.data(), packet.size());
      f.ota.write(chunk, sizeof(chunk)); during_write = [&] { f.telemetry.record_wheel_edge_from_isr(clock_us); };
      f.ota.process_pending(); assert(aborted && f.ota.status().error == 1 && f.ota.status().received == 0); }
    { Fixture f; auto packet = begin_packet(); f.ota.control(packet.data(), packet.size());
      f.ota.write(chunk, sizeof(chunk)); flash_fails = true; f.ota.process_pending();
      assert(aborted && f.ota.status().error == 3 && f.ota.status().received == 0); }
    // A full 512-byte ATT write remains unacknowledged until persisted.
    { Fixture f; auto packet = begin_packet(true, 508); assert(f.ota.control(packet.data(), packet.size()));
      uint8_t frame[512]{}; assert(f.ota.write(frame, sizeof(frame)));
      auto p = f.ota.progress(); assert(p.received == 0 && p.size == 508);
      assert(p.target_code == ebike::kFirmwareVersionCode + 1);
      f.ota.process_pending(); p = f.ota.progress();
      assert(p.received == 508 && p.state == 1 && p.error == 0); }
    // Erase the known image range before sending; batch flash writes at 4 KiB.
    { Fixture f; auto packet = begin_packet(true, 9000); assert(f.ota.control(packet.data(), packet.size()));
      assert(erased_length == 9000);
      uint8_t frame[512]{};
      for (uint32_t i = 0; i < 8; ++i) {
          const auto offset = i * 508; std::memcpy(frame, &offset, 4);
          assert(f.ota.write(frame, sizeof(frame)));
      }
      assert(!f.ota.process_pending()); assert(f.ota.progress().received == 0);
      uint32_t offset = 4064; std::memcpy(frame, &offset, 4);
      assert(f.ota.write(frame, 36)); assert(f.ota.process_pending());
      assert(f.ota.progress().received == 4096 && written.size() == 4096); }
}
