#pragma once

namespace ebike {
class TelemetryStore;

// Setters are task-context only; no LED transmission occurs in a wheel ISR.
namespace status_led {
void begin(TelemetryStore& telemetry);
void ready();
void connected(bool value);
void fault();
}
}
