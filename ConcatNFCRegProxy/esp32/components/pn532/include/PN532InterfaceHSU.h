#ifndef CONCAT_NFC_PROXY_ESP32_PN532_INTERFACE_HSU_H
#define CONCAT_NFC_PROXY_ESP32_PN532_INTERFACE_HSU_H

#include "driver/uart.h"

class PN532InterfaceHSU: public PN532Interface {
public:
    PN532InterfaceHSU(gpio_num_t uart_rx, gpio_num_t uart_tx, gpio_num_t reset, gpio_num_t irq, uart_port_t uart_port, int32_t baudrate);

    esp_err_t pn532_init_io() override;
    void pn532_release_io() override;
    esp_err_t pn532_init_extra() override;
    esp_err_t pn532_wakeup() override;
    esp_err_t pn532_read(uint8_t *read_buffer, size_t read_size, int xfer_timeout_ms) override;
    esp_err_t pn532_write(const uint8_t *write_buffer, size_t write_size, int xfer_timeout_ms) override;

private:
    gpio_num_t m_uart_rx;
    gpio_num_t m_uart_tx;
    uart_port_t m_uart_port;
    uint8_t m_uart_baud_wanted;
    uint8_t m_uart_baud_used;
};

#endif //CONCAT_NFC_PROXY_ESP32_PN532_INTERFACE_HSU_H
