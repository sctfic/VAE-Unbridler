idf_component_register(
    SRCS
        "main.cpp"
        "config_store.cpp"
        "telemetry_store.cpp"
        "pulse_generator.cpp"
        "wheel_capture.cpp"
        "ble_service.cpp"
    INCLUDE_DIRS "include"
    REQUIRES bt driver esp_timer nvs_flash
)
