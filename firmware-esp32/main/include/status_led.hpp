#pragma once

namespace ebike {
class TelemetryStore;

// GPIO21 drives a discrete blue LED: steady when Bluetooth is connected and
// flashing on a fatal initialization error. The red wheel-input and green
// output-pulse LEDs are driven directly by their respective hardware signals.
namespace status_led {
void begin(TelemetryStore& telemetry);
void ready();
void connected(bool value);
void fault();
}
}
