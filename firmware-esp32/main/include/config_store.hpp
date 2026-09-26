#pragma once

#include "ebike_types.hpp"
#include "freertos/FreeRTOS.h"

namespace ebike {

class ConfigStore {
  public:
    void begin();
    RuntimeConfig snapshot() const;
    bool update(const RuntimeConfig& config);
    bool speed_enabled() const;
    void set_speed_enabled(bool enabled);

  private:
    bool is_valid(const RuntimeConfig& config) const;
    void persist(const RuntimeConfig& config);

    mutable portMUX_TYPE lock_ = portMUX_INITIALIZER_UNLOCKED;
    RuntimeConfig config_{};
    bool speed_enabled_ = false;
};

}  // namespace ebike
