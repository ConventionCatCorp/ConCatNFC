idf_component_register(
        SRCS
            main.cpp
            nfc_operations.cpp
            ConCatTag.cpp
        PRIV_REQUIRES spi_flash esp_driver_gpio esp_driver_spi esp_driver_uart log pn532 console mbedtls json
        INCLUDE_DIRS ".")