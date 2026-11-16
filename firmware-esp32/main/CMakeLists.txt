idf_component_register(
    SRCS
        "main.cpp"
        "status_led.cpp"
        "config_store.cpp"
        "telemetry_store.cpp"
        "pulse_generator.cpp"
        "wheel_capture.cpp"
        "ble_service.cpp"
        "ota_service.cpp"
    INCLUDE_DIRS "include"
    REQUIRES bt driver esp_timer nvs_flash esp_app_format app_update mbedtls
)
