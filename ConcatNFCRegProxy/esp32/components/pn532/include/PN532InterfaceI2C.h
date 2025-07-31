#ifndef CONCAT_NFC_PROXY_ESP32_PN532_INTERFACE_I2C_H
#define CONCAT_NFC_PROXY_ESP32_PN532_INTERFACE_I2C_H

#include <hal/uart_types.h>
#include "driver/i2c_master.h"
#include "driver/gpio.h"
#include "PN532Interface.h"

class PN532InterfaceI2C: public PN532Interface {
public:
    PN532InterfaceI2C(gpio_num_t sda, gpio_num_t scl, gpio_num_t reset, gpio_num_t irq, i2c_port_num_t i2c_port_number);

    esp_err_t pn532_init_io() override;
    void pn532_release_io() override;
    esp_err_t pn532_init_extra() override;
    esp_err_t pn532_wakeup() override;
    esp_err_t pn532_read(uint8_t *read_buffer, size_t read_size, int xfer_timeout_ms, uart_port_t uart_port=UART_NUM_MAX) override;
    esp_err_t pn532_write(const uint8_t *write_buffer, size_t write_size, int xfer_timeout_ms) override;
    esp_err_t pn532_is_ready();

private:
    gpio_num_t m_sda;
    gpio_num_t m_scl;
    i2c_port_num_t m_i2c_port_number;
    i2c_master_bus_handle_t m_i2c_bus_handle;
    i2c_master_dev_handle_t m_i2c_dev_handle;
    bool m_bus_created;
    uint8_t m_frame_buffer[256];
};

#endif //CONCAT_NFC_PROXY_ESP32_PN532_INTERFACE_HSU_H
