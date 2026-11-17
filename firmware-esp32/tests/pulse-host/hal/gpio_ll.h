#pragma once
#include "soc/gpio_struct.h"
inline void gpio_ll_set_level(int*, int, int level) { host_contact(level == 0); }
