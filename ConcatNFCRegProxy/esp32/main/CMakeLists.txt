idf_component_register(SRCS "main.c"
        PRIV_REQUIRES spi_flash esp_driver_gpio esp_driver_spi esp_driver_uart log pn532 console
        INCLUDE_DIRS ".")